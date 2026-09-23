"""The account-wide heart, end to end through the real routes.

One list lives on the server rather than on the phone, so the contract the
Android client leans on is: heart a title, see it lead the list; tap it again
and nothing is duplicated; un-heart it and it disappears from both the list and
the detail payload. Paging has to mirror the library pages exactly, or the
favourites grid would stop one screen short of the end.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from test_movie_sort import add_movie_source, headers_for, index_movies
from test_scan import boot


def movie_ids(body: dict) -> list[int]:
    return [int(item["id"]) for item in body["items"]]


@pytest.mark.asyncio
async def test_hearting_a_title_leads_the_list_and_survives_a_second_tap(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    source = await add_movie_source(main, "一线女优", "/movies/first")
    index_movies(main, source, ["AAA-001", "AAA-002", "AAA-003"])
    rows = {
        row["name"]: int(row["id"])
        for row in main.database.movies(sources=(source,), limit=10)
    }
    first, second = rows["AAA-001.mp4"], rows["AAA-002.mp4"]

    with TestClient(main.app) as client:
        headers = headers_for(main)
        assert client.get("/api/movies/favorites", headers=headers).json() == {
            "items": [],
            "total": 0,
            "nextOffset": None,
        }

        assert client.put(
            f"/api/movies/{first}/favorite", headers=headers
        ).json()["favorite"] is True
        assert client.put(
            f"/api/movies/{second}/favorite", headers=headers
        ).json()["favorite"] is True
        # Tapping an already-hearted title is not a second row.
        assert client.put(
            f"/api/movies/{first}/favorite", headers=headers
        ).status_code == 200

        body = client.get("/api/movies/favorites", headers=headers).json()
        # Most recently hearted leads; the repeat tap did not reorder anything
        # because the row was never inserted a second time.
        assert movie_ids(body) == [second, first]
        assert body["total"] == 2
        assert body["nextOffset"] is None

        detail = client.get(f"/api/movies/{first}", headers=headers).json()
        assert detail["favorite"] is True
        assert client.delete(
            f"/api/movies/{first}/favorite", headers=headers
        ).json()["favorite"] is False
        assert client.get(
            f"/api/movies/{first}", headers=headers
        ).json()["favorite"] is False
        assert movie_ids(
            client.get("/api/movies/favorites", headers=headers).json()
        ) == [second]
        assert client.get(
            "/api/movies/favorites", headers=headers
        ).json()["total"] == 1

        # Un-hearting something that was never hearted is still a success: the
        # button has to be safe to mash.
        third = rows["AAA-003.mp4"]
        assert client.delete(
            f"/api/movies/{third}/favorite", headers=headers
        ).status_code == 200
        assert client.get(
            "/api/movies/favorites", headers=headers
        ).json()["total"] == 1


@pytest.mark.asyncio
async def test_favourites_page_the_same_way_a_library_page_does(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    source = await add_movie_source(main, "一线女优", "/movies/first")
    index_movies(main, source, [f"AAA-00{n}" for n in range(1, 6)])
    rows = main.database.movies(sources=(source,), limit=10)
    ids = [int(row["id"]) for row in rows]

    with TestClient(main.app) as client:
        headers = headers_for(main)
        for movie_id in ids:
            client.put(f"/api/movies/{movie_id}/favorite", headers=headers)

        head = client.get(
            "/api/movies/favorites?limit=2", headers=headers
        ).json()
        assert head["total"] == 5
        assert head["nextOffset"] == 2
        assert len(head["items"]) == 2

        tail = client.get(
            "/api/movies/favorites?limit=2&offset=4", headers=headers
        ).json()
        assert len(tail["items"]) == 1
        # Nothing left to fetch, so the client stops asking.
        assert tail["nextOffset"] is None


@pytest.mark.asyncio
async def test_hearting_a_title_that_is_not_in_the_library_is_a_404(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    await add_movie_source(main, "一线女优", "/movies/first")

    with TestClient(main.app) as client:
        headers = headers_for(main)
        assert client.put(
            "/api/movies/424242/favorite", headers=headers
        ).status_code == 404
        assert client.delete(
            "/api/movies/424242/favorite", headers=headers
        ).status_code == 404
