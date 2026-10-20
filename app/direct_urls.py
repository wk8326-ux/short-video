from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

FORBIDDEN_MEDIA_HEADERS = frozenset(
    {
        "connection",
        "content-length",
        "host",
        "keep-alive",
        "proxy-connection",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    }
)


def _header_items(value: Any) -> Sequence[tuple[Any, Any]]:
    if isinstance(value, Mapping):
        return list(value.items())
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return ()
        try:
            decoded = json.loads(text)
        except (TypeError, ValueError):
            decoded = None
        if decoded is not None and decoded != value:
            return _header_items(decoded)
        items: list[tuple[str, str]] = []
        for raw_line in text.splitlines():
            name, separator, raw_value = raw_line.partition(":")
            if separator:
                items.append((name, raw_value))
        return items
    if isinstance(value, (list, tuple)):
        if value and all(
            isinstance(item, (list, tuple)) and len(item) == 2 for item in value
        ):
            return list(value)
        return ()
    return ()


def normalize_media_headers(value: Any) -> dict[str, str]:
    """Return safe HTTP request headers from an upstream provider payload.

    AList has shipped ``header`` as an object, a JSON string, and occasionally
    as a newline-separated block depending on the backend version. Keeping the
    parser here means every resolver, probe, and playback proxy sees the same
    values and none of them has to guess the provider's shape.
    """

    normalized: dict[str, str] = {}
    for raw_name, raw_value in _header_items(value):
        name = str(raw_name or "").strip().lower()
        if not name or name in FORBIDDEN_MEDIA_HEADERS or "\n" in name or "\r" in name:
            continue
        header_value = str(raw_value or "").replace("\r", "").replace("\n", " ").strip()
        normalized[name] = header_value
    return normalized


@dataclass(frozen=True, eq=False)
class ResolvedMedia:
    """One playable upstream URL and the headers it needs.

    The tuple-shaped accessors are deliberate compatibility shims: existing
    callers can still unpack ``url, requires_headers`` while new code reads
    ``headers`` without making another provider request.
    """

    url: str
    headers: dict[str, str] = field(default_factory=dict)
    requires_headers: bool = False

    def __post_init__(self) -> None:
        headers = normalize_media_headers(self.headers)
        object.__setattr__(self, "headers", headers)
        object.__setattr__(self, "requires_headers", bool(self.requires_headers or headers))

    def as_tuple(self) -> tuple[str, bool]:
        return self.url, self.requires_headers

    def __iter__(self):
        yield self.url
        yield self.requires_headers

    def __len__(self) -> int:
        return 2

    def __getitem__(self, index: int | slice | str):
        if isinstance(index, str):
            if index == "url":
                return self.url
            if index == "headers":
                return self.headers
            if index == "requires_headers":
                return self.requires_headers
            raise KeyError(index)
        return self.as_tuple()[index]

    def __eq__(self, other: object) -> bool:
        if isinstance(other, ResolvedMedia):
            return (
                self.url == other.url
                and self.headers == other.headers
                and self.requires_headers == other.requires_headers
            )
        if isinstance(other, tuple):
            return self.as_tuple() == other
        return NotImplemented


ResolveResult = ResolvedMedia | tuple[str, bool] | tuple[str, bool, Mapping[str, str]]
ResolveUrl = Callable[[str], Awaitable[ResolveResult]]


def coerce_resolved_media(value: ResolveResult | Any) -> ResolvedMedia:
    if isinstance(value, ResolvedMedia):
        return value
    if isinstance(value, tuple) and len(value) in (2, 3):
        return ResolvedMedia(
            url=str(value[0]),
            headers=normalize_media_headers(value[2]) if len(value) == 3 else {},
            requires_headers=bool(value[1]),
        )
    raise TypeError("Media resolver must return ResolvedMedia or (url, requires_headers)")


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
        self._entries: dict[int, tuple[ResolvedMedia, float]] = {}
        self._locks: dict[int, asyncio.Lock] = {}

    def clear(self) -> None:
        self._entries.clear()

    async def get(self, video_id: int, path: str, *, refresh: bool = False) -> ResolvedMedia:
        cached = self._entries.get(video_id)
        if cached and cached[1] > self._clock() and not refresh:
            return cached[0]

        lock = self._locks.setdefault(video_id, asyncio.Lock())
        async with lock:
            cached = self._entries.get(video_id)
            if cached and cached[1] > self._clock() and not refresh:
                return cached[0]

            resolved = coerce_resolved_media(await self._resolve(path))
            self._entries[video_id] = (resolved, self._clock() + self._ttl_seconds)
            return resolved
