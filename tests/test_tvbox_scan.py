from __future__ import annotations

import asyncio
from dataclasses import dataclass

from app.tvbox import TVBoxCategory, TVBoxPage


@dataclass
class FakeRuntime:
    config: dict
    client: object


class FakeClient:
    def __init__(self, totals: dict[str, int]):
        self.totals = totals
        self.listed: list[str] = []

    async def categories(self):
        return [
            TVBoxCategory("1", "无码专区"),
            TVBoxCategory("2", "麻豆传媒"),
        ]

    async def list_page(self, category: str, page: int = 1):
        self.listed.append((category, page))
        return TVBoxPage(items=[], total=self.totals[category], page_count=1)

    async def list_items_all(self, category: str, **kwargs):
        self.listed.append((category, "all"))
        return []


class FakeDatabase:
    def __init__(self, existing: dict[str, int]):
        self.existing = existing
        self.added: list[dict] = []
        self.updated: list[str] = []
        self.scan_states: list[tuple] = []

    def get_media_source(self, source_id: str):
        return self.added[-1] if self.added and source_id.endswith("-2") else None

    def add_media_source(self, values: dict):
        self.added.append(values)
        return values

    def update_media_source(self, source_id: str, values: dict):
        self.updated.append(source_id)
        return values

    def movie_count(self, *, sources):
        return self.existing.get(sources[0], 0)

    def replace_scan(self, rows, *, source):
        raise AssertionError("an already-complete category must not be rescanned")

    def sync_tvbox_movie_index(self, *args, **kwargs):
        raise AssertionError("an already-complete category must not be rescanned")

    def sync_movie_index(self, *args, **kwargs):
        raise AssertionError("an already-complete category must not be rescanned")

    def delete_tvbox_children_not_in(self, *args, **kwargs):
        return None

    def update_source_scan_state(self, source_id, **kwargs):
        self.scan_states.append((source_id, kwargs))


def test_tvbox_virtual_child_scan_targets_only_its_category(monkeypatch, tmp_path):
    monkeypatch.setenv("ALIST_BASE_URL", "https://guangya.example")
    monkeypatch.setenv("ALIST_MEDIA_PATH", "/media")
    monkeypatch.setenv("ASMR_BASE_URL", "https://asmr.example")
    monkeypatch.setenv("ASMR_MEDIA_PATH", "/asmr6")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "library.db"))
    monkeypatch.setenv("STATIC_DIR", str(tmp_path / "static"))
    monkeypatch.setenv("AUTH_PASSWORD_HASH", "scrypt:test")
    monkeypatch.setenv("SESSION_SECRET", "test-session-secret-with-at-least-32-characters")
    monkeypatch.setenv("SHARED_METADATA_BASE_URL", "http://shared.test/api/shared-metadata")
    monkeypatch.setenv("SHARED_METADATA_TOKEN", "test-token")

    from app import main as main_module

    client = FakeClient({"1": 10899, "2": 4379})
    database = FakeDatabase({"tvbox-parent-2": 4379})
    registry = type("Registry", (), {"get": lambda self, source_id: FakeRuntime(
        config={"base_url": "https://example.com/config.json", "virtual_category_id": "2"},
        client=client,
    )})()

    monkeypatch.setattr(main_module, "source_registry", registry)
    monkeypatch.setattr(main_module, "database", database)
    monkeypatch.setattr(main_module, "refresh_scan_summary", lambda: None)

    assert asyncio.run(main_module.scan_tvbox_source("parent")) is True
    # Page one is read only to compare the database total with the upstream total.
    assert client.listed == [("2", 1)]
    assert [row["id"] for row in database.added] == ["tvbox-parent-2"]
    assert database.scan_states and database.scan_states[-1][0] == "parent"
