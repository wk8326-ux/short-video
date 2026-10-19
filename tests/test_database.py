import sqlite3

from app.database import LibraryDatabase
from app.movie_metadata import parse_movie_filename


class TracedLibraryDatabase(LibraryDatabase):
    def __init__(self, path: str):
        super().__init__(path)
        self.statements: list[str] = []

    def _connect(self):
        connection = super()._connect()
        connection.set_trace_callback(self.statements.append)
        return connection


def _records() -> list[dict]:
    return [
        {
            "path": f"/root/video-{index}.mp4",
            "name": f"video-{index}.mp4",
            "size": index * 100,
            "modified": f"2026-08-{index:02d}T10:00:00Z",
            "thumb": "",
        }
        for index in range(1, 8)
    ]


def test_seed_migrates_legacy_builtin_asmr_to_recursive_scan(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    source = {
        "id": "asmr",
        "name": "中文音声",
        "provider": "alist",
        "base_url": "https://example.com",
        "root_path": "/asmr/中文音声",
        "section": "asmr",
        "scan_mode": "authors",
        "anonymous": True,
        "enabled": True,
    }
    database.seed_media_sources([source])
    assert database.get_media_source("asmr")["scan_mode"] == "authors"

    database.seed_media_sources([{**source, "scan_mode": "authors_recursive"}])

    assert database.get_media_source("asmr")["scan_mode"] == "authors_recursive"


def test_scan_and_stable_shuffle_cursor(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    assert database.replace_scan(_records()) == 7

    first, cursor, total = database.feed(limit=3, cursor=None, mode="shuffle")
    second, next_cursor, _ = database.feed(limit=3, cursor=cursor, mode="shuffle")

    assert total == 7
    assert cursor is not None
    assert next_cursor is not None
    assert not ({row["id"] for row in first} & {row["id"] for row in second})


def test_catalog_changes_are_revisioned_and_collapsed(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()

    database.replace_scan(_records()[:2], source="sync")
    first_batch, first_revision = database.catalog_changes_since(0)
    assert first_batch
    assert all(change["operation"] == "upsert" for change in first_batch)
    first_cursor = max(int(change["revision"]) for change in first_batch)
    assert first_revision == first_cursor

    # A second scan updates one row and retires the other. The consumer should
    # receive one current change per media id, rather than every intermediate
    # write generated while the scan was being committed.
    changed = _records()[0] | {"name": "updated-title.mp4"}
    database.replace_scan([changed], source="sync")
    second_batch, second_revision = database.catalog_changes_since(first_cursor)
    by_id = {change["entity_id"]: change for change in second_batch}
    assert by_id["1"]["operation"] == "upsert"
    assert by_id["2"]["operation"] == "delete"
    assert second_revision == database.catalog_revision()


def test_tvbox_category_snapshots_are_independent_and_prunable(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()

    database.record_tvbox_category_snapshot(
        source_id="forest",
        remote_category_id="movie",
        name="电影",
        upstream_total=120,
        page_count=6,
        item_count=120,
        last_error=None,
    )
    database.record_tvbox_category_snapshot(
        source_id="forest",
        remote_category_id="empty",
        name="空分类",
        upstream_total=0,
        page_count=0,
        item_count=0,
        last_error="upstream timeout",
    )

    snapshots = database.tvbox_category_snapshots("forest")
    assert [row["remote_category_id"] for row in snapshots] == ["movie", "empty"]
    assert snapshots[1]["last_error"] == "upstream timeout"

    database.delete_tvbox_category_snapshots_not_in("forest", ("movie",))
    assert [row["remote_category_id"] for row in database.tvbox_category_snapshots("forest")] == ["movie"]


def test_movie_metadata_link_changes_advance_catalog_revision(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(
        [{
            "path": "/movies/example.mp4",
            "name": "example.mp4",
            "size": 100,
            "modified": "2026-10-18",
            "thumb": "",
        }],
        source="movies",
    )
    database.sync_movie_index("movies", parse_movie_filename)
    movie = database.movies(sources=("movies",), limit=1)[0]
    before = database.catalog_revision()

    database.update_movie_metadata(
        int(movie["id"]),
        metadata_entities={"performer": ["演员甲"]},
    )

    changes, after = database.catalog_changes_since(before)
    assert any(
        change["entity_type"] == "movie"
        and change["entity_id"] == str(movie["id"])
        and change["operation"] == "upsert"
        for change in changes
    )
    assert after > before


def test_seed_skips_a_default_source_the_user_already_added_under_another_id(tmp_path):
    """Boot must survive a default source that is already present by other key."""
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    seeded = {
        "id": "91crdjai",
        "name": "91crdjai 短剧",
        "provider": "html",
        "base_url": "https://91crdjai.com",
        "root_path": "/",
        "root_paths": ["/"],
        "section": "drama",
        "scan_mode": "remote_pages",
        "anonymous": True,
        "enabled": True,
    }
    # The user added the same site first, so it is stored under their own id.
    database.add_media_source({**seeded, "id": "source-mine", "name": "91短剧"})

    database.seed_media_sources([seeded])

    assert database.get_media_source("91crdjai") is None
    assert database.get_media_source("source-mine") is not None


def test_sort_modes_and_removed_files(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    records = _records()
    database.replace_scan(records)

    newest, _, _ = database.feed(limit=7, cursor=None, mode="newest")
    oldest, _, _ = database.feed(limit=7, cursor=None, mode="oldest")
    database.replace_scan(records[:2])

    assert newest[0]["name"] == "video-7.mp4"
    assert oldest[0]["name"] == "video-1.mp4"
    assert database.stats()["videos"] == 2


def test_incremental_scan_deactivates_only_missing_source_rows(tmp_path):
    database = TracedLibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    guangya = _records()[:2]
    asmr = [
        {
            "path": f"/asmr6/author/track-{index}.mp3",
            "name": f"track-{index}.mp3",
            "size": index * 10,
            "modified": "2026-08-19T00:00:00Z",
            "thumb": "",
            "author": "author",
            "media_format": "mp3",
            "media_kind": "audio",
        }
        for index in range(1, 101)
    ]
    database.replace_scan(guangya)
    database.replace_scan(asmr, source="asmr")
    stable_path = "/asmr6/author/track-1.mp3"
    stable_id = {
        item["path"]: item["id"]
        for item in database.asmr_items(author="author", limit=200)
    }[stable_path]

    database.statements.clear()
    replacement = asmr[:-1] + [
        {
            **asmr[-1],
            "path": "/asmr6/author/new-track.mp3",
            "name": "new-track.mp3",
        }
    ]
    assert database.replace_scan(replacement, source="asmr") == 100

    statements = [" ".join(statement.split()).lower() for statement in database.statements]
    cleanup = [
        statement
        for statement in statements
        if statement.startswith("update videos set active = 0")
    ]
    # SQLite's trace callback reports a trigger's inner statements once per
    # affected row; assert the scanner still issued one distinct cleanup query.
    assert len(set(cleanup)) == 1
    assert "scan_id is not" in cleanup[0]
    current_ids = {
        item["path"]: item["id"]
        for item in database.asmr_items(author="author", limit=200)
    }
    assert current_ids[stable_path] == stable_id
    assert "/asmr6/author/track-100.mp3" not in current_ids
    assert "/asmr6/author/new-track.mp3" in current_ids
    assert database.stats(source="asmr")["videos"] == 100
    assert database.stats(source="guangya")["videos"] == 2
    assert database.get_video(stable_id)["active"] == 1

    database.replace_scan([], source="asmr")
    assert database.stats(source="asmr")["videos"] == 0
    assert database.asmr_authors() == []
    assert database.stats(source="guangya")["videos"] == 2


def test_shuffle_excludes_recent_videos_and_keeps_resume_first(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(_records())

    rows, cursor, total = database.feed(
        limit=3,
        cursor=None,
        mode="shuffle",
        exclude_ids={1, 2, 3},
        start_id=2,
    )
    next_rows, _, _ = database.feed(
        limit=3,
        cursor=cursor,
        mode="shuffle",
        exclude_ids={1, 2, 3},
        start_id=2,
    )

    assert total == 7
    assert rows[0]["id"] == 2
    assert not ({1, 3} & {row["id"] for row in rows + next_rows})
    assert len({row["id"] for row in rows + next_rows}) == len(rows + next_rows)


def test_fast_start_results_are_persisted_and_summarized(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(_records())

    database.update_fast_start(1, "optimized", "moov before mdat")
    database.update_fast_start(2, "not_optimized", "mdat before moov")
    summary = database.fast_start_summary()
    issues = database.fast_start_issues(limit=10)

    assert summary == {
        "total": 7,
        "checked": 2,
        "optimized": 1,
        "notOptimized": 1,
        "inconclusive": 0,
        "errors": 0,
        "pending": 5,
    }
    assert [issue["id"] for issue in issues] == [2]


def test_source_scans_are_isolated_and_duration_boundary_is_180_seconds(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(_records())
    database.update_media_metadata(1, duration_seconds=179.9, detail="test")
    database.update_media_metadata(2, duration_seconds=180, detail="test")

    asmr = [
        {
            "path": "/asmr6/author/track.mp3",
            "name": "track.mp3",
            "size": 42,
            "modified": "2026-08-17T00:00:00Z",
            "thumb": "",
            "author": "author",
            "media_format": "mp3",
            "media_kind": "audio",
        }
    ]
    database.replace_scan(asmr, source="asmr")
    database.replace_scan(_records()[:3], source="guangya")

    short, _, short_total = database.feed(
        limit=20, cursor=None, mode="oldest", category="short"
    )
    long, _, long_total = database.feed(
        limit=20, cursor=None, mode="oldest", category="long"
    )

    assert short_total == 1  # Unknown duration is not misclassified as short
    assert [row["id"] for row in long] == [2]
    assert long_total == 1
    assert database.stats(source="asmr")["videos"] == 1


def test_asmr_authors_and_kind_filters(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    records = [
        {
            "path": f"/asmr6/A/{name}",
            "name": name,
            "size": 10,
            "modified": "2026-08-17",
            "thumb": "",
            "author": "A",
            "media_format": name.rsplit(".", 1)[-1],
            "media_kind": kind,
        }
        for name, kind in [("session.m3u8", "video"), ("voice.mp3", "audio")]
    ]
    database.replace_scan(records, source="asmr")

    authors = database.asmr_authors()
    audio = database.asmr_items(author="A", kind="audio")
    second_item = database.asmr_items(author="A", limit=1, offset=1)

    assert authors[0]["item_count"] == 2
    assert authors[0]["video_count"] == 1
    assert authors[0]["audio_count"] == 1
    assert [item["name"] for item in audio] == ["voice.mp3"]
    assert [item["name"] for item in second_item] == ["voice.mp3"]
    assert database.asmr_item_count(author="A") == 2


def test_asmr_authors_skip_folders_without_media(tmp_path):
    """An author folder with no active media is not a browseable author.

    Author folders and the media rows under them are written by separate scan
    steps, so an interrupted scan leaves the folder behind. Listing it would
    show a 0/0/0 author whose page can only ever be empty.
    """
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(
        [
            {
                "path": "/asmr6/A/session.m3u8",
                "name": "session.m3u8",
                "size": 10,
                "modified": "2026-08-17",
                "thumb": "",
                "author": "A",
                "media_format": "m3u8",
                "media_kind": "video",
            }
        ],
        source="asmr",
    )
    with database._connect() as connection:
        connection.execute(
            "INSERT INTO asmr_author_directories(source, path, author) "
            "VALUES('asmr', '/asmr6/B', 'B')"
        )
        connection.commit()

    authors = database.asmr_authors()

    assert [row["author"] for row in authors] == ["A"]
    assert database.asmr_author_count() == 1
    assert database.asmr_author_count(search="B") == 0
    assert [row["author"] for row in database.asmr_authors(limit=1)] == ["A"]


def test_m3u8_cannot_be_reclassified_as_audio_by_browser_metadata(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(
        [
            {
                "path": "/asmr6/A/session.m3u8",
                "name": "session.m3u8",
                "size": 10,
                "modified": "2026-08-17",
                "thumb": "",
                "author": "A",
                "media_format": "m3u8",
                "media_kind": "video",
            }
        ],
        source="asmr",
    )
    item = database.asmr_items(author="A")[0]

    database.update_media_metadata(item["id"], media_kind="audio", detail="browser metadata")

    assert database.get_video(item["id"])["media_kind"] == "video"


def test_duration_probe_batch_is_limited_and_prioritizes_large_files(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(_records())

    candidates = database.duration_candidates(limit=2)

    assert [candidate["name"] for candidate in candidates] == [
        "video-7.mp4",
        "video-6.mp4",
    ]


def test_multiple_sources_can_share_paths_and_aggregate_into_one_section(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    shared_path = {
        "path": "/media/shared.mp4",
        "name": "shared.mp4",
        "size": 10,
        "modified": "2026-08-19",
        "thumb": "",
    }

    database.replace_scan([shared_path], source="feed-a")
    database.replace_scan([{**shared_path, "size": 20}], source="feed-b")

    rows, _, total = database.feed(
        limit=10,
        cursor=None,
        mode="oldest",
        sources=("feed-a", "feed-b"),
    )

    assert total == 2
    assert {row["source"] for row in rows} == {"feed-a", "feed-b"}
    assert database.stats(sources=("feed-a", "feed-b")) == {
        "videos": 2,
        "bytes": 30,
    }


def test_media_source_registry_persists_scan_state_and_section(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.seed_media_sources(
        [
            {
                "id": "guangya",
                "name": "光鸭",
                "provider": "alist",
                "base_url": "https://guangya.example",
                "root_path": "/media",
                "section": "feed",
                "scan_mode": "tree",
                "anonymous": False,
                "token": "secret",
                "username": "",
                "password": "",
                "enabled": True,
            }
        ]
    )
    database.update_source_scan_state(
        "guangya",
        last_success=1787100000,
        last_error=None,
        directories=12,
    )

    source = database.get_media_source("guangya")

    assert source is not None
    assert source["section"] == "feed"
    assert source["last_scan_success"] == 1787100000
    assert source["directories"] == 12
    assert database.source_ids_for_section("feed") == ("guangya",)


def test_deleting_a_media_source_removes_its_scan_history(tmp_path):
    """A removed library must not keep reporting a half-finished scan."""
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.seed_media_sources(
        [
            {
                "id": "source-gone",
                "name": "已删除",
                "provider": "alist",
                "base_url": "https://guangya.example",
                "root_path": "/gone",
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
    job = database.create_scan_job(
        source="source-gone",
        root_path="/gone",
        base_url="https://guangya.example",
        scan_mode="tree",
        section="movie",
    )
    database.seed_scan_directories(str(job["id"]), [("list", "/gone")])
    database.update_scan_job_status(str(job["id"]), "interrupted", error="读取失败")

    assert database.delete_media_source("source-gone") is True

    assert database.source_scan_states() == {}
    connection = sqlite3.connect(database.path)
    try:
        remaining = connection.execute(
            "SELECT COUNT(*) FROM media_scan_directories"
        ).fetchone()[0]
    finally:
        connection.close()
    assert remaining == 0


def test_initialize_migrates_legacy_path_unique_without_changing_ids(tmp_path):
    path = tmp_path / "library.db"
    with sqlite3.connect(path) as connection:
        connection.executescript(
            """
            CREATE TABLE videos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                modified TEXT,
                thumb TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                source TEXT NOT NULL DEFAULT 'guangya'
            );
            INSERT INTO videos(id, path, name, source)
            VALUES(41, '/media/existing.mp4', 'existing.mp4', 'guangya');
            """
        )

    database = LibraryDatabase(str(path))
    database.initialize()
    database.replace_scan(
        [{"path": "/media/existing.mp4", "name": "existing.mp4", "size": 2, "thumb": ""}],
        source="second-source",
    )

    legacy_video = database.get_video(41)
    assert legacy_video["source"] == "guangya"
    assert legacy_video["hidden"] == 0
    assert database.stats()["videos"] == 2


def test_movie_index_keeps_metadata_across_rescans_and_removes_missing_rows(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    records = [
        {
            "path": "/movies/流浪地球2.2023.2160p.mkv",
            "name": "流浪地球2.2023.2160p.mkv",
            "size": 100,
            "modified": "2026-08-20",
            "thumb": "",
            "media_format": "mkv",
        },
        {
            "path": "/movies/另一部.2022.mp4",
            "name": "另一部.2022.mp4",
            "size": 200,
            "modified": "2026-08-20",
            "thumb": "",
            "media_format": "mp4",
        },
    ]
    database.replace_scan(records, source="movies")
    database.sync_movie_index("movies", parse_movie_filename)

    first = database.movies(sources=("movies",), limit=10)
    assert [item["display_title"] for item in first] == ["另一部", "流浪地球2"]
    assert first[1]["year"] == 2023
    matched_id = first[1]["id"]
    assert database.movie_metadata_summary(sources=("movies",)) == {
        "total": 2,
        "pending": 2,
        "matched": 0,
        "ambiguous": 0,
        "unmatched": 0,
        "lastSuccess": None,
    }
    database.update_movie_metadata(
        matched_id,
        display_title="流浪地球 2",
        match_status="matched",
        tmdb_id=123,
    )
    metadata_summary = database.movie_metadata_summary(sources=("movies",))
    last_success = metadata_summary.pop("lastSuccess")
    assert metadata_summary == {
        "total": 2,
        "pending": 1,
        "matched": 1,
        "ambiguous": 0,
        "unmatched": 0,
    }
    assert last_success is not None

    database.replace_scan(records[:1], source="movies")
    database.sync_movie_index("movies", parse_movie_filename)

    current = database.movies(sources=("movies",), limit=10)
    assert len(current) == 1
    assert current[0]["id"] == matched_id
    assert current[0]["display_title"] == "流浪地球 2"
    assert database.movie_count(sources=("movies",)) == 1


def test_movie_index_backfills_http_thumbnails_without_overwriting_covers(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(
        [
            {
                "path": "/tvbox/1/101",
                "name": "远程封面.m3u8",
                "size": 0,
                "modified": None,
                "thumb": "https://images.example/remote.jpg",
                "media_format": "m3u8",
            },
            {
                "path": "/tvbox/1/102",
                "name": "相对缩略图.m3u8",
                "size": 0,
                "modified": None,
                "thumb": "/poster?path=relative.jpg",
                "media_format": "m3u8",
            },
        ],
        source="tvbox-child",
    )
    database.sync_movie_index("tvbox-child", parse_movie_filename)

    rows = database.movies(sources=("tvbox-child",), limit=10)
    by_path = {row["path"]: row for row in rows}
    assert by_path["/tvbox/1/101"]["poster_url"] == "https://images.example/remote.jpg"
    assert by_path["/tvbox/1/101"]["backdrop_url"] == "https://images.example/remote.jpg"
    assert not by_path["/tvbox/1/102"]["poster_url"]

    database.update_movie_metadata(
        by_path["/tvbox/1/101"]["id"],
        poster_url="https://metadata.example/curated.jpg",
        match_status="matched",
        metadata_provider="shared",
    )
    database.replace_scan(
        [
            {
                "path": "/tvbox/1/101",
                "name": "远程封面.m3u8",
                "size": 0,
                "modified": None,
                "thumb": "https://images.example/updated.jpg",
                "media_format": "m3u8",
            }
        ],
        source="tvbox-child",
    )
    database.sync_tvbox_movie_index(
        "tvbox-child",
        [{"vod_id": "101", "vod_name": "远程封面", "vod_pic": "https://images.example/updated.jpg"}],
        "/tvbox/1",
    )
    database.sync_movie_index("tvbox-child", parse_movie_filename)

    stored = database.movies(sources=("tvbox-child",), limit=10)[0]
    assert stored["poster_url"] == "https://metadata.example/curated.jpg"


def test_hidden_movies_stay_hidden_across_scans(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    records = [
        {
            "path": "/movies/无封面A.2024.mkv",
            "name": "无封面A.2024.mkv",
            "size": 100,
            "modified": "2026-08-26",
            "thumb": "",
            "media_format": "mkv",
        },
        {
            "path": "/movies/正常B.2023.mp4",
            "name": "正常B.2023.mp4",
            "size": 200,
            "modified": "2026-08-26",
            "thumb": "",
            "media_format": "mp4",
        },
    ]
    database.replace_scan(records, source="movies")
    database.sync_movie_index("movies", parse_movie_filename)
    visible = database.movies(sources=("movies",), limit=10)
    assert len(visible) == 2

    hidden_video_id = visible[0]["video_id"]
    assert database.hide_videos([hidden_video_id]) == 1
    assert database.get_video(hidden_video_id) is None
    assert database.movie_count(sources=("movies",)) == 1

    # The source file still exists, so a later scan sees it again. A user
    # deletion must therefore be remembered instead of being undone.
    database.replace_scan(records, source="movies")
    database.sync_movie_index("movies", parse_movie_filename)

    remaining = database.movies(sources=("movies",), limit=10)
    assert len(remaining) == 1
    assert remaining[0]["video_id"] != hidden_video_id
    with database._connect() as connection:
        hidden = connection.execute(
            "SELECT active, hidden FROM videos WHERE id = ?", (hidden_video_id,)
    ).fetchone()
    assert tuple(hidden) == (0, 1)


def test_delete_media_source_removes_its_index_and_prevents_default_reseed(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    source = {
        "id": "removable",
        "name": "可删除测试源",
        "base_url": "https://movies.example",
        "root_path": "/movies",
        "section": "movie",
        "anonymous": True,
    }
    database.add_media_source(source)
    database.replace_scan(
        [{"path": "/movies/test.mp4", "name": "test.mp4", "size": 100}],
        source="removable",
    )
    database.sync_movie_index("removable", parse_movie_filename)

    assert database.delete_media_source("removable") is True
    assert database.get_media_source("removable") is None
    assert database.stats(sources=("removable",))["videos"] == 0
    assert database.movie_count(sources=("removable",)) == 0

    database.seed_media_sources([source])
    assert database.get_media_source("removable") is None


def test_movie_filename_parser_is_conservative():
    parsed = parse_movie_filename("The.Dark.Knight.2008.1080p.BluRay.x264.mkv")
    assert parsed.display_title == "The Dark Knight"
    assert parsed.normalized_title == "the dark knight"
    assert parsed.year == 2008

    extensionless = parse_movie_filename("《无间道》")
    assert extensionless.display_title == "无间道"
    assert extensionless.normalized_title == "无间道"
    assert extensionless.year is None

    dotted_extensionless = parse_movie_filename("《蝙蝠侠.黑暗骑士》")
    assert dotted_extensionless.display_title == "蝙蝠侠 黑暗骑士"
