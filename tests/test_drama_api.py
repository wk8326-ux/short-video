"""The drama wall's HTTP surface: wall ordering, episode order and artwork.

Episodes are ordinary videos, so the wall is the only place a series can be
presented wrongly: a series without artwork must still be listed (it is
playable) but must never outrank one that has artwork, a detail page must hand
the player the episodes in play order, and the artwork route has to undo the
picture CDN's AES wrapper instead of forwarding ciphertext.
"""

import io

import httpx
import pytest
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from fastapi.testclient import TestClient

PIL = pytest.importorskip("PIL.Image", reason="Pillow drives the artwork validator")

from test_drama_scan import (
    DRAMA_ROOT,
    SERIES_DESERT,
    SERIES_SWAP,
    add_drama_source,
    drama_rows,
    drama_tree,
)
from test_scan import FakeAList, boot, serve

POSTER_URL = "https://pic.tuafjz.cn/upload_01/drama/poster.jpeg?auth_key=abc"


def _aes_cbc(payload: bytes) -> bytes:
    """Wrap bytes the way the picture CDN does: AES-CBC, no padding."""
    key = b"f5d965df75336270"
    iv = b"97b60394abc2fbe1"
    encryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    return encryptor.update(payload) + encryptor.finalize()


def _padded_jpeg() -> bytes:
    """A real JPEG, zero-padded to a whole number of AES blocks.

    The bytes have to survive both checks the artwork path makes: the picture
    CDN hands us ciphertext, so the payload must be block-aligned, and the
    proxy now decodes what it serves, so a fake header-only blob would be
    rejected. Trailing zeroes after the EOI marker are ignored by decoders.
    """
    image = PIL.new("RGB", (64, 48), (30, 120, 200))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=80)
    body = buffer.getvalue()
    return body + b"\x00" * (-len(body) % 16)


async def scanned_drama_source(main) -> str:
    """Drive a real scan so every assertion reads production-shaped rows."""
    source = await add_drama_source(main, DRAMA_ROOT)
    await serve(main, source, FakeAList(drama_tree()))
    assert await main.scan_source(source) is True
    return source


def headers_for(main) -> dict[str, str]:
    return {"Cookie": f"short_session={main.session_manager.issue()}"}


@pytest.mark.asyncio
async def test_the_wall_lists_every_series_and_ranks_covered_ones_first(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)

    with TestClient(main.app) as client:
        headers = headers_for(main)
        wall = client.get("/api/dramas", headers=headers)
        assert wall.status_code == 200
        body = wall.json()
        assert body["total"] == 3
        assert body["nextOffset"] is None
        # Nothing is scraped yet: playable, but coverless and therefore last.
        assert {item["title"] for item in body["items"]} == {
            "交换游戏",
            "大漠遗孤",
            "▶ 立即观看",
        }
        assert [item["posterUrl"] for item in body["items"]] == [None, None, None]
        assert all(item["matchStatus"] == "pending" for item in body["items"])

        desert = drama_rows(main, source)[SERIES_DESERT]
        main.database.update_drama_metadata(
            str(desert["id"]),
            title="大漠遗孤",
            overview="简介",
            tags='["古装"]',
            poster_url=POSTER_URL,
            metadata_status="matched",
        )

        wall = client.get("/api/dramas", headers=headers).json()
        first = wall["items"][0]
        assert first["id"] == desert["id"]
        assert first["title"] == "大漠遗孤"
        assert first["posterUrl"] == f"/api/dramas/{desert['id']}/poster"
        assert first["matchStatus"] == "matched"
        assert first["episodeCount"] == 2
        assert [item["title"] for item in wall["items"][1:]] == [
            "▶ 立即观看",
            "交换游戏",
        ]
        assert [item["posterUrl"] for item in wall["items"][1:]] == [None, None]

        found = client.get("/api/dramas?q=交换", headers=headers).json()
        assert found["total"] == 1
        assert found["items"][0]["title"] == "交换游戏"
        # The site code stays searchable even when the folder name hides it.
        by_code = client.get("/api/dramas?q=91crdj-1192", headers=headers).json()
        assert [item["title"] for item in by_code["items"]] == ["▶ 立即观看"]

    await main.source_registry.close()


