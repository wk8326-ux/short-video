"""Continue-watching lives with the library, not with the phone.

The app used to keep resume positions in its own preferences, so a second
device started every film from zero and a reinstall forgot everything. The
position is now reported to the server and read back through two literal
routes that sit in front of the id routes. What matters here is that the
report is honoured, that a finished film stops occupying the row, that a
series appears once with the episode to actually resume, and that removing a
library takes its history with it.
"""

from __future__ import annotations

import sqlite3

import pytest
from fastapi.testclient import TestClient

from test_drama_api import scanned_drama_source
from test_drama_scan import SERIES_DESERT, SERIES_SWAP, drama_rows
from test_movie_groups import add_movie_source, index_movies
from test_scan import boot

MOVIE_ROOT = "/movies/continue"


async def movie_source_with(main, names: list[str]) -> str:
    source = await add_movie_source(main, "光鸭 / 电影", MOVIE_ROOT)
    index_movies(main, source, names)
    return source


def episode_ids(main, source: str, series_id: str) -> dict[str, int]:
    """Map episode file names to their media row ids, in filename order."""
    connection = sqlite3.connect(main.database.path)
    connection.row_factory = sqlite3.Row
    try:
        rows = connection.execute(
            """
            SELECT id, name FROM videos
            WHERE source = ? AND series_id = ? AND active = 1
            ORDER BY name
            """,
            (source, series_id),
        ).fetchall()
    finally:
        connection.close()
    return {str(row["name"]): int(row["id"]) for row in rows}


def media_id(main, source: str, name: str) -> int:
    connection = sqlite3.connect(main.database.path)
    try:
        row = connection.execute(
            "SELECT id FROM videos WHERE source = ? AND name = ?", (source, name)
        ).fetchone()
    finally:
        connection.close()
    assert row is not None, f"{name} was never indexed"
    return int(row[0])


def stored_progress(main) -> dict[int, dict[str, int]]:
    connection = sqlite3.connect(main.database.path)
    connection.row_factory = sqlite3.Row
    try:
        rows = connection.execute("SELECT * FROM watch_progress").fetchall()
    finally:
        connection.close()
    return {int(row["video_id"]): dict(row) for row in rows}


def headers_for(main) -> dict[str, str]:
    return {"Cookie": f"short_session={main.session_manager.issue()}"}


