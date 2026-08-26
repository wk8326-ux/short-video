from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import httpx


@dataclass(frozen=True)
class TmdbMatch:
    tmdb_id: int
    title: str
    original_title: str
    year: int | None
    overview: str
    poster_url: str | None
    backdrop_url: str | None
    rating: float | None
    status: str
    confidence: float
    runtime_minutes: int | None = None


class TmdbClient:
    def __init__(
        self,
        *,
        read_token: str = "",
        api_key: str = "",
        language: str = "zh-CN",
        client: httpx.AsyncClient | None = None,
    ):
        self._read_token = read_token
        self._api_key = api_key
        self._language = language
        headers = {"Accept": "application/json", "User-Agent": "short-video-movie-library/1"}
        if read_token:
            headers["Authorization"] = f"Bearer {read_token}"
        self._client = client or httpx.AsyncClient(
            base_url="https://api.themoviedb.org/3",
            headers=headers,
            timeout=httpx.Timeout(15.0, connect=8.0),
        )
        self._owns_client = client is None

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def movie_by_id(self, tmdb_id: int) -> TmdbMatch | None:
        params: dict[str, Any] = {"language": self._language}
        if self._api_key and not self._read_token:
            params["api_key"] = self._api_key
        response = await self._client.get(f"/movie/{int(tmdb_id)}", params=params)
        if response.status_code == 404:
            return None
        response.raise_for_status()
        item = response.json()
        if not isinstance(item, dict) or not item.get("id"):
            return None
        return _match_from_item(
            item,
            fallback_title="",
            requested_year=None,
            exact=True,
            confidence=0.99,
        )

    async def search_movie(self, title: str, *, year: int | None = None) -> TmdbMatch | None:
        params: dict[str, Any] = {"query": title, "language": self._language, "include_adult": "false"}
        if year:
            params["year"] = year
        if self._api_key and not self._read_token:
            params["api_key"] = self._api_key
        response = await self._client.get("/search/movie", params=params)
        response.raise_for_status()
        payload = response.json()
        results = payload.get("results") if isinstance(payload, dict) else None
        if not isinstance(results, list) or not results:
            return None
        normalized_query = _normalize_title(title)

        def rank(item: dict[str, Any]) -> tuple[int, int]:
            release_date = str(item.get("release_date") or "")
            candidate_year = int(release_date[:4]) if release_date[:4].isdigit() else None
            candidate_titles = {
                _normalize_title(str(item.get("title") or "")),
                _normalize_title(str(item.get("original_title") or "")),
            }
            exact_title = normalized_query in candidate_titles
            return (
                (4 if exact_title else 0) + (2 if year and candidate_year == year else 0),
                1 if exact_title else 0,
            )

        candidate = max(results, key=rank)
        release_date = str(candidate.get("release_date") or "")
        candidate_year = int(release_date[:4]) if release_date[:4].isdigit() else None
        candidate_titles = {
            _normalize_title(str(candidate.get("title") or "")),
            _normalize_title(str(candidate.get("original_title") or "")),
        }
        exact_title = normalized_query in candidate_titles
        exact_year = year is None or candidate_year == year
        exact = exact_title and exact_year
        poster_path = str(candidate.get("poster_path") or "")
        backdrop_path = str(candidate.get("backdrop_path") or "")
        return _match_from_item(
            candidate,
            fallback_title=title,
            requested_year=year,
            exact=exact,
            confidence=0.95 if exact else 0.7,
        )


def _match_from_item(
    item: dict[str, Any],
    *,
    fallback_title: str,
    requested_year: int | None,
    exact: bool,
    confidence: float,
) -> TmdbMatch:
    release_date = str(item.get("release_date") or "")
    candidate_year = int(release_date[:4]) if release_date[:4].isdigit() else None
    poster_path = str(item.get("poster_path") or "")
    backdrop_path = str(item.get("backdrop_path") or "")
    runtime = item.get("runtime")
    return TmdbMatch(
        tmdb_id=int(item.get("id") or 0),
        title=str(item.get("title") or item.get("name") or fallback_title),
        original_title=str(item.get("original_title") or ""),
        year=candidate_year,
        overview=str(item.get("overview") or ""),
        poster_url=f"https://image.tmdb.org/t/p/w500{poster_path}" if poster_path else None,
        backdrop_url=f"https://image.tmdb.org/t/p/w1280{backdrop_path}" if backdrop_path else None,
        rating=float(item["vote_average"]) if item.get("vote_average") is not None else None,
        runtime_minutes=int(runtime) if isinstance(runtime, int) and runtime > 0 else None,
        status="matched" if exact else "ambiguous",
        confidence=confidence,
    )


def _normalize_title(value: str) -> str:
    return "".join(character for character in value.casefold() if character.isalnum())
