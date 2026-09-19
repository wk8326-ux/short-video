from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time
import unicodedata
import urllib.parse
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any

import httpx

logger = logging.getLogger("short-video")

# One index fetch covers a whole scrape run. The remote side walks its sidecar
# tree on every /index call, so keep the page size large and the TTL generous.
INDEX_TTL_SECONDS = 600.0
# After a failed refresh the cached copy stays usable; wait this long before
# trying the network again so one outage cannot turn a scan into a retry storm.
INDEX_RETRY_SECONDS = 60.0
# A caller can ask to go past the cache, but not more often than this. Refreshing
# six movie libraries in a row is six user actions and still one catalogue walk,
# because the copy that was just fetched already contains every cover they added.
INDEX_FORCE_FLOOR_SECONDS = 60.0
# ``index_version`` only moves when the local sidecar inventory moves, so a
# catalogue that answers with the same version is provably identical and does
# not need re-walking. Emby-only rows and Emby-side artwork are invisible to
# that version, which is why one unconditional walk per day stays mandatory.
INDEX_FULL_REFRESH_SECONDS = 86400.0
# The service clamps ``limit`` to 2000 and echoes the value it used, so the walk
# advances by the rows that actually arrived rather than by the size it asked
# for: asking for more than the clamp would otherwise skip records silently.
INDEX_PAGE_SIZE = 2000
# The service rebuilds its whole sidecar/Emby inventory on a cold ``/index``
# (30-180 s there), and the gateway in front of it drops the request at about
# thirty seconds. Waiting a little past that cut turns a cold catalogue into the
# gateway's own answer -- which the retries below recover from once the service
# has cached the inventory -- instead of a bare client timeout.
INDEX_TIMEOUT_SECONDS = 45.0
INDEX_ATTEMPTS = 4
# The published worst case for a cold inventory is 180 s, and the gateway in
# front of the service answers 502 for every one of those seconds. A ladder
# that stops at twenty seconds never sees the catalogue at all, so the delays
# are sized to outlast the build: 30 + 20 + 30 + 45 + 30 + 90 + 30 s.
INDEX_RETRY_DELAYS = (20.0, 45.0, 90.0)
# The published catalogue is past 27k records and keeps growing. The page cap
# only guards against a runaway pagination loop; the old value of 20 silently
# truncated the catalogue at 20k records and left every title past that point
# without a cover no matter how often the library was rescanned.
INDEX_MAX_PAGES = 80
INDEX_CACHE_FILENAME = "shared-metadata-index.json"
# Only the fields the lookup tables actually read survive the trim. The full
# record set is 27k+ entries and the container is capped at 256 MB, so carrying
# the unused Emby fields would cost megabytes for nothing.
INDEX_ENTRY_KEYS = (
    "code",
    "folder_name",
    "title",
    "original_title",
    "year",
    "poster_url",
    "nfo_url",
    "cover_url",
    "source",
    "poster_source",
    "media_path",
    "cloud_path",
    "path_key",
    "metadata_status",
    "overview",
    "studio",
    "actors",
    "genres",
    "website",
)

# The catalogue's canonical paths are relative to the ``光鸭`` mount, but the
# scanner records the AList path with that mount included. Both spellings are
# registered and both are accepted on lookup, so neither side has to guess.
MOUNT_PREFIX = "\u5149\u9e2d"

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


def _path_segments(value: Any) -> list[str]:
    """Split one cloud path the way the shared service folds it.

    URL-encoded octets, backslashes, duplicate slashes and decomposed Unicode
    all reach the API in real requests; normalising them here is what makes a
    path fetched from the scanner equal a path published by the catalogue.
    """
    text = urllib.parse.unquote(str(value or "")).strip().replace("\\", "/")
    while "//" in text:
        text = text.replace("//", "/")
    text = unicodedata.normalize("NFC", text).strip("/")
    parts = [part for part in text.split("/") if part and part != "."]
    if not parts or any(part == ".." for part in parts):
        return []
    return parts


def normalize_media_path(value: Any) -> str:
    """Fold a full media path into the catalogue's lookup key."""
    parts = _path_segments(value)
    return "/".join(parts).casefold() if parts else ""


def _path_key_variants(value: Any) -> tuple[str, ...]:
    """Exact path keys for one value, with the ``光鸭`` mount optional."""
    parts = _path_segments(value)
    if not parts:
        return ()
    keys = ["/".join(parts).casefold()]
    if parts[0] == MOUNT_PREFIX:
        keys.append("/".join(parts[1:]).casefold())
    return tuple(dict.fromkeys(key for key in keys if key))


