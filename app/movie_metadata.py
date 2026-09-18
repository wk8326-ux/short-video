from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import PurePath


_YEAR = re.compile(r"(?<!\d)((?:19|20)\d{2})(?!\d)")
_TMDB_ID = re.compile(r"\[\s*tmdbid\s*[:=]\s*(\d+)\s*\]", re.IGNORECASE)
_CODE_PATTERN = re.compile(r"(?<![A-Za-z0-9])([A-Za-z]{2,12})[-_ ]?(\d{2,6})(?!\d)")
_CODE_AFTER_DIGITS = re.compile(
    r"(?<![A-Za-z0-9])\d{2,5}([A-Za-z]{2,12})[-_ ]?(\d{2,6})(?!\d)"
)
_TECHNICAL = re.compile(
    r"\b(?:2160p|1080p|720p|576p|480p|4k|8k|uhd|hdr10?\+?|hdr|dv|dolby[ ._-]?vision"
    r"|web[ ._-]?dl|web[ ._-]?rip|blu[ ._-]?ray|brrip|remux|x26[45]|av1|hevc|aac|dts"
    r"|truehd|atmos|中文|国语|中字)\b",
    re.IGNORECASE,
)
_MOVIE_EXTENSIONS = frozenset(
    {
        ".3gp", ".avi", ".flv", ".m2ts", ".m4v", ".mkv", ".mov", ".mp4",
        ".mpeg", ".mpg", ".ts", ".webm", ".wmv",
    }
)


@dataclass(frozen=True)
class MovieName:
    display_title: str
    normalized_title: str
    year: int | None
    tmdb_id: int | None = None
    code: str | None = None


def movie_search_candidates(title: str) -> list[str]:
    """Build cheap fallback queries for noisy cloud-drive file names."""
    value = re.sub(r"\s+", " ", title).strip(" -_:：·…")
    candidates = [value] if value else []

    noise = (
        r"(?:主演[:：]?|国粤双语|国语中字|中文字幕"
        r"|粤语中字|国语配音|国粤|国语|粤语|双语|中字"
        r"|修复版|未删减|加长版|导演剪辑版"
        r"|4k60帧|4k|1080p|720p).*$"
    )
    stripped = re.sub(noise, "", value, flags=re.IGNORECASE)
    stripped = re.sub(r"\s+", " ", stripped).strip(" -_:：·…")
    if stripped:
        candidates.append(stripped)

    # Cloud shares often concatenate Chinese and English names. Searching either
    # isolated name works better than the whole mixed-language string.
    pattern = r"[㐀-鿿]+|[A-Za-z][A-Za-z0-9'&:. -]*"
    for segment in re.findall(pattern, value):
        segment = segment.strip(" -_:：·…")
        if len(segment) >= 2:
            candidates.append(segment)

    # Some web-distributed names replace lowercase l with uppercase I. Only
    # repair likely letter shapes; initialisms such as xXx remain untouched.
    latin = next((item for item in candidates if re.search(r"[A-Za-z]{4}", item)), "")
    if latin and "I" in latin:
        repaired = re.sub(r"I{2,}", lambda match: "l" * len(match.group()), latin)
        repaired = re.sub(r"(?<=[a-z])I(?=[a-z])", "l", repaired)
        if repaired != latin:
            candidates.append(repaired)

    return list(dict.fromkeys(item for item in candidates if len(item) >= 2))


def parse_movie_filename(name: str) -> MovieName:
    suffix = PurePath(name).suffix.lower()
    stem = name[: -len(suffix)] if suffix in _MOVIE_EXTENSIONS else name
    tmdb_match = _TMDB_ID.search(stem)
    tmdb_id = int(tmdb_match.group(1)) if tmdb_match else None
    if tmdb_match:
        stem = stem[: tmdb_match.start()] + stem[tmdb_match.end():]
    # Cloud-drive names often prefix the real番号 with an origin such as
    # ``hhd800.com@`` or ``rh2048.com@``. Search the suffix after the final
    # ``@`` so the domain itself cannot be mistaken for a movie code.
    code_stem = stem.rsplit("@", 1)[-1]
    code_match = _CODE_PATTERN.search(code_stem)
    if code_match is None:
        code_match = _CODE_AFTER_DIGITS.search(code_stem)
    code = (
        f"{code_match.group(1).upper()}-{int(code_match.group(2)):03d}"
        if code_match
        else None
    )
    year_match = _YEAR.search(stem)
    year = int(year_match.group(1)) if year_match else None
    title = stem[: year_match.start()] if year_match else stem
    title = re.sub(r"[._]+", " ", title)
    title = _TECHNICAL.sub(" ", title)
    title = re.sub(r"[\[\](){}【《》「」『』]", " ", title)
    title = re.sub(r"\s+", " ", title).strip(" -_")
    if not title:
        title = stem.strip() or "未命名电影"
    return MovieName(
        display_title=title,
        normalized_title=title.casefold(),
        year=year,
        tmdb_id=tmdb_id,
        code=code,
    )
