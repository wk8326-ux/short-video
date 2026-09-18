"""AList library scans: batching, retirement and resume.

Production broke here twice. A streaming scan deactivated every batch it had
just written (thousands of rows indexed, none of them active), and an
interrupted scan restarted from the root instead of continuing where it
stopped. Both regressions are pinned down below by driving the real
scan_source against an in-memory AList served through httpx.MockTransport, so
traversal, batching, retirement and resume all run through the production code
path.
"""

from __future__ import annotations

import asyncio
import importlib
import json
import posixpath
import sqlite3
import sys
from typing import Any

import httpx
import pytest

from app.alist import AListClient
from app.database import SCAN_STEP_MAX_ATTEMPTS

BASE_URL = "https://alist.example"
ROOT = "/guangya-root"
ASMR_ROOT = "/asmr6"
SESSION_SECRET = "test-session-secret-with-at-least-32-characters"


def file_entry(name: str, *, size: int = 4096) -> dict[str, Any]:
    return {
        "name": name,
        "is_dir": False,
        "size": size,
        "modified": "2026-01-01T00:00:00Z",
    }


def dir_entry(name: str) -> dict[str, Any]:
    return {
        "name": name,
        "is_dir": True,
        "size": 0,
        "modified": "2026-01-01T00:00:00Z",
    }


def build_tree(files: dict[str, list[str]]) -> dict[str, list[dict[str, Any]]]:
    """Expand a directory-to-media-name map into AList listings."""
    tree: dict[str, list[dict[str, Any]]] = {}
    for directory, names in files.items():
        tree.setdefault(directory, [])
        parent, child = posixpath.split(directory.rstrip("/"))
        if child and parent and parent != "/":
            entries = tree.setdefault(parent, [])
            if all(entry["name"] != child for entry in entries):
                entries.append(dir_entry(child))
        tree[directory].extend(file_entry(name) for name in names)
    return tree


class FakeAList:
    """Answers /api/fs/list from an in-memory directory tree."""

    def __init__(
        self,
        tree: dict[str, list[dict[str, Any]]],
        *,
        failures: dict[str, list[int]] | None = None,
        bad_gateways: dict[str, int] | None = None,
    ) -> None:
        self.tree = dict(tree)
        self.failures = {path: list(codes) for path, codes in (failures or {}).items()}
        self.bad_gateways = dict(bad_gateways or {})
        self.listed: list[str] = []
        self.unexpected: list[str] = []

    async def handle(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        if request.url.path != "/api/fs/list":
            self.unexpected.append(request.url.path)
            return httpx.Response(404, json={"code": 404, "message": "unsupported"})
        path = str(body["path"])
        self.listed.append(path)
        codes = self.failures.get(path)
        if codes is not None:
            # An exhausted list keeps failing, so permanent outages stay permanent.
            status = codes.pop(0) if codes else 403
            return httpx.Response(
                status,
                json={"code": status, "message": "alist read failed: " + path},
            )
        if self.bad_gateways.get(path, 0):
            self.bad_gateways[path] -= 1
            return httpx.Response(502, text="bad gateway")
        content = self.tree.get(path)
        if content is None:
            self.unexpected.append(path)
            return httpx.Response(
                404, json={"code": 404, "message": "no such folder: " + path}
            )
        return httpx.Response(
            200,
            json={
                "code": 200,
                "message": "success",
                "data": {"content": list(content), "total": len(content)},
            },
        )


async def boot(monkeypatch, tmp_path):
    """Import app.main against a throwaway database and no real AList."""
    monkeypatch.setenv("ALIST_BASE_URL", BASE_URL)
    monkeypatch.setenv("ALIST_MEDIA_PATH", ROOT)
    monkeypatch.setenv("ASMR_BASE_URL", BASE_URL)
    monkeypatch.setenv("ASMR_MEDIA_PATH", ASMR_ROOT)
    monkeypatch.setenv("ASMR_REQUEST_INTERVAL_SECONDS", "0")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "library.db"))
    monkeypatch.setenv("STATIC_DIR", str(tmp_path / "static"))
    monkeypatch.setenv("AUTH_PASSWORD_HASH", "scrypt:test")
    monkeypatch.setenv("SESSION_SECRET", SESSION_SECRET)
    sys.modules.pop("app.main", None)
    main = importlib.import_module("app.main")
    main.database.initialize()
    await main.source_registry.initialize()

    spawned: list[str] = []

    def record_background(coroutine: Any, *, name: str) -> None:
        spawned.append(name)
        coroutine.close()

    monkeypatch.setattr(main, "spawn_background", record_background)
    main.spawned_background = spawned
    return main


