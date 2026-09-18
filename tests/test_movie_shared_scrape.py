"""The shared catalogue is the only movie metadata provider, and it never wipes."""

import importlib
import sys

import pytest

from app.movie_metadata import parse_movie_filename
from app.shared_metadata import SharedMetadataMatch
from app.tmdb import TmdbMatch

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


class _FakeTmdbClient:
    """Records every TMDB call so a test can assert it was never reached."""

    calls: list[str] = []
    closed = False

    def __init__(
        self,
        *,
        read_token: str = "",
        api_key: str = "",
        language: str = "zh-CN",
    ) -> None:
        pass

    async def movie_by_id(self, tmdb_id: int):
        type(self).calls.append(f"id:{tmdb_id}")
        return None

    async def search_movie(self, title: str, *, year: int | None = None):
        type(self).calls.append(title)
        return None

    async def close(self) -> None:
        type(self).closed = True


def _import_main(monkeypatch, tmp_path, *, tmdb: bool = False):
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
    for key in ("TMDB_API_KEY", "TMDB_API_READ_TOKEN"):
        monkeypatch.delenv(key, raising=False)
    if tmdb:
        monkeypatch.setenv("TMDB_API_KEY", "test-tmdb-key")
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
    """A shared-catalogue-only deployment still has to be scrapeable."""
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


def _seed_one_movie(main, path: str, name: str) -> dict:
    main.database.initialize()
    main.database.replace_scan(
        [{"path": path, "name": name, "size": 1024}],
        source="movies",
    )
    main.database.sync_movie_index("movies", parse_movie_filename)
    return main.database.movies(sources=("movies",), limit=10)[0]


@pytest.mark.asyncio
async def test_a_miss_never_discards_metadata_an_earlier_run_found(
    monkeypatch, tmp_path
):
    """Re-running the pass must not blank covers that are already on the wall."""
    main = _import_main(monkeypatch, tmp_path)
    monkeypatch.setattr(main, "SharedMetadataClient", _FakeSharedClient)
    movie = _seed_one_movie(
        main,
        "/media/关键词分类/20260901/ABP-485/hhd800.com@ABP-485.mp4",
        "hhd800.com@ABP-485.mp4",
    )
    main.database.update_movie_metadata(
        int(movie["id"]),
        display_title="ABP-485 既有标题",
        poster_url="https://c0.jdbstatic.com/covers/ab/abp485.jpg",
        metadata_provider="metatube",
        match_status="matched",
    )

    await main.scrape_movie_metadata("movies", force=True)

    stored = main.database.movies(sources=("movies",), limit=10)[0]
    assert stored["poster_url"] == "https://c0.jdbstatic.com/covers/ab/abp485.jpg"
    assert stored["display_title"] == "ABP-485 既有标题"
    assert stored["match_status"] == "matched"


@pytest.mark.asyncio
async def test_coded_titles_never_reach_tmdb(monkeypatch, tmp_path):
    """A番号 is a shared-catalogue key, not something TMDB can answer."""
    main = _import_main(monkeypatch, tmp_path, tmdb=True)
    monkeypatch.setattr(main, "SharedMetadataClient", _FakeSharedClient)
    monkeypatch.setattr(main, "TmdbClient", _FakeTmdbClient)
    _FakeTmdbClient.calls = []
    _seed_one_movie(
        main,
        "/media/关键词分类/20260901/ABP-485/hhd800.com@ABP-485.mp4",
        "hhd800.com@ABP-485.mp4",
    )

    await main.scrape_movie_metadata("movies")

    assert _FakeTmdbClient.calls == []
    stored = main.database.movies(sources=("movies",), limit=10)[0]
    assert stored["match_status"] == "unmatched"
    assert not stored["metadata_provider"]


@pytest.mark.asyncio
async def test_plain_titles_still_fall_back_to_tmdb(monkeypatch, tmp_path):
    main = _import_main(monkeypatch, tmp_path, tmdb=True)
    monkeypatch.setattr(main, "SharedMetadataClient", _FakeSharedClient)

    class _MatchingTmdbClient(_FakeTmdbClient):
        async def search_movie(self, title: str, *, year: int | None = None):
            type(self).calls.append(title)
            return TmdbMatch(
                tmdb_id=32249,
                title="公元2000",
                original_title="公元2000",
                year=2000,
                overview="简介",
                poster_url="https://image.tmdb.org/t/p/w500/poster.jpg",
                backdrop_url="https://image.tmdb.org/t/p/w780/backdrop.jpg",
                rating=6.5,
                status="matched",
                confidence=0.9,
            )

    monkeypatch.setattr(main, "TmdbClient", _MatchingTmdbClient)
    _MatchingTmdbClient.calls = []
    _seed_one_movie(main, "/media/movies/公元2000.2000.mkv", "公元2000.2000.mkv")

    await main.scrape_movie_metadata("movies")

    stored = main.database.movies(sources=("movies",), limit=10)[0]
    assert stored["metadata_provider"] == "tmdb"
    assert stored["match_status"] == "matched"
    assert stored["poster_url"] == "https://image.tmdb.org/t/p/w500/poster.jpg"
