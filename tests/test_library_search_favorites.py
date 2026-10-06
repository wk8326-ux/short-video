from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from test_scan import boot


def session_headers(main) -> dict[str, str]:
    return {"Cookie": f"short_session={main.session_manager.issue()}"}


@pytest.mark.asyncio
async def test_asmr_search_returns_matching_author_folder_and_media(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    with TestClient(main.app) as client:
        assert main.source_registry.ids("asmr")
        main.database.replace_scan(
            [
                {
                    "path": "/asmr6/小苮儿/晚安，小苮儿.mp3",
                    "name": "晚安，小苮儿.mp3",
                    "size": 4096,
                    "author": "小苮儿",
                    "media_kind": "audio",
                    "media_format": "mp3",
                }
            ],
            source="asmr",
        )
        response = client.get(
            "/api/search",
            params={"q": "小苮儿", "section": "asmr"},
            headers=session_headers(main),
        )

    assert response.status_code == 200
    items = response.json()["items"]
    assert [(item["type"], item["title"]) for item in items] == [
        ("author", "小苮儿"),
        ("media", "晚安，小苮儿"),
    ]


@pytest.mark.asyncio
async def test_favorite_status_batch_and_settings_persist(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)

    with TestClient(main.app) as client:
        main.database.replace_scan(
            [
                {
                    "path": "/asmr6/收藏测试/耳边雨声.mp3",
                    "name": "耳边雨声.mp3",
                    "size": 4096,
                    "author": "收藏测试",
                    "media_kind": "audio",
                    "media_format": "mp3",
                }
            ],
            source="asmr",
        )
        media_id = str(main.database.asmr_items(author="收藏测试")[0]["id"])
        main.database.set_media_favorite("asmr", media_id, True)
        main.database.set_media_favorite("short", "55", True)
        headers = session_headers(main)
        status = client.get(
            "/api/favorites/status",
            params=[("section", "asmr"), ("ids", media_id), ("ids", "813")],
            headers=headers,
        )
        page = client.get("/api/favorites?section=asmr", headers=headers)
        saved = client.put(
            "/api/settings",
            json={"skin": "forest-mint"},
            headers=headers,
        )
        settings = client.get("/api/settings", headers=headers)

    assert status.status_code == 200
    assert status.json() == {"ids": [media_id]}
    assert page.status_code == 200
    assert [item["title"] for item in page.json()["items"]] == ["耳边雨声"]
    assert saved.json() == {"skin": "forest-mint"}
    assert settings.json() == {"skin": "forest-mint"}
