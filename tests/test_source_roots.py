"""One media source can summarise several root folders.

A library that is spread over more than one folder — an active downloader
directory plus an archive, say — belongs in one source, because the wall and
the management screen render one section per source. What has to hold is that
every folder is really traversed, that the definition comes back intact, and
that redefining the folders invalidates a half-finished traversal of the old
ones instead of resuming a library that no longer exists.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.scanner import STEP_DIRECTORY, initial_steps
from test_scan import (  # noqa: F401 - the shared AList harness
    BASE_URL,
    ROOT,
    FakeAList,
    active_paths,
    boot,
    build_tree,
    serve,
)

ARCHIVE_ROOT = "/guangya-archive"


def source_payload(**overrides):
    payload = {
        "name": "光鸭 / 合集",
        "provider": "alist",
        "baseUrl": BASE_URL,
        "rootPath": "",
        "rootPaths": [ROOT, ARCHIVE_ROOT],
        "section": "feed",
        "anonymous": True,
        "token": "",
        "username": "",
        "password": "",
        "enabled": True,
    }
    payload.update(overrides)
    return payload


def headers_for(main) -> dict[str, str]:
    return {"Cookie": f"short_session={main.session_manager.issue()}"}


def test_initial_steps_cover_every_root_once():
    steps = initial_steps("tree", [ROOT, ARCHIVE_ROOT, ROOT])
    assert steps == [(STEP_DIRECTORY, ROOT), (STEP_DIRECTORY, ARCHIVE_ROOT)]
    # A single folder is still accepted on its own: it is a one-item list.
    assert initial_steps("tree", ROOT) == [(STEP_DIRECTORY, ROOT)]


@pytest.mark.asyncio
async def test_a_source_scans_every_folder_it_was_given(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    with TestClient(main.app) as client:
        created = client.post(
            "/api/admin/sources", json=source_payload(), headers=headers_for(main)
        )
        assert created.status_code == 201
        body = created.json()
        assert body["rootPaths"] == [ROOT, ARCHIVE_ROOT]
        # The single-path field keeps naming the first folder, so an app built
        # before this change still shows something sensible.
        assert body["rootPath"] == ROOT
        source = body["id"]

    # Leaving the TestClient closes the registry, as a shutdown would.
    await main.source_registry.initialize()
    await serve(
        main,
        source,
        FakeAList(
            build_tree(
                {
                    ROOT: ["downloader.mp4"],
                    ARCHIVE_ROOT: ["archived.mp4"],
                    ARCHIVE_ROOT + "/2019": ["older.mp4"],
                }
            )
        ),
    )
    assert await main.scan_source(source) is True
    assert active_paths(main, source) == [
        ARCHIVE_ROOT + "/2019/older.mp4",
        ARCHIVE_ROOT + "/archived.mp4",
        ROOT + "/downloader.mp4",
    ]


@pytest.mark.asyncio
async def test_a_source_without_any_folder_is_rejected(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    with TestClient(main.app) as client:
        response = client.post(
            "/api/admin/sources",
            json=source_payload(rootPath="", rootPaths=[]),
            headers=headers_for(main),
        )
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_editing_the_folder_list_restarts_the_traversal(monkeypatch, tmp_path):
    """A job describes the folders it was started for, and nothing else."""
    main = await boot(monkeypatch, tmp_path)
    with TestClient(main.app) as client:
        headers = headers_for(main)
        source = client.post(
            "/api/admin/sources", json=source_payload(), headers=headers
        ).json()["id"]
        await serve(
            main,
            source,
            FakeAList(
                build_tree({ROOT: ["downloader.mp4"]}),
                failures={ARCHIVE_ROOT: [403]},
            ),
        )
        assert await main.scan_source(source) is False
        old_job = main.database.latest_incomplete_scan_job(source=source)["id"]

        updated = client.put(
            f"/api/admin/sources/{source}",
            json=source_payload(rootPaths=[ARCHIVE_ROOT]),
            headers=headers,
        )
        assert updated.status_code == 200
        assert updated.json()["rootPaths"] == [ARCHIVE_ROOT]
        assert updated.json()["rootPath"] == ARCHIVE_ROOT

    await main.source_registry.initialize()
    replacement = FakeAList(build_tree({ARCHIVE_ROOT: ["archived.mp4"]}))
    await serve(main, source, replacement)
    assert await main.scan_source(source) is True
    assert main.database.get_scan_job(old_job)["status"] == "superseded"
    assert active_paths(main, source) == [ARCHIVE_ROOT + "/archived.mp4"]
