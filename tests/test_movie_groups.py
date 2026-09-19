"""The movie wall mirrors the libraries the user added.

Every section is one media source, so the tests below pin the three things the
UI depends on: section order and naming come from the source registry, a source
without indexed movies never renders an empty section, and "load more" keeps
reading the same section instead of falling back to the mixed catalogue.
"""

import pytest
from fastapi.testclient import TestClient

from app.movie_metadata import parse_movie_filename
from test_scan import BASE_URL, boot

BIG_SOURCE_MOVIES = 7


async def add_movie_source(main, name: str, root_path: str) -> str:
    source = main.database.add_media_source(
        {
            "name": name,
            "provider": "alist",
            "base_url": BASE_URL,
            "root_path": root_path,
            "section": "movie",
            "scan_mode": "tree",
            "anonymous": True,
            "token": "",
            "username": "",
            "password": "",
            "enabled": True,
        }
    )
    await main.source_registry.reload_source(source["id"])
    return str(source["id"])


def index_movies(main, source: str, names: list[str]) -> None:
    """Write listing rows the way a finished scan would."""
    main.database.replace_scan(
        [
            {"path": f"/movies/{name}/{name}.mp4", "name": f"{name}.mp4", "size": 2048}
            for name in names
        ],
        source=source,
    )
    main.database.sync_movie_index(source, parse_movie_filename)


def headers_for(main) -> dict[str, str]:
    return {"Cookie": f"short_session={main.session_manager.issue()}"}


@pytest.mark.asyncio
async def test_the_wall_is_split_into_one_section_per_media_source(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    # The names deliberately sort against the insertion order: sections follow
    # the order the user added them, not alphabetical order.
    big = await add_movie_source(main, "夸克 / 电影", "/movies/quark")
    small = await add_movie_source(main, "光鸭 / 电影", "/movies/guangya")
    index_movies(main, big, [f"AAA-{index:03d}" for index in range(BIG_SOURCE_MOVIES)])
    index_movies(main, small, ["BBB-001", "BBB-002"])

    with TestClient(main.app) as client:
        body = client.get("/api/movies/groups", headers=headers_for(main)).json()

    assert body["total"] == BIG_SOURCE_MOVIES + 2
    assert [group["sourceId"] for group in body["groups"]] == [big, small]
    assert [group["name"] for group in body["groups"]] == ["夸克 / 电影", "光鸭 / 电影"]
    first, second = body["groups"]
    # A section previews six titles and only says "load more" when more exist.
    assert len(first["items"]) == 6
    assert first["total"] == BIG_SOURCE_MOVIES
    assert first["nextOffset"] == 6
    assert len(second["items"]) == 2
    assert second["total"] == 2
    assert second["nextOffset"] is None


@pytest.mark.asyncio
async def test_a_source_without_movies_never_becomes_an_empty_section(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    filled = await add_movie_source(main, "光鸭 / 电影", "/movies/guangya")
    await add_movie_source(main, "空目录", "/movies/empty")
    index_movies(main, filled, ["AAA-001"])

    with TestClient(main.app) as client:
        body = client.get("/api/movies/groups", headers=headers_for(main)).json()

    assert [group["sourceId"] for group in body["groups"]] == [filled]


@pytest.mark.asyncio
async def test_loading_more_stays_inside_the_section_it_was_asked_for(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    big = await add_movie_source(main, "光鸭 / 电影", "/movies/guangya")
    small = await add_movie_source(main, "夸克 / 电影", "/movies/quark")
    index_movies(main, big, [f"AAA-{index:03d}" for index in range(BIG_SOURCE_MOVIES)])
    index_movies(main, small, ["BBB-001"])

    with TestClient(main.app) as client:
        headers = headers_for(main)
        rest = client.get(
            f"/api/movies/groups/{big}",
            params={"offset": 6, "limit": 6},
            headers=headers,
        ).json()
        foreign = client.get(
            f"/api/movies/groups/{small}",
            params={"offset": 6, "limit": 6},
            headers=headers,
        ).json()
        missing = client.get("/api/movies/groups/does-not-exist", headers=headers)

    assert [item["source"] for item in rest["items"]] == [big]
    assert len(rest["items"]) == BIG_SOURCE_MOVIES - 6
    assert rest["total"] == BIG_SOURCE_MOVIES
    assert rest["nextOffset"] is None
    # Past the end of a section the client gets an empty tail, never another
    # section's titles.
    assert foreign["items"] == []
    assert foreign["nextOffset"] is None
    assert missing.status_code == 404


@pytest.mark.asyncio
async def test_search_filters_every_section_and_drops_the_rest(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    first = await add_movie_source(main, "光鸭 / 电影", "/movies/guangya")
    second = await add_movie_source(main, "夸克 / 电影", "/movies/quark")
    index_movies(main, first, ["AAA-001", "AAA-002"])
    index_movies(main, second, ["BBB-001"])

    with TestClient(main.app) as client:
        body = client.get(
            "/api/movies/groups",
            params={"q": "AAA"},
            headers=headers_for(main),
        ).json()

    assert body["total"] == 2
    assert [group["sourceId"] for group in body["groups"]] == [first]
    assert all("BBB" not in item["title"] for item in body["groups"][0]["items"])
