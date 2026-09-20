"""The drama wall mirrors the downloader's own folders.

One short-drama source ships several categories side by side ("短剧", "漫剧",
…), so the wall is grouped per first-level folder the way the movie wall is
grouped per media source. The tests below pin what the UI leans on: the
sections come from the folders themselves, "load more" stays inside the
category it was asked for, and a category with nothing indexed never renders
as an empty section.
"""

import sqlite3

import pytest
from fastapi.testclient import TestClient

from test_drama_scan import (
    SERIES_DESERT,
    SERIES_PLACEHOLDER,
    SERIES_SWAP,
    add_drama_source,
    drama_rows,
    drama_tree,
)
from test_scan import FakeAList, boot, serve

SHORT = "短剧"
MANGA = "漫剧"


async def scanned_drama_source(main) -> str:
    """Drive a real scan so every assertion reads production-shaped rows."""
    source = await add_drama_source(main)
    await serve(main, source, FakeAList(drama_tree()))
    assert await main.scan_source(source) is True
    return source


def headers_for(main) -> dict[str, str]:
    return {"Cookie": f"short_session={main.session_manager.issue()}"}


@pytest.mark.asyncio
async def test_the_wall_splits_into_one_section_per_category_folder(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)

    with TestClient(main.app) as client:
        body = client.get(
            "/api/dramas/groups",
            params={"perGroup": 1},
            headers=headers_for(main),
        ).json()

    # The bigger category leads, then the smaller one, and both are named after
    # the folders the downloader created.
    assert body["total"] == 3
    assert [group["name"] for group in body["groups"]] == [SHORT, MANGA]
    short, manga = body["groups"]
    assert short["groupId"] == SHORT
    assert len(short["items"]) == 1
    assert short["total"] == 2
    assert short["nextOffset"] == 1
    assert len(manga["items"]) == 1
    assert manga["total"] == 1
    assert manga["nextOffset"] is None
    assert manga["items"][0]["path"] == SERIES_DESERT
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_load_more_stays_inside_the_category_it_was_asked_for(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)
    swap = drama_rows(main, source)[SERIES_SWAP]

    with TestClient(main.app) as client:
        headers = headers_for(main)
        rest = client.get(
            "/api/dramas",
            params={"category": SHORT, "offset": 1, "limit": 6},
            headers=headers,
        ).json()
        foreign = client.get(
            "/api/dramas",
            params={"category": MANGA, "offset": 6, "limit": 6},
            headers=headers,
        ).json()
        unknown = client.get(
            "/api/dramas",
            params={"category": "不存在"},
            headers=headers,
        ).json()

    assert rest["total"] == 2
    # Coverless series sort by title, and the first slice of the category is
    # already on the wall, so the tail is exactly what is left of "短剧".
    assert [item["path"] for item in rest["items"]] == [SERIES_SWAP]
    assert rest["nextOffset"] is None
    # Past the end of a category the client gets an empty tail, never another
    # category's series.
    assert foreign["items"] == []
    assert foreign["total"] == 1
    assert unknown["items"] == []
    assert unknown["total"] == 0
    assert unknown["nextOffset"] is None
    assert swap["category"] == SHORT
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_search_filters_every_section_and_drops_the_rest(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    await scanned_drama_source(main)

    with TestClient(main.app) as client:
        body = client.get(
            "/api/dramas/groups",
            params={"q": "大漠"},
            headers=headers_for(main),
        ).json()

    assert body["total"] == 1
    assert [group["name"] for group in body["groups"]] == [MANGA]
    assert [item["title"] for item in body["groups"][0]["items"]] == ["大漠遗孤"]
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_a_series_without_a_category_folder_reads_as_the_default(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)
    # Folders the downloader dropped straight under the root, and rows indexed
    # before the column existed, both have no category of their own.
    connection = sqlite3.connect(main.database.path)
    try:
        connection.executemany(
            """
            INSERT INTO dramas(id, source, root_path, folder, title, folder_title,
                               category, episode_count)
            VALUES(?, ?, ?, ?, ?, ?, ?, 1)
            """,
            [
                ("bare", source, "/root", "无分类剧 [91crdj-1]", "无分类剧", "无分类剧", None),
                ("blank", source, "/root", "空白分类 [91crdj-2]", "空白分类", "空白分类", "  "),
            ],
        )
        connection.commit()
    finally:
        connection.close()

    with TestClient(main.app) as client:
        body = client.get("/api/dramas/groups", params={"perGroup": 24}, headers=headers_for(main)).json()
        short = client.get(
            "/api/dramas",
            params={"category": SHORT},
            headers=headers_for(main),
        ).json()

    assert [group["name"] for group in body["groups"]] == [SHORT, MANGA]
    assert short["total"] == 4
    assert {item["path"] for item in short["items"]} == {
        SERIES_SWAP,
        SERIES_PLACEHOLDER,
        "无分类剧 [91crdj-1]",
        "空白分类 [91crdj-2]",
    }
    await main.source_registry.close()
