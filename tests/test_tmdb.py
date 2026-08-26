import httpx
import pytest

from app.tmdb import TmdbClient


@pytest.mark.asyncio
async def test_search_movie_prefers_exact_title_over_first_result():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/3/search/movie"
        assert request.url.params["query"] == "无间道"
        return httpx.Response(
            200,
            json={
                "results": [
                    {
                        "id": 1422,
                        "title": "无间道风云",
                        "original_title": "The Departed",
                        "release_date": "2006-10-06",
                        "vote_average": 8.2,
                    },
                    {
                        "id": 10775,
                        "title": "无间道",
                        "original_title": "無間道",
                        "release_date": "2002-12-12",
                        "poster_path": "/poster.jpg",
                        "vote_average": 7.8,
                    },
                ]
            },
        )

    http_client = httpx.AsyncClient(
        base_url="https://api.themoviedb.org/3",
        transport=httpx.MockTransport(handler),
    )
    client = TmdbClient(client=http_client)

    match = await client.search_movie("无间道")
    await http_client.aclose()

    assert match is not None
    assert match.tmdb_id == 10775
    assert match.status == "matched"
    assert match.poster_url == "https://image.tmdb.org/t/p/w500/poster.jpg"


@pytest.mark.asyncio
async def test_search_movie_uses_requested_year_to_disambiguate_exact_titles():
    async def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "results": [
                    {"id": 1, "title": "同名电影", "release_date": "2020-01-01"},
                    {"id": 2, "title": "同名电影", "release_date": "2024-01-01"},
                ]
            },
        )

    http_client = httpx.AsyncClient(
        base_url="https://api.themoviedb.org/3",
        transport=httpx.MockTransport(handler),
    )
    client = TmdbClient(client=http_client)

    match = await client.search_movie("同名电影", year=2024)
    await http_client.aclose()

    assert match is not None
    assert match.tmdb_id == 2
    assert match.status == "matched"

@pytest.mark.asyncio
async def test_movie_by_id_returns_exact_metadata_without_search():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/3/movie/32249"
        return httpx.Response(
            200,
            json={
                "id": 32249,
                "title": "公元2000",
                "original_title": "公元2000",
                "release_date": "2000-01-01",
                "runtime": 103,
                "poster_path": "/poster.jpg",
                "backdrop_path": "/backdrop.jpg",
                "vote_average": 6.4,
            },
        )

    http_client = httpx.AsyncClient(
        base_url="https://api.themoviedb.org/3",
        transport=httpx.MockTransport(handler),
    )
    client = TmdbClient(client=http_client)

    match = await client.movie_by_id(32249)
    await http_client.aclose()

    assert match is not None
    assert match.tmdb_id == 32249
    assert match.status == "matched"
    assert match.runtime_minutes == 103
