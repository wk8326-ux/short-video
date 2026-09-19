import time

import httpx
import pytest

from app.shared_metadata import (
    INDEX_ATTEMPTS,
    INDEX_FULL_REFRESH_SECONDS,
    SharedMetadataClient,
    candidate_keys,
    fold_key,
    guess_code,
)

BASE = "http://shared.test/api/shared-metadata"


def _entry(code: str, folder: str, title: str = "标题", **overrides) -> dict:
    row = {
        "code": code,
        "title": title,
        "year": "",
        "poster_url": f"/poster?path={folder}%2F{folder}.%28mp4%29-poster.jpg",
        "nfo_url": f"/nfo?path={folder}%2F{folder}.%28mp4%29.nfo",
        "folder_name": folder,
    }
    row.update(overrides)
    return row


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
async def test_lookup_uses_the_complete_media_path_before_code_fallback():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/index")
        return httpx.Response(
            200,
            json={
                "ok": True,
                "index_version": "v1",
                "total": 2,
                "items": [
                    _entry(
                        "SAME-001",
                        "同一个目录",
                        "第一个文件",
                        media_path="同一个目录/first.mp4",
                        cloud_path="/光鸭/关键词分类/同一个目录/first.mp4",
                    ),
                    _entry(
                        "SAME-001",
                        "同一个目录",
                        "第二个文件",
                        media_path="同一个目录/second.mp4",
                        cloud_path="/光鸭/关键词分类/同一个目录/second.mp4",
                    ),
                ],
            },
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup(
        "second.mp4",
        "/光鸭/关键词分类/同一个目录/second.mp4",
    )
    await http_client.aclose()

    assert match is not None
    assert match.title == "第二个文件"


@pytest.mark.asyncio
async def test_path_index_normalizes_mount_encoding_slashes_and_case():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/index")
        return httpx.Response(
            200,
            json={
                "ok": True,
                "total": 1,
                "items": [
                    _entry(
                        "CAFE-300",
                        "Café-300",
                        "路径匹配",
                        media_path="Café-300/File One.MP4",
                        cloud_path="/光鸭/关键词分类/Café-300/File One.MP4",
                    )
                ],
            },
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup(
        "File One.MP4",
        "/光鸭//关键词分类\\Café-300/File%20One.MP4",
    )
    await http_client.aclose()

    assert match is not None and match.title == "路径匹配"


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
    assert calls == ["/api/shared-metadata/index"]


@pytest.mark.asyncio
async def test_lookup_maps_remote_metadata_fields_and_absolutises_artwork():
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(
                200,
                json={
                    "ok": True,
                    "total": 1,
                    "items": [
                        _entry(
                            "CEMD-822",
                            "CEMD-822",
                            "CEMD-822 标题",
                            original_title="CEMD-822 Original",
                            year="2024",
                            overview="剧情简介",
                            studio="片商",
                            actors=["演员甲", " 演员乙 ", ""],
                            cover_url="https://c0.jdbstatic.com/covers/ve/veyGnb.jpg",
                            website="https://javdb.com/v/veyGnb",
                            poster_url="/poster?path=CEMD-822%2Fx-poster.jpg",
                            media_path="CEMD-822/CEMD-822.mp4",
                            cloud_path="/光鸭/关键词分类/20260901/CEMD-822/CEMD-822.mp4",
                        )
                    ],
                },
            )
        raise AssertionError("lookup must not request per-title metadata")

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
            return httpx.Response(
                200,
                json={
                    "ok": True,
                    "total": 1,
                    "items": [
                        _entry(
                            "ABP-485",
                            "ABP-485",
                            cover_url="",
                            media_path="ABP-485/ABP-485.mp4",
                            cloud_path="/光鸭/关键词分类/20260901/ABP-485/ABP-485.mp4",
                        )
                    ],
                },
            )
        raise AssertionError("lookup must not request per-title metadata")

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
        raise AssertionError("lookup must not request per-title metadata")

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    first = await client.lookup("ABP-485.mp4", "/光鸭/关键词分类/20260901/ABP-485")
    second = await client.lookup("ABP-485.mp4", "/光鸭/关键词分类/20260901/ABP-485")
    await http_client.aclose()

    assert first is not None and second is not None
    assert calls.count("/api/shared-metadata/index") == 1


@pytest.mark.asyncio
async def test_a_requested_refresh_reads_past_the_cached_catalogue():
    """A cover uploaded after the last fetch is invisible to a cached read.

    The mirror is a deliberate ten-minute trade, so the only way a user who just
    uploaded artwork sees it is a read that ignores both that copy and the file.
    """
    covers = {"ABP-485": "/poster?path=old.jpg"}

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            entry = _entry("ABP-485", "ABP-485")
            entry["poster_url"] = covers["ABP-485"]
            return httpx.Response(200, json={"ok": True, "total": 1, "items": [entry]})
        return httpx.Response(200, json={"ok": True, "code": "ABP-485", "title": "ABP-485"})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    before = await client.index()
    assert before.names["abp485"]["poster_url"].endswith("old.jpg")

    # The same client, the same second: only the explicit request may re-read.
    cached = await client.index()
    assert cached.names["abp485"]["poster_url"].endswith("old.jpg")

    covers["ABP-485"] = "/poster?path=new.jpg"
    refreshed = await client.index(refresh=True)
    assert refreshed.names["abp485"]["poster_url"].endswith("new.jpg")
    await http_client.aclose()


@pytest.mark.asyncio
async def test_a_failed_refresh_reuses_the_cached_catalogue_without_retrying(monkeypatch):
    """One dead catalogue must not become one fetch attempt per media file."""
    monkeypatch.setattr("app.shared_metadata.INDEX_RETRY_DELAYS", (0.0, 0.0))
    healthy = True
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if not healthy:
            raise httpx.ConnectError("catalogue is down", request=request)
        return httpx.Response(200, json={"ok": True, "total": 1, "items": [_entry("ABP-485", "ABP-485")]})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    await client.index()
    assert calls == 1

    healthy = False
    assert (await client.index(refresh=True)).names["abp485"]["code"] == "ABP-485"
    # A refresh is retried: a cold catalogue answers the gateway with a 502
    # before the service finishes building the inventory, and one retry is what
    # turns that into a served page instead of a silently stale mirror.
    assert calls == 1 + INDEX_ATTEMPTS

    # The failed refresh backed off instead of leaving the flag raised.
    assert (await client.index(refresh=True)).names["abp485"]["code"] == "ABP-485"
    assert calls == 1 + INDEX_ATTEMPTS
    await http_client.aclose()


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
        return httpx.Response(
            200,
            json={
                "ok": True,
                "index_version": "inventory-v2",
                "total": 1001,
                "start": start,
                "items": items,
            },
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    # The page-2 entry is only reachable through the second /index page.
    index = await client.index()
    await http_client.aclose()

    assert index.names["hhd800"]["code"] == "HHD-800"
    assert index.version == "inventory-v2"
    assert starts == [0, 1000]


@pytest.mark.asyncio
async def test_a_clamped_page_size_does_not_skip_the_records_after_it():
    """The service caps ``limit``; the walk has to follow the rows it got."""
    rows = [_entry(f"ABP-{number:04d}", f"ABP-{number:04d}") for number in range(3000)]

    async def handler(request: httpx.Request) -> httpx.Response:
        start = int(request.url.params.get("start", "0"))
        limit = min(int(request.url.params.get("limit", "500")), 1500)
        return httpx.Response(
            200,
            json={
                "ok": True,
                "index_version": "clamped",
                "total": len(rows),
                "start": start,
                "limit": limit,
                "items": rows[start : start + limit],
            },
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    index = await client.index()
    await http_client.aclose()

    # Every record has to arrive, including the ones behind the server's clamp.
    assert len(index.names) == len(rows)
    assert "abp0000" in index.names
    assert "abp2999" in index.names


@pytest.mark.asyncio
async def test_a_broken_index_never_aborts_a_scrape_run(
    monkeypatch: pytest.MonkeyPatch,
):
    """A dead catalogue yields no match instead of an exception."""
    monkeypatch.setattr("app.shared_metadata.INDEX_RETRY_DELAYS", (0.0, 0.0))

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
    return [
        _entry(
            code,
            folder,
            f"{code} 标题",
            cover_url=f"https://c/{code}.jpg",
        )
        for code in codes
    ]


@pytest.mark.asyncio
async def test_flat_folder_name_never_hands_one_cover_to_every_release():
    """``绝顶FUCK`` holds many codes; the old index gave them all entry #1."""
    codes = ["ABP-167", "ABP-259", "HODV-20987", "KCPN-054", "SDNM-181"]
    asked: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/index"):
            return httpx.Response(200, json={"ok": True, "total": 5, "items": _flat_folder_index(codes)})
        raise AssertionError("flat-folder matching must use the fetched index only")

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
    assert asked == []


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
                json={
                    "ok": True,
                    "total": 1,
                    "items": [
                        _entry(
                            "CARIB-010225",
                            "别的目录名",
                            "日期番号",
                            cover_url="https://c/x.jpg",
                        )
                    ],
                },
            )
        raise AssertionError("lookup must not request per-title metadata")

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup("carib-010225-001-FHD.mp4", "/下载/杂项/carib-010225-001-FHD.mp4")
    await http_client.aclose()

    assert match is not None and match.code == "CARIB-010225"


@pytest.mark.asyncio
async def test_a_cold_catalogue_page_is_retried_instead_of_abandoned(
    monkeypatch: pytest.MonkeyPatch,
):
    """The first request after a restart outlives the gateway's timeout.

    The service finishes building its inventory anyway, so the retry is served
    from memory. Without it the pass demoted itself to the disk mirror and the
    covers the user had just uploaded stayed invisible.
    """
    monkeypatch.setattr("app.shared_metadata.INDEX_RETRY_DELAYS", (0.0, 0.0))
    attempts = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.ReadTimeout("cold catalogue", request=request)
        return httpx.Response(
            200, json={"ok": True, "total": 1, "items": [_entry("ABP-485", "ABP-485")]}
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup("ABP-485.mp4", "/光鸭/关键词分类/20260901/ABP-485/ABP-485.mp4")
    await http_client.aclose()

    assert match is not None and match.code == "ABP-485"
    assert attempts == 2


@pytest.mark.asyncio
async def test_a_catalog_that_never_answers_still_raises_after_the_retries(
    monkeypatch: pytest.MonkeyPatch,
):
    """Retrying a dead catalogue must not turn into an endless walk."""
    monkeypatch.setattr("app.shared_metadata.INDEX_RETRY_DELAYS", (0.0, 0.0))
    attempts = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        raise httpx.ReadTimeout("catalogue is down", request=request)

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    # The caller still sees a failure, which is what the fallbacks rely on.
    with pytest.raises(httpx.ReadTimeout):
        await client.index()
    await http_client.aclose()

    assert attempts == INDEX_ATTEMPTS


@pytest.mark.asyncio
async def test_a_path_claimed_twice_prefers_the_row_that_carries_artwork():
    """Multi-part releases repeat one path; the row with a cover has to win."""
    thin = _entry("ABP-485", "ABP-485", "没有封面", poster_url="", nfo_url="")
    rich = _entry("ABP-485", "ABP-485", "有封面", cover_url="https://c/abp485.jpg")

    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/index")
        return httpx.Response(200, json={"ok": True, "total": 2, "items": [thin, rich]})

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    match = await client.lookup("ABP-485.mp4", "/光鸭/关键词分类/20260901/ABP-485/ABP-485.mp4")
    await http_client.aclose()

    assert match is not None and match.title == "有封面"


@pytest.mark.asyncio
async def test_one_path_claimed_by_two_releases_resolves_to_neither():
    """An inconsistent catalogue must not hand one file another release's row."""
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/index")
        return httpx.Response(
            200,
            json={
                "ok": True,
                "total": 2,
                "items": [
                    _entry("ABP-485", "第一个目录", "第一个"),
                    _entry("KCPN-054", "第二个目录", "第二个"),
                ],
            },
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client)

    ambiguity = [
        _entry("ABP-485", "占位", "占位", media_path="共享/同名.mp4"),
        _entry("KCPN-054", "占位", "占位", media_path="共享/同名.mp4"),
    ]

    async def contested(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/index")
        return httpx.Response(200, json={"ok": True, "total": 2, "items": ambiguity})

    contested_client = _client(contested)
    second = SharedMetadataClient(base_url=BASE, token="t", client=contested_client)

    assert await second.lookup("同名.mp4", "/光鸭/关键词分类/共享/同名.mp4") is None
    await http_client.aclose()
    await contested_client.aclose()


@pytest.mark.asyncio
async def test_a_scan_reuses_the_catalogue_when_the_published_version_has_not_moved():
    """A second pass must cost one row, not the whole ~28-page walk."""
    walk_starts: list[int] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/index")
        start = int(request.url.params.get("start", "0"))
        limit = int(request.url.params.get("limit", "500"))
        if limit != 1:
            walk_starts.append(start)
        return httpx.Response(
            200,
            json={
                "ok": True,
                "total": 1,
                "index_version": "6fbf4570d833efb6",
                "items": [_entry("ABP-485", "ABP-485")],
            },
        )

    http_client = _client(handler)
    # ``index_ttl=0`` makes every call past the first one go looking for a
    # reason to re-walk, which is exactly the decision under test.
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client, index_ttl=0.0)

    assert (await client.index()).names["abp485"]["code"] == "ABP-485"
    assert walk_starts == [0]

    walk_starts.clear()
    assert (await client.index()).names["abp485"]["code"] == "ABP-485"
    # The second pass asks the catalogue one question -- has the version moved?
    # -- and gets its answer without walking the pages again.
    assert walk_starts == []
    await http_client.aclose()


@pytest.mark.asyncio
async def test_a_moved_version_walks_the_catalogue_again():
    """A cover uploaded behind a new version still has to reach the library."""
    version = "6fbf4570d833efb6"
    covers = {"ABP-485": "/poster?path=old.jpg"}
    walk_starts: list[int] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        start = int(request.url.params.get("start", "0"))
        limit = int(request.url.params.get("limit", "500"))
        if limit != 1:
            walk_starts.append(start)
        return httpx.Response(
            200,
            json={
                "ok": True,
                "total": 1,
                "index_version": version,
                "items": [
                    _entry("ABP-485", "ABP-485", poster_url=covers["ABP-485"])
                ],
            },
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client, index_ttl=0.0)

    await client.index()
    assert walk_starts == [0]

    covers["ABP-485"] = "/poster?path=new.jpg"
    version = "00000000deadbeef"
    walk_starts.clear()
    refreshed = await client.index()

    assert walk_starts == [0]
    assert refreshed.names["abp485"]["poster_url"].endswith("new.jpg")
    await http_client.aclose()


@pytest.mark.asyncio
async def test_a_day_old_mirror_is_walked_even_when_the_version_matches():
    """Emby-only rows move without moving the version, so a walk is forced."""
    limits: list[int] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        limits.append(int(request.url.params.get("limit", "500")))
        return httpx.Response(
            200,
            json={
                "ok": True,
                "total": 1,
                "index_version": "6fbf4570d833efb6",
                "items": [_entry("ABP-485", "ABP-485")],
            },
        )

    http_client = _client(handler)
    client = SharedMetadataClient(base_url=BASE, token="t", client=http_client, index_ttl=0.0)

    await client.index()
    client._walked_at = time.monotonic() - INDEX_FULL_REFRESH_SECONDS - 1.0
    limits.clear()
    await client.index()

    # No probe at all: the day-old mirror goes straight back to the full walk.
    assert limits and all(limit != 1 for limit in limits)
    await http_client.aclose()
