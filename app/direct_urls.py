from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable

ResolvedUrl = tuple[str, bool]
ResolveUrl = Callable[[str], Awaitable[ResolvedUrl]]


class DirectUrlCache:
    def __init__(
        self,
        resolve: ResolveUrl,
        ttl_seconds: int,
        *,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._resolve = resolve
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._entries: dict[int, tuple[ResolvedUrl, float]] = {}
        self._locks: dict[int, asyncio.Lock] = {}

    def clear(self) -> None:
        self._entries.clear()

    async def get(self, video_id: int, path: str, *, refresh: bool = False) -> ResolvedUrl:
        cached = self._entries.get(video_id)
        if cached and cached[1] > self._clock() and not refresh:
            return cached[0]

        lock = self._locks.setdefault(video_id, asyncio.Lock())
        async with lock:
            cached = self._entries.get(video_id)
            if cached and cached[1] > self._clock() and not refresh:
                return cached[0]

            resolved = await self._resolve(path)
            self._entries[video_id] = (resolved, self._clock() + self._ttl_seconds)
            return resolved
