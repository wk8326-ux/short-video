import asyncio
import importlib
import io
import sys
import time

import httpx
import pytest
from fastapi.testclient import TestClient

PIL = pytest.importorskip("PIL.Image", reason="Pillow drives the artwork downscaler")


def _load_main(monkeypatch, tmp_path):
    monkeypatch.setenv("ALIST_BASE_URL", "https://guangya.example")
    monkeypatch.setenv("ALIST_MEDIA_PATH", "/media")
    monkeypatch.setenv("ASMR_BASE_URL", "https://asmr.example")
    monkeypatch.setenv("ASMR_MEDIA_PATH", "/asmr6")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "library.db"))
    monkeypatch.setenv("STATIC_DIR", str(tmp_path / "static"))
    monkeypatch.setenv("AUTH_PASSWORD_HASH", "scrypt:test")
    monkeypatch.setenv("SESSION_SECRET", "test-session-secret-with-at-least-32-characters")
    sys.modules.pop("app.main", None)
    return importlib.import_module("app.main")


def test_movie_images_use_same_origin_proxy_and_follow_redirects(monkeypatch, tmp_path):
    main = _load_main(monkeypatch, tmp_path)
    cookie = f"short_session={main.session_manager.issue()}"
    headers = {"Cookie": cookie}
    requests: list[httpx.Request] = []
    mode = {"value": "image"}

    async def image_handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if mode["value"] == "invalid":
            return httpx.Response(200, headers={"content-type": "text/html"}, content=b"<html>")
        if request.url.host in {"images.example", "www.javbus.com"}:
            return httpx.Response(302, headers={"location": "https://cdn.example/cover.jpg"})
        return httpx.Response(
            200,
            headers={"content-type": "image/jpeg"},
            content=_cover_jpeg(900, 600),
        )

    with TestClient(main.app) as client:
        main.movie_image_client = httpx.AsyncClient(
            follow_redirects=True,
            transport=httpx.MockTransport(image_handler),
        )
        async def skip_wall_prewarm(rows, **_kwargs):
            return None

        monkeypatch.setattr(main, "prewarm_movie_wall_images", skip_wall_prewarm)
        source_response = client.post(
            "/api/admin/sources",
            headers=headers,
            json={
                "name": "电影",
                "provider": "alist",
                "baseUrl": "https://movies.example",
                "rootPath": "/movies",
                "section": "movie",
                "scanMode": "tree",
                "anonymous": True,
                "enabled": True,
            },
        )
        assert source_response.status_code == 201
        source_id = str(source_response.json()["id"])
        main.database.replace_scan(
            [
                {
                    "path": "/movies/ABP-485.mp4",
                    "name": "ABP-485.mp4",
                    "size": 100,
                    "media_format": "mp4",
                }
            ],
            source=source_id,
        )
        main.database.sync_movie_index(source_id, main.parse_movie_filename)
        movie = main.database.movies(sources=(source_id,))[0]
        main.database.update_movie_metadata(
            movie["id"],
            poster_url="https://www.javbus.com/pics/cover/cover.jpg",
            backdrop_url="https://images.example/backdrop.jpg",
            match_status="matched",
        )

        listing = client.get("/api/movies", headers=headers)
        assert listing.status_code == 200
        item = listing.json()["items"][0]
        assert item["posterUrl"].startswith(f"/api/movies/{movie['id']}/poster?v=")
        # The wall, the library page and the detail page all paint the same
        # picture, so the API hands out one URL: three different strings for one
        # upstream file meant three cache entries and a blank detail page on a
        # device that had already drawn the wall cover.
        assert item["backdropUrl"] == item["posterUrl"]
        assert item["wallUrl"] == item["posterUrl"]

        row = main.database.movies(sources=(source_id,))[0]
        row["backdrop_url"] = ""
        row["poster_url"] = ""
        row["thumb"] = "https://thumbs.example/frame.jpg"
        fallback_payload = main.public_movie(row)
        assert fallback_payload["wallUrl"] == f"/api/videos/{movie['video_id']}/poster"

        requests.clear()
        image = client.get(item["posterUrl"], headers=headers)
        assert image.status_code == 200
        assert image.headers["content-type"] == "image/jpeg"
        # Public on purpose: the Cloudflare edge in front of this host only
        # stores a response it is allowed to reuse, and every artwork URL
        # carries the ``?v=`` fingerprint so a swapped cover gets a new URL.
        assert image.headers["cache-control"] == "public, max-age=604800, immutable"
        assert image.headers["x-movie-image-cache"] == "miss"
        assert image.content.startswith(b"\xff\xd8\xff")
        poster_requests = [
            request for request in requests if request.url.host in {"www.javbus.com", "cdn.example"}
        ]
        assert [request.url.host for request in poster_requests] == ["www.javbus.com", "cdn.example"]
        assert poster_requests[0].headers["referer"] == "https://www.javbus.com/"
        assert list((tmp_path / "movie-images").glob("*.bin"))

        not_modified = client.get(
            item["posterUrl"],
            headers={**headers, "If-None-Match": image.headers["etag"]},
        )
        assert not_modified.status_code == 304
        assert not_modified.headers["x-movie-image-cache"] == "hit"

        poster_request_count = len(poster_requests)
        mode["value"] = "invalid"
        cached = client.get(item["posterUrl"], headers=headers)
        assert cached.status_code == 200
        assert cached.headers["x-movie-image-cache"] == "hit"
        assert cached.content == image.content
        assert len([request for request in requests if request.url.host in {"www.javbus.com", "cdn.example"}]) == poster_request_count


