import hashlib
import importlib
import json

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

    main = importlib.import_module("app.main")

    async def idle_scan(stop):
        await stop.wait()

    monkeypatch.setattr(main, "scan_loop", idle_scan)
    spawned_names: list[str] = []

    def record_background(coroutine, *, name: str) -> None:
        spawned_names.append(name)
        coroutine.close()

    monkeypatch.setattr(main, "spawn_background", record_background)

    async def resolved_direct_url(video_id, path, *, refresh=False):
        return "https://media.example/video.mp4", False

    monkeypatch.setattr(main.direct_urls, "get", resolved_direct_url)
    cookie = f"short_session={main.session_manager.issue()}"
    headers = {"Cookie": cookie}

    with TestClient(main.app) as client:
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
        stale_update = client.get("/api/app/update/apk?versionCode=131", headers=headers)

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
    assert stale_update.status_code == 409
    assert any(name.startswith("prewarm-asmr-play-") for name in spawned_names)
