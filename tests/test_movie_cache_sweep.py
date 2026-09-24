"""The cover cache is the app's own disk, so its two jobs are pinned here.

A phone reads covers straight off this host and nowhere else, which makes the
cache the entire first-frame budget. Two things follow, and both are checked
below: the cache must fill itself without waiting for someone to scroll to a
library for the first time, and it must not keep paying rent on the bytes a
re-match left behind. The ladder of widths is pinned too, because the Android
client mirrors it - a rung that exists on one side only is a cache entry that
is either never written or never read.
"""

from __future__ import annotations

import asyncio
import os
import time

import pytest
from fastapi.testclient import TestClient

from test_movie_groups import add_movie_source, index_movies
from test_scan import boot

COVER_A = "https://images.example/a.jpg"
COVER_B = "https://images.example/b.jpg"
RETIRED = "https://images.example/retired.jpg"


def cache_blob(main, url: str, *, width: int | None = None, body: bytes = b"cover"):
    """Write the blob a phone would read for ``url`` at ``width``."""
    key = main._movie_image_request_key(url, width or 0)
    main._write_movie_image_cache(key, body, "image/jpeg", f"etag-{width or 0}")
    return key


def blob_path(main, key: str):
    content_path, _metadata_path = main._movie_image_cache_paths(key)
    return content_path


async def seed_movies(main, *, names: list[str]) -> dict[str, dict]:
    """Index a library and hand back its rows keyed by file name."""
    source = await add_movie_source(main, "光鸭 / 电影", "/movies/guangya")
    index_movies(main, source, names)
    return {
        row["name"]: row for row in main.database.movies(sources=(source,), limit=50)
    }


@pytest.mark.asyncio
async def test_the_ladder_is_the_one_the_android_client_mirrors(monkeypatch, tmp_path):
    """Three rungs, and every request lands on one of them.

    `ArtworkWidthInterceptor` holds the same three numbers. Extra rungs used to
    cost a second copy of the same picture per device density while the census
    showed nothing reading them, so the ladder is deliberately short - and a
    width between two rungs has to snap instead of minting a private entry.
    """
    main = await boot(monkeypatch, tmp_path)

    assert main.MOVIE_IMAGE_WIDTHS == (360, 720, 1080)
    assert main._requested_image_width(300) == 360
    assert main._requested_image_width(430) == 720
    assert main._requested_image_width(1080) == 1080
    # A device denser than the top rung reads the top rung rather than growing
    # the ladder behind the server's back.
    assert main._requested_image_width(1440) == 1080
    assert main._requested_image_width(0) is None
    assert main._requested_image_width("nonsense") is None


@pytest.mark.asyncio
async def test_the_sweep_drops_only_the_covers_no_row_can_ask_for_again(
    monkeypatch, tmp_path
):
    """A re-matching library rewrites its artwork URLs; the old bytes stay.

    A census found those leftovers at about half the cache. Everything a row
    can still ask for - a poster at the wall's width, at the narrow rung, whole
    - has to survive the sweep, and everything else has to go.
    """
    main = await boot(monkeypatch, tmp_path)
    rows = await seed_movies(main, names=["AAA-001", "AAA-002"])
    main.database.update_movie_metadata(
        rows["AAA-001.mp4"]["id"], poster_url=COVER_A, match_status="matched"
    )
    # The backdrop column is a separate cover as far as the cache is concerned:
    # the detail page reads it, so it is reachable even with no poster at all.
    main.database.update_movie_metadata(
        rows["AAA-002.mp4"]["id"], backdrop_url=COVER_B, match_status="matched"
    )

    kept = [cache_blob(main, COVER_A, width=width) for width in (None, 360, 720, 1080)]
    kept.append(cache_blob(main, COVER_B, width=720))
    orphan = cache_blob(main, RETIRED, width=720)

    freed = main._collect_movie_image_orphans(min_age_seconds=0)

    assert freed > 0
    assert not blob_path(main, orphan).exists()
    assert all(blob_path(main, key).exists() for key in kept)


