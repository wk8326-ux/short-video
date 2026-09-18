import httpx
import pytest

from app.shared_metadata import (
    SharedMetadataClient,
    candidate_keys,
    fold_key,
    guess_code,
)

BASE = "http://shared.test/api/shared-metadata"


def _entry(code: str, folder: str, title: str = "标题") -> dict:
    return {
        "code": code,
        "title": title,
        "year": "",
        "poster_url": f"/poster?path={folder}%2F{folder}.%28mp4%29-poster.jpg",
        "nfo_url": f"/nfo?path={folder}%2F{folder}.%28mp4%29.nfo",
        "folder_name": folder,
    }


def _client(handler, *, token: str = "test-token") -> httpx.AsyncClient:
    return httpx.AsyncClient(base_url=BASE + "/", transport=httpx.MockTransport(handler))


def test_fold_key_strips_everything_but_alphanumerics():
    assert fold_key("HHD800.com@CEMD-822") == "hhd800comcemd822"
    assert fold_key("carib-010225-001-FHD") == "carib010225001fhd"
    assert fold_key(None) == ""


def test_guess_code_ignores_domains_and_quality_tokens():
    assert guess_code("hhd800.com@CEMD-822.mp4") == "CEMD-822"
    assert guess_code("277DCV-303.mp4") == "DCV-303"
    # ``1pon-1080p`` must not become the release code ``PON-1080``.
    assert guess_code("010124_001-1pon-1080p.(mp4)") == ""
    # Six-digit date runs are normalized the same way movie_metadata does it,
    # so both sides of a lookup fold to the same key.
    assert guess_code("carib-010225-001-HD.mp4") == "CARIB-10225"
    assert guess_code("carib-010225-001-HD.mp4") == guess_code("carib-010225-001-FHD")


def test_candidate_keys_separate_exact_names_from_guessed_codes():
    names, codes = candidate_keys(
        "hhd800.com@CEMD-822.mp4",
        "/光鸭/关键词分类/20260901/CEMD-822/hhd800.com@CEMD-822.mp4",
    )

    assert "cemd822" in names
    assert "hhd800comcemd822" in names
    assert "cemd822" in codes
    # The folder name is the strongest signal, so it has to be tried first.
    assert names.index("cemd822") < names.index("hhd800comcemd822")


def test_candidate_keys_uses_the_containing_folder_not_the_file_path():
    names, _ = candidate_keys(
        "2048.info@carib-010225-001-HD.mp4",
        "/光鸭/关键词分类/20260901/carib-010225-001-FHD/2048.info@carib-010225-001-HD.mp4",
    )

    assert "carib010225001fhd" in names


@pytest.mark.asyncio
async def test_lookup_prefers_the_exact_folder_name_over_a_guessed_code():
    calls: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            calls.append("/api/shared-metadata/index")
            return httpx.Response(
                200,
                json={
                    "ok": True,
                    "total": 2,
                    "items": [
                        # Same guessed code, different release.
                        _entry("CARIB-010225", "wrong-carib-010225-FHD", "错误影片"),
                        _entry("CARIB-010225", "carib-010225-001-FHD", "正确影片"),
                    ],
                },
            )
        calls.append(request.url.path)
        return httpx.Response(404, json={"ok": False})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup(
        "2048.info@carib-010225-001-HD.mp4",
        "/光鸭/关键词分类/20260901/carib-010225-001-FHD/2048.info@carib-010225-001-HD.mp4",
    )
    await http_client.aclose()

    assert match is not None
    assert match.title == "正确影片"
    # Only the containing folder is matched: the guessed code never gets a vote
    # while an exact name key is available.
    assert calls == ["/api/shared-metadata/index", "/api/shared-metadata/metadata"]


