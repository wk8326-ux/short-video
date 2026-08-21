import hashlib
import importlib
import json
import sys

from fastapi.testclient import TestClient


def test_feed_and_asmr_library_routes_are_source_scoped(monkeypatch, tmp_path):
    monkeypatch.setenv("ALIST_BASE_URL", "https://guangya.example")
    monkeypatch.setenv("ALIST_MEDIA_PATH", "/media")
    monkeypatch.setenv("ASMR_BASE_URL", "https://asmr.example")
    monkeypatch.setenv("ASMR_MEDIA_PATH", "/asmr6")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "library.db"))
    monkeypatch.setenv("STATIC_DIR", str(tmp_path / "static"))
    monkeypatch.setenv("AUTH_PASSWORD_HASH", "scrypt:test")
    monkeypatch.setenv("SESSION_SECRET", "test-session-secret-with-at-least-32-characters")
    monkeypatch.setenv("TMDB_API_KEY", "test-tmdb-key")

    sys.modules.pop("app.main", None)
    main = importlib.import_module("app.main")

    spawned_names: list[str] = []

    def record_background(coroutine, *, name: str) -> None:
        spawned_names.append(name)
        coroutine.close()

    monkeypatch.setattr(main, "spawn_background", record_background)

    cookie = f"short_session={main.session_manager.issue()}"
    headers = {"Cookie": cookie}

    with TestClient(main.app) as client:
        async def resolved_direct_url(video_id, path, *, refresh=False):
            return "https://media.example/video.mp4", False

        monkeypatch.setattr(
            main.source_registry.get("guangya").direct_urls,
            "get",
            resolved_direct_url,
        )
        main.database.replace_scan(
            [
                {
                    "path": "/media/short.mp4",
                    "name": "short.mp4",
                    "size": 10,
                    "modified": "2026-08-17",
                    "thumb": "",
                    "duration_seconds": 120,
                },
                {
                    "path": "/media/long.mp4",
                    "name": "long.mp4",
                    "size": 20,
                    "modified": "2026-08-17",
                    "thumb": "",
                    "duration_seconds": 180,
                },
            ]
        )
        main.database.replace_scan(
            [
                {
                    "path": "/asmr6/Author/session.m3u8",
                    "name": "session.m3u8",
                    "size": 30,
                    "modified": "2026-08-17",
                    "thumb": "",
                    "author": "Author",
                    "media_format": "m3u8",
                    # Simulate a stale browser metadata report from an older release.
                    "media_kind": "audio",
                },
                {
                    "path": "/asmr6/Author/session-2.m3u8",
                    "name": "session-2.m3u8",
                    "size": 31,
                    "modified": "2026-08-17",
                    "thumb": "",
                    "author": "Author",
                    "media_format": "m3u8",
                    "media_kind": "video",
                },
            ],
            source="asmr",
        )

        short = client.get("/api/feed?category=short&mode=oldest", headers=headers)
        long = client.get("/api/feed?category=long&mode=oldest", headers=headers)
        authors = client.get("/api/asmr/authors", headers=headers)
        items = client.get("/api/asmr/authors/Author/items", headers=headers)
        item_page = client.get(
            "/api/asmr/authors/Author/items?limit=1&offset=1",
            headers=headers,
        )
        audio_items = client.get("/api/asmr/authors/Author/items?kind=audio", headers=headers)
        play = client.get(
            f"/api/videos/{short.json()['items'][0]['id']}/play",
            headers=headers,
            follow_redirects=False,
        )

        update_without_auth = client.get("/api/app/update")
        missing_update = client.get("/api/app/update", headers=headers)
        update_directory = tmp_path / "app-update"
        update_directory.mkdir()
        (update_directory / "manifest.json").write_text(
            json.dumps(
                {
                    "versionCode": 130,
                    "versionName": "1.3.0",
                    "apkFile": "../outside.apk",
                    "sha256": "0" * 64,
                    "size": 1,
                    "notes": "invalid",
                }
            ),
            encoding="utf-8",
        )
        invalid_update = client.get("/api/app/update", headers=headers)

        apk = update_directory / "short-video-android-v1.3.0-debug.apk"
        apk.write_bytes(b"signed-apk")
        sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
        (update_directory / "manifest.json").write_text(
            json.dumps(
                {
                    "versionCode": 130,
                    "versionName": "1.3.0",
                    "apkFile": apk.name,
                    "sha256": sha256,
                    "size": apk.stat().st_size,
                    "notes": "应用内更新",
                }
            ),
            encoding="utf-8",
        )
        update = client.get("/api/app/update", headers=headers)
        update_head = client.head(update.json()["downloadUrl"], headers=headers)
        update_apk = client.get(update.json()["downloadUrl"], headers=headers)
        update_range = client.get(
            update.json()["downloadUrl"],
            headers={**headers, "Range": "bytes=3-"},
        )
        stale_update = client.get("/api/app/update/apk?versionCode=131", headers=headers)
        added_source = client.post(
            "/api/admin/sources",
            headers=headers,
            json={
                "name": "备用 ASMR",
                "provider": "openlist",
                "baseUrl": "https://backup.example",
                "rootPath": "/library",
                "section": "asmr",
                "scanMode": "authors_recursive",
                "anonymous": False,
                "token": "private-token",
                "username": "reader",
                "password": "private-password",
                "enabled": True,
            },
        )
        source_id = added_source.json()["id"]
        source_list = client.get("/api/admin/sources", headers=headers)
        disabled_source = client.put(
            f"/api/admin/sources/{source_id}",
            headers=headers,
            json={
                "name": "备用 ASMR",
                "provider": "openlist",
                "baseUrl": "https://backup.example",
                "rootPath": "/library",
                "section": "asmr",
                "scanMode": "authors_recursive",
                "anonymous": False,
                "username": "reader",
                "enabled": False,
            },
        )
        disabled_scan = client.post(
            f"/api/admin/sources/{source_id}/scan",
            headers=headers,
        )
        movie_source = client.post(
            "/api/admin/sources",
            headers=headers,
            json={
                "name": "电影测试库",
                "provider": "openlist",
                "baseUrl": "https://movies.example",
                "rootPath": "/movies",
                "section": "movie",
                "anonymous": True,
                "enabled": True,
            },
        )
        movie_source_id = movie_source.json()["id"]
        main.database.replace_scan(
            [
                {
                    "path": "/movies/test.mp4",
                    "name": "test.mp4",
                    "size": 100,
                    "media_format": "mp4",
                }
            ],
            source=movie_source_id,
        )
        main.database.sync_movie_index(movie_source_id, main.parse_movie_filename)
        admin_status = client.get("/api/admin/status", headers=headers)
        movie_metadata = client.post(
            f"/api/admin/sources/{movie_source_id}/metadata",
            headers=headers,
        )
        scan_during_metadata = client.post(
            f"/api/admin/sources/{movie_source_id}/scan",
            headers=headers,
        )

    assert [item["title"] for item in short.json()["items"]] == ["short"]
    assert [item["title"] for item in long.json()["items"]] == ["long"]
    assert len(item_page.json()["items"]) == 1
    assert item_page.json()["total"] == 2
    assert item_page.json()["nextOffset"] is None
    assert authors.json()["items"][0]["name"] == "Author"
    assert authors.json()["items"][0]["videoCount"] == 2
    assert authors.json()["items"][0]["audioCount"] == 0
    assert items.json()["items"][0]["format"] == "m3u8"
    assert items.json()["items"][0]["kind"] == "video"
    assert audio_items.json()["items"] == []
    assert play.status_code == 302
    assert play.headers["location"] == "https://media.example/video.mp4"
    assert play.headers["cache-control"] == "private, max-age=300"
    assert play.headers["vary"] == "Cookie"
    assert update_without_auth.status_code == 401
    assert missing_update.status_code == 404
    assert invalid_update.status_code == 503
    assert update.status_code == 200
    assert update.json()["versionCode"] == 130
    assert update.json()["sha256"] == sha256
    assert update_head.status_code == 200
    assert update_head.content == b""
    assert update_head.headers["content-length"] == str(apk.stat().st_size)
    assert update_apk.content == b"signed-apk"
    assert update_apk.headers["content-type"] == "application/vnd.android.package-archive"
    assert update_apk.headers["x-apk-sha256"] == sha256
    assert update_range.status_code == 206
    assert update_range.content == b"ned-apk"
    assert update_range.headers["accept-ranges"] == "bytes"
    assert update_range.headers["content-range"] == "bytes 3-9/10"
    assert stale_update.status_code == 409
    assert added_source.status_code == 201
    assert added_source.json()["section"] == "asmr"
    assert added_source.json()["tokenConfigured"] is True
    assert "token" not in added_source.json()
    assert "password" not in added_source.json()
    assert len(source_list.json()["items"]) == 4
    assert disabled_source.json()["enabled"] is False
    assert disabled_scan.status_code == 404
    assert movie_source.status_code == 201
    movie_status = next(
        source for source in admin_status.json()["sources"] if source["id"] == movie_source_id
    )
    assert movie_status["movieMetadata"] == {
        "total": 1,
        "pending": 1,
        "matched": 0,
        "ambiguous": 0,
        "unmatched": 0,
        "lastSuccess": None,
    }
    assert movie_metadata.status_code == 202
    assert movie_metadata.json()["started"] is True
    assert movie_metadata.json()["movieMetadata"]["source"] == movie_source_id
    assert f"movie-metadata-{movie_source_id}" in spawned_names
    assert scan_during_metadata.status_code == 409
    assert any(name.startswith("prewarm-asmr-play-") for name in spawned_names)
