"""The shared metadata service must win over MetaTube and land in the DB."""

import importlib
import sys

import pytest

from app.movie_metadata import parse_movie_filename
from app.shared_metadata import SharedMetadataMatch

SAMPLE = SharedMetadataMatch(
    code="CEMD-822",
    title="CEMD-822 共享标题",
    original_title="CEMD-822 Original",
    year=2024,
    overview="来自共享元数据服务的简介",
    studio="片商",
    performers=("演员甲", "演员乙"),
    poster_url="https://c0.jdbstatic.com/covers/ve/veyGnb.jpg",
    backdrop_url="http://shared.test/api/shared-metadata/poster?path=CEMD-822%2Fx.jpg",
    website="https://javdb.com/v/veyGnb",
    confidence=0.98,
)


class _FakeSharedClient:
    """Stands in for ``SharedMetadataClient`` so the test never hits a network."""

    lookups: list[tuple[str, str]] = []
    closed = False

    def __init__(self, *, base_url: str, token: str = "", **_kwargs) -> None:
        self.base_url = base_url
        self.token = token

    async def lookup(self, name: str, path: str = "") -> SharedMetadataMatch | None:
        type(self).lookups.append((name, path))
        if "CEMD-822" in name:
            return SAMPLE
        return None

    async def close(self) -> None:
        type(self).closed = True


def _import_main(monkeypatch, tmp_path):
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "library.db"))
    monkeypatch.setenv("STATIC_DIR", str(tmp_path / "static"))
    monkeypatch.setenv("ALIST_BASE_URL", "https://guangya.example")
    monkeypatch.setenv("ALIST_MEDIA_PATH", "/media")
    monkeypatch.setenv("ASMR_BASE_URL", "https://asmr.example")
    monkeypatch.setenv("ASMR_MEDIA_PATH", "/asmr6")
    monkeypatch.setenv("AUTH_PASSWORD_HASH", "scrypt:test")
    monkeypatch.setenv("SESSION_SECRET", "test-session-secret-with-at-least-32-characters")
    monkeypatch.setenv("SHARED_METADATA_BASE_URL", "http://shared.test/api/shared-metadata")
    monkeypatch.setenv("SHARED_METADATA_TOKEN", "test-token")
    for key in ("TMDB_API_KEY", "TMDB_API_READ_TOKEN", "METATUBE_BASE_URL"):
        monkeypatch.delenv(key, raising=False)
    sys.modules.pop("app.main", None)
    return importlib.import_module("app.main")


@pytest.mark.asyncio
async def test_shared_metadata_is_used_without_any_local_scraper(monkeypatch, tmp_path):
    main = _import_main(monkeypatch, tmp_path)
    _FakeSharedClient.lookups = []
    _FakeSharedClient.closed = False
    monkeypatch.setattr(main, "SharedMetadataClient", _FakeSharedClient)

    main.database.initialize()
    main.database.replace_scan(
        [
            {
                "path": "/media/关键词分类/20260901/CEMD-822/hhd800.com@CEMD-822.mp4",
                "name": "hhd800.com@CEMD-822.mp4",
                "size": 1024,
            },
        ],
        source="movies",
    )
    main.database.sync_movie_index("movies", parse_movie_filename)

    await main.scrape_movie_metadata("movies")

    assert main.movie_metadata_state["lastError"] is None
    assert main.movie_metadata_state["matched"] == 1
    assert _FakeSharedClient.closed is True
    # The containing folder is what the shared index is keyed by.
    assert _FakeSharedClient.lookups == [
        (
            "hhd800.com@CEMD-822.mp4",
            "/media/关键词分类/20260901/CEMD-822/hhd800.com@CEMD-822.mp4",
        )
    ]

    movie = main.database.movies(sources=("movies",), limit=10)[0]
    assert movie["match_status"] == "matched"
    assert movie["metadata_provider"] == "shared"
    assert movie["display_title"] == "CEMD-822 共享标题"
    assert movie["original_title"] == "CEMD-822 Original"
    assert movie["year"] == 2024
    assert movie["overview"] == "来自共享元数据服务的简介"
    assert movie["studio"] == "片商"
    assert movie["poster_url"] == "https://c0.jdbstatic.com/covers/ve/veyGnb.jpg"
    assert movie["match_confidence"] == pytest.approx(0.98)

    payload = main.public_movie(movie, detail=True)
    assert payload["metadataProvider"] == "shared"
    assert payload["posterUrl"] == f"/api/movies/{movie['id']}/poster"


@pytest.mark.asyncio
async def test_unmatched_titles_stay_pending_when_the_shared_index_misses(
    monkeypatch, tmp_path
):
    main = _import_main(monkeypatch, tmp_path)
    _FakeSharedClient.lookups = []
    monkeypatch.setattr(main, "SharedMetadataClient", _FakeSharedClient)

    main.database.initialize()
    main.database.replace_scan(
        [
            {
                "path": "/media/movies/无封面影片.2024.mp4",
                "name": "无封面影片.2024.mp4",
                "size": 1024,
            },
        ],
        source="movies",
    )
    main.database.sync_movie_index("movies", parse_movie_filename)

    await main.scrape_movie_metadata("movies")

    movie = main.database.movies(sources=("movies",), limit=10)[0]
    assert movie["match_status"] in {"pending", "unmatched", "ambiguous"}
    assert not movie["poster_url"]


@pytest.mark.asyncio
async def test_scrape_endpoint_accepts_a_shared_metadata_only_deployment(
    monkeypatch, tmp_path
):
    """No TMDB key and no MetaTube still has to be a scrapeable setup."""
    main = _import_main(monkeypatch, tmp_path)
    monkeypatch.setattr(main.source_registry, "ids", lambda *_args, **_kwargs: ["movies"])
    monkeypatch.setattr(
        main.database,
        "movie_metadata_summary",
        lambda **_kwargs: {"total": 1},
    )
    spawned: list[str] = []

    def record_background(coroutine, *, name: str) -> None:
        spawned.append(name)
        coroutine.close()

    monkeypatch.setattr(main, "spawn_background", record_background)

    payload = await main.start_movie_metadata("movies")

    assert payload["started"] is True
    assert spawned == ["movie-metadata-movies"]
