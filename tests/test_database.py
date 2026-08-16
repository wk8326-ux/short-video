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