def test_a_swapped_cover_gets_a_new_url(monkeypatch, tmp_path):
    """A re-index has to be able to replace a cover a device already cached.

    The public endpoint is addressed by movie id, so without the upstream URL in
    the query a swap leaves the URL unchanged and the seven-day image TTL keeps
    the old picture on screen.
    """
    main = _load_main(monkeypatch, tmp_path)
    row = {
        "id": 7,
        "video_id": 9,
        "name": "ABP-485.mp4",
        "poster_url": "https://c0.jdbstatic.com/covers/ve/veyGnb.jpg",
        "backdrop_url": "",
        "thumb": "",
    }

    original = row["poster_url"]
    first = main.public_movie(row)["posterUrl"]
    row["poster_url"] = "https://c0.jdbstatic.com/covers/ve/other.jpg"
    second = main.public_movie(row)["posterUrl"]

    assert first == f"/api/movies/7/poster?v={main._artwork_token(original)}"
    assert first != second
    # Re-reading the same row keeps the URL stable, so unchanged covers stay cached.
    assert main.public_movie(row)["posterUrl"] == second


def _cover_png(width: int, height: int) -> bytes:
    image = PIL.new("RGB", (width, height), (120, 30, 200))
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def _cover_jpeg(width: int, height: int) -> bytes:
    image = PIL.new("RGB", (width, height), (30, 120, 200))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=80)
    return buffer.getvalue()


def _seed_movie_with_cover(main, client, headers, cover: bytes, handler=None):
    """Create one movie whose cover is served by a stub upstream."""

    if handler is None:
        async def image_handler(_request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, headers={"content-type": "image/png"}, content=cover)
    else:
        async def image_handler(request: httpx.Request) -> httpx.Response:
            return await handler(request)

    source_id = str(
        client.post(
            "/api/admin/sources",
            headers=headers,
            json={
                "name": "电影",
                "provider": "alist",
                "baseUrl": "https://movies.example",
                "rootPath": "/movies",
                "section": "movie",
                "scanMode": "tree",
                "anonymous": True,
                "enabled": True,
            },
        ).json()["id"]
    )
    main.database.replace_scan(
        [{"path": "/movies/ABP-485.mp4", "name": "ABP-485.mp4", "size": 100}],
        source=source_id,
    )
    main.database.sync_movie_index(source_id, main.parse_movie_filename)
    movie = main.database.movies(sources=(source_id,))[0]
    main.database.update_movie_metadata(
        movie["id"],
        poster_url="https://c0.jdbstatic.com/covers/ve/veyGnb.jpg",
        match_status="matched",
    )
    return movie, httpx.AsyncClient(
        follow_redirects=True, transport=httpx.MockTransport(image_handler)
    )


async def _skip_prewarm(rows, **_kwargs):
    return None


