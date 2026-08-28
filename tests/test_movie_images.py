import importlib
import sys

import httpx
from fastapi.testclient import TestClient


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
            content=b"\xff\xd8\xff\xe0fake-jpeg",
        )

    with TestClient(main.app) as client:
        main.movie_image_client = httpx.AsyncClient(
            follow_redirects=True,
            transport=httpx.MockTransport(image_handler),
        )
        async def skip_wall_prewarm(rows):
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
        assert item["posterUrl"] == f"/api/movies/{movie['id']}/poster"
        assert item["backdropUrl"] == f"/api/movies/{movie['id']}/backdrop"
        assert item["wallUrl"] == item["backdropUrl"]

        requests.clear()
        image = client.get(item["posterUrl"], headers=headers)
        assert image.status_code == 200
        assert image.headers["content-type"] == "image/jpeg"
        assert image.headers["cache-control"] == "private, max-age=86400"
        assert image.headers["x-movie-image-cache"] == "miss"
        assert image.content.startswith(b"\xff\xd8\xff")
        poster_requests = [
            request for request in requests if request.url.host in {"www.javbus.com", "cdn.example"}
        ]
        assert [request.url.host for request in poster_requests] == ["www.javbus.com", "cdn.example"]
        assert poster_requests[0].headers["referer"] == "https://www.javbus.com/"
        assert list((tmp_path / "movie-images").glob("*.bin"))

        poster_request_count = len(poster_requests)
        mode["value"] = "invalid"
        cached = client.get(item["posterUrl"], headers=headers)
        assert cached.status_code == 200
        assert cached.headers["x-movie-image-cache"] == "hit"
        assert cached.content == image.content
        assert len([request for request in requests if request.url.host in {"www.javbus.com", "cdn.example"}]) == poster_request_count
