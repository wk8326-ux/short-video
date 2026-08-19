import importlib
import sys

import pytest


@pytest.mark.asyncio
async def test_manual_scan_is_isolated_by_alist_source(monkeypatch, tmp_path):
    monkeypatch.setenv("ALIST_BASE_URL", "https://guangya.example")
    monkeypatch.setenv("ALIST_MEDIA_PATH", "/guangya")
    monkeypatch.setenv("ASMR_BASE_URL", "https://asmr.example")
    monkeypatch.setenv("ASMR_MEDIA_PATH", "/asmr6")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "library.db"))
    monkeypatch.setenv("STATIC_DIR", str(tmp_path / "static"))
    monkeypatch.setenv("AUTH_PASSWORD_HASH", "scrypt:test")
    monkeypatch.setenv(
        "SESSION_SECRET",
        "test-session-secret-with-at-least-32-characters",
    )
    sys.modules.pop("app.main", None)
    main = importlib.import_module("app.main")
    main.database.initialize()
    await main.source_registry.initialize()
    calls: list[str] = []
    spawned_names: list[str] = []

    async def scan_guangya():
        calls.append("scan:guangya")
        return ([{"path": "/guangya/video.mp4", "name": "video.mp4"}], 3)

    async def scan_asmr(**_kwargs):
        calls.append("scan:asmr")
        return ([{"path": "/asmr/author/audio.mp3", "name": "audio.mp3"}], 5)

    def replace_scan(_items, *, source="guangya"):
        calls.append(f"replace:{source}")
        return 1

    def record_background(coroutine, *, name: str) -> None:
        spawned_names.append(name)
        coroutine.close()

    guangya = main.source_registry.get("guangya")
    asmr = main.source_registry.get("asmr")
    monkeypatch.setattr(guangya.client, "scan", scan_guangya)
    monkeypatch.setattr(asmr.client, "scan_authors", scan_asmr)
    monkeypatch.setattr(main.database, "replace_scan", replace_scan)
    monkeypatch.setattr(guangya.direct_urls, "clear", lambda: calls.append("clear:guangya"))
    monkeypatch.setattr(asmr.direct_urls, "clear", lambda: calls.append("clear:asmr"))
    monkeypatch.setattr(main, "spawn_background", record_background)

    await main.scan_library(sources=("guangya",))

    assert calls == ["scan:guangya", "replace:guangya", "clear:guangya"]
    assert spawned_names == ["media-metadata-check"]

    calls.clear()
    spawned_names.clear()
    await main.scan_library(sources=("asmr",))

    assert calls == ["scan:asmr", "replace:asmr", "clear:asmr"]
    assert spawned_names == []
    await main.source_registry.close()
