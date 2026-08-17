from app.database import LibraryDatabase


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

    assert short_total == 2  # 179.9 seconds plus one pending metadata row
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

    assert authors[0]["item_count"] == 2
    assert authors[0]["video_count"] == 1
    assert authors[0]["audio_count"] == 1
    assert [item["name"] for item in audio] == ["voice.mp3"]


def test_duration_probe_batch_is_limited_and_prioritizes_large_files(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(_records())

    candidates = database.duration_candidates(limit=2)

    assert [candidate["name"] for candidate in candidates] == [
        "video-7.mp4",
        "video-6.mp4",
    ]
