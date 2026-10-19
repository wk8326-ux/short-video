from __future__ import annotations

import asyncio
import inspect

from fastapi.testclient import TestClient

from app.catalog_cache import AsyncCatalogCache, cached_catalog_endpoint
from test_scan import boot


def test_catalog_cache_single_flight_expiry_and_copy_isolation():
    async def run() -> None:
        cache = AsyncCatalogCache(ttl_seconds=0.1, max_entries=4)
        calls = 0

        async def factory() -> dict[str, list[int]]:
            nonlocal calls
            calls += 1
            await asyncio.sleep(0.01)
            return {"items": [1]}

        results = await asyncio.gather(
            *(cache.get_or_create("movies:{}", factory) for _ in range(5))
        )
        assert calls == 1
        results[0]["items"].append(99)
        assert await cache.get_or_create("movies:{}", factory) == {"items": [1]}

        await asyncio.sleep(0.12)
        assert await cache.get_or_create("movies:{}", factory) == {"items": [1]}
        assert calls == 2

        await cache.invalidate("movies:")
        assert await cache.get_or_create("movies:{}", factory) == {"items": [1]}
        assert calls == 3
        assert not cache._locks
        assert not cache._lock_users

    asyncio.run(run())


def test_catalog_cache_decorator_preserves_fastapi_signature():
    cache = AsyncCatalogCache()

    @cached_catalog_endpoint(cache, "movies")
    async def endpoint(query: str = "", limit: int = 24) -> dict[str, object]:
        return {"query": query, "limit": limit}

    signature = inspect.signature(endpoint)
    assert list(signature.parameters) == ["query", "limit"]
    assert inspect.iscoroutinefunction(endpoint)


async def test_movie_route_collapses_identical_catalog_requests(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    calls = {"movies": 0, "counts": 0}
    original_movies = main.database.movies
    original_count = main.database.movie_count

    def traced_movies(*args, **kwargs):
        calls["movies"] += 1
        return original_movies(*args, **kwargs)

    def traced_count(*args, **kwargs):
        calls["counts"] += 1
        return original_count(*args, **kwargs)

    monkeypatch.setattr(main.database, "movies", traced_movies)
    monkeypatch.setattr(main.database, "movie_count", traced_count)
    headers = {"Cookie": f"short_session={main.session_manager.issue()}"}

    with TestClient(main.app) as client:
        first = client.get("/api/movies?limit=1", headers=headers)
        second = client.get("/api/movies?limit=1", headers=headers)

    assert first.status_code == 200
    assert second.status_code == 200
    assert calls == {"movies": 1, "counts": 1}
    await main.source_registry.close()
