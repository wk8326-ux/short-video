from __future__ import annotations

import asyncio
import copy
import json
import time
from collections.abc import Awaitable, Callable
from functools import wraps
from typing import Any, TypeVar


T = TypeVar("T")


class AsyncCatalogCache:
    """Short-lived, single-flight cache for shared catalogue responses.

    Catalogue rows are shared by all signed-in users; favourites, watch
    progress and playable URLs are deliberately served by their own endpoints
    and never enter this cache. The short TTL keeps a scan visible quickly,
    while the per-key lock prevents a burst of identical wall requests from
    multiplying the same SQLite work.
    """

    def __init__(self, *, ttl_seconds: float = 3.0, max_entries: int = 256) -> None:
        self.ttl_seconds = max(0.1, float(ttl_seconds))
        self.max_entries = max(1, int(max_entries))
        self._values: dict[str, tuple[float, T]] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._lock_users: dict[str, int] = {}
        self._guard = asyncio.Lock()

    async def get_or_create(
        self,
        key: str,
        factory: Callable[[], Awaitable[T]],
    ) -> T:
        now = time.monotonic()
        async with self._guard:
            cached = self._values.get(key)
            if cached is not None and cached[0] > now:
                return copy.deepcopy(cached[1])
            lock = self._locks.setdefault(key, asyncio.Lock())
            self._lock_users[key] = self._lock_users.get(key, 0) + 1

        try:
            async with lock:
                now = time.monotonic()
                async with self._guard:
                    cached = self._values.get(key)
                    if cached is not None and cached[0] > now:
                        return copy.deepcopy(cached[1])
                value = await factory()
                async with self._guard:
                    self._values[key] = (time.monotonic() + self.ttl_seconds, copy.deepcopy(value))
                    self._trim_locked()
                return value
        finally:
            async with self._guard:
                users = self._lock_users.get(key, 0) - 1
                if users <= 0:
                    self._lock_users.pop(key, None)
                    if self._locks.get(key) is lock and not lock.locked():
                        self._locks.pop(key, None)
                else:
                    self._lock_users[key] = users

    async def invalidate(self, prefix: str | None = None) -> None:
        async with self._guard:
            if prefix is None:
                self._values.clear()
                return
            for key in tuple(self._values):
                if key.startswith(prefix):
                    self._values.pop(key, None)

    def _trim_locked(self) -> None:
        if len(self._values) <= self.max_entries:
            return
        expired = [key for key, (deadline, _value) in self._values.items() if deadline <= time.monotonic()]
        for key in expired:
            self._values.pop(key, None)
        while len(self._values) > self.max_entries:
            self._values.pop(next(iter(self._values)))


def cached_catalog_endpoint(
    cache: AsyncCatalogCache,
    namespace: str,
    version_provider: Callable[[], Any] | None = None,
) -> Callable[[Callable[..., Awaitable[T]]], Callable[..., Awaitable[T]]]:
    """Decorate a FastAPI catalogue route with a stable query-key cache.

    A cheap revision provider lets database writes invalidate the logical key
    immediately, even when they happen outside an HTTP route (for example a
    metadata worker updating a movie between two wall requests).
    """

    def decorate(function: Callable[..., Awaitable[T]]) -> Callable[..., Awaitable[T]]:
        @wraps(function)
        async def wrapped(*args: Any, **kwargs: Any) -> T:
            version = version_provider() if version_provider is not None else None
            key = f"{namespace}:v={version}:{_stable_key(args, kwargs)}"
            return await cache.get_or_create(key, lambda: function(*args, **kwargs))

        return wrapped

    return decorate


def _stable_key(args: tuple[Any, ...], kwargs: dict[str, Any]) -> str:
    return json.dumps(
        {"args": args, "kwargs": kwargs},
        ensure_ascii=False,
        sort_keys=True,
        default=str,
        separators=(",", ":"),
    )
