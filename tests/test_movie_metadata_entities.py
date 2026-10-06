from app.database import LibraryDatabase
from app.movie_metadata import parse_movie_filename


def _seed(database: LibraryDatabase) -> list[dict]:
    database.initialize()
    database.replace_scan(
        [
            {"path": "/a/ABP-001.mp4", "name": "ABP-001.mp4", "size": 1},
            {"path": "/a/ABP-002.mp4", "name": "ABP-002.mp4", "size": 1},
        ],
        source="library-a",
    )
    database.replace_scan(
        [{"path": "/b/ABP-003.mp4", "name": "ABP-003.mp4", "size": 1}],
        source="library-b",
    )
    database.sync_movie_index("library-a", parse_movie_filename)
    database.sync_movie_index("library-b", parse_movie_filename)
    return database.movies(sources=("library-a", "library-b"), limit=10, sort="title")


def test_metadata_entities_are_normalized_and_replaced_transactionally(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    movies = _seed(database)
    with database._connect() as connection:
        columns = {row["name"] for row in connection.execute("PRAGMA table_info(movies)")}
    assert not columns.intersection({"genres", "performers", "studio"})
    movie_id = int(movies[0]["id"])

    database.update_movie_metadata(
        movie_id,
        release_date="2024-07-19",
        metadata_entities={
            "performer": [" 演员甲 ", "演员甲", "演员乙"],
            "studio": ["片商"],
            "genre": ["剧情", "制服"],
            "year": ["2024"],
        },
    )
    first = database.movie_metadata_entities(movie_id)

    assert [item["name"] for item in first["performer"]] == ["演员甲", "演员乙"]
    assert len(first["studio"]) == 1
    assert all(item["movie_count"] == 1 for values in first.values() for item in values)

    database.update_movie_metadata(
        movie_id,
        metadata_entities={
            "performer": ["演员乙"],
            "studio": [],
            "genre": ["剧情"],
            "year": ["2025"],
        },
    )
    replaced = database.movie_metadata_entities(movie_id)

    assert [item["name"] for item in replaced["performer"]] == ["演员乙"]
    assert replaced["studio"] == []
    assert [item["name"] for item in replaced["year"]] == ["2025"]


def test_entity_movie_page_spans_libraries_and_reports_counts(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    movies = _seed(database)
    for movie in movies:
        database.update_movie_metadata(
            int(movie["id"]),
            metadata_entities={"performer": ["演员甲"]},
        )

    entity = database.movie_metadata_entities(int(movies[0]["id"]))["performer"][0]
    first = database.movies(
        sources=("library-a", "library-b"),
        metadata_entity_id=int(entity["id"]),
        limit=2,
        offset=0,
        sort="title",
    )
    second = database.movies(
        sources=("library-a", "library-b"),
        metadata_entity_id=int(entity["id"]),
        limit=2,
        offset=2,
        sort="title",
    )

    assert entity["movie_count"] == 3
    assert len(first) == 2
    assert len(second) == 1
    assert database.movie_count(
        sources=("library-a", "library-b"),
        metadata_entity_id=int(entity["id"]),
    ) == 3


def test_movie_recommendations_use_or_signals_and_number_prefix(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    movies = _seed(database)
    source, keyword_led, performer_led = movies
    database.update_movie_metadata(
        int(source["id"]),
        metadata_entities={
            "performer": ["演员甲", "演员乙"],
            "studio": ["片商甲"],
            "genre": ["剧情", "悬疑"],
        },
    )
    database.update_movie_metadata(
        int(keyword_led["id"]),
        metadata_entities={
            "performer": ["演员甲"],
            "studio": ["片商甲"],
            "genre": ["剧情", "悬疑"],
        },
    )
    database.update_movie_metadata(
        int(performer_led["id"]),
        metadata_entities={
            "performer": ["演员甲", "演员乙"],
            "studio": [],
            "genre": ["剧情"],
        },
    )

    ranked = database.movie_recommendations(
        int(source["id"]),
        sources=("library-a", "library-b"),
    )

    assert [row["id"] for row in ranked] == [performer_led["id"], keyword_led["id"]]
    assert ranked[0]["code_matches"] == 1
    assert ranked[1]["code_matches"] == 1
    assert ranked[0]["score"] > ranked[1]["score"]


def test_movie_recommendations_do_not_require_metadata_signals_to_coincide(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(
        [
            {"path": "/a/KTSB-001.mp4", "name": "KTSB-001.mp4", "size": 1},
            {"path": "/a/KTSB-002.mp4", "name": "KTSB-002.mp4", "size": 1},
            {"path": "/a/ABP-001.mp4", "name": "ABP-001.mp4", "size": 1},
            {"path": "/a/XYZ-001.mp4", "name": "XYZ-001.mp4", "size": 1},
        ],
        source="library-a",
    )
    database.sync_movie_index("library-a", parse_movie_filename)
    movies = database.movies(sources=("library-a",), limit=10, sort="title")
    by_title = {str(movie["display_title"]): movie for movie in movies}
    current = by_title["KTSB-001"]
    code_only = by_title["KTSB-002"]
    performer_only = by_title["ABP-001"]
    genre_only = by_title["XYZ-001"]
    database.update_movie_metadata(
        int(current["id"]),
        metadata_entities={"performer": ["演员甲"], "genre": ["剧情"]},
    )
    database.update_movie_metadata(
        int(performer_only["id"]),
        metadata_entities={"performer": ["演员甲"]},
    )
    database.update_movie_metadata(
        int(genre_only["id"]),
        metadata_entities={"genre": ["剧情"]},
    )

    ranked = database.movie_recommendations(
        int(current["id"]),
        sources=("library-a",),
    )
    ids = [row["id"] for row in ranked]
    assert int(code_only["id"]) in ids
    assert int(performer_only["id"]) in ids
    assert int(genre_only["id"]) in ids
    assert ranked[0]["id"] == code_only["id"]