@pytest.mark.asyncio
async def test_a_blob_younger_than_the_grace_window_is_not_yet_an_orphan(
    monkeypatch, tmp_path
):
    """A write landing mid-sweep must not be deleted from under the process.

    The sweep lists the directory and then deletes; a cover fetched by a phone
    between those two moments is unreachable from the catalogue only because
    its row has not been written yet. The age floor is what makes that safe.
    """
    main = await boot(monkeypatch, tmp_path)
    await seed_movies(main, names=["AAA-001"])
    fresh = cache_blob(main, RETIRED, width=720)
    assert blob_path(main, fresh).exists()

    assert main._collect_movie_image_orphans() == 0
    assert blob_path(main, fresh).exists()

    stale = time.time() - main.MOVIE_IMAGE_ORPHAN_MIN_AGE_SECONDS - 60
    os.utime(blob_path(main, fresh), (stale, stale))
    assert main._collect_movie_image_orphans() > 0
    assert not blob_path(main, fresh).exists()


@pytest.mark.asyncio
async def test_a_drama_poster_is_never_collected_as_an_orphan(monkeypatch, tmp_path):
    """Short dramas have covers in their own table, read by their own endpoint."""
    main = await boot(monkeypatch, tmp_path)
    with main.database._lock, main.database._connect() as connection:
        connection.execute(
            """
            INSERT INTO dramas(id, source, root_path, folder, title, folder_title,
                               poster_url, episode_count)
            VALUES('91crdj', 'asmr', '/dramas', '/dramas/one', '短剧', '短剧',
                   ?, 3)
            """,
            (COVER_B,),
        )
        connection.commit()

    kept = cache_blob(main, COVER_B, width=720)
    assert main._collect_movie_image_orphans(min_age_seconds=0) == 0
    assert blob_path(main, kept).exists()


@pytest.mark.asyncio
async def test_the_batch_walks_the_catalogue_in_id_order_and_skips_coverless_rows(
    monkeypatch, tmp_path
):
    """The sweeper resumes from a cursor, so the batch has to be a stable page.

    Rows with no artwork at all are excluded here rather than filtered later,
    which keeps a page from being half wasted on titles that have nothing to
    warm - and the cursor makes an interrupted pass cheap to continue.
    """
    main = await boot(monkeypatch, tmp_path)
    rows = await seed_movies(main, names=["AAA-001", "AAA-002", "AAA-003"])
    main.database.update_movie_metadata(
        rows["AAA-001.mp4"]["id"], poster_url=COVER_A, match_status="matched"
    )
    main.database.update_movie_metadata(
        rows["AAA-003.mp4"]["id"], poster_url=COVER_B, match_status="matched"
    )
    # A retired video is not on the wall, so its cover is not worth fetching.
    with main.database._lock, main.database._connect() as connection:
        connection.execute(
            "UPDATE videos SET active = 0 WHERE id = ?",
            (rows["AAA-003.mp4"]["video_id"],),
        )
        connection.commit()

    page = main.database.movie_artwork_batch(after_id=0, limit=50)

    assert [row["id"] for row in page] == [rows["AAA-001.mp4"]["id"]]
    assert page[0]["poster_url"] == COVER_A
    assert "backdrop_url" in page[0]
    # The cursor is the last id handed out, so the next page is the rest of the
    # catalogue and a finished pass is exactly the empty page.
    assert main.database.movie_artwork_batch(after_id=page[0]["id"], limit=50) == []


