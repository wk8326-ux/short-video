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
        self.last_values: dict = {}
        self.existing_source: dict | None = None
        self.by_key: dict[tuple[str, str, str], dict] = {}
        self.category_snapshots: list[dict] = []

    def get_media_source(self, source_id: str):
        if self.existing_source and self.existing_source["id"] == source_id:
            return self.existing_source
        return self.added[-1] if self.added and source_id.endswith("-2") else None

    def find_media_source_by_key(self, base_url, root_paths, section):
        return self.by_key.get((base_url, ",".join(root_paths), section))

    def add_media_source(self, values: dict):
        self.added.append(values)
        self.by_key[(values["base_url"], ",".join(values["root_paths"]), values["section"])] = values
        return values

    def update_media_source(self, source_id: str, values: dict):
        self.updated.append(source_id)
        self.last_values = values
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

    def delete_tvbox_category_snapshots_not_in(self, *args, **kwargs):
        return None

    def record_tvbox_category_snapshot(self, **values):
        self.category_snapshots.append(values)

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
    # A virtual library scans itself, so the completed count is keyed by the
    # library's own id rather than by a ``tvbox-<parent>-<slug>`` child id.
    database = FakeDatabase({"parent": 4379})
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
    # The library refreshes itself instead of generating a ``tvbox-parent-2``
    # duplicate on top of it.
    assert [row["id"] for row in database.added] == ["parent"]
    # Re-scanning a library must not make it its own parent: it kept the TVBox
    # source that owns the configuration, so it stays inside that group in the
    # management screen instead of turning into a standalone source.
    assert database.added[0]["parent_source_id"] == "parent"
    assert database.scan_states and database.scan_states[-1][0] == "parent"


def test_tvbox_virtual_child_rescan_keeps_its_real_parent(monkeypatch, tmp_path):
    """A library that already belongs to a TVBox source keeps that parent.

    Scanning the library reuses its own id as the child id, so the parent
    cannot be derived from the id the scan was called with.
    """
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

    library_id = "tvbox-source-abc-2"
    client = FakeClient({"1": 10899, "2": 4379})
    database = FakeDatabase({library_id: 4379})
    # The library already exists and is owned by the TVBox source ``source-abc``.
    database.existing_source = {
        "id": library_id,
        "name": "麻豆传媒",
        "parent_source_id": "source-abc",
        "sort_order": 2,
    }
    registry = type("Registry", (), {"get": lambda self, source_id: FakeRuntime(
        config={"base_url": "https://example.com/api.php", "virtual_category_id": "2"},
        client=client,
    )})()

    monkeypatch.setattr(main_module, "source_registry", registry)
    monkeypatch.setattr(main_module, "database", database)
    monkeypatch.setattr(main_module, "refresh_scan_summary", lambda: None)

    assert asyncio.run(main_module.scan_tvbox_source(library_id)) is True
    assert database.updated == [library_id]
    # The row that was written back still points at the owning TVBox source.
    assert database.last_values["parent_source_id"] == "source-abc"
    assert database.last_values["id"] == library_id


def test_tvbox_rescan_adopts_the_natural_key_owner(monkeypatch, tmp_path):
    """A stale derived id must not overwrite the row that owns the key.

    Upstream renamed a category after the first import. The new derived id did
    not exist, but the natural key did; updating the derived id again is what
    produced the UNIQUE error and stopped the rest of the scan.
    """
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

    client = FakeClient({"1": 4379})
    database = FakeDatabase({"old-id": 0})
    database.by_key[(
        "https://example.com/api.php",
        "/tvbox/1",
        "movie",
    )] = {
        "id": "old-id",
        "parent_source_id": "parent",
        "sort_order": 1,
    }
    registry = type("Registry", (), {"get": lambda self, source_id: FakeRuntime(
        config={"base_url": "https://example.com/api.php"},
        client=client,
    )})()

    monkeypatch.setattr(main_module, "source_registry", registry)
    monkeypatch.setattr(main_module, "database", database)
    monkeypatch.setattr(main_module, "refresh_scan_summary", lambda: None)

    assert asyncio.run(main_module.scan_tvbox_source("parent")) is True
    assert database.added == []
    assert database.updated == ["old-id"]
    assert database.last_values["id"] == "old-id"
