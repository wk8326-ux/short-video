import httpx
import pytest

from app.metatube import MetatubeClient
from app.movie_metadata import parse_movie_filename


@pytest.mark.asyncio
async def test_metatube_lookup_requires_an_exact_number_match():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/movies/JavBus/ABP-485"
        assert request.headers["Authorization"] == "Bearer test-token"
        return httpx.Response(
            200,
            json={
                "data": {
                    "id": "abp-485",
                    "number": "ABP-485",
                    "title": "测试影片",
                    "release_date": "2024-03-05",
                    "cover_url": "https://images.example/cover.jpg",
                    "actors": [{"name": "演员甲"}],
                    "genres": ["剧情", "测试"],
                }
            },
        )

    http_client = httpx.AsyncClient(
        base_url="http://metatube.test",
        transport=httpx.MockTransport(handler),
    )
    client = MetatubeClient(
        base_url="http://metatube.test",
        token="test-token",
        provider="JavBus",
        client=http_client,
    )

    match = await client.lookup("abp485")
    await http_client.aclose()

    assert match is not None
    assert match.title == "测试影片"
    assert match.release_date == "2024-03-05"
    assert match.performers == ("演员甲",)
    assert match.genres == ("剧情", "测试")


def test_movie_filename_extracts_metatube_number_without_affecting_tmdb_id():
    parsed = parse_movie_filename("ABP-485.2024.[tmdbid=32249].mkv")

    assert parsed.metatube_code == "ABP-485"
    assert parsed.tmdb_id == 32249
