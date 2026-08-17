import importlib

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
                }
            ],
            source="asmr",
        )

        short = client.get("/api/feed?category=short&mode=oldest", headers=headers)
        long = client.get("/api/feed?category=long&mode=oldest", headers=headers)
        authors = client.get("/api/asmr/authors", headers=headers)
        items = client.get("/api/asmr/authors/Author/items", headers=headers)
        audio_items = client.get("/api/asmr/authors/Author/items?kind=audio", headers=headers)

    assert [item["title"] for item in short.json()["items"]] == ["short"]
    assert [item["title"] for item in long.json()["items"]] == ["long"]
    assert authors.json()["items"][0]["name"] == "Author"
    assert authors.json()["items"][0]["videoCount"] == 1
    assert authors.json()["items"][0]["audioCount"] == 0
    assert items.json()["items"][0]["format"] == "m3u8"
    assert items.json()["items"][0]["kind"] == "video"
    assert audio_items.json()["items"] == []
    assert any(name.startswith("prewarm-asmr-play-") for name in spawned_names)