def _entry_rank(item: dict[str, Any]) -> tuple[int, int, int, int]:
    """How well one row covers a media file, used to break path ties."""
    return (
        1 if item.get("poster_url") else 0,
        1 if item.get("source") == "local" else 0,
        1 if item.get("nfo_url") else 0,
        1 if item.get("metadata_status") == "ready" else 0,
    )


def _entry_path_keys(item: dict[str, Any]) -> tuple[str, ...]:
    """Every exact key an index row answers to."""
    keys: list[str] = []
    for value in (item.get("cloud_path"), item.get("media_path"), item.get("path_key")):
        keys.extend(_path_key_variants(value))
    return tuple(dict.fromkeys(keys))


def media_lookup_keys(name: str, path: str = "") -> tuple[str, ...]:
    """Exact full-path keys for one media file, filename included.

    The caller may pass either the complete AList path or only the folder that
    holds the file, so the filename is appended when it is not already there.
    Parent-folder-only keys are deliberately never produced: a flat folder of
    unrelated releases must not resolve to whichever row shares its directory.
    """
    file_name = str(name or "").strip().replace("\\", "/").split("/")[-1]
    raw = str(path or "").strip()
    if raw and file_name:
        tail = raw.replace("\\", "/").rstrip("/").split("/")[-1]
        if tail.casefold() != file_name.casefold():
            raw = raw.rstrip("/") + "/" + file_name
    elif not raw:
        raw = file_name
    return _path_key_variants(raw)


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
    paths: dict[str, dict[str, Any]] = field(default_factory=dict)
    version: str = ""


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
        cache_path: Path | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        headers = {"Accept": "application/json", "User-Agent": "deepfuck-movie-library/1"}
        if token:
            headers["X-Media-Shared-Token"] = token
        self._headers = headers
        self._index_ttl = index_ttl
        # The catalogue takes ~30 s to walk over the wire, so the folded copy is
        # mirrored to disk: a container restart, a redeploy or a second scan
        # process then starts from the file instead of refetching 28 pages.
        self._cache_path = cache_path
        self._index: SharedMetadataIndex | None = None
        self._index_at = 0.0
        # When a caller's request actually reached the network past the cache.
        # Unlike ``_index_at`` this only moves for those requests, which is what
        # makes it safe to answer a second one from the copy the first fetched.
        self._refreshed_at = 0.0
        # When the full page walk last happened, as opposed to the copy merely
        # being confirmed current by a version probe.
        self._walked_at = 0.0
        self._client = client or httpx.AsyncClient(
            base_url=f"{self.base_url}/",
            headers=headers,
            timeout=httpx.Timeout(INDEX_TIMEOUT_SECONDS, connect=8.0),
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

    @asynccontextmanager
    async def borrow(self):
        """Lend this client out without closing it when the caller is done.

        The folded catalogue is process-wide: closing the client at the end of
        a scrape threw away a 27k-entry index that the next run had to rebuild.
        """
        yield self

    async def warm_index(self, *, refresh: bool = False) -> SharedMetadataIndex:
        """Load the catalogue before the first lookup, optionally past the cache.

        A pass that knows the catalogue moved on -- the rescan a user runs after
        uploading covers -- asks for ``refresh`` so the fetch cannot be answered
        from the copy that predates the upload.
        """
        try:
            return await self.index(refresh=refresh)
        except Exception:
            # Lookups already tolerate a dead catalogue and mark their rows as
            # unresolved, so a warm-up failure must not abort the whole pass.
            logger.warning("Shared metadata index unavailable at the start of a pass")
            return SharedMetadataIndex(names={}, codes={})

    async def index(self, *, refresh: bool = False) -> SharedMetadataIndex:
        """Return the folded-key index, refreshed at most every ``index_ttl``.

        ``refresh`` ignores both the in-memory TTL and the on-disk mirror: the
        service re-walks its sidecar tree on every ``/index`` call, so asking
        again is the only way a cover added after the last fetch becomes visible.
        Refreshing eight libraries in a row is eight user actions and still one
        walk, because the copy the first one fetched already covers the rest.
        """
        forced = refresh and (
            time.monotonic() - self._refreshed_at >= INDEX_FORCE_FLOOR_SECONDS
        )
        if self._is_fresh() and not forced:
            return self._index  # type: ignore[return-value]
        if self._index is None:
            restored = await asyncio.to_thread(self._read_cache_file)
            if restored is not None:
                self._index, self._index_at = restored
                # The mirror was written when the walk happened, so the daily
                # full-refresh clock runs from the fetch it recorded.
                self._walked_at = self._index_at
                if self._is_fresh() and not forced:
                    logger.info("Shared metadata index restored from %s", self._cache_path)
                    return self._index
        # A catalogue whose ``index_version`` has not moved is provably the same
        # inventory, and proving it costs one row instead of the ~28 pages a
        # walk costs on the wire. A user-requested refresh still walks: that is
        # the action whose whole point is to re-read the catalogue.
        if self._index is not None and not forced and await self._version_is_current():
            self._index_at = time.monotonic()
            return self._index
        try:
            entries, version = await self._fetch_entries()
        except Exception:
            if forced:
                # Back off before deciding what to do with the failure: a
                # refresh that just failed must not be retried once per media
                # file for the rest of the pass that asked for it.
                self._refreshed_at = time.monotonic()
            if self._index is None:
                raise
            # A stale index still matches covers; back off instead of hammering
            # the service once per media file for the rest of the scan.
            logger.warning("Shared metadata index refresh failed, reusing the cached copy")
            self._index_at = time.monotonic() - self._index_ttl + INDEX_RETRY_SECONDS
            return self._index
        index = self._build_index(entries, version)
        if index.names or index.codes:
            self._index = index
            self._index_at = time.monotonic()
            self._walked_at = self._index_at
            if forced:
                self._refreshed_at = self._index_at
                # The one line a support question needs: a user who uploaded
                # covers can be told their refresh really did re-read the
                # catalogue, rather than being sent to look at the cache.
                logger.info(
                    "Shared metadata catalogue re-read on request: %s name keys, "
                    "%s path keys",
                    len(index.names),
                    len(index.paths),
                )
            await asyncio.to_thread(self._write_cache_file, entries, version)
        return index

    def _is_fresh(self) -> bool:
        return self._index is not None and time.monotonic() - self._index_at < self._index_ttl

    async def _version_is_current(self) -> bool:
        """Whether the published ``index_version`` still matches our copy.

        The catalogue recomputes its inventory on every ``/index`` call, so the
        version tells us whether the copy we already hold can still be trusted
        without paying for the walk. Anything unexpected -- a probe failure, an
        empty version, a mirror older than a day -- answers ``False`` and leaves
        the caller on the full walk, which is the behaviour that was there
        before the probe existed.
        """
        local = str(getattr(self._index, "version", "") or "")
        if not local:
            return False
        if time.monotonic() - self._walked_at >= INDEX_FULL_REFRESH_SECONDS:
            return False
        try:
            response = await self._client.get("/index", params={"start": 0, "limit": 1})
            response.raise_for_status()
            payload = response.json()
        except Exception as exc:
            logger.info("Shared metadata version probe failed: %s", exc)
            return False
        published = payload.get("index_version") if isinstance(payload, dict) else None
        return bool(published) and published == local

    async def _fetch_entries(self) -> tuple[list[dict[str, Any]], str]:
        """Walk every page the service publishes, not just the first N thousand.

        The response carries a deterministic ``index_version``; it is stored
        alongside the rows so a cache restored from disk can be checked against
        the catalogue without refetching every page.
        """
        entries: list[dict[str, Any]] = []
        start = 0
        total: int | None = None
        version = ""
        for _ in range(INDEX_MAX_PAGES):
            payload = await self._fetch_page(start)
            items = payload.get("items") if isinstance(payload, dict) else None
            if not isinstance(items, list):
                break
            entries.extend(
                self._trim_entry(item) for item in items if isinstance(item, dict)
            )
            start += len(items)
            if not version and isinstance(payload.get("index_version"), str):
                version = payload["index_version"]
            if isinstance(payload.get("total"), int):
                total = int(payload["total"])
            if not items or (total is not None and start >= total):
                break
        if total is not None and len(entries) < total:
            logger.warning(
                "Shared metadata index stopped at %s of %s records", len(entries), total
            )
        return entries, version

    async def _fetch_page(self, start: int) -> dict[str, Any]:
        """One page of the walk, retried.

        The first request after the service restarts pays for the whole
        inventory build, so it can outlive the gateway's timeout while the
        service finishes the work anyway and serves the next request from
        memory. Retrying is therefore what makes a cold catalogue invisible to
        the pass instead of silently demoting it to the stale disk mirror. A
        failed page also used to throw away every page fetched before it.
        """
        last: Exception | None = None
        for attempt in range(INDEX_ATTEMPTS):
            if attempt:
                # The ladder is shorter than the attempt count on purpose: the
                # last delay stands in for every retry past it, so a test that
                # pins two delays still exercises a four-attempt walk.
                delay = INDEX_RETRY_DELAYS[min(attempt, len(INDEX_RETRY_DELAYS)) - 1]
                await asyncio.sleep(delay)
            try:
                response = await self._client.get(
                    "/index", params={"start": start, "limit": INDEX_PAGE_SIZE}
                )
                response.raise_for_status()
                payload = response.json()
            except Exception as exc:
                last = exc
                logger.info(
                    "Shared metadata index page at %s failed (attempt %s/%s): %s",
                    start,
                    attempt + 1,
                    INDEX_ATTEMPTS,
                    exc,
                )
                continue
            return payload
        assert last is not None
        raise last

    @staticmethod
    def _trim_entry(item: dict[str, Any]) -> dict[str, Any]:
        return {
            key: item[key]
            for key in INDEX_ENTRY_KEYS
            if item.get(key) not in (None, "")
        }

    def _read_cache_file(self) -> tuple[SharedMetadataIndex, float] | None:
        if self._cache_path is None:
            return None
        try:
            payload = json.loads(self._cache_path.read_text(encoding="utf-8"))
            fetched_at = float(payload["fetchedAt"])
            items = payload["items"]
            version = str(payload.get("indexVersion") or "")
        except (OSError, ValueError, KeyError, TypeError):
            return None
        if not isinstance(items, list):
            return None
        entries = [item for item in items if isinstance(item, dict) and item]
        if not entries:
            return None
        age = max(0.0, time.time() - fetched_at)
        return self._build_index(entries, version), time.monotonic() - age

    def _write_cache_file(self, entries: list[dict[str, Any]], version: str = "") -> None:
        if self._cache_path is None:
            return
        temporary: Path | None = None
        try:
            self._cache_path.parent.mkdir(parents=True, exist_ok=True)
            suffix = f".{os.getpid()}.{time.time_ns()}.tmp"
            temporary = self._cache_path.with_name(self._cache_path.name + suffix)
            temporary.write_text(
                json.dumps(
                    {
                        "fetchedAt": time.time(),
                        "indexVersion": version,
                        "items": entries,
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                encoding="utf-8",
            )
            os.replace(temporary, self._cache_path)
        except (OSError, TypeError, ValueError) as exc:
            logger.info("Shared metadata index cache write failed: %s", exc)
        finally:
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass

    @staticmethod
    def _build_index(
        entries: list[dict[str, Any]],
        version: str = "",
    ) -> SharedMetadataIndex:
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
        paths: dict[str, dict[str, Any]] = {}
        rejected_names: set[str] = set()
        rejected_codes: set[str] = set()
        rejected_paths: set[str] = set()
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
                elif _entry_rank(item) > _entry_rank(names[name_key]):
                    # One release can be published as several rows (multi-part
                    # files, or a sidecar row plus a bare one). They are the
                    # same identity, so the row that actually carries artwork
                    # has to survive instead of whichever arrived first.
                    names[name_key] = item
            for key in _entry_code_keys(item):
                if not key or key in rejected_codes:
                    continue
                if key not in codes:
                    codes[key] = item
                elif _entry_identity(codes[key]) != identity:
                    codes.pop(key, None)
                    rejected_codes.add(key)
                elif _entry_rank(item) > _entry_rank(codes[key]):
                    codes[key] = item
            for key in _entry_path_keys(item):
                if not key or key in rejected_paths:
                    continue
                current = paths.get(key)
                if current is None:
                    paths[key] = item
                    continue
                if _entry_identity(current) != identity:
                    # A full path is the strongest identity in the catalogue.
                    # Two different releases claiming it means the index is
                    # inconsistent, so neither row may win silently.
                    paths.pop(key, None)
                    rejected_paths.add(key)
                    continue
                if _entry_rank(item) > _entry_rank(current):
                    paths[key] = item
        return SharedMetadataIndex(
            names=names,
            codes=codes,
            paths=paths,
            version=version,
        )

    async def lookup(self, name: str, path: str = "") -> SharedMetadataMatch | None:
        """Match one cloud media file against the shared sidecar index."""
        name_keys, code_keys = candidate_keys(name, path)
        path_keys = media_lookup_keys(name, path)
        if not path_keys and not name_keys and not code_keys:
            return None
        try:
            index = await self.index()
        except Exception as exc:  # network hiccups must not abort a scrape run
            logger.warning("Shared metadata index unavailable: %s", exc)
            return None

        # A media file's complete AList path, including its filename, is the
        # primary identity. This is exact and safe even for flat folders where
        # dozens of unrelated releases share one parent directory.
        for key in path_keys:
            entry = index.paths.get(key)
            if entry is not None:
                return await self._match_from_entry(entry)

        # Older catalogue rows and records outside the sidecar roots may not
        # publish a path. Keep the former name/code matching as a conservative
        # fallback, but never use a parent-only key as exact identity.
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
        """Build a match entirely from the already-fetched inventory.

        The index carries the sidecar metadata, poster URL and status needed by
        the player. A per-title ``/metadata`` request would add thousands of
        round trips to each scan and is therefore not part of normal matching.
        """
        return self._match(entry)

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
            overview=str(payload.get("overview") or payload.get("plot") or "").strip(),
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
