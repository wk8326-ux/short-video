from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any

import httpx

logger = logging.getLogger("short-video")

# One index fetch covers a whole scrape run. The remote side walks its sidecar
# tree on every /index call, so keep the page size large and the TTL generous.
INDEX_TTL_SECONDS = 600.0
INDEX_PAGE_SIZE = 1000
INDEX_MAX_PAGES = 20

_CODE = re.compile(r"([A-Za-z]{2,12})[-_ ]?(\d{2,6})(?!\d)")
_BRACKETED = re.compile(r"[\[\(（【][^\]\)）】]*[\]\)）】]")
_EXTENSION = re.compile(r"\.[A-Za-z0-9]{2,4}$")
# Bare tokens that show up in cloud-drive file names and would otherwise be
# mistaken for a release code (``010124_001-1pon-1080p`` -> ``PON-1080``).
_CODE_STOPWORDS = frozenset(
    {
        "av", "com", "fhd", "hd", "mp4", "mkv", "mov", "net", "org", "pon",
        "rip", "site", "tv", "vip", "www", "xyz",
    }
)
# Folder names that say nothing about which release a file is. The catalogue
# folds to alphanumerics, so a flat "关键词分类" folder full of unrelated titles
# can be called ``mp4`` or a quality tag, and matching on that name glues dozens
# of files onto one poster. Those keys are never allowed to decide a match.
_GENERIC_NAME_KEYS = frozenset(
    {
        "1080p", "2160p", "480p", "4k", "720p", "8k", "avi", "bd", "bluray",
        "blurayrip", "download", "downloads", "fhd", "flv", "hd", "hdrip",
        "mkv", "mov", "movie", "movies", "mp4", "mpeg", "mpg", "new", "other",
        "others", "temp", "tmp", "ts", "uhd", "video", "videos", "web", "webdl",
        "wmv", "www",
    }
)


@dataclass(frozen=True)
class SharedMetadataMatch:
    code: str
    title: str
    original_title: str
    year: int | None
    overview: str
    studio: str
    performers: tuple[str, ...]
    poster_url: str
    backdrop_url: str
    website: str
    confidence: float


def fold_key(value: Any) -> str:
    """Normalise a name to the same shape the shared service uses for lookups."""
    return re.sub(r"[^a-z0-9]+", "", str(value or "").lower())


def guess_code(value: Any) -> str:
    """Extract a ``ABC-123`` code from a cloud-drive name, ignoring origin hosts."""
    raw = _BRACKETED.sub(" ", str(value or ""))
    raw = _EXTENSION.sub("", raw)
    raw = raw.rsplit("@", 1)[-1]
    for match in _CODE.finditer(raw):
        if match.group(1).lower() not in _CODE_STOPWORDS:
            return f"{match.group(1).upper()}-{int(match.group(2)):03d}"
    return ""


def code_key(value: Any) -> str:
    """Fold any code spelling so ``CARIB-010225`` and ``carib-10225`` agree."""
    return fold_key(guess_code(value)) or fold_key(value)


def _entry_identity(item: dict[str, Any]) -> str:
    """A stable identity for one index entry: its release code, else its folder."""
    return code_key(item.get("code")) or fold_key(item.get("folder_name"))


def _entry_code_keys(item: dict[str, Any]) -> tuple[str, ...]:
    keys: list[str] = []
    raw_code = fold_key(item.get("code"))
    if raw_code:
        keys.append(raw_code)
    for value in (item.get("code"), item.get("folder_name")):
        # Only a value that really carries a ``ABC-123`` shape may become a code
        # key; a folder name that is just a word must not leak into the table.
        if guess_code(value):
            keys.append(code_key(value))
    return tuple(dict.fromkeys(keys))


def _entry_agrees_with(entry: dict[str, Any], code_keys: tuple[str, ...]) -> bool:
    """Reject an index entry that describes a different release than the file.

    The file's own name is the arbiter: when it carries a code and the entry is
    about another code, the entry is wrong no matter how well a folder name
    happens to line up. Files without any code keep the name-only behaviour.
    """
    if not code_keys:
        return True
    entry_code = code_key(entry.get("code"))
    return not entry_code or entry_code in code_keys


