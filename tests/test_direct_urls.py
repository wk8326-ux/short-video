import asyncio

import pytest

from app.direct_urls import DirectUrlCache


@pytest.mark.asyncio
async def test_reuses_cached_url_until_expiry_and_supports_forced_refresh():
    calls = 0
    now = [100.0]

    async def resolve(path: str) -> tuple[str, bool]:
        nonlocal calls
        calls += 1
        return f"https://cdn.example/{calls}.mp4", False

    cache = DirectUrlCache(resolve, 600, clock=lambda: now[0])

    first = await cache.get(7, "/media/video.mp4")
    cached = await cache.get(7, "/media/video.mp4")
    refreshed = await cache.get(7, "/media/video.mp4", refresh=True)
    now[0] += 601
    expired = await cache.get(7, "/media/video.mp4")

    assert first == cached
    assert refreshed[0].endswith("/2.mp4")
    assert expired[0].endswith("/3.mp4")
    assert calls == 3


@pytest.mark.asyncio
async def test_concurrent_requests_share_one_resolution():
    calls = 0
    started = asyncio.Event()
    release = asyncio.Event()

    async def resolve(path: str) -> tuple[str, bool]:
        nonlocal calls
        calls += 1
        started.set()
        await release.wait()
        return "https://cdn.example/video.mp4", False

    cache = DirectUrlCache(resolve, 600)
    first = asyncio.create_task(cache.get(7, "/media/video.mp4"))
    await started.wait()
    second = asyncio.create_task(cache.get(7, "/media/video.mp4"))
    await asyncio.sleep(0)
    release.set()

    assert await asyncio.gather(first, second) == [
        ("https://cdn.example/video.mp4", False),
        ("https://cdn.example/video.mp4", False),
    ]
    assert calls == 1


@pytest.mark.asyncio
async def test_clear_invalidates_cached_urls():
    calls = 0

    async def resolve(path: str) -> tuple[str, bool]:
        nonlocal calls
        calls += 1
        return f"https://cdn.example/{calls}.mp4", False

    cache = DirectUrlCache(resolve, 600)
    await cache.get(7, "/media/video.mp4")
    cache.clear()
    resolved = await cache.get(7, "/media/video.mp4")

    assert resolved[0].endswith("/2.mp4")
    assert calls == 2