@pytest.mark.asyncio
async def test_a_half_watched_film_leads_the_continue_row(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await movie_source_with(main, ["AAA-001", "AAA-002", "AAA-003"])
    resumed = media_id(main, source, "AAA-001.mp4")

    with TestClient(main.app) as client:
        headers = headers_for(main)
        reported = client.post(
            "/api/progress",
            json={"videoId": resumed, "positionMs": 90_000, "durationMs": 600_000},
            headers=headers,
        )
        assert reported.status_code == 204

        body = client.get("/api/movies/recent", headers=headers).json()

    assert [item["videoId"] for item in body["items"]] == [resumed]
    first = body["items"][0]
    # The tile carries the position so tapping it resumes where the film was
    # left, and the row it came from is a normal movie payload otherwise.
    assert first["resumePositionMs"] == 90_000
    assert first["watchedAt"]
    assert first["playUrl"] == f"/api/videos/{resumed}/play"
    assert stored_progress(main)[resumed]["completed"] == 0


@pytest.mark.asyncio
async def test_a_film_watched_to_the_end_leaves_the_row(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await movie_source_with(main, ["AAA-001", "AAA-002"])
    finished = media_id(main, source, "AAA-001.mp4")
    open_ended = media_id(main, source, "AAA-002.mp4")

    with TestClient(main.app) as client:
        headers = headers_for(main)
        client.post(
            "/api/progress",
            json={"videoId": open_ended, "positionMs": 1_000, "durationMs": 600_000},
            headers=headers,
        )
        # 96% of the way through: the credits are running, so it is done.
        client.post(
            "/api/progress",
            json={"videoId": finished, "positionMs": 576_000, "durationMs": 600_000},
            headers=headers,
        )

        body = client.get("/api/movies/recent", headers=headers).json()

    assert [item["videoId"] for item in body["items"]] == [open_ended]
    assert stored_progress(main)[finished]["completed"] == 1


@pytest.mark.asyncio
async def test_a_position_past_the_end_is_clamped_to_the_duration(monkeypatch, tmp_path):
    """A player that reports while the file ends must not poison the row."""
    main = await boot(monkeypatch, tmp_path)
    source = await movie_source_with(main, ["AAA-001"])
    video = media_id(main, source, "AAA-001.mp4")

    with TestClient(main.app) as client:
        client.post(
            "/api/progress",
            json={"videoId": video, "positionMs": 900_000, "durationMs": 600_000},
            headers=headers_for(main),
        )

    assert stored_progress(main)[video]["position_ms"] == 600_000


@pytest.mark.asyncio
async def test_each_series_returns_once_with_the_episode_to_resume(
    monkeypatch, tmp_path
):
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)
    series = drama_rows(main, source)
    swap = episode_ids(main, source, str(series[SERIES_SWAP]["id"]))
    desert = episode_ids(main, source, str(series[SERIES_DESERT]["id"]))
    swap_second = swap["交换游戏 第02集 [91crdj-1172-2].mp4"]
    swap_third = swap["交换游戏 第03集 [91crdj-1172-3].mp4"]
    desert_first = desert["大漠遗孤 第01集 [91crdj-1301-1].mp4"]

    with TestClient(main.app) as client:
        headers = headers_for(main)
        client.post(
            "/api/progress",
            json={"videoId": swap_second, "positionMs": 60_000, "durationMs": 600_000},
            headers=headers,
        )
        # The same series is reported twice: the newest episode is the one the
        # tile has to reopen, and the series is still listed only once.
        client.post(
            "/api/progress",
            json={"videoId": swap_third, "positionMs": 30_000, "durationMs": 600_000},
            headers=headers,
        )
        client.post(
            "/api/progress",
            json={
                "videoId": desert_first,
                "positionMs": 15_000,
                "durationMs": 600_000,
            },
            headers=headers,
        )

        body = client.get("/api/dramas/recent", headers=headers).json()

    assert [item["id"] for item in body["items"]] == [
        series[SERIES_SWAP]["id"],
        series[SERIES_DESERT]["id"],
    ]
    assert body["items"][0]["episodeId"] == swap_third
    assert body["items"][0]["resumePositionMs"] == 30_000
    assert body["items"][1]["episodeId"] == desert_first
    assert body["items"][0]["episodeCount"] == 3


@pytest.mark.asyncio
async def test_a_finished_episode_hands_the_series_to_the_next_one(
    monkeypatch, tmp_path
):
    """A short-drama episode is minutes long, so finishing one is the normal way
    to leave a show.

    Dropping the series the moment its newest episode completed emptied the
    strip of exactly the shows the user had just worked through — hit a few
    episodes in a row and only the one left half-watched stayed. The tile has
    to stay put and point at the episode that comes next.
    """
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)
    series = drama_rows(main, source)
    swap = episode_ids(main, source, str(series[SERIES_SWAP]["id"]))
    first = swap["交换游戏 第01集 [91crdj-1172-1].mp4"]
    second = swap["交换游戏 第02集 [91crdj-1172-2].mp4"]

    with TestClient(main.app) as client:
        headers = headers_for(main)
        client.post(
            "/api/progress",
            json={"videoId": first, "positionMs": 590_000, "durationMs": 600_000},
            headers=headers,
        )
        assert stored_progress(main)[first]["completed"] == 1

        body = client.get("/api/dramas/recent", headers=headers).json()

    assert [item["id"] for item in body["items"]] == [series[SERIES_SWAP]["id"]]
    assert body["items"][0]["episodeId"] == second
    assert body["items"][0]["resumePositionMs"] == 0


@pytest.mark.asyncio
async def test_the_last_episode_of_a_finished_series_replays_from_the_top(
    monkeypatch, tmp_path
):
    """Nothing follows the finale, but the show is still the last thing watched.

    The tile keeps naming it and starts it over instead of resuming on the end
    credits, which is what a linear position would have done.
    """
    main = await boot(monkeypatch, tmp_path)
    source = await scanned_drama_source(main)
    series = drama_rows(main, source)
    swap = episode_ids(main, source, str(series[SERIES_SWAP]["id"]))
    finale = swap["交换游戏 第03集 [91crdj-1172-3].mp4"]

    with TestClient(main.app) as client:
        headers = headers_for(main)
        client.post(
            "/api/progress",
            json={"videoId": finale, "positionMs": 600_000, "durationMs": 600_000},
            headers=headers,
        )

        body = client.get("/api/dramas/recent", headers=headers).json()

    assert [item["id"] for item in body["items"]] == [series[SERIES_SWAP]["id"]]
    assert body["items"][0]["episodeId"] == finale
    assert body["items"][0]["resumePositionMs"] == 0


@pytest.mark.asyncio
async def test_removing_a_library_takes_its_history_with_it(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await movie_source_with(main, ["AAA-001"])
    video = media_id(main, source, "AAA-001.mp4")

    with TestClient(main.app) as client:
        headers = headers_for(main)
        client.post(
            "/api/progress",
            json={"videoId": video, "positionMs": 5_000, "durationMs": 600_000},
            headers=headers,
        )
        assert client.delete(f"/api/admin/sources/{source}", headers=headers).status_code == 200

        assert client.get("/api/movies/recent", headers=headers).json()["items"] == []

    # A row whose media is gone would keep naming a film that cannot be played.
    assert stored_progress(main) == {}


@pytest.mark.asyncio
async def test_reporting_an_unknown_medium_is_refused(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    await movie_source_with(main, ["AAA-001"])

    with TestClient(main.app) as client:
        response = client.post(
            "/api/progress",
            json={"videoId": 999_999, "positionMs": 1_000, "durationMs": 6_000},
            headers=headers_for(main),
        )

    assert response.status_code == 404
    assert stored_progress(main) == {}