async def serve(main: Any, source: str, fake: FakeAList) -> None:
    """Route one source's AList client through the in-memory fake."""
    client = main.source_registry.get(source).client
    await client._client.aclose()
    client._client = httpx.AsyncClient(
        base_url=BASE_URL, transport=httpx.MockTransport(fake.handle)
    )


def stored_rows(main: Any, source: str) -> dict[str, dict[str, Any]]:
    connection = sqlite3.connect(main.database.path)
    connection.row_factory = sqlite3.Row
    try:
        cursor = connection.execute(
            "SELECT path, active, author, media_kind FROM videos WHERE source = ?",
            (source,),
        )
        return {str(row["path"]): dict(row) for row in cursor.fetchall()}
    finally:
        connection.close()


def active_paths(main: Any, source: str) -> list[str]:
    return sorted(
        path for path, row in stored_rows(main, source).items() if row["active"] == 1
    )


@pytest.mark.asyncio
async def test_app_startup_does_not_scan_media_sources(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    scan_calls: list[Any] = []

    async def record_scan(sources: Any = None) -> None:
        scan_calls.append(sources)

    monkeypatch.setattr(main, "scan_library", record_scan)
    async with main.lifespan(main.app):
        await asyncio.sleep(0)
        assert scan_calls == []
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_scan_finishes_when_source_states_refresh_mid_scan(monkeypatch, tmp_path):
    """Adding another library mid-scan used to strand the running flag.

    ``refresh_source_states`` replaced every per-source dict, so the ``running``
    flag it inherited lived in a different object than the one the scanner
    cleared when it finished. The management screen then showed a scan that had
    already completed and refused to start a new one.
    """
    main = await boot(monkeypatch, tmp_path)
    fake = FakeAList(build_tree({ROOT: ["intro.mp4"], ROOT + "/branch": ["clip.mp4"]}))
    raw_handle = fake.handle
    refreshes: list[int] = []

    async def handle(request: httpx.Request) -> httpx.Response:
        if not refreshes:
            refreshes.append(1)
            main.refresh_source_states()
        return await raw_handle(request)

    fake.handle = handle  # type: ignore[method-assign]
    await serve(main, "guangya", fake)

    assert await main.scan_source("guangya") is True

    assert refreshes
    state = main.scan_state["sources"]["guangya"]
    assert state["running"] is False
    assert main.scan_state["running"] is False
    assert state["status"] == "completed"
    assert main.database.latest_scan_job(source="guangya")["status"] == "completed"
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_state_refresh_reconciles_a_running_flag_with_no_live_scan(
    monkeypatch, tmp_path
):
    """A stale flag from an older process must not survive a refresh.

    The lock is the only thing that proves a scan is in flight, so rebuilding
    the state has to clear any leftover ``running`` marker.
    """
    main = await boot(monkeypatch, tmp_path)
    main.refresh_source_states()
    state = main.scan_state["sources"]["guangya"]
    state["running"] = True
    main.refresh_scan_summary()
    assert main.scan_state["running"] is True

    main.refresh_source_states()

    assert state["running"] is False
    assert main.scan_state["running"] is False
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_tree_scan_indexes_every_directory_level(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    files: dict[str, list[str]] = {ROOT: ["intro.mp4"]}
    for index in range(1, 16):
        branch = ROOT + "/branch-" + f"{index:02d}"
        files[branch] = ["clip-" + f"{index:02d}" + ".mp4", "notes.txt"]
    fake = FakeAList(build_tree(files))
    await serve(main, "guangya", fake)

    assert await main.scan_source("guangya") is True

    expected = sorted(
        [ROOT + "/intro.mp4"]
        + [
            ROOT + "/branch-" + f"{index:02d}" + "/clip-" + f"{index:02d}" + ".mp4"
            for index in range(1, 16)
        ]
    )
    stored = stored_rows(main, "guangya")
    assert active_paths(main, "guangya") == expected
    assert all(row["active"] == 1 for row in stored.values())
    assert main.database.latest_scan_job(source="guangya")["status"] == "completed"
    assert main.scan_state["sources"]["guangya"]["directories"] == 16
    assert main.scan_state["sources"]["guangya"]["lastError"] is None
    assert fake.unexpected == []
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_completed_scan_retires_media_that_disappeared(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    fake = FakeAList(build_tree({ROOT: ["kept.mp4", "gone.mp4"]}))
    await serve(main, "guangya", fake)

    assert await main.scan_source("guangya") is True
    assert active_paths(main, "guangya") == sorted([ROOT + "/gone.mp4", ROOT + "/kept.mp4"])

    fake.tree[ROOT] = [file_entry("kept.mp4"), file_entry("added.mp4")]
    assert await main.scan_source("guangya") is True

    stored = stored_rows(main, "guangya")
    assert active_paths(main, "guangya") == sorted([ROOT + "/added.mp4", ROOT + "/kept.mp4"])
    assert stored[ROOT + "/gone.mp4"]["active"] == 0
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_failed_directory_keeps_existing_media_active(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    fake = FakeAList(build_tree({ROOT: ["existing.mp4"]}))
    await serve(main, "guangya", fake)
    assert await main.scan_source("guangya") is True

    branch = ROOT + "/new-branch"
    fake.tree[ROOT] = [file_entry("existing.mp4"), dir_entry("new-branch")]
    fake.tree[branch] = [file_entry("new.mp4")]
    fake.failures[branch] = [403]

    assert await main.scan_source("guangya") is False

    stored = stored_rows(main, "guangya")
    assert active_paths(main, "guangya") == [ROOT + "/existing.mp4"]
    assert branch + "/new.mp4" not in stored
    assert main.database.latest_incomplete_scan_job(source="guangya") is not None
    assert main.database.latest_scan_job(source="guangya")["status"] == "interrupted"
    state = main.scan_state["sources"]["guangya"]
    assert state["status"] == "interrupted"
    assert "个目录读取失败" in str(state["lastError"])
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_repeatedly_failing_directory_is_skipped_so_scan_can_finish(
    monkeypatch, tmp_path
):
    """One permanently unreadable directory must not stall a whole library.

    Production hit this with a single AList directory that kept timing out: the
    scan stayed 'interrupted' forever, so the media library was never finalized
    and none of the newly found movies ever showed up in the app.
    """
    main = await boot(monkeypatch, tmp_path)
    branch = ROOT + "/stuck"
    other = ROOT + "/other"
    files: dict[str, list[str]] = {ROOT: [], branch: ["kept.mp4"]}
    fake = FakeAList(build_tree(files))
    await serve(main, "guangya", fake)
    assert await main.scan_source("guangya") is True
    assert active_paths(main, "guangya") == [branch + "/kept.mp4"]

    # The branch now answers every request with an error, and new media shows up
    # elsewhere in the same source.
    fake.tree[ROOT] = [dir_entry("stuck"), dir_entry("other")]
    fake.tree[other] = [file_entry("fresh.mp4")]
    fake.failures[branch] = []

    attempts = 0
    budget = SCAN_STEP_MAX_ATTEMPTS
    while attempts < budget:
        attempts += 1
        finalized = await main.scan_source("guangya")
        assert finalized is (attempts >= budget)

    stored = stored_rows(main, "guangya")
    # Media under the unreadable branch is preserved, not reported as deleted.
    assert stored[branch + "/kept.mp4"]["active"] == 1
    assert stored[other + "/fresh.mp4"]["active"] == 1
    assert main.database.latest_scan_job(source="guangya")["status"] == "completed"
    state = main.scan_state["sources"]["guangya"]
    assert state["status"] == "completed"
    assert state["directoriesSkipped"] >= 1
    assert "已跳过" in str(state["lastError"])
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_interrupted_scan_resumes_where_it_stopped(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    files: dict[str, list[str]] = {ROOT: []}
    for index in (1, 2, 3):
        branch = ROOT + "/branch-" + f"{index:02d}"
        files[branch] = ["clip-" + f"{index:02d}" + ".mp4"]
    broken = ROOT + "/branch-02"
    fake = FakeAList(build_tree(files), failures={broken: [403]})
    await serve(main, "guangya", fake)

    assert await main.scan_source("guangya") is False
    # One pass visits everything it can, then reports the failures; the failed
    # directory is not retried inside the same run.
    first_run = list(fake.listed)
    assert first_run == sorted([ROOT, ROOT + "/branch-01", broken, ROOT + "/branch-03"])
    job_id = main.database.latest_incomplete_scan_job(source="guangya")["id"]

    fake.failures.clear()
    assert await main.scan_source("guangya") is True

    # The resumed scan only re-lists the directory that failed last time.
    assert fake.listed[len(first_run):] == [broken]
    assert main.database.latest_scan_job(source="guangya")["id"] == job_id
    assert main.scan_state["sources"]["guangya"]["resumed"] is False
    expected = sorted(
        ROOT + "/branch-" + f"{index:02d}" + "/clip-" + f"{index:02d}" + ".mp4"
        for index in (1, 2, 3)
    )
    assert active_paths(main, "guangya") == expected
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_scan_left_running_by_a_restart_is_reopened(monkeypatch, tmp_path):
    """A deploy in the middle of a scan must not leave a phantom running job.

    The scan worker only lives inside the application process, so a job still
    marked ``running`` after a restart can never advance. Reopening it at
    startup keeps the recorded progress and lets the next manual scan continue
    instead of showing an endless "scanning" state.
    """
    main = await boot(monkeypatch, tmp_path)
    branch = ROOT + "/branch-01"
    fake = FakeAList(
        build_tree({ROOT: [], branch: ["clip.mp4"]}), failures={branch: [403]}
    )
    await serve(main, "guangya", fake)

    assert await main.scan_source("guangya") is False
    job_id = main.database.latest_incomplete_scan_job(source="guangya")["id"]

    # Simulate the process dying while a directory was in flight.
    main.database.update_scan_job_status(job_id, "running")
    with main.database._connect() as connection:
        connection.execute(
            "UPDATE media_scan_directories SET status = 'working'"
            " WHERE job_id = ? AND path = ?",
            (job_id, branch),
        )
    assert main.database.get_scan_job(job_id)["status"] == "running"

    async with main.lifespan(main.app):
        assert main.database.get_scan_job(job_id)["status"] == "interrupted"
        assert main.scan_state["sources"]["guangya"]["status"] == "interrupted"

    await main.source_registry.initialize()
    fake.failures.clear()
    await serve(main, "guangya", fake)
    already_listed = list(fake.listed)

    assert await main.scan_source("guangya") is True
    assert fake.listed[len(already_listed):] == [branch]
    assert main.database.latest_scan_job(source="guangya")["id"] == job_id
    assert active_paths(main, "guangya") == [branch + "/clip.mp4"]
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_transient_bad_gateway_is_retried_without_losing_the_directory(
    monkeypatch, tmp_path
):
    monkeypatch.setattr(
        AListClient, "_transient_http_delay", staticmethod(lambda _remaining: 0.0)
    )
    main = await boot(monkeypatch, tmp_path)
    branch = ROOT + "/flaky-branch"
    fake = FakeAList(build_tree({ROOT: [], branch: ["clip.mp4"]}), bad_gateways={branch: 2})
    await serve(main, "guangya", fake)

    assert await main.scan_source("guangya") is True

    assert fake.listed.count(branch) == 3
    assert active_paths(main, "guangya") == [branch + "/clip.mp4"]
    assert main.database.latest_scan_job(source="guangya")["status"] == "completed"
    assert main.scan_state["sources"]["guangya"]["lastError"] is None
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_changed_source_root_starts_a_fresh_job(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    fake = FakeAList(
        build_tree(
            {
                ROOT: [],
                ROOT + "/branch-01": ["kept.mp4"],
                ROOT + "/branch-02": ["unreachable.mp4"],
            }
        ),
        failures={ROOT + "/branch-02": [403]},
    )
    await serve(main, "guangya", fake)
    assert await main.scan_source("guangya") is False
    old_job = main.database.latest_incomplete_scan_job(source="guangya")["id"]

    main.database.update_media_source("guangya", {"root_path": "/new-root"})
    await main.source_registry.reload_source("guangya")
    replacement = FakeAList(build_tree({"/new-root": ["fresh.mp4"]}))
    await serve(main, "guangya", replacement)

    assert await main.scan_source("guangya") is True

    job = main.database.latest_scan_job(source="guangya")
    assert job["id"] != old_job
    assert main.database.get_scan_job(old_job)["status"] == "superseded"
    assert active_paths(main, "guangya") == ["/new-root/fresh.mp4"]
    assert main.database.latest_scan_job(source="guangya")["status"] == "completed"
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_authors_scan_records_author_and_media_kind(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    files = {
        ASMR_ROOT: [],
        ASMR_ROOT + "/作者甲": ["录音.mp3", "影片.mp4", "直播.m3u8", "封面.jpg"],
        ASMR_ROOT + "/作者乙": ["声音.opus"],
    }
    fake = FakeAList(build_tree(files))
    await serve(main, "asmr6", fake)

    assert await main.scan_source("asmr6") is True

    stored = stored_rows(main, "asmr6")
    assert sorted(stored) == [
        ASMR_ROOT + "/作者乙/声音.opus",
        ASMR_ROOT + "/作者甲/录音.mp3",
        ASMR_ROOT + "/作者甲/影片.mp4",
        ASMR_ROOT + "/作者甲/直播.m3u8",
    ]
    assert stored[ASMR_ROOT + "/作者甲/录音.mp3"]["author"] == "作者甲"
    assert stored[ASMR_ROOT + "/作者甲/录音.mp3"]["media_kind"] == "audio"
    assert stored[ASMR_ROOT + "/作者甲/影片.mp4"]["media_kind"] == "video"
    assert stored[ASMR_ROOT + "/作者甲/直播.m3u8"]["media_kind"] == "video"
    assert "封面.jpg" not in " ".join(stored)
    assert main.scan_state["sources"]["asmr6"]["directories"] == 3
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_scanning_one_source_leaves_other_sources_untouched(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    fake = FakeAList(build_tree({ROOT: ["clip.mp4"]}))
    await serve(main, "guangya", fake)

    await main.scan_library(sources=("guangya",))

    assert active_paths(main, "guangya") == [ROOT + "/clip.mp4"]
    assert active_paths(main, "asmr6") == []
    assert main.database.latest_scan_job(source="asmr6") is None
    assert main.spawned_background == ["media-metadata-check"]
    await main.source_registry.close()


MOVIE_ROOT = "/movies"


@pytest.mark.asyncio
async def test_a_finished_movie_scan_runs_the_cover_pass_on_its_own(
    monkeypatch, tmp_path
):
    """Scanning is the only manual step; covers must land without a second tap."""
    main = await boot(monkeypatch, tmp_path)
    main.database.seed_media_sources(
        [
            {
                "id": "movies",
                "name": "光鸭-电影",
                "provider": "alist",
                "base_url": BASE_URL,
                "root_path": MOVIE_ROOT,
                "section": "movie",
                "scan_mode": "tree",
                "anonymous": False,
                "token": "",
                "username": "",
                "password": "",
                "enabled": True,
            }
        ]
    )
    await main.source_registry.reload()
    fake = FakeAList(build_tree({MOVIE_ROOT: ["hhd800.com@ABP-485.mp4"]}))
    await serve(main, "movies", fake)
    started: list[str] = []
    monkeypatch.setattr(
        main,
        "start_movie_metadata_job",
        lambda source, **kwargs: started.append(source) or True,
    )

    assert await main.scan_source("movies") is True

    assert started == ["movies"]
    assert main.spawned_background == []
    await main.source_registry.close()
