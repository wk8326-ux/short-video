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
