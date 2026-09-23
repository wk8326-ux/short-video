"""Ordering rules the movie wall depends on.

Four things have to hold at once, and each of them broke in production at
least once:

* editing a library (adding a root folder, renaming it) must never move it on
  the wall -- only a genuinely new library lands last;
* "cover first" must put every title that actually renders a picture ahead of
  every title that does not, whichever of the three artwork columns it uses;
* title and time both have two directions, because tapping the active sort
  again flips the arrow;
* the wall previews a library's first six titles, so the preview has to follow
  that library's own order instead of the wall-wide default.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.movie_metadata import parse_movie_filename
from test_scan import BASE_URL, boot


async def add_movie_source(main, name: str, *roots: str) -> str:
    source = main.database.add_media_source(
        {
            "name": name,
            "provider": "alist",
            "base_url": BASE_URL,
            "root_path": roots[0],
            "root_paths": list(roots),
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


def index_movies(main, source: str, names: list[str], *, modified: str = "") -> None:
    main.database.replace_scan(
        [
            {
                "path": f"/movies/{name}/{name}.mp4",
                "name": f"{name}.mp4",
                "size": 2048,
                "modified": modified or None,
            }
            for name in names
        ],
        source=source,
    )
    main.database.sync_movie_index(source, parse_movie_filename)


def headers_for(main) -> dict[str, str]:
    return {"Cookie": f"short_session={main.session_manager.issue()}"}


def titles(body: dict) -> list[str]:
    return [item["title"] for item in body["items"]]


@pytest.mark.asyncio
async def test_editing_a_library_keeps_its_place_on_the_wall(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    first = await add_movie_source(main, "一线女优", "/movies/first")
    second = await add_movie_source(main, "二线女优", "/movies/second")
    third = await add_movie_source(main, "月度更新", "/movies/third")

    assert main.source_registry.ids("movie") == (first, second, third)

    with TestClient(main.app) as client:
        response = client.put(
            f"/api/admin/sources/{first}",
            headers=headers_for(main),
            json={
                "name": "一线女优",
                "provider": "alist",
                "baseUrl": BASE_URL,
                "rootPath": "/movies/first",
                "rootPaths": ["/movies/first", "/movies/first-archive"],
                "section": "movie",
                "anonymous": True,
                "token": "",
                "username": "",
                "password": "",
                "enabled": True,
            },
        )
        assert response.status_code == 200
        assert response.json()["rootPaths"] == ["/movies/first", "/movies/first-archive"]
        # The registry drives the wall order, and the database drives the next
        # restart: both have to agree that an edited library did not move.
        # (Asserted inside the client context: closing it shuts the registry
        # down, which is the app's own lifespan behaviour, not a bug.)
        assert main.source_registry.ids("movie") == (first, second, third)
        assert [
            source["id"]
            for source in main.database.list_media_sources()
            if source["section"] == "movie"
        ] == [first, second, third]

        # A library that is added afterwards still lands at the end.
        fourth = await add_movie_source(main, "新人女优", "/movies/fourth")
        assert main.source_registry.ids("movie") == (first, second, third, fourth)


@pytest.mark.asyncio
async def test_cover_first_ranks_every_kind_of_artwork_ahead_of_none(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    source = await add_movie_source(main, "一线女优", "/movies/first")
    index_movies(main, source, ["AAA-001", "AAA-002", "AAA-003", "AAA-004"])
    rows = {
        row["name"]: row for row in main.database.movies(sources=(source,), limit=10)
    }
    poster_only = rows["AAA-001.mp4"]
    backdrop_only = rows["AAA-002.mp4"]
    thumb_only = rows["AAA-003.mp4"]
    coverless = rows["AAA-004.mp4"]

    main.database.update_movie_metadata(
        poster_only["id"],
        poster_url="https://images.example/poster.jpg",
        match_status="matched",
    )
    main.database.update_movie_metadata(
        backdrop_only["id"],
        backdrop_url="https://images.example/backdrop.jpg",
        match_status="matched",
    )
    # A backdrop-only row still renders a picture, which is what "cover first"
    # promises; ranking on poster_url alone used to hide it below blank cards.
    with main.database._lock, main.database._connect() as connection:
        connection.execute(
            "UPDATE videos SET thumb = ? WHERE id = ?",
            ("https://thumbs.example/frame.jpg", thumb_only["video_id"]),
        )
        connection.commit()

    with TestClient(main.app) as client:
        body = client.get(
            "/api/movies", params={"sort": "cover"}, headers=headers_for(main)
        ).json()

    assert titles(body) == ["AAA-001", "AAA-002", "AAA-003", "AAA-004"]
    assert body["items"][-1]["id"] == coverless["id"]
    assert all(item["posterUrl"] for item in body["items"][:3])


@pytest.mark.asyncio
async def test_cover_first_ranks_artwork_the_host_no_longer_serves_as_absent(
    monkeypatch, tmp_path
):
    """A declared cover the host answers 502 for is not a cover.

    The wall paints the same empty placeholder for a dead URL as for a row with
    no artwork at all, so "cover first" has to rank them together. This was the
    reported shape of the bug: 一线女优 sorted by cover still led with tiles
    whose pictures never arrived, because every one of those rows *declared* a
    poster and the query only ever looked at the column, never at whether the
    file was still there.
    """
    main = await boot(monkeypatch, tmp_path)
    source = await add_movie_source(main, "一线女优", "/movies/first")
    # AAA-001 sorts first by title, so the only thing that can push it down is
    # the verdict about its picture.
    index_movies(main, source, ["AAA-001", "AAA-002"])
    rows = {
        row["name"]: row for row in main.database.movies(sources=(source,), limit=10)
    }
    dead = rows["AAA-001.mp4"]
    live = rows["AAA-002.mp4"]
    main.database.update_movie_metadata(
        dead["id"],
        poster_url="https://images.example/dead.jpg",
        match_status="matched",
    )
    main.database.update_movie_metadata(
        live["id"],
        poster_url="https://images.example/live.jpg",
        match_status="matched",
    )
    main.database.remember_movie_image_failure(
        "https://images.example/dead.jpg",
        ttl_seconds=3600,
    )
    assert main.database.movie_image_failed_urls() == {"https://images.example/dead.jpg"}

    with TestClient(main.app) as client:
        headers = headers_for(main)
        body = client.get("/api/movies", params={"sort": "cover"}, headers=headers).json()
        assert titles(body) == ["AAA-002", "AAA-001"]
        assert all(item["posterUrl"] for item in body["items"])

        # The verdict is a lease, not a life sentence: once the host serves the
        # file again the row leads on its own merits.
        main.database.forget_movie_image_failure("https://images.example/dead.jpg")
        body = client.get("/api/movies", params={"sort": "cover"}, headers=headers).json()
        assert titles(body) == ["AAA-001", "AAA-002"]

        # And an expired verdict is the same as none: the ledger is swept by the
        # same comparison the ranking uses, so a stale row can never hide art.
        with main.database._lock, main.database._connect() as connection:
            connection.execute(
                """
                INSERT INTO movie_image_failures(url, expires_at) VALUES(?, ?)
                ON CONFLICT(url) DO UPDATE SET expires_at = excluded.expires_at
                """,
                ("https://images.example/dead.jpg", 1),
            )
            connection.commit()
        body = client.get("/api/movies", params={"sort": "cover"}, headers=headers).json()
        assert titles(body) == ["AAA-001", "AAA-002"]


@pytest.mark.asyncio
async def test_title_and_time_each_sort_in_two_directions(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await add_movie_source(main, "一线女优", "/movies/first")
    index_movies(main, source, ["AAA-003", "AAA-001", "AAA-002"])
    with main.database._lock, main.database._connect() as connection:
        for name, modified in (
            ("AAA-001", "2026-01-01T00:00:00Z"),
            ("AAA-002", "2026-02-01T00:00:00Z"),
            ("AAA-003", "2026-03-01T00:00:00Z"),
        ):
            connection.execute(
                "UPDATE videos SET modified = ? WHERE name = ?",
                (modified, f"{name}.mp4"),
            )
        connection.commit()

    with TestClient(main.app) as client:
        headers = headers_for(main)

        def order(**params) -> list[str]:
            return titles(
                client.get("/api/movies", params=params, headers=headers).json()
            )

        assert order(sort="title") == ["AAA-001", "AAA-002", "AAA-003"]
        assert order(sort="title", dir="desc") == ["AAA-003", "AAA-002", "AAA-001"]
        # Newest first is the historical default; "asc" asks for the oldest.
        assert order(sort="time") == ["AAA-003", "AAA-002", "AAA-001"]
        assert order(sort="time", dir="asc") == ["AAA-001", "AAA-002", "AAA-003"]


@pytest.mark.asyncio
async def test_the_wall_previews_each_library_in_that_library_s_own_order(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    sorted_library = await add_movie_source(main, "一线女优", "/movies/first")
    other_library = await add_movie_source(main, "二线女优", "/movies/second")
    index_movies(main, sorted_library, ["AAA-001", "AAA-002", "AAA-003"])
    index_movies(main, other_library, ["BBB-001", "BBB-002", "BBB-003"])

    with TestClient(main.app) as client:
        body = client.get(
            "/api/movies/groups",
            params={"sorts": f"{sorted_library}:title:desc"},
            headers=headers_for(main),
        ).json()

    assert [group["sourceId"] for group in body["groups"]] == [
        sorted_library,
        other_library,
    ]
    assert [item["title"] for item in body["groups"][0]["items"]] == [
        "AAA-003",
        "AAA-002",
        "AAA-001",
    ]
    # The library the client did not mention keeps the wall-wide default.
    assert [item["title"] for item in body["groups"][1]["items"]] == [
        "BBB-001",
        "BBB-002",
        "BBB-003",
    ]
