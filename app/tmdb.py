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


class TmdbClient:
    def __init__(self, *, read_token: str = "", api_key: str = "", language: str = "zh-CN"):
        self._read_token = read_token
        self._api_key = api_key
        self._language = language
        headers = {"Accept": "application/json", "User-Agent": "short-video-movie-library/1"}
        if read_token:
            headers["Authorization"] = f"Bearer {read_token}"
        self._client = httpx.AsyncClient(
            base_url="https://api.themoviedb.org/3",
            headers=headers,
            timeout=httpx.Timeout(15.0, connect=8.0),
        )

    async def close(self) -> None:
        await self._client.aclose()

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
        candidate = next(
            (item for item in results if year and str(item.get("release_date") or "").startswith(str(year))),
            results[0],
        )
        release_date = str(candidate.get("release_date") or "")
        candidate_year = int(release_date[:4]) if release_date[:4].isdigit() else None
        exact = bool(year and candidate_year == year)
        poster_path = str(candidate.get("poster_path") or "")
        backdrop_path = str(candidate.get("backdrop_path") or "")
        return TmdbMatch(
            tmdb_id=int(candidate.get("id") or 0),
            title=str(candidate.get("title") or candidate.get("name") or title),
            original_title=str(candidate.get("original_title") or ""),
            year=candidate_year,
            overview=str(candidate.get("overview") or ""),
            poster_url=f"https://image.tmdb.org/t/p/w500{poster_path}" if poster_path else None,
            backdrop_url=f"https://image.tmdb.org/t/p/w1280{backdrop_path}" if backdrop_path else None,
            rating=float(candidate["vote_average"]) if candidate.get("vote_average") is not None else None,
            status="matched" if exact or year is None else "ambiguous",
            confidence=0.95 if exact else 0.7,
        )