@pytest.mark.asyncio
async def test_a_cover_already_on_disk_is_not_fetched_a_second_time(
    monkeypatch, tmp_path
):
    """Warming is idempotent: the pass skips whatever a phone already pulled."""
    main = await boot(monkeypatch, tmp_path)
    rows = await seed_movies(main, names=["AAA-001", "AAA-002"])
    main.database.update_movie_metadata(
        rows["AAA-001.mp4"]["id"], poster_url=COVER_A, match_status="matched"
    )
    main.database.update_movie_metadata(
        rows["AAA-002.mp4"]["id"], poster_url=COVER_B, match_status="matched"
    )
    warm = rows["AAA-001.mp4"]
    cache_blob(main, COVER_A, width=main.MOVIE_WALL_IMAGE_WIDTH)

    fetched: list[tuple[str, int]] = []

    async def record(source_url, width):
        fetched.append((source_url, width))

    monkeypatch.setattr(main, "prewarm_movie_image_at_width", record)

    batch = main.database.movie_artwork_batch(after_id=0, limit=50)
    cold = [row for row in batch if not main._movie_row_cover_cached(row)]
    await main._warm_movie_covers(cold)

    assert main._movie_row_cover_cached(warm) is True
    assert fetched == [(COVER_B, main.MOVIE_WALL_IMAGE_WIDTH)]


@pytest.mark.asyncio
async def test_the_sweeper_stands_down_for_anything_with_a_user_waiting(
    monkeypatch, tmp_path
):
    """The sweep is the lowest-priority task on a one-core host."""
    main = await boot(monkeypatch, tmp_path)
    monkeypatch.setattr(main, "MOVIE_IMAGE_CACHE_MIN_FREE_BYTES", 0)
    assert await main._movie_cover_sweep_stop_reason() is None

    main.movie_metadata_state["running"] = True
    reason = await main._movie_cover_sweep_stop_reason()
    assert reason is not None and "metadata" in reason
    main.movie_metadata_state["running"] = False

    main.scan_state["sources"] = {"guangya": {"running": True}}
    reason = await main._movie_cover_sweep_stop_reason()
    assert reason is not None and "guangya" in reason
    main.scan_state["sources"] = {}

    # A box running out of room outranks the cache, whatever the ceiling says:
    # the database and the library share the filesystem the covers live on.
    monkeypatch.setattr(main, "MOVIE_IMAGE_CACHE_MIN_FREE_BYTES", 10**18)
    reason = await main._movie_cover_sweep_stop_reason()
    assert reason is not None and "free" in reason
    monkeypatch.setattr(main, "MOVIE_IMAGE_CACHE_MIN_FREE_BYTES", 0)

    # And the ceiling itself stops the pass, so a full cache never turns into a
    # download loop that evicts the cover it just fetched.
    monkeypatch.setattr(
        main,
        "_movie_image_cache_usage",
        lambda **_kwargs: (main.MOVIE_IMAGE_CACHE_MAX_BYTES + 1, 10),
    )
    reason = await main._movie_cover_sweep_stop_reason()
    assert reason is not None and "ceiling" in reason


@pytest.mark.asyncio
async def test_the_ceiling_yields_to_the_disk_the_cache_shares(monkeypatch, tmp_path):
    """The database, the app and the cache are one filesystem with 45 GB on it.

    A ceiling alone would let the cache grow until the box that runs the scans
    and holds the library database ran out of room, so the free-space floor
    wins whenever it is the lower of the two.
    """
    main = await boot(monkeypatch, tmp_path)
    # Which is also what makes the guard read correctly before anything has
    # been cached: a fresh install has to decide how much to download while the
    # cache directory still does not exist.
    assert not main.movie_image_cache_dir.exists()
    free = main._movie_image_disk_free()
    assert free is not None

    monkeypatch.setattr(main, "MOVIE_IMAGE_CACHE_MIN_FREE_BYTES", 0)
    assert main._movie_image_cache_budget(0) == min(
        free, main.MOVIE_IMAGE_CACHE_MAX_BYTES
    )

    # A floor the box can no longer satisfy stops the cache growing at all,
    # rather than letting the ceiling spend the database's room.
    monkeypatch.setattr(main, "MOVIE_IMAGE_CACHE_MIN_FREE_BYTES", free + 10**9)
    assert main._movie_image_cache_budget(0) == 0