def test_the_wall_asks_for_a_scaled_webp_instead_of_the_original(monkeypatch, tmp_path):
    """A 1080px cover is 1.1 MB; the wall shows it 170dp wide.

    The client sends ``?w=`` and the proxy answers with a WebP at that size, so
    a phone on a slow link never downloads the full-size original to paint a
    grid cell.
    """
    main = _load_main(monkeypatch, tmp_path)
    cookie = f"short_session={main.session_manager.issue()}"
    headers = {"Cookie": cookie}
    with TestClient(main.app) as client:
        monkeypatch.setattr(main, "prewarm_movie_wall_images", _skip_prewarm)
        movie, upstream = _seed_movie_with_cover(
            main, client, headers, _cover_png(1080, 1620)
        )
        main.movie_image_client = upstream

        scaled = client.get(f"/api/movies/{movie['id']}/poster?w=480", headers=headers)
        assert scaled.status_code == 200
        assert scaled.headers["content-type"] == "image/webp"
        with PIL.open(io.BytesIO(scaled.content)) as decoded:
            assert decoded.width == 480
            # The source here is portrait, so it is scaled and nothing else: a
            # 480x320 answer would mean it had been cut into the wall's wide
            # frame, which is exactly what must not happen to a tall cover.
            assert decoded.height == 720

        # A nearby request snaps onto the same ladder step instead of creating a
        # distinct cache entry for every phone density on the market.
        same_step = client.get(f"/api/movies/{movie['id']}/poster?w=430", headers=headers)
        assert same_step.headers["x-movie-image-cache"] == "hit"
        assert same_step.content == scaled.content

        original = client.get(f"/api/movies/{movie['id']}/poster", headers=headers)
        assert original.status_code == 200
        assert original.headers["content-type"] == "image/png"
        assert original.content != scaled.content
        # The full-size copy the scaled request downloaded is kept, so the
        # detail page that follows reads it from disk instead of pulling the
        # same picture across the Pacific a second time.
        assert original.headers["x-movie-image-cache"] == "hit"


def test_a_small_cover_is_never_upscaled(monkeypatch, tmp_path):
    """An 800px source asked for at 1080px stays as it is."""
    main = _load_main(monkeypatch, tmp_path)
    cookie = f"short_session={main.session_manager.issue()}"
    headers = {"Cookie": cookie}
    with TestClient(main.app) as client:
        monkeypatch.setattr(main, "prewarm_movie_wall_images", _skip_prewarm)
        movie, upstream = _seed_movie_with_cover(
            main, client, headers, _cover_png(800, 1200)
        )
        main.movie_image_client = upstream

        response = client.get(
            f"/api/movies/{movie['id']}/poster?w=1080", headers=headers
        )
        assert response.status_code == 200
        assert response.headers["content-type"] == "image/png"
        assert response.content == _cover_png(800, 1200)


def test_every_width_is_cut_from_the_one_copy_that_was_already_fetched(monkeypatch, tmp_path):
    """Opening a film must not open a second stream to the artwork host.

    The wall draws a 480px WebP the moment a library page loads; tapping a card
    then asks for the full-size cover. Deriving that cover from the byte-for-byte
    copy the wall just cached keeps the detail page instant, and it is the only
    path that survives the artwork hosts that drop a share of connections.
    """
    main = _load_main(monkeypatch, tmp_path)
    cookie = f"short_session={main.session_manager.issue()}"
    headers = {"Cookie": cookie}
    upstream_calls: list[str] = []

    async def counting_handler(request: httpx.Request) -> httpx.Response:
        upstream_calls.append(str(request.url))
        return httpx.Response(
            200,
            headers={"content-type": "image/png"},
            content=_cover_png(1080, 1620),
        )

    with TestClient(main.app) as client:
        monkeypatch.setattr(main, "prewarm_movie_wall_images", _skip_prewarm)
        movie, upstream = _seed_movie_with_cover(
            main, client, headers, b"", handler=counting_handler
        )
        main.movie_image_client = upstream

        wall = client.get(f"/api/movies/{movie['id']}/poster?w=480", headers=headers)
        assert wall.headers["x-movie-image-cache"] == "miss"
        assert len(upstream_calls) == 1

        # The original the scaled request already paid for is left behind, so
        # the detail page reads it from disk instead of fetching it again.
        detail = client.get(f"/api/movies/{movie['id']}/poster", headers=headers)
        assert detail.status_code == 200
        assert detail.headers["content-type"] == "image/png"
        assert detail.headers["x-movie-image-cache"] == "hit"
        assert detail.content == _cover_png(1080, 1620)
        assert len(upstream_calls) == 1

        # And the other direction: a wall that renders after the detail page
        # re-cuts the cached original rather than re-downloading it.
        other_width = client.get(
            f"/api/movies/{movie['id']}/poster?w=720", headers=headers
        )
        assert other_width.status_code == 200
        assert other_width.headers["content-type"] == "image/webp"
        assert other_width.headers["x-movie-image-cache"] == "derived"
        with PIL.open(io.BytesIO(other_width.content)) as decoded:
            assert decoded.width == 720
        assert len(upstream_calls) == 1

        cached_derivative = client.get(
            f"/api/movies/{movie['id']}/poster?w=720", headers=headers
        )
        assert cached_derivative.headers["x-movie-image-cache"] == "hit"
        assert cached_derivative.content == other_width.content
        assert len(upstream_calls) == 1