@pytest.mark.asyncio
async def test_lookup_maps_remote_metadata_fields_and_absolutises_artwork():
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(200, json={"ok": True, "total": 1, "items": [_entry("CEMD-822", "CEMD-822")]})
        assert request.url.path.endswith("/metadata")
        assert request.url.params["code"] == "CEMD-822"
        return httpx.Response(
            200,
            json={
                "ok": True,
                "code": "CEMD-822",
                "title": "CEMD-822 标题",
                "original_title": "CEMD-822 Original",
                "year": "2024",
                "plot": "剧情简介",
                "studio": "片商",
                "actors": ["演员甲", " 演员乙 ", ""],
                "cover_url": "https://c0.jdbstatic.com/covers/ve/veyGnb.jpg",
                "website": "https://javdb.com/v/veyGnb",
                "poster_url": "/poster?path=CEMD-822%2Fx-poster.jpg",
            },
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup("CEMD-822.mp4", "/光鸭/关键词分类/20260901/CEMD-822")
    await http_client.aclose()

    assert match is not None
    assert match.code == "CEMD-822"
    assert match.year == 2024
    assert match.overview == "剧情简介"
    assert match.studio == "片商"
    assert match.performers == ("演员甲", "演员乙")
    # High-resolution cover drives the wall; the sidecar image is the fallback.
    assert match.poster_url == "https://c0.jdbstatic.com/covers/ve/veyGnb.jpg"
    assert match.backdrop_url == f"{BASE}/poster?path=CEMD-822%2Fx-poster.jpg"
    assert match.confidence == 0.98


@pytest.mark.asyncio
async def test_lookup_falls_back_to_the_sidecar_when_no_cover_exists():
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(200, json={"ok": True, "total": 1, "items": [_entry("ABP-485", "ABP-485")]})
        return httpx.Response(200, json={"ok": True, "code": "ABP-485", "title": "ABP-485", "cover_url": ""})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup("ABP-485.mp4", "/光鸭/关键词分类/20260901/ABP-485")
    await http_client.aclose()

    assert match is not None
    assert match.poster_url.startswith(f"{BASE}/poster?path=")
    assert match.poster_url == match.backdrop_url


@pytest.mark.asyncio
async def test_index_is_fetched_once_and_cached_across_lookups():
    calls: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if request.url.path.endswith("/index"):
            return httpx.Response(200, json={"ok": True, "total": 1, "items": [_entry("ABP-485", "ABP-485")]})
        return httpx.Response(200, json={"ok": True, "code": "ABP-485", "title": "ABP-485", "cover_url": "https://c/x.jpg"})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    first = await client.lookup("ABP-485.mp4", "/光鸭/关键词分类/20260901/ABP-485")
    second = await client.lookup("ABP-485.mp4", "/光鸭/关键词分类/20260901/ABP-485")
    await http_client.aclose()

    assert first is not None and second is not None
    assert calls.count("/api/shared-metadata/index") == 1


@pytest.mark.asyncio
async def test_index_pages_until_total_is_reached():
    starts: list[int] = []
    pages = {
        0: [_entry(f"ABP-{index:03d}", f"ABP-{index:03d}") for index in range(1000)],
        1000: [_entry("HHD-800", "HHD-800")],
    }

    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/index")
        start = int(request.url.params.get("start", "0"))
        starts.append(start)
        items = pages.get(start, [])
        return httpx.Response(200, json={"ok": True, "total": 1001, "start": start, "items": items})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    # The page-2 entry is only reachable through the second /index page.
    index = await client.index()
    await http_client.aclose()

    assert index.names["hhd800"]["code"] == "HHD-800"
    assert starts == [0, 1000]


@pytest.mark.asyncio
async def test_a_broken_index_never_aborts_a_scrape_run():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"ok": False})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    assert await client.lookup("ABP-485.mp4") is None

    # A later run must be able to recover instead of caching the failure.
    async def healed(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(200, json={"ok": True, "total": 1, "items": [_entry("ABP-485", "ABP-485")]})
        return httpx.Response(200, json={"ok": True, "code": "ABP-485", "title": "ABP-485", "cover_url": "https://c/x.jpg"})

    healed_client = _client(healed)
    retry = SharedMetadataClient(base_url=BASE, token="t", client=healed_client)
    assert await retry.lookup("ABP-485.mp4", "/光鸭/ABP-485") is not None
    await http_client.aclose()
    await healed_client.aclose()


@pytest.mark.asyncio
async def test_metadata_by_code_reads_a_single_record():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params["code"] == "CEMD-822"
        return httpx.Response(200, json={"ok": True, "code": "CEMD-822", "title": "标题"})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.metadata_by_code("CEMD-822")
    await http_client.aclose()

    assert match is not None and match.code == "CEMD-822"
    assert client.host == "shared.test"
    assert client.absolute_url("/poster?path=x") == f"{BASE}/poster?path=x"
    assert client.absolute_url("poster?path=x") == f"{BASE}/poster?path=x"
    assert client.absolute_url("") == ""


def _flat_folder_index(codes: list[str], folder: str = "绝顶FUCK") -> list[dict]:
    """A flat "关键词分类" directory: one folder name, many unrelated codes."""
    return [_entry(code, folder, f"{code} 标题") for code in codes]


@pytest.mark.asyncio
async def test_flat_folder_name_never_hands_one_cover_to_every_release():
    """``绝顶FUCK`` holds many codes; the old index gave them all entry #1."""
    codes = ["ABP-167", "ABP-259", "HODV-20987", "KCPN-054", "SDNM-181"]
    asked: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(200, json={"ok": True, "total": 5, "items": _flat_folder_index(codes)})
        asked.append(request.url.params["code"])
        code = request.url.params["code"]
        return httpx.Response(200, json={"ok": True, "code": code, "title": f"{code} 标题", "cover_url": f"https://c/{code}.jpg"})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    matches = [
        await client.lookup(f"{code}.mp4", f"/光鸭/关键词分类/绝顶FUCK/{code}.mp4")
        for code in codes
    ]
    await http_client.aclose()

    assert [match.code for match in matches if match] == codes
    assert [match.poster_url for match in matches if match] == [
        f"https://c/{code}.jpg" for code in codes
    ]
    assert asked == codes


@pytest.mark.asyncio
async def test_lookup_prefers_no_cover_over_a_borrowed_one():
    """One index entry claims the folder name: unrelated files get nothing."""
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(200, json={"ok": True, "total": 1, "items": [_entry("ABP-167", "绝顶FUCK")]})
        raise AssertionError("no detail call may happen for a rejected match")

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    assert (
        await client.lookup("KCPN-054.mp4", "/光鸭/关键词分类/绝顶FUCK/KCPN-054.mp4")
        is None
    )
    await http_client.aclose()


@pytest.mark.asyncio
async def test_generic_container_folder_names_are_ignored():
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(200, json={"ok": True, "total": 1, "items": [_entry("XYZ-001", "mp4")]})
        raise AssertionError("a folder called ``mp4`` must not match anything")

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    assert await client.lookup("SDNM-181.mp4", "/下载/新/mp4/SDNM-181.mp4") is None
    await http_client.aclose()


@pytest.mark.asyncio
async def test_date_style_codes_normalise_on_both_sides():
    """``CARIB-010225`` in the catalogue must answer a ``carib-10225`` guess."""
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(
                200,
                json={"ok": True, "total": 1, "items": [_entry("CARIB-010225", "别的目录名", "日期番号")]},
            )
        assert request.url.params["code"] == "CARIB-010225"
        return httpx.Response(200, json={"ok": True, "code": "CARIB-010225", "title": "日期番号", "cover_url": "https://c/x.jpg"})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup("carib-010225-001-FHD.mp4", "/下载/杂项/carib-010225-001-FHD.mp4")
    await http_client.aclose()

    assert match is not None and match.code == "CARIB-010225"
