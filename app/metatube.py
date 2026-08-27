from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

import httpx


_CODE = re.compile(r"^([A-Za-z]{2,12})[-_ ]?(\d{2,6})$")


@dataclass(frozen=True)
class MetatubeMatch:
    provider: str
    movie_id: str
    title: str
    original_title: str
    release_date: str | None
    overview: str
    poster_url: str | None
    backdrop_url: str | None
    rating: float | None
    runtime_minutes: int | None
    genres: tuple[str, ...]
    performers: tuple[str, ...]
    studio: str | None
    confidence: float


class MetatubeClient:
    def __init__(
        self,
        *,
        base_url: str,
        token: str = "",
        provider: str = "JavBus",
        client: httpx.AsyncClient | None = None,
    ):
        headers = {"Accept": "application/json", "User-Agent": "deepfuck-movie-library/1"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        self._provider = provider
        self._headers = headers
        self._client = client or httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            headers=headers,
            timeout=httpx.Timeout(20.0, connect=8.0),
        )
        self._owns_client = client is None

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def lookup(self, code: str) -> MetatubeMatch | None:
        normalized = normalize_code(code)
        if not normalized:
            return None
        detail = await self._get_detail(normalized)
        if detail and codes_match(normalized, _code_from_item(detail)):
            return _match_from_item(detail, self._provider, 0.99)

        response = await self._client.get(
            "/v1/movies/search",
            params={"q": normalized, "provider": self._provider},
            headers=self._headers,
        )
        if self._is_soft_failure(response.status_code):
            return None
        response.raise_for_status()
        payload = response.json()
        results = payload.get("data") if isinstance(payload, dict) else payload
        if not isinstance(results, list):
            return None
        for item in results:
            if isinstance(item, dict) and codes_match(normalized, _code_from_item(item)):
                movie_id = str(item.get("id") or item.get("number") or normalized)
                detailed = await self._get_detail(movie_id)
                return _match_from_item(detailed or item, self._provider, 0.99)
        return None

    async def _get_detail(self, movie_id: str) -> dict[str, Any] | None:
        provider = quote(self._provider, safe="")
        encoded_id = quote(movie_id, safe="")
        response = await self._client.get(
            f"/v1/movies/{provider}/{encoded_id}",
            headers=self._headers,
        )
        if self._is_soft_failure(response.status_code):
            return None
        response.raise_for_status()
        payload = response.json()
        item = payload.get("data") if isinstance(payload, dict) and "data" in payload else payload
        return item if isinstance(item, dict) and not item.get("error") else None

    @staticmethod
    def _is_soft_failure(status_code: int) -> bool:
        """Treat provider misses/outages as a miss so one title cannot abort a scan."""
        return status_code == 404 or status_code >= 500


def normalize_code(value: str) -> str:
    match = _CODE.match(str(value or "").strip())
    if not match:
        return ""
    return f"{match.group(1).upper()}-{int(match.group(2)):03d}"


def codes_match(left: str, right: str) -> bool:
    return bool(left and right and normalize_code(left) == normalize_code(right))


def _code_from_item(item: dict[str, Any]) -> str:
    return str(item.get("number") or item.get("id") or "")


def _strings(value: Any) -> tuple[str, ...]:
    if isinstance(value, str):
        return tuple(part.strip() for part in re.split(r"[,/|]", value) if part.strip())
    if not isinstance(value, list):
        return ()
    values: list[str] = []
    for item in value:
        if isinstance(item, dict):
            item = item.get("name") or item.get("title") or item.get("label")
        text = str(item or "").strip()
        if text and text not in values:
            values.append(text)
    return tuple(values)


def _number(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _match_from_item(item: dict[str, Any], provider: str, confidence: float) -> MetatubeMatch:
    release_date = str(item.get("release_date") or item.get("releaseDate") or item.get("date") or "").strip() or None
    runtime = _number(item.get("runtime") or item.get("runtime_minutes") or item.get("duration"))
    return MetatubeMatch(
        provider=str(item.get("provider") or provider),
        movie_id=str(item.get("id") or item.get("number") or ""),
        title=str(item.get("title") or item.get("name") or item.get("number") or ""),
        original_title=str(item.get("original_title") or item.get("originalTitle") or ""),
        release_date=release_date,
        overview=str(item.get("overview") or item.get("plot") or item.get("description") or ""),
        poster_url=str(item.get("cover_url") or item.get("poster_url") or item.get("cover") or item.get("poster") or "") or None,
        backdrop_url=str(item.get("backdrop_url") or item.get("fanart_url") or item.get("thumb_url") or "") or None,
        rating=_number(item.get("rating") or item.get("score")),
        runtime_minutes=round(runtime) if runtime and runtime > 0 else None,
        genres=_strings(item.get("genres") or item.get("genre") or item.get("tags")),
        performers=_strings(item.get("performers") or item.get("actors") or item.get("actresses")),
        studio=str(item.get("studio") or item.get("maker") or item.get("label") or "").strip() or None,
        confidence=confidence,
    )