@pytest.mark.asyncio
async def test_detail_hands_the_player_episodes_in_play_order(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)
    swap = drama_rows(main, source)[SERIES_SWAP]

    with TestClient(main.app) as client:
        headers = headers_for(main)
        detail = client.get(f"/api/dramas/{swap['id']}", headers=headers)
        assert detail.status_code == 200
        payload = detail.json()
        assert payload["title"] == "交换游戏"
        assert payload["path"] == SERIES_SWAP
        assert [episode["position"] for episode in payload["episodes"]] == [1, 2, 3]
        assert [episode["title"] for episode in payload["episodes"]] == [
            "交换游戏 第01集 [91crdj-1172-1]",
            "交换游戏 第02集 [91crdj-1172-2]",
            "交换游戏 第03集 [91crdj-1172-3]",
        ]
        assert all(
            episode["playUrl"] == f"/api/videos/{episode['videoId']}/play"
            for episode in payload["episodes"]
        )
        # Opening a series warms the first episode, not the last one.
        assert any(
            name.startswith("prewarm-drama-play-") for name in main.spawned_background
        )

        missing = client.get("/api/dramas/does-not-exist", headers=headers)
        assert missing.status_code == 404

    await main.source_registry.close()


@pytest.mark.asyncio
async def test_the_poster_route_decrypts_the_picture_cdn(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)
    swap = drama_rows(main, source)[SERIES_SWAP]
    main.database.update_drama_metadata(
        str(swap["id"]),
        title="交换游戏",
        poster_url=POSTER_URL,
        metadata_status="matched",
    )

    requested: list[str] = []
    jpeg = _padded_jpeg()

    async def handler(request: httpx.Request) -> httpx.Response:
        requested.append(str(request.url))
        if request.url.path.endswith("broken.jpg"):
            # Not a whole number of AES blocks, so it cannot be decoded.
            return httpx.Response(
                200, headers={"content-type": "image/jpeg"}, content=b"not-a-block"
            )
        return httpx.Response(
            200, headers={"content-type": "image/jpeg"}, content=_aes_cbc(jpeg)
        )

    upstream = httpx.AsyncClient(
        follow_redirects=True, transport=httpx.MockTransport(handler)
    )
    with TestClient(main.app) as client:
        headers = headers_for(main)
        main.movie_image_client = upstream

        response = client.get(f"/api/dramas/{swap['id']}/poster", headers=headers)
        assert response.status_code == 200
        assert response.headers["content-type"] == "image/jpeg"
        assert response.headers["x-movie-image-cache"] == "miss"
        assert response.content[:3] == b"\xff\xd8\xff"
        # The padding the CDN appends comes back as the trailing zero blocks.
        assert response.content[: len(jpeg)] == jpeg
        assert len(response.content) % 16 == 0

        cached = client.get(f"/api/dramas/{swap['id']}/poster", headers=headers)
        assert cached.status_code == 200
        assert cached.headers["x-movie-image-cache"] == "hit"
        assert cached.content == response.content
        assert len(requested) == 1

        main.database.update_drama_metadata(
            str(swap["id"]),
            poster_url=POSTER_URL.replace("poster.jpeg", "broken.jpg"),
        )
        broken = client.get(f"/api/dramas/{swap['id']}/poster", headers=headers)
        assert broken.status_code == 502
        assert len(requested) == 2

        main.movie_image_client = None
    await upstream.aclose()
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_a_series_without_artwork_has_no_poster_route(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)
    swap = drama_rows(main, source)[SERIES_SWAP]

    with TestClient(main.app) as client:
        response = client.get(
            f"/api/dramas/{swap['id']}/poster", headers=headers_for(main)
        )
        assert response.status_code == 404

    await main.source_registry.close()