def candidate_keys(name: str, path: str = "") -> tuple[tuple[str, ...], tuple[str, ...]]:
    """Split lookup keys into strong name keys and derived code keys.

    The shared index is keyed by sidecar folder name, so the containing folder
    is tried first, then the file name with any origin-host prefix stripped,
    then the raw file name. Code keys are guesses, so they are only consulted
    after every name key missed; a wrong cover is worse than no cover.
    """
    stem = _EXTENSION.sub("", str(name or ""))
    parent = PurePosixPath(str(path or "")).parent.name
    names: list[str] = []
    codes: list[str] = []
    for value in (parent, stem.rsplit("@", 1)[-1], stem):
        folded = fold_key(value)
        if folded:
            names.append(folded)
        code = guess_code(value)
        if code:
            codes.append(code_key(code))
    return tuple(dict.fromkeys(names)), tuple(dict.fromkeys(codes))


@dataclass(frozen=True)
class SharedMetadataIndex:
    """Folded-key lookup tables published by the shared service."""

    names: dict[str, dict[str, Any]]
    codes: dict[str, dict[str, Any]]


class SharedMetadataClient:
    """Read-only client for the shared cover/NFO service used by the media stack.

    The service already holds the sidecar covers and NFO metadata that the
    standalone Emby stack scraped, so the movie library can reuse them instead
    of scraping the same titles a second time.
    """

    def __init__(
        self,
        *,
        base_url: str,
        token: str = "",
        client: httpx.AsyncClient | None = None,
        index_ttl: float = INDEX_TTL_SECONDS,
    ):
        self.base_url = base_url.rstrip("/")
        headers = {"Accept": "application/json", "User-Agent": "deepfuck-movie-library/1"}
        if token:
            headers["X-Media-Shared-Token"] = token
        self._headers = headers
        self._index_ttl = index_ttl
        self._index: SharedMetadataIndex | None = None
        self._index_at = 0.0
        self._client = client or httpx.AsyncClient(
            base_url=f"{self.base_url}/",
            headers=headers,
            timeout=httpx.Timeout(20.0, connect=8.0),
        )
        self._owns_client = client is None

    @property
    def host(self) -> str:
        try:
            return (httpx.URL(self.base_url).host or "").lower()
        except (httpx.InvalidURL, ValueError):
            return ""

    def absolute_url(self, relative: str) -> str:
        value = str(relative or "").strip()
        if not value:
            return ""
        if value.startswith(("http://", "https://")):
            return value
        return f"{self.base_url}/{value.lstrip('/')}"

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def index(self) -> SharedMetadataIndex:
        """Return the folded-key index, refreshed at most every ``index_ttl``."""
        if self._index is not None and time.monotonic() - self._index_at < self._index_ttl:
            return self._index
        entries: list[dict[str, Any]] = []
        start = 0
        for _ in range(INDEX_MAX_PAGES):
            response = await self._client.get(
                "/index", params={"start": start, "limit": INDEX_PAGE_SIZE}
            )
            response.raise_for_status()
            payload = response.json()
            items = payload.get("items") if isinstance(payload, dict) else None
            if not isinstance(items, list):
                break
            entries.extend(item for item in items if isinstance(item, dict))
            total = payload.get("total")
            start += INDEX_PAGE_SIZE
            if not items or not isinstance(total, int) or start >= total:
                break
        index = self._build_index(entries)
        if index.names or index.codes:
            self._index = index
            self._index_at = time.monotonic()
        return index

    @staticmethod
    def _build_index(entries: list[dict[str, Any]]) -> SharedMetadataIndex:
        """Fold the published catalogue into lookup tables that never lie.

        The catalogue has no stable primary key: several unrelated releases can
        share one ``folder_name`` (flat "关键词分类" folders full of different
        codes). Keeping whichever entry arrived first - the old ``setdefault``
        behaviour - handed every file in that folder the same cover. A key that
        is claimed by more than one release is now dropped outright, and the
        lookup falls through to the file's own code instead.
        """
        names: dict[str, dict[str, Any]] = {}
        codes: dict[str, dict[str, Any]] = {}
        rejected_names: set[str] = set()
        rejected_codes: set[str] = set()
        for item in entries:
            identity = _entry_identity(item)
            name_key = fold_key(item.get("folder_name"))
            if name_key and name_key not in rejected_names:
                if name_key in _GENERIC_NAME_KEYS:
                    rejected_names.add(name_key)
                elif name_key not in names:
                    names[name_key] = item
                elif _entry_identity(names[name_key]) != identity:
                    names.pop(name_key, None)
                    rejected_names.add(name_key)
            for key in _entry_code_keys(item):
                if not key or key in rejected_codes:
                    continue
                if key not in codes:
                    codes[key] = item
                elif _entry_identity(codes[key]) != identity:
                    codes.pop(key, None)
                    rejected_codes.add(key)
        return SharedMetadataIndex(names=names, codes=codes)

    async def lookup(self, name: str, path: str = "") -> SharedMetadataMatch | None:
        """Match one cloud media file against the shared sidecar index."""
        name_keys, code_keys = candidate_keys(name, path)
        if not name_keys and not code_keys:
            return None
        try:
            index = await self.index()
        except Exception as exc:  # network hiccups must not abort a scrape run
            logger.warning("Shared metadata index unavailable: %s", exc)
            return None
        for key in name_keys:
            entry = index.names.get(key)
            # A folder-name hit still has to describe the same release as the
            # file itself, otherwise a flat directory would hand every one of
            # its files the cover of whichever entry happened to be indexed.
            if entry is not None and _entry_agrees_with(entry, code_keys):
                return await self._match_from_entry(entry)
        for key in code_keys:
            entry = index.codes.get(key)
            if entry is not None and _entry_agrees_with(entry, code_keys):
                return await self._match_from_entry(entry)
        return None

    async def _match_from_entry(self, entry: dict[str, Any]) -> SharedMetadataMatch:
        detail: dict[str, Any] = {}
        code = str(entry.get("code") or "").strip()
        if code:
            try:
                response = await self._client.get("/metadata", params={"code": code})
                response.raise_for_status()
                payload = response.json()
                if isinstance(payload, dict) and payload.get("ok"):
                    detail = payload
            except Exception as exc:
                # The index entry alone still carries a usable cover.
                logger.info("Shared metadata detail miss for %s: %s", code, exc)
        merged = {**entry, **{key: value for key, value in detail.items() if value}}
        return self._match(merged)

    def _match(self, payload: dict[str, Any]) -> SharedMetadataMatch:
        actors = payload.get("actors")
        performers = tuple(
            str(item).strip() for item in actors if str(item).strip()
        ) if isinstance(actors, list) else ()
        year = str(payload.get("year") or "").strip()
        poster = self.absolute_url(str(payload.get("poster_url") or ""))
        cover = str(payload.get("cover_url") or "").strip()
        return SharedMetadataMatch(
            code=str(payload.get("code") or "").strip(),
            title=str(payload.get("title") or "").strip(),
            original_title=str(payload.get("original_title") or "").strip(),
            year=int(year) if year.isdigit() else None,
            overview=str(payload.get("plot") or "").strip(),
            studio=str(payload.get("studio") or "").strip(),
            performers=performers,
            # The wall and the detail artwork both read ``poster_url`` and crop
            # it, so the high-resolution JavDB cover has to win. The sidecar
            # image only fills in when the remote index has no cover at all.
            poster_url=cover or poster,
            backdrop_url=poster or cover,
            website=str(payload.get("website") or "").strip(),
            confidence=0.98,
        )

    async def metadata_by_code(self, code: str) -> SharedMetadataMatch | None:
        normalized = str(code or "").strip()
        if not normalized:
            return None
        try:
            response = await self._client.get("/metadata", params={"code": normalized})
            response.raise_for_status()
            payload = response.json()
        except Exception as exc:
            logger.info("Shared metadata lookup failed for %s: %s", normalized, exc)
            return None
        if not isinstance(payload, dict) or not payload.get("ok"):
            return None
        return self._match(payload)
