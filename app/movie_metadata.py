from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import PurePath


_YEAR = re.compile(r"(?<!\d)((?:19|20)\d{2})(?!\d)")
_TECHNICAL = re.compile(
    r"\b(?:2160p|1080p|720p|576p|480p|4k|8k|uhd|hdr10?\+?|hdr|dv|dolby[ ._-]?vision|web[ ._-]?dl|web[ ._-]?rip|blu[ ._-]?ray|brrip|remux|x26[45]|av1|hevc|aac|dts|truehd|atmos|中文|国语|中字)\b",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class MovieName:
    display_title: str
    normalized_title: str
    year: int | None


def parse_movie_filename(name: str) -> MovieName:
    stem = PurePath(name).stem
    year_match = _YEAR.search(stem)
    year = int(year_match.group(1)) if year_match else None
    title = stem[: year_match.start()] if year_match else stem
    title = re.sub(r"[._]+", " ", title)
    title = _TECHNICAL.sub(" ", title)
    title = re.sub(r"[\[\](){}]", " ", title)
    title = re.sub(r"\s+", " ", title).strip(" -_")
    if not title:
        title = stem.strip() or "未命名电影"
    return MovieName(
        display_title=title,
        normalized_title=title.casefold(),
        year=year,
    )