@pytest.mark.asyncio
async def test_a_finished_pass_warms_every_cover_and_only_then_sweeps(
    monkeypatch, tmp_path
):
    """The whole loop, on two rows: warm, reach the end, then collect.

    The orphan sweep runs at the end of a pass on purpose - that is the moment
    the reachable set was just computed from a freshly read catalogue - so the
    order matters, not just the fact that both happen.
    """
    main = await boot(monkeypatch, tmp_path)
    rows = await seed_movies(main, names=["AAA-001", "AAA-002"])
    main.database.update_movie_metadata(
        rows["AAA-001.mp4"]["id"], poster_url=COVER_A, match_status="matched"
    )
    main.database.update_movie_metadata(
        rows["AAA-002.mp4"]["id"], poster_url=COVER_B, match_status="matched"
    )
    monkeypatch.setattr(main, "MOVIE_IMAGE_WARM_START_DELAY_SECONDS", 0)
    monkeypatch.setattr(main, "MOVIE_IMAGE_WARM_IDLE_SECONDS", 0)
    monkeypatch.setattr(main, "MOVIE_IMAGE_WARM_PAUSE_SECONDS", 0)
    monkeypatch.setattr(main, "MOVIE_IMAGE_WARM_BATCH", 1)
    monkeypatch.setattr(main, "MOVIE_IMAGE_CACHE_MIN_FREE_BYTES", 0)

    fetched: list[tuple[str, int]] = []

    async def record(source_url, width):
        fetched.append((source_url, width))

    swept = {"count": 0}

    def sweep(*, min_age_seconds=None):
        swept["count"] += 1
        return 0

    monkeypatch.setattr(main, "prewarm_movie_image_at_width", record)
    monkeypatch.setattr(main, "_collect_movie_image_orphans", sweep)

    task = asyncio.create_task(main.sweep_movie_cover_cache())
    try:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and not swept["count"]:
            await asyncio.sleep(0.05)
    finally:
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    # A pass walks the whole catalogue before it sweeps, so the sweep can only
    # be reached once both covers have been asked for, in id order. The loop
    # then starts over, which is why this is a set with a pinned prefix.
    assert fetched[:2] == [(COVER_A, 720), (COVER_B, 720)]
    assert set(fetched) == {(COVER_A, 720), (COVER_B, 720)}
    assert swept["count"] >= 1


@pytest.mark.asyncio
async def test_the_failure_ledger_drops_only_the_rows_that_lapsed(
    monkeypatch, tmp_path
):
    """The ledger is a backoff, not a record: an expired row says nothing.

    Left behind, it would cost one row per URL a library no longer uses, and
    the sweep that already knows the catalogue is the cheapest place to clear
    it.
    """
    main = await boot(monkeypatch, tmp_path)
    main.database.remember_movie_image_failure(COVER_A, ttl_seconds=3600)
    with main.database._lock, main.database._connect() as connection:
        connection.execute(
            """
            INSERT INTO movie_image_failures(url, expires_at) VALUES(?, ?)
            """,
            (COVER_B, int(time.time()) - 60),
        )
        connection.commit()

    assert main.database.purge_expired_movie_image_failures() == 1

    with main.database._lock, main.database._connect() as connection:
        rows = connection.execute("SELECT url FROM movie_image_failures").fetchall()
    assert [row["url"] for row in rows] == [COVER_A]
    assert main.database.movie_artwork_urls() == set()


@pytest.mark.asyncio
async def test_the_reachable_set_covers_both_cover_columns(monkeypatch, tmp_path):
    """Both columns are listed, because the wall and the detail page differ."""
    main = await boot(monkeypatch, tmp_path)
    rows = await seed_movies(main, names=["AAA-001", "AAA-002"])
    main.database.update_movie_metadata(
        rows["AAA-001.mp4"]["id"], poster_url=COVER_A, match_status="matched"
    )
    main.database.update_movie_metadata(
        rows["AAA-002.mp4"]["id"], backdrop_url=COVER_B, match_status="matched"
    )

    assert main.database.movie_artwork_urls() == {COVER_A, COVER_B}


@pytest.mark.asyncio
async def test_the_sweeper_is_started_by_the_app_itself(monkeypatch, tmp_path):
    """Nothing else asks for a warm pass, so the lifespan has to start one."""
    main = await boot(monkeypatch, tmp_path)
    with TestClient(main.app):
        pass
    assert "movie-cover-sweep" in main.spawned_background
