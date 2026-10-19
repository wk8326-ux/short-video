from __future__ import annotations

from fastapi.testclient import TestClient

from test_scan import boot


def auth_headers(main) -> dict[str, str]:
    return {"Cookie": f"short_session={main.session_manager.issue()}"}


async def test_sync_bootstrap_and_changes_are_bounded_and_cursored(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    main.database.replace_scan(
        [
            {
                "path": "/media/local-one.mp4",
                "name": "local-one.mp4",
                "size": 10,
                "duration_seconds": 120,
                "modified": "2026-10-18T00:00:00Z",
                "thumb": "",
            },
            {
                "path": "/media/local-two.mp4",
                "name": "local-two.mp4",
                "size": 20,
                "duration_seconds": 121,
                "modified": "2026-10-18T00:00:00Z",
                "thumb": "",
            },
        ]
    )
    headers = auth_headers(main)

    with TestClient(main.app) as client:
        bootstrap = client.get(
            "/api/sync/bootstrap",
            params={"section": "short", "limit": 1},
            headers=headers,
        )
        assert bootstrap.status_code == 200
        body = bootstrap.json()
        assert body["schemaVersion"] == 1
        assert body["nextOffset"] == 1
        assert [item["item"]["title"] for item in body["upserts"]] == ["local-one"]
        assert "playUrl" not in body["upserts"][0]["item"]
        assert "password" not in body["sources"][0]
        assert "token" not in body["sources"][0]

        cursor = body["serverRevision"]
        main.database.replace_scan(
            [
                {
                    "path": "/media/local-one-renamed.mp4",
                    "name": "local-one-renamed.mp4",
                    "size": 11,
                    "duration_seconds": 120,
                    "modified": "2026-10-19T00:00:00Z",
                    "thumb": "",
                }
            ]
        )
        changes = client.get(
            "/api/sync/changes",
            params={"section": "short", "cursor": cursor},
            headers=headers,
        )

    assert changes.status_code == 200
    change_body = changes.json()
    assert [item["item"]["title"] for item in change_body["upserts"]] == [
        "local-one-renamed"
    ]
    assert any(item["operation"] == "delete" for item in change_body["deletes"])
    assert next(item for item in change_body["deletes"] if item["entityType"] == "media")["section"] == "feed"
    assert change_body["nextCursor"] >= cursor


async def test_sync_deletes_are_filtered_by_source_section(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    main.database.replace_scan(
        [{
            "path": "/feed/keep.mp4",
            "name": "keep.mp4",
            "size": 10,
            "duration_seconds": 120,
            "modified": "2026-10-18T00:00:00Z",
            "thumb": "",
        }],
        source="guangya",
    )
    main.database.replace_scan(
        [{
            "path": "/asmr/keep.mp3",
            "name": "keep.mp3",
            "size": 10,
            "duration_seconds": 20,
            "modified": "2026-10-18T00:00:00Z",
            "thumb": "",
            "author": "作者",
            "media_kind": "audio",
        }],
        source="asmr",
    )
    cursor = main.database.catalog_revision()
    main.database.replace_scan([], source="guangya")
    main.database.replace_scan([], source="asmr")

    with TestClient(main.app) as client:
        short = client.get(
            "/api/sync/changes",
            params={"section": "short", "cursor": cursor},
            headers=auth_headers(main),
        )
        asmr = client.get(
            "/api/sync/changes",
            params={"section": "asmr", "cursor": cursor},
            headers=auth_headers(main),
        )

    assert short.status_code == 200
    assert asmr.status_code == 200
    assert {item["sourceId"] for item in short.json()["deletes"]} == {"guangya"}
    assert {item["sourceId"] for item in asmr.json()["deletes"]} == {"asmr"}


async def test_sync_delete_source_keeps_deleted_source_in_scope(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    main.database.add_media_source({
        "id": "removed-feed",
        "name": "待删除源",
        "provider": "alist",
        "base_url": "https://removed.example",
        "root_path": "/videos",
        "section": "feed",
        "scan_mode": "tree",
        "anonymous": True,
        "enabled": True,
    })
    await main.source_registry.reload()
    main.database.replace_scan(
        [{
            "path": "/videos/removed.mp4",
            "name": "removed.mp4",
            "size": 10,
            "duration_seconds": 120,
            "modified": "2026-10-18T00:00:00Z",
            "thumb": "",
        }],
        source="removed-feed",
    )
    cursor = main.database.catalog_revision()
    assert main.database.delete_media_source("removed-feed") is True
    await main.source_registry.reload()

    with TestClient(main.app) as client:
        changes = client.get(
            "/api/sync/changes",
            params={"section": "short", "cursor": cursor},
            headers=auth_headers(main),
        )

    assert changes.status_code == 200
    deletes = changes.json()["deletes"]
    assert any(item["entityType"] == "media" and item["sourceId"] == "removed-feed" for item in deletes)
    assert any(item["entityType"] == "source" and item["sourceId"] == "removed-feed" for item in deletes)
    assert main.database.get_deleted_media_source("removed-feed")["section"] == "feed"


async def test_sync_changes_can_follow_one_source_without_leaking_other_sources(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    for source_id in ("feed-a", "feed-b"):
        main.database.add_media_source(
            {
                "id": source_id,
                "name": source_id,
                "provider": "alist",
                "base_url": "https://source.example",
                "root_path": f"/{source_id}",
                "section": "feed",
                "scan_mode": "tree",
                "anonymous": True,
                "enabled": True,
            }
        )
    await main.source_registry.reload()
    main.database.replace_scan(
        [{
            "path": "/a/one.mp4",
            "name": "one.mp4",
            "size": 10,
            "duration_seconds": 120,
        }],
        source="feed-a",
    )
    main.database.replace_scan(
        [{
            "path": "/b/one.mp4",
            "name": "one.mp4",
            "size": 20,
            "duration_seconds": 120,
        }],
        source="feed-b",
    )
    cursor = main.database.catalog_revision()
    main.database.replace_scan(
        [{
            "path": "/a/two.mp4",
            "name": "two.mp4",
            "size": 30,
            "duration_seconds": 120,
        }],
        source="feed-a",
    )
    main.database.replace_scan(
        [{
            "path": "/b/two.mp4",
            "name": "two.mp4",
            "size": 40,
            "duration_seconds": 120,
        }],
        source="feed-b",
    )

    with TestClient(main.app) as client:
        changes = client.get(
            "/api/sync/changes",
            params={
                "section": "feed",
                "sourceId": "feed-a",
                "cursor": cursor,
            },
            headers=auth_headers(main),
        )

    assert changes.status_code == 200
    body = changes.json()
    assert [source["id"] for source in body["sources"]] == ["feed-a"]
    assert {item["item"]["sourceId"] for item in body["upserts"]} == {"feed-a"}
    assert not any(item["item"].get("title") == "two" and item["item"].get("sourceId") == "feed-b" for item in body["upserts"])