def _banded_cover_png(width: int, height: int) -> bytes:
    """A 16:9-ish picture whose outer edges are a colour nothing else uses.

    Marking the two edges is how the crop test proves the trim actually
    happened: the wall frame is narrower than the shot, so those colours have
    to be gone from what the phone receives.
    """
    image = PIL.new("RGB", (width, height), (0, 200, 0))
    pixels = image.load()
    band = max(1, width // 16)
    for x in range(band):
        for y in range(height):
            pixels[x, y] = (220, 0, 0)
            pixels[width - 1 - x, y] = (0, 0, 220)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def test_a_wide_cover_is_cut_to_the_wall_frame(monkeypatch, tmp_path):
    """A 16:9 cover is trimmed to 3:2, evenly off both sides.

    The wall, the library page and the detail page are one fixed 3:2 frame. A
    16:9 cover drawn whole inside it would sit letterboxed and a couple of
    sizes smaller than its neighbours, so the proxy cuts the middle out of it
    instead and every cell ends up the same shape.
    """
    main = _load_main(monkeypatch, tmp_path)
    cookie = f"short_session={main.session_manager.issue()}"
    headers = {"Cookie": cookie}
    with TestClient(main.app) as client:
        monkeypatch.setattr(main, "prewarm_movie_wall_images", _skip_prewarm)
        cover = _banded_cover_png(1600, 900)
        movie, upstream = _seed_movie_with_cover(main, client, headers, cover)
        main.movie_image_client = upstream

        cut = client.get(f"/api/movies/{movie['id']}/poster?w=480", headers=headers)
        assert cut.status_code == 200
        assert cut.headers["content-type"] == "image/webp"
        with PIL.open(io.BytesIO(cut.content)) as decoded:
            assert (decoded.width, decoded.height) == (480, 320)
            pixels = decoded.convert("RGB").load()
            for x, y in ((0, 160), (479, 160), (240, 160)):
                red, green, blue = pixels[x, y]
                assert green > 150, (x, y, pixels[x, y])
                assert red < 90 and blue < 90, (x, y, pixels[x, y])

        # The full-size copy the wall's re-cut was cut from is the upstream
        # file itself, byte for byte: every width is derived from it, and an
        # already-trimmed original would compound the crop.
        original = client.get(f"/api/movies/{movie['id']}/poster", headers=headers)
        assert original.status_code == 200
        assert original.content == cover


def test_a_resized_cover_is_cached_under_the_shape_it_was_rendered_in(monkeypatch, tmp_path):
    """Changing the render tag retires the old shape instead of mixing two.

    The scaled copies live in a persistent disk cache keyed by upstream URL and
    width. If the shape were not part of that key, the phone would keep reading
    yesterday's 16:9 derivative for a URL that now promises 3:2.
    """
    main = _load_main(monkeypatch, tmp_path)
    url = "https://c0.jdbstatic.com/covers/ve/veyGnb.jpg"

    current = main._movie_image_request_key(url, 480)
    assert main.COVER_RENDER_TAG in current
    # The unused width is the pristine upstream file: it is shared by every
    # shape, so its own key must not move when the shape changes.
    assert main._movie_image_request_key(url, None) == url

    monkeypatch.setattr(main, "COVER_RENDER_TAG", "16x9")
    assert main._movie_image_request_key(url, 480) != current

    token_before = main._artwork_token(url)
    monkeypatch.setattr(main, "COVER_RENDER_TAG", "3x2-legacy")
    assert main._artwork_token(url) != token_before


@pytest.mark.asyncio
async def test_a_cover_that_is_junk_under_an_image_header_is_never_served(
    monkeypatch, tmp_path
):
    """Some hosts answer 200 + ``image/jpeg`` with a body no decoder can open.

    One artwork host does exactly that: a couple of hundred kilobytes that
    merely *start* like a picture. The body used to be cached and handed to the
    phone, which painted an empty tile for ever - and, because nothing had
    failed, "cover first" went on ranking that film in front of every cover
    that actually renders.
    """
    main = _load_main(monkeypatch, tmp_path)
    junk = b"\xff\xd8\xff\xe0" + bytes(range(256)) * 16
    poster_url = "https://c0.jdbstatic.com/covers/ve/dead.jpg"
    calls: list[str] = []
    deferred: list[object] = []

    async def junk_handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return httpx.Response(200, headers={"content-type": "image/jpeg"}, content=junk)

    def record_background(coroutine, *, name):
        deferred.append(coroutine)

    monkeypatch.setattr(main, "spawn_background", record_background)
    main.database.initialize()
    main.movie_image_client = httpx.AsyncClient(
        follow_redirects=True, transport=httpx.MockTransport(junk_handler)
    )

    with pytest.raises(main.HTTPException) as failure:
        await main._proxy_movie_image(poster_url, width=480)
    assert failure.value.status_code == 502
    assert calls == [poster_url]
    assert not list((tmp_path / "movie-images").glob("*.bin"))

    # The verdict reaches the ledger the ranking reads, and it is a week out
    # rather than six hours: the same bytes will be waiting tomorrow.
    assert main.movie_image_bad_urls == {poster_url}
    await asyncio.gather(*deferred)
    assert main.database.movie_image_failed_urls() == {poster_url}
    with main.database._lock, main.database._connect() as connection:
        expires_at = connection.execute(
            "SELECT expires_at FROM movie_image_failures WHERE url = ?",
            (poster_url,),
        ).fetchone()[0]
    remaining = expires_at - int(time.time())
    assert main.MOVIE_IMAGE_CORRUPT_RANK_SECONDS - 60 <= remaining <= main.MOVIE_IMAGE_CORRUPT_RANK_SECONDS

    # And the next scroll is answered from the memo, without the host.
    with pytest.raises(main.HTTPException) as repeat:
        await main._proxy_movie_image(poster_url, width=480)
    assert repeat.value.status_code == 502
    assert calls == [poster_url]
    await main.movie_image_client.aclose()


def test_a_cover_cached_before_this_check_is_dropped_and_re_fetched(monkeypatch, tmp_path):
    """The disk cache still holds bodies written by a build that trusted the header.

    Those entries paint nothing, so the read drops them and lets the fetch path
    ask the host again instead of serving poison until the entry ages out.
    """
    main = _load_main(monkeypatch, tmp_path)
    cookie = f"short_session={main.session_manager.issue()}"
    headers = {"Cookie": cookie}
    calls: list[str] = []
    junk = b"\xff\xd8\xff\xe0" + bytes(range(256)) * 16

    async def image_handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return httpx.Response(
            200, headers={"content-type": "image/png"}, content=_cover_png(1200, 800)
        )

    with TestClient(main.app) as client:
        monkeypatch.setattr(main, "prewarm_movie_wall_images", _skip_prewarm)
        movie, upstream = _seed_movie_with_cover(
            main, client, headers, b"", handler=image_handler
        )
        main.movie_image_client = upstream

        poisoned = main._movie_image_request_key(movie["poster_url"], 480)
        main._write_movie_image_cache(poisoned, junk, "image/jpeg", "poison")

        cover = client.get(f"/api/movies/{movie['id']}/poster?w=480", headers=headers)
        assert cover.status_code == 200
        assert cover.headers["content-type"] == "image/webp"
        assert cover.headers["x-movie-image-cache"] == "miss"
        assert len(calls) == 1
        with PIL.open(io.BytesIO(cover.content)) as decoded:
            assert decoded.width == 480
