from __future__ import annotations

import asyncio
import hashlib
import io
import json
import logging
import os
import secrets
import sqlite3
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlsplit

import httpx
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from app.alist import AListError
from app.app_update import AppUpdateStore, InvalidUpdateManifest, UpdateNotPublished
from app.auth import LoginRateLimiter, SESSION_COOKIE, SessionManager, verify_password
from app.database import LibraryDatabase, normalize_root_paths
from app.drama import (
    CATEGORY_SLUGS,
    build_series,
    decrypt_media,
    episode_order_key,
    is_encrypted_image_url,
    parse_series_metadata,
)
from app.faststart import inspect_mp4_prefix
from app.media_metadata import mp4_duration_seconds
from app.media_sources import MediaSourceRegistry
from app.scanner import ResumableScanner, initial_steps
from app.movie_metadata import parse_movie_filename
from app.shared_metadata import INDEX_DB_FILENAME, SharedMetadataClient

try:  # Pillow is a hard dependency of the image; the guard keeps imports working
    from PIL import Image  # in a bare interpreter used by tooling.
except ImportError:  # pragma: no cover - the runtime image always installs it
    Image = None  # type: ignore[assignment]
from app.settings import Settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
logger = logging.getLogger("short-video")

APP_VERSION = "1.6.0-beta.29"
settings = Settings.from_env()
settings.validate()
database = LibraryDatabase(settings.database_path)
app_update_store = AppUpdateStore(Path(settings.database_path).resolve().parent / "app-update")
source_registry = MediaSourceRegistry(database, settings)
session_manager = SessionManager(
    settings.session_secret,
    settings.auth_password_hash,
    settings.session_days * 24 * 60 * 60,
)
login_limiter = LoginRateLimiter()

scan_state: dict[str, Any] = {
    "running": False,
    "lastSuccess": None,
    "lastError": None,
    "directories": 0,
    "sources": {},
}
scan_locks: dict[str, asyncio.Lock] = {}
metadata_state: dict[str, Any] = {
    "running": False,
    "checked": 0,
    "total": 0,
    "lastSuccess": None,
    "lastError": None,
}
metadata_lock = asyncio.Lock()
movie_metadata_state: dict[str, Any] = {
    "running": False,
    "source": None,
    "checked": 0,
    "total": 0,
    "matched": 0,
    "lastSuccess": None,
    "lastError": None,
}
movie_metadata_lock = asyncio.Lock()
# Sources whose cover pass was requested while another one held the lock. The
# pass is a single-flight job, and dropping the request instead of queueing it
# is what left whole libraries stamped ``pending`` forever: the scan that
# finished second simply returned ``False`` and nothing ever looked again.
movie_metadata_queue: list[tuple[str, bool, bool]] = []
movie_metadata_queued: set[str] = set()
drama_metadata_state: dict[str, Any] = {
    "running": False,
    "source": None,
    "checked": 0,
    "total": 0,
    "matched": 0,
    "lastSuccess": None,
    "lastError": None,
}
drama_metadata_queue: list[str] = []
drama_metadata_queued: set[str] = set()
drama_metadata_lock = asyncio.Lock()
fast_start_state: dict[str, Any] = {
    "running": False,
    "checked": 0,
    "total": 0,
    "lastSuccess": None,
    "lastError": None,
}
fast_start_lock = asyncio.Lock()
background_tasks: set[asyncio.Task[Any]] = set()
movie_image_client: httpx.AsyncClient | None = None
MOVIE_IMAGE_MAX_BYTES = 12 * 1024 * 1024
MOVIE_IMAGE_CACHE_SECONDS = 7 * 24 * 60 * 60
MOVIE_IMAGE_USER_AGENT = "deepfuck-movie-library/1"
# Phones read these covers straight off the origin, so the cache is the whole
# first-frame budget: a phone that has to wait for a Pacific round trip sees the
# poster arrive half a second late every time it scrolls back. The disk beside
# the library database is far larger than this ceiling, so it is set by what the
# artwork hosts can hand out per hour rather than by free space.
MOVIE_IMAGE_CACHE_MAX_BYTES = 2048 * 1024 * 1024
# Posters are proxied so phones never touch JavBus/DMM directly. The wall only
# ever draws them a few hundred pixels wide, so the proxy can hand out a
# downscaled WebP instead of the multi-megabyte original: same picture, a
# fraction of the bytes on a trans-Pacific mobile link.
MOVIE_IMAGE_WIDTHS = (240, 360, 480, 720, 1080, 1440)
# The wall cell, the library page cell and the detail page hero are one shape:
# 3:2, which is what the majority of the catalogue's covers already are. A 16:9
# cover in a 3:2 frame can either leave bars down both sides or have its edges
# trimmed; the bars shrink the artwork and make every neighbouring tile look
# misplaced, so the proxy trims instead - centred, equally off both sides, which
# keeps the picture's own proportions and its subject. Portrait artwork is never
# trimmed, because a tall cover cut down to a wide cell loses most of itself.
COVER_ASPECT = 3 / 2
# Covers within two percent of 3:2 are left as they are: the leftover sliver is
# a couple of pixels wide and re-encoding them would only cost quality.
COVER_ASPECT_TOLERANCE = 0.02
# Bumping this tag re-cuts every cover and, because it rides in the client-facing
# URL, makes every device fetch the new shape instead of its cached copy.
COVER_RENDER_TAG = "3x2"
# The width every wall card (movie, drama, library page) ends up asking for on a
# 3x phone: prewarming a different size filled cache entries nothing ever read.
# Snapping a 178dp card on a 3x screen lands on 720, and the access log of the
# old 480px warm-up showed exactly that: a few hundred entries read, a few dozen
# ever used. Both sizes are still derived from the same cached original, so a
# 2x phone pays a local resize instead of a second download.
MOVIE_WALL_IMAGE_WIDTH = 720
# A cover that failed upstream is retried on every scroll otherwise, and the
# upstream hosts take twelve seconds to admit they are down. Remembering the
# failure for a short while turns a scrolling stall into an instant placeholder.
MOVIE_IMAGE_FAILURE_TTL_SECONDS = 10 * 60
# The same failure is also written to the library database, where "cover first"
# reads it to push titles whose artwork will never render below the ones that do
# render. That decision has to hold for as long as the picture stays dead, which
# is far longer than the in-process memo needs to live, and it is cleared the
# moment the host serves the file again - so a cover that comes back is never
# hidden behind a stale verdict.
MOVIE_IMAGE_FAILURE_RANK_SECONDS = 6 * 60 * 60
# A library page shows a grid, so it warms two screens' worth of cards.
MOVIE_LIBRARY_PREWARM_LIMIT = 12
# Every section of the wall is warmed, but a wall with twenty libraries must not
# start a hundred downloads on a phone's first frame.
MOVIE_WALL_PREWARM_MAX_ROWS = 48
# The shared catalogue is the only metadata writer this app still runs, so a row
# stamped with anything else came from a scraper that has since been deleted.
ACTIVE_METADATA_PROVIDER = "shared"
movie_image_cache_dir = Path(settings.database_path).resolve().parent / "movie-images"
movie_image_cache_locks: dict[str, asyncio.Lock] = {}
movie_image_cache_guard = threading.RLock()
# cache_key -> time.monotonic() deadline. Only ever holds covers that just proved
# unreachable, so it stays a few hundred entries and empties itself.
movie_image_failure_cache: dict[str, float] = {}
movie_image_failure_guard = threading.RLock()
# The upstream URLs behind those keys, mirrored from the database so the
# success path can drop a ledger row without asking the database whether there
# is one on every single cover it serves.
movie_image_bad_urls: set[str] = set()
# One client for the whole process: the folded catalogue is the expensive part
# of a scrape, and building it per run made every rescan pay for the same walk.
# The mirror is a SQLite file next to the library database, so the catalogue
# costs the container a bounded page cache instead of one dict entry per key.
shared_metadata_index_path = (
    Path(settings.database_path).resolve().parent / INDEX_DB_FILENAME
)
shared_metadata_client: SharedMetadataClient | None = None

# 91crdj publishes one detail page per series; its artwork lives on a picture
# CDN as AES-CBC blobs that the site decrypts in a browser worker. The series
# folder already carries the site's id token, so one lookup per series is the
# whole scrape.
DRAMA_SITE_BASE = "https://91crdj.com"
DRAMA_USER_AGENT = "deepfuck-drama-library/1"


def spawn_background(coroutine: Any, *, name: str) -> None:
    task = asyncio.create_task(coroutine, name=name)
    background_tasks.add(task)
    task.add_done_callback(background_tasks.discard)


def refresh_scan_summary() -> None:
    sources = scan_state["sources"]
    scan_state.update(
        {
            "running": any(state["running"] for state in sources.values()),
            "lastSuccess": max(
                (
                    state["lastSuccess"]
                    for state in sources.values()
                    if state["lastSuccess"] is not None
                ),
                default=None,
            ),
            "lastError": "; ".join(
                f"{source}: {state['lastError']}"
                for source, state in sources.items()
                if state["lastError"]
            )
            or None,
            "directories": sum(state["directories"] for state in sources.values()),
        }
    )


def empty_source_scan_state() -> dict[str, Any]:
    return {
        "running": False,
        "lastSuccess": None,
        "lastError": None,
        "directories": 0,
        "status": None,
        "resumed": False,
        "directoriesTotal": 0,
        "directoriesPending": 0,
        "directoriesFailed": 0,
        "directoriesSkipped": 0,
        "filesDiscovered": 0,
        "pendingErrors": [],
    }


def apply_scan_progress(state: dict[str, Any], progress: dict[str, Any]) -> None:
    state["directories"] = int(progress.get("directories_completed") or 0)
    state["directoriesTotal"] = int(progress.get("directories_total") or 0)
    state["directoriesPending"] = int(progress.get("directories_pending") or 0)
    state["directoriesFailed"] = int(progress.get("directories_failed") or 0)
    state["directoriesSkipped"] = int(progress.get("directories_exhausted") or 0)
    state["filesDiscovered"] = int(progress.get("files_discovered") or 0)
    errors = progress.get("errors")
    if errors:
        state["pendingErrors"] = [str(error) for error in errors][:5]


def refresh_source_states() -> None:
    current_ids: set[str] = set()
    jobs = database.source_scan_states()
    for source in database.list_media_sources():
        source_id = str(source["id"])
        current_ids.add(source_id)
        lock = scan_locks.setdefault(source_id, asyncio.Lock())
        # ``scan_source`` holds a reference to this very dict for the whole run
        # (progress callbacks write into it), so the entry must be updated in
        # place. Replacing it here orphaned the scanner's object and left the
        # published state saying "scanning" forever after a single refresh.
        state = scan_state["sources"].setdefault(
            source_id, empty_source_scan_state()
        )
        # The lock is the only authority on whether a scan is really in flight.
        # A flag remembered from a previous state snapshot could outlive the
        # coroutine that set it, which made the management screen show a scan
        # that had already finished and silently refused to start a new one.
        state["running"] = lock.locked()
        job = jobs.get(source_id) or {}
        error = job.get("error") or source.get("last_scan_error")
        if not state["running"]:
            # A live scan owns its own progress and error fields; only an idle
            # source is described by the persisted job.
            state["lastSuccess"] = source.get("last_scan_success")
            state["status"] = job.get("status")
            state["lastError"] = error
            state["directories"] = int(source.get("directories") or 0)
            if error:
                state["pendingErrors"] = [str(error)]
            if job:
                apply_scan_progress(
                    state,
                    database.scan_step_progress(
                        job_id=str(job["id"]),
                        source=source_id,
                        marker=str(job["marker"]),
                    ),
                )
    for source_id in set(scan_state["sources"]) - current_ids:
        scan_state["sources"].pop(source_id, None)
        scan_locks.pop(source_id, None)
    refresh_scan_summary()


def _scan_job_matches(job: dict[str, Any], config: dict[str, Any]) -> bool:
    # The job carries its roots as JSON so a half-finished traversal can be
    # told apart from one the user has since redefined. Legacy rows hold a
    # single path and normalise into the same one-item list.
    return (
        str(job["base_url"]) == str(config["base_url"])
        and normalize_root_paths(job["root_path"]) == list(config["root_paths"])
        and str(job["scan_mode"]) == str(config["scan_mode"])
    )


async def scan_source(source: str) -> bool:
    try:
        runtime = source_registry.get(source)
    except KeyError:
        return False
    lock = scan_locks.setdefault(source, asyncio.Lock())
    if lock.locked():
        return False
    async with lock:
        config = runtime.config
        source_state = scan_state["sources"].setdefault(
            source, empty_source_scan_state()
        )
        source_state["running"] = True
        source_state["pendingErrors"] = []
        refresh_scan_summary()
        job: dict[str, Any] | None = None
        try:
            roots = list(config["root_paths"])
            job = await asyncio.to_thread(
                database.latest_incomplete_scan_job, source=source
            )
            if job and not _scan_job_matches(job, config):
                # The source definition itself changed, so a half-finished
                # traversal no longer describes the intended library.
                await asyncio.to_thread(
                    database.update_scan_job_status, str(job["id"]), "superseded"
                )
                job = None
            resumed = job is not None
            if job is None:
                job = await asyncio.to_thread(
                    database.create_scan_job,
                    source=source,
                    root_path=json.dumps(roots),
                    base_url=str(config["base_url"]),
                    scan_mode=str(config["scan_mode"]),
                    section=str(config["section"]),
                )
                await asyncio.to_thread(
                    database.seed_scan_directories,
                    str(job["id"]),
                    initial_steps(
                        str(config["scan_mode"]),
                        roots,
                        search_paths=settings.asmr_search_paths
                        if source == "asmr"
                        else (),
                    ),
                )
            else:
                await asyncio.to_thread(database.resume_scan_job, str(job["id"]))
            job_id = str(job["id"])
            marker = str(job["marker"])
            await asyncio.to_thread(
                database.update_scan_job_status,
                job_id,
                "running",
                count_attempt=not resumed,
            )
            source_state["resumed"] = resumed
            source_state["status"] = "running"
            scanner = ResumableScanner(
                database=database,
                client=runtime.client,
                job_id=job_id,
                source=source,
                marker=marker,
                author_group_paths=settings.asmr_author_group_paths
                if source == "asmr"
                else frozenset(),
                search_result_limit=settings.asmr_search_result_limit,
                on_progress=lambda progress: apply_scan_progress(source_state, progress),
            )
            progress = await scanner.run()
            apply_scan_progress(source_state, progress)
            errors = [str(error) for error in progress.get("errors") or []]
            # Only failures we still intend to retry block the run. Directories
            # that already exhausted their retry budget are skipped instead, so a
            # single unreadable path cannot keep a whole library unfinished.
            blocked = int(progress.get("directories_pending") or 0) + int(
                progress.get("directories_retryable") or 0
            )
            if blocked:
                message = "; ".join(errors[:3]) or "scan incomplete"
                await asyncio.to_thread(
                    database.update_scan_job_status,
                    job_id,
                    "interrupted",
                    error=message[:400],
                    count_attempt=False,
                )
                await asyncio.to_thread(
                    database.update_source_scan_state,
                    source,
                    last_success=source_state["lastSuccess"],
                    last_error=message[:400],
                    directories=source_state["directories"],
                )
                source_state["status"] = "interrupted"
                source_state["lastError"] = (
                    f"{blocked} 个目录读取失败，再次扫描即可续扫"
                )
                logger.warning(
                    "%s scan paused with %s unfinished directories",
                    source,
                    blocked,
                )
                return False
            skipped_paths = await asyncio.to_thread(
                database.exhausted_scan_step_paths, job_id
            )
            if skipped_paths:
                carried = await asyncio.to_thread(
                    database.carry_over_scan_paths,
                    source=source,
                    marker=marker,
                    paths=skipped_paths,
                )
                logger.warning(
                    "%s scan skipping %s unreadable directories (kept %s media active)",
                    source,
                    len(skipped_paths),
                    carried,
                )
            retired = await asyncio.to_thread(
                database.finalize_scan, source=source, marker=marker
            )
            await asyncio.to_thread(
                database.update_scan_job_status,
                job_id,
                "completed",
                error=(
                    f"{len(skipped_paths)} 个目录多次读取失败，已跳过"
                    if skipped_paths
                    else None
                ),
                count_attempt=False,
            )
            if config["section"] == "movie":
                await asyncio.to_thread(
                    database.sync_movie_index, source, parse_movie_filename
                )
            if config["section"] == "drama":
                # Episodes are already in ``videos``; grouping them into series
                # is the last step of the scan, so the drama wall is never a
                # separate manual step the user has to remember.
                series = await asyncio.to_thread(
                    database.sync_drama_index,
                    source,
                    list(config["root_paths"]),
                    build_series,
                )
                logger.info("Grouped %s series for %s", series, source)
            active = await asyncio.to_thread(database.active_media_count, source=source)
            now = int(time.time())
            skipped_warning = (
                f"{len(skipped_paths)} 个目录多次读取失败，已跳过"
                if skipped_paths
                else None
            )
            source_state.update(
                {
                    "status": "completed",
                    "lastSuccess": now,
                    "lastError": skipped_warning,
                    "resumed": False,
                    "pendingErrors": [skipped_warning] if skipped_warning else [],
                }
            )
            await asyncio.to_thread(
                database.update_source_scan_state,
                source,
                last_success=now,
                last_error=skipped_warning,
                directories=source_state["directories"],
            )
            runtime.direct_urls.clear()
            logger.info(
                "Indexed %s active %s media items across %s directories (%s retired)",
                active,
                source,
                source_state["directories"],
                retired,
            )
            if config["section"] == "feed" and not metadata_lock.locked():
                spawn_background(
                    check_media_metadata(force=False),
                    name="media-metadata-check",
                )
            if config["section"] == "movie":
                # Assembling covers from the shared catalogue is an index lookup
                # rather than a scrape, so a finished scan can always finish the
                # job: a refresh here is what closes the "I changed the source"
                # loop without a second manual button. The refresh a user runs
                # is also the moment to stop trusting the cached catalogue: the
                # covers may have landed on the other side since the last fetch.
                start_movie_metadata_job(source, refresh_index=True)
            if config["section"] == "drama":
                # Each series needs exactly one 91crdj lookup and the pass skips
                # anything already resolved, so this stays cheap on re-scans.
                start_drama_metadata_job(source)
            return True
        except Exception as exc:
            if job:
                await asyncio.to_thread(
                    database.update_scan_job_status,
                    str(job["id"]),
                    "interrupted",
                    error=str(exc)[:400],
                    count_attempt=False,
                )
            source_state["status"] = "interrupted"
            source_state["lastError"] = str(exc)
            await asyncio.to_thread(
                database.update_source_scan_state,
                source,
                last_success=source_state["lastSuccess"],
                last_error=str(exc)[:400],
                directories=source_state["directories"],
            )
            logger.exception("%s library scan failed", source)
            return False
        finally:
            source_state["running"] = False
            refresh_scan_summary()

async def scan_library(sources: tuple[str, ...] | None = None) -> None:
    for source in sources if sources is not None else source_registry.ids():
        await scan_source(source)


async def check_fast_start(*, force: bool) -> None:
    if fast_start_lock.locked():
        return
    async with fast_start_lock:
        fast_start_state.update(
            {"running": True, "checked": 0, "total": 0, "lastError": None}
        )
        try:
            feed_sources = source_registry.ids("feed")
            videos = await asyncio.to_thread(
                database.fast_start_candidates,
                force=force,
                sources=feed_sources,
            )
            fast_start_state["total"] = len(videos)
            semaphore = asyncio.Semaphore(3)

            async def inspect(video: dict[str, Any]) -> None:
                status = "error"
                detail = "check failed"
                try:
                    runtime = source_registry.get(video["source"])
                    async with semaphore:
                        prefix = await runtime.client.read_prefix(
                            video["path"],
                            max_bytes=256 * 1024,
                        )
                    status = inspect_mp4_prefix(prefix)
                    detail = {
                        "optimized": "moov atom precedes mdat",
                        "not_optimized": "mdat atom precedes moov",
                        "inconclusive": "moov/mdat order not visible in first 256 KiB",
                    }[status]
                except Exception as exc:
                    detail = str(exc)
                    logger.warning("Fast Start check failed for %s: %s", video["path"], exc)
                await asyncio.to_thread(
                    database.update_fast_start, video["id"], status, detail
                )
                fast_start_state["checked"] += 1

            await asyncio.gather(*(inspect(video) for video in videos))
            fast_start_state["lastSuccess"] = int(time.time())
            logger.info("Checked Fast Start for %s videos", len(videos))
        except Exception as exc:
            fast_start_state["lastError"] = str(exc)
            logger.exception("Fast Start check failed")
        finally:
            fast_start_state["running"] = False


async def check_media_metadata(*, force: bool) -> None:
    if metadata_lock.locked():
        return
    async with metadata_lock:
        metadata_state.update(
            {"running": True, "checked": 0, "total": 0, "lastError": None}
        )
        try:
            feed_sources = source_registry.ids("feed")
            videos = await asyncio.to_thread(
                database.duration_candidates,
                force=force,
                limit=settings.metadata_probe_batch_size,
                sources=feed_sources,
            )
            semaphore = asyncio.Semaphore(6)

            async def inspect(video: dict[str, Any]) -> None:
                duration: float | None = None
                detail = "duration not found in MP4 ranges"
                try:
                    runtime = source_registry.get(video["source"])
                    async with semaphore:
                        raw_url, requires_headers = await runtime.direct_urls.get(
                            video["id"], video["path"]
                        )
                        if requires_headers:
                            raise AListError("This media source requires proxy headers")
                        prefix_size = 256 * 1024
                        prefix = await runtime.client.read_url_range(
                            raw_url,
                            range_header=f"bytes=0-{prefix_size - 1}",
                            max_bytes=prefix_size,
                        )
                        duration = mp4_duration_seconds(prefix)
                        if duration is None and int(video.get("size") or 0) > prefix_size:
                            suffix_size = min(1024 * 1024, int(video["size"]))
                            suffix = await runtime.client.read_url_range(
                                raw_url,
                                range_header=f"bytes=-{suffix_size}",
                                max_bytes=suffix_size,
                            )
                            duration = mp4_duration_seconds(prefix, suffix)
                    if duration is not None:
                        detail = "MP4 mvhd range probe"
                except Exception as exc:
                    detail = str(exc)
                    logger.warning("Duration check failed for %s: %s", video["path"], exc)
                await asyncio.to_thread(
                    database.update_media_metadata,
                    video["id"],
                    duration_seconds=duration,
                    detail=detail,
                )
                metadata_state["checked"] += 1

            batch_count = 0
            while videos and batch_count < 200:
                batch_count += 1
                metadata_state["total"] += len(videos)
                await asyncio.gather(*(inspect(video) for video in videos))
                videos = await asyncio.to_thread(
                    database.duration_candidates,
                    force=force,
                    limit=settings.metadata_probe_batch_size,
                    sources=feed_sources,
                )
            metadata_state["lastSuccess"] = int(time.time())
            logger.info("Checked media duration for %s videos", metadata_state["checked"])
        except Exception as exc:
            metadata_state["lastError"] = str(exc)
            logger.exception("Media metadata check failed")
        finally:
            metadata_state["running"] = False


async def _mark_movie_unmatched(
    movie: dict[str, Any],
    *,
    fallback_title: str = "",
    authoritative: bool = False,
) -> None:
    """Record a miss without discarding metadata an earlier run already found.

    A forced pass re-reads the whole shared catalogue, so it is authoritative:
    a row that no longer matches has to lose the cover an earlier (buggy) run
    handed it, because the stored title and artwork would otherwise stay wrong
    forever. Rows curated outside the shared catalogue are never touched.

    The shared catalogue is the only metadata writer this app still runs, so a
    row stamped with any other provider was filled in by a scraper that no
    longer exists (``tmdb``). Its artwork is not somebody else's curation, and a
    pass that cannot re-match it has to drop what the retired scraper stored:
    keeping it is exactly how a stale provider's cover survives a re-index.
    """
    provider = str(movie.get("metadata_provider") or "").strip()
    retired = bool(provider) and provider != ACTIVE_METADATA_PROVIDER
    if not authoritative and not retired:
        if provider or str(movie.get("poster_url") or "").strip():
            return
        await asyncio.to_thread(
            database.update_movie_metadata,
            int(movie["id"]),
            match_status="unmatched",
            match_confidence=0.0,
        )
        return
    title = fallback_title or str(movie.get("display_title") or "")
    await asyncio.to_thread(
        database.update_movie_metadata,
        int(movie["id"]),
        display_title=title,
        normalized_title=title.casefold(),
        original_title=None,
        overview=None,
        poster_url=None,
        backdrop_url=None,
        performers="[]",
        studio=None,
        metadata_provider=None,
        match_status="unmatched",
        match_confidence=0.0,
    )


def drama_slug_candidates(category: Any) -> list[str]:
    """Category slugs to try for one series, best guess first."""
    slugs: list[str] = []
    mapped = CATEGORY_SLUGS.get(str(category or "").strip())
    if mapped:
        slugs.append(mapped)
    for slug in CATEGORY_SLUGS.values():
        if slug not in slugs:
            slugs.append(slug)
    return slugs


async def _fetch_drama_page(
    client: httpx.AsyncClient,
    code: str,
    category: Any,
) -> tuple[str, str] | None:
    """The series detail page, or ``None`` when the site does not know it."""
    site_id = str(code or "").strip()
    if site_id.startswith("91crdj-"):
        site_id = site_id.split("-", 1)[1]
    if not site_id.isdigit():
        return None
    for slug in drama_slug_candidates(category):
        url = f"{DRAMA_SITE_BASE}/{slug}/{site_id}/"
        try:
            response = await client.get(url)
        except httpx.HTTPError as exc:
            logger.info("Drama metadata lookup failed for %s: %s", url, exc)
            continue
        if response.status_code == 200 and response.text:
            return response.text, str(response.url)
    return None


def start_drama_metadata_job(source: str, *, force: bool = False) -> bool:
    if drama_metadata_lock.locked() or drama_metadata_state["running"]:
        _queue_drama_metadata(source, force=force)
        return False
    drama_metadata_state.update(
        {
            "running": True,
            "source": source,
            "checked": 0,
            "total": 0,
            "matched": 0,
            "lastError": None,
        }
    )
    spawn_background(
        scrape_drama_metadata(source, force=force),
        name=f"drama-metadata-{source}",
    )
    return True


def _queue_drama_metadata(source: str, *, force: bool) -> None:
    """Hold a cover pass that could not start yet instead of discarding it."""
    if source in drama_metadata_queued:
        return
    drama_metadata_queued.add(source)
    drama_metadata_queue.append((source, force))
    logger.info(
        "Drama cover pass for %s queued behind %s (%s waiting)",
        source,
        drama_metadata_state["source"],
        len(drama_metadata_queue),
    )


def _drain_drama_metadata_queue() -> None:
    while drama_metadata_queue:
        source, force = drama_metadata_queue.pop(0)
        drama_metadata_queued.discard(source)
        if source not in source_registry.ids("drama"):
            continue
        start_drama_metadata_job(source, force=force)
        return


async def scrape_drama_metadata(source: str, *, force: bool = False) -> None:
    """One 91crdj lookup per series: real title, synopsis, tags and cover."""
    if drama_metadata_lock.locked():
        return
    try:
        async with drama_metadata_lock:
            drama_metadata_state.update(
                {
                    "running": True,
                    "source": source,
                    "checked": 0,
                    "total": 0,
                    "matched": 0,
                    "lastError": None,
                }
            )
            try:
                dramas = await asyncio.to_thread(
                    database.drama_metadata_candidates,
                    sources=(source,),
                    force=force,
                )
                drama_metadata_state["total"] = len(dramas)
                semaphore = asyncio.Semaphore(4)
                client = httpx.AsyncClient(
                    follow_redirects=True,
                    timeout=httpx.Timeout(15.0, connect=5.0),
                    headers={"User-Agent": DRAMA_USER_AGENT},
                    limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
                )

                async def enrich(drama: dict[str, Any]) -> None:
                    async with semaphore:
                        lookup = await _fetch_drama_page(
                            client,
                            str(drama.get("code") or ""),
                            drama.get("category"),
                        )
                        if lookup is None:
                            await asyncio.to_thread(
                                database.update_drama_metadata,
                                str(drama["id"]),
                                metadata_status="unmatched",
                            )
                            drama_metadata_state["checked"] += 1
                            return
                        page, page_url = lookup
                        metadata = parse_series_metadata(
                            str(drama.get("code") or ""), page, page_url
                        )
                        values: dict[str, Any] = {
                            "metadata_status": "matched"
                            if metadata.poster_url
                            else "unmatched"
                        }
                        # ``title`` is NOT NULL, so only ever send a real string.
                        if metadata.title:
                            values["title"] = metadata.title
                        if metadata.overview:
                            values["overview"] = metadata.overview
                        if metadata.tags:
                            values["tags"] = json.dumps(
                                metadata.tags, ensure_ascii=False
                            )
                        if metadata.poster_url:
                            values["poster_url"] = metadata.poster_url
                        await asyncio.to_thread(
                            database.update_drama_metadata, str(drama["id"]), **values
                        )
                        if metadata.poster_url:
                            drama_metadata_state["matched"] += 1
                        drama_metadata_state["checked"] += 1

                try:
                    await asyncio.gather(*(enrich(drama) for drama in dramas))
                finally:
                    await client.aclose()
                drama_metadata_state["lastSuccess"] = int(time.time())
                logger.info(
                    "Drama metadata resolved %s/%s series for %s",
                    drama_metadata_state["matched"],
                    drama_metadata_state["total"],
                    source,
                )
            except Exception as exc:
                drama_metadata_state["lastError"] = str(exc)
                logger.exception("Drama metadata pass failed for %s", source)
            finally:
                drama_metadata_state["running"] = False
    finally:
        # Released outside the lock, exactly like the movie queue: draining
        # while the pass still holds it would just re-queue the same library.
        _drain_drama_metadata_queue()


def start_movie_metadata_job(
    source: str,
    *,
    force: bool = False,
    refresh_index: bool = False,
) -> bool:
    """Kick off a metadata pass and report whether this call started it.

    The pass is single-flight because it walks one catalogue and writes rows as
    it goes, but a request that arrives while another one holds the lock is
    queued rather than dropped. Dropping it was silent and permanent: a user who
    scanned eight libraries in a row got covers for the first one and rows
    stamped ``pending`` for the rest, with nothing left to retry them.
    """
    if movie_metadata_lock.locked() or movie_metadata_state["running"]:
        _queue_movie_metadata(source, force=force, refresh_index=refresh_index)
        return False
    movie_metadata_state.update(
        {
            "running": True,
            "source": source,
            "checked": 0,
            "total": 0,
            "matched": 0,
            "lastError": None,
        }
    )
    spawn_background(
        scrape_movie_metadata(source, force=force, refresh_index=refresh_index),
        name=f"movie-metadata-{source}",
    )
    return True


def _queue_movie_metadata(source: str, *, force: bool, refresh_index: bool) -> None:
    """Remember a pass that could not start yet, merging repeat requests.

    Repeat requests for the same library collapse into one, but the flags are
    folded with OR: a manual ``force`` or ``refresh_index`` request that lands
    while the library is already waiting still has to run with those flags.
    """
    if source in movie_metadata_queued:
        for index, (queued_source, queued_force, queued_refresh) in enumerate(
            movie_metadata_queue
        ):
            if queued_source == source:
                movie_metadata_queue[index] = (
                    source,
                    queued_force or force,
                    queued_refresh or refresh_index,
                )
                break
        return
    movie_metadata_queued.add(source)
    movie_metadata_queue.append((source, force, refresh_index))
    logger.info(
        "Movie cover pass for %s queued behind %s (%s waiting)",
        source,
        movie_metadata_state["source"],
        len(movie_metadata_queue),
    )


def _drain_movie_metadata_queue() -> None:
    """Start the next queued cover pass once the running one has finished."""
    while movie_metadata_queue:
        source, force, refresh_index = movie_metadata_queue.pop(0)
        movie_metadata_queued.discard(source)
        # A library can be deleted while its pass waits in line.
        if source not in source_registry.ids("movie"):
            continue
        start_movie_metadata_job(
            source,
            force=force,
            refresh_index=refresh_index,
        )
        return


async def scrape_movie_metadata(
    source: str,
    *,
    force: bool = False,
    refresh_index: bool = False,
) -> None:
    if movie_metadata_lock.locked():
        return
    try:
        async with movie_metadata_lock:
            movie_metadata_state.update(
                {
                    "running": True,
                    "source": source,
                    "checked": 0,
                    "total": 0,
                    "matched": 0,
                    "lastError": None,
                }
            )
            try:
                has_shared_metadata = bool(settings.shared_metadata_base_url)
                if not has_shared_metadata:
                    raise RuntimeError("共享元数据接口未配置")
                source_ids = (source,)
                movies = await asyncio.to_thread(
                    database.movie_metadata_candidates,
                    sources=source_ids,
                    force=force,
                )
                movie_metadata_state["total"] = len(movies)
                async with shared_metadata_service().borrow() as shared_metadata:
                    # Titles the catalogue knows but has no cover for are queued
                    # again by ``movie_metadata_candidates``, so the covers have to
                    # come from the current catalogue rather than the 10-minute-old
                    # copy: warming first is what turns "I uploaded covers" into a
                    # visible change on the very next refresh.
                    await shared_metadata.warm_index(refresh=refresh_index)
                    for movie in movies:
                        parsed_name = parse_movie_filename(str(movie.get("name") or ""))
                        # The shared service already holds the sidecar covers and NFO
                        # metadata produced for the Emby stack, so it is both faster
                        # and more complete than re-scraping the same title here.
                        shared_match = await shared_metadata.lookup(
                            str(movie.get("name") or ""),
                            str(movie.get("media_path") or ""),
                        )
                        if shared_match is None:
                            # The shared catalogue is the only artwork source: a title
                            # it does not know stays unmatched instead of being guessed
                            # from a coincidental title match somewhere else.
                            await _mark_movie_unmatched(
                                movie,
                                fallback_title=parsed_name.display_title,
                                authoritative=force,
                            )
                            movie_metadata_state["checked"] += 1
                            continue
                        # The shared catalogue is the only cover source for the rows
                        # it curates, so a match that arrives without artwork has to
                        # clear whatever the row held: that value may belong to the
                        # very release the matcher has just ruled out, and keeping it
                        # is what re-surfaced one poster across a whole flat folder.
                        await asyncio.to_thread(
                            database.update_movie_metadata,
                            int(movie["id"]),
                            display_title=shared_match.title or movie["display_title"],
                            original_title=shared_match.original_title or None,
                            year=shared_match.year or movie.get("year"),
                            overview=shared_match.overview or None,
                            poster_url=shared_match.poster_url or None,
                            backdrop_url=shared_match.backdrop_url or None,
                            tmdb_id=None,
                            metadata_provider=ACTIVE_METADATA_PROVIDER,
                            release_date=str(shared_match.year) if shared_match.year else None,
                            genres="[]",
                            performers=json.dumps(shared_match.performers, ensure_ascii=False),
                            studio=shared_match.studio or None,
                            match_status="matched",
                            match_confidence=shared_match.confidence,
                        )
                        movie_metadata_state["matched"] += 1
                        movie_metadata_state["checked"] += 1
                movie_metadata_state["lastSuccess"] = int(time.time())
            except Exception as exc:
                movie_metadata_state["lastError"] = str(exc)
                logger.exception("Movie metadata scrape failed for %s", source)
            finally:
                movie_metadata_state["running"] = False
    finally:
        # A scan that finished while this pass held the lock left its request in
        # the queue, and the lock is only free once the ``async with`` above has
        # exited: draining any earlier would queue the same library again.
        _drain_movie_metadata_queue()


@asynccontextmanager
async def lifespan(_: FastAPI):
    global movie_image_client, shared_metadata_client
    await asyncio.to_thread(database.initialize)
    # The ledger of dead artwork outlives the process; carrying it in means a
    # restart does not have to re-discover every broken cover before the wall
    # can rank them last again.
    _prime_movie_image_failures(
        await asyncio.to_thread(database.movie_image_failed_urls)
    )
    await source_registry.initialize()
    orphaned = await asyncio.to_thread(database.reconcile_orphaned_scan_jobs)
    if orphaned:
        logger.warning(
            "Reopened %s scan job(s) left running by a previous process",
            len(orphaned),
        )
    await asyncio.to_thread(refresh_source_states)
    movie_image_client = httpx.AsyncClient(
        follow_redirects=True,
        timeout=httpx.Timeout(12.0, connect=5.0),
        limits=httpx.Limits(max_connections=16, max_keepalive_connections=8),
        headers={"User-Agent": MOVIE_IMAGE_USER_AGENT},
    )
    if settings.shared_metadata_base_url:
        # The catalogue takes tens of seconds to walk, and the disk mirror only
        # covers a restart inside the TTL. Warming it here means the first scan
        # a user triggers never waits for the download.
        spawn_background(
            _warm_shared_metadata_index(shared_metadata_service()),
            name="shared-metadata-index-warmup",
        )
        # A queue is in-memory, so a restart is exactly when a library can be
        # left with rows still stamped ``pending``: the pass that was going to
        # fix them died with the process. Resuming from the database is what
        # makes the queue survive a deploy instead of needing another scan.
        spawn_background(
            _resume_movie_metadata_passes(),
            name="movie-metadata-resume",
        )
    try:
        yield
    finally:
        for task in list(background_tasks):
            task.cancel()
        await asyncio.gather(*background_tasks, return_exceptions=True)
        image_client = movie_image_client
        movie_image_client = None
        if image_client is not None:
            await image_client.aclose()
        metadata_client = shared_metadata_client
        shared_metadata_client = None
        if metadata_client is not None:
            await metadata_client.close()
        await source_registry.close()


async def _warm_shared_metadata_index(client: SharedMetadataClient) -> None:
    started = time.monotonic()
    try:
        index = await client.index()
    except Exception as exc:
        logger.warning("Shared metadata index warm-up failed: %s", exc)
        return
    logger.info(
        "Shared metadata index ready: %s name keys and %s code keys in %.1fs",
        len(index.names),
        len(index.codes),
        time.monotonic() - started,
    )


async def _resume_movie_metadata_passes() -> None:
    """Re-queue the libraries a restart left with unresolved rows.

    The queue that carries a cover pass from one library to the next lives in
    memory, and so does the lock: a deploy in the middle of a batch left the
    remaining libraries stamped ``pending`` with nothing scheduled to look at
    them. Reading the database back is what closes that hole -- and it runs on
    every boot, so a library that was never matched is fixed without the user
    having to remember which one it was.
    """
    pending = await asyncio.to_thread(database.movie_metadata_pending_sources)
    # The registry is authoritative: a source that was deleted or disabled
    # while its rows sat unresolved must not bring a worker back to life.
    active = set(source_registry.ids("movie"))
    pending = [source for source in pending if source in active]
    if not pending:
        return
    logger.info(
        "Resuming cover passes for %s movie library(ies) left pending: %s",
        len(pending),
        ", ".join(pending),
    )
    for source in pending:
        start_movie_metadata_job(source)


def shared_metadata_service() -> SharedMetadataClient:
    """The process-wide read-only catalogue client.

    Every movie scan used to build its own client, and with it a fresh copy of
    the folded catalogue: ~28 pages over the wire and ~30 s before the first
    title could be matched. One client keeps the tables warm across scans and
    across media libraries.
    """
    global shared_metadata_client
    if shared_metadata_client is None:
        shared_metadata_client = SharedMetadataClient(
            base_url=settings.shared_metadata_base_url,
            token=settings.shared_metadata_token,
            cache_path=shared_metadata_index_path,
        )
    return shared_metadata_client


app = FastAPI(
    title="deepfuck private media player",
    version=APP_VERSION,
    docs_url=None,
    redoc_url=None,
    lifespan=lifespan,
)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    public_api_paths = {"/api/auth/login", "/api/auth/status"}
    requires_auth = request.url.path.startswith("/api/") and request.url.path not in public_api_paths
    if requires_auth and not session_manager.verify(request.cookies.get(SESSION_COOKIE)):
        response = JSONResponse(status_code=401, content={"detail": "Authentication required"})
    else:
        response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    response.headers["Strict-Transport-Security"] = "max-age=31536000"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; script-src 'self'; style-src 'self'; "
        "img-src 'self' data: https:; media-src 'self' blob: https:; "
        "connect-src 'self' https:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
    )
    if request.url.path.startswith("/api/") and "cache-control" not in response.headers:
        response.headers["Cache-Control"] = "no-store"
    return response


class LoginRequest(BaseModel):
    password: str = Field(min_length=1, max_length=256)


class MediaSourceRequest(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    provider: Literal["alist", "openlist"] = "alist"
    baseUrl: str = Field(min_length=8, max_length=500)
    # A library is often spread over several folders, so the management screen
    # sends a list. The single-path field stays accepted because older installs
    # of the app keep sending it, and one folder is just a one-item list.
    rootPath: str = Field(default="", max_length=500)
    rootPaths: list[str] = Field(default_factory=list)
    section: Literal["feed", "asmr", "movie", "drama"]
    scanMode: Literal["tree", "authors", "authors_recursive"] | None = None
    anonymous: bool = True
    token: str = Field(default="", max_length=2000)
    username: str = Field(default="", max_length=200)
    password: str = Field(default="", max_length=500)
    enabled: bool = True


class MediaSourceOrderRequest(BaseModel):
    """The whole library list in the order the user dragged it into.

    Sending every id, rather than a pair of neighbours, keeps the write
    idempotent: a dropped request can simply be retried with the same list.
    """

    ids: list[str] = Field(default_factory=list, max_length=500)


def normalize_source_payload(payload: MediaSourceRequest) -> dict[str, Any]:
    base_url = payload.baseUrl.strip().rstrip("/")
    parsed = urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username:
        raise HTTPException(status_code=422, detail="AList 地址必须是有效的 HTTP(S) 地址")
    submitted = [*payload.rootPaths, payload.rootPath]
    roots = normalize_root_paths(submitted, fallback="")
    if not any(str(candidate).strip() for candidate in submitted):
        raise HTTPException(status_code=422, detail="至少需要一个根目录")
    root_path = roots[0]
    tree_sections = {"feed", "movie", "drama"}
    scan_mode = payload.scanMode or (
        "tree" if payload.section in tree_sections else "authors_recursive"
    )
    if payload.section in tree_sections and scan_mode != "tree":
        raise HTTPException(status_code=422, detail="该板块来源必须使用递归目录扫描")
    if payload.section == "asmr" and scan_mode == "tree":
        raise HTTPException(status_code=422, detail="ASMR 来源必须使用作者目录扫描")
    return {
        "name": payload.name.strip(),
        "provider": payload.provider,
        "base_url": base_url,
        "root_path": root_path,
        "root_paths": roots,
        "section": payload.section,
        "scan_mode": scan_mode,
        "anonymous": payload.anonymous,
        "token": payload.token.strip(),
        "username": payload.username.strip(),
        "password": payload.password,
        "enabled": payload.enabled,
    }


def public_source(
    source: dict[str, Any],
    *,
    movie_metadata: dict[str, int | None] | None = None,
    drama_metadata: dict[str, int | None] | None = None,
) -> dict[str, Any]:
    state = scan_state["sources"].get(
        source["id"],
        {"running": False, "lastSuccess": None, "lastError": None, "directories": 0},
    )
    payload = {
        "id": source["id"],
        "name": source["name"],
        "provider": source["provider"],
        "baseUrl": source["base_url"],
        "rootPath": source["root_path"],
        "rootPaths": list(source["root_paths"]),
        "section": source["section"],
        "scanMode": source["scan_mode"],
        "anonymous": source["anonymous"],
        "usernameConfigured": bool(source["username"]),
        "tokenConfigured": bool(source["token"]),
        "enabled": source["enabled"],
        "videos": int(source.get("videos") or 0),
        "bytes": int(source.get("bytes") or 0),
        "scan": state,
    }
    if movie_metadata is not None:
        payload["movieMetadata"] = movie_metadata
    if drama_metadata is not None:
        payload["dramaMetadata"] = drama_metadata
    return payload


def _artwork_token(source_url: Any) -> str:
    """Short digest of the upstream artwork URL.

    The public image endpoints are addressed by movie id, so a re-index that
    swaps a cover leaves the client-facing URL untouched and every device keeps
    serving the old bytes for the whole image TTL. Folding the upstream URL into
    the query turns a new cover into a new URL, while rows whose artwork did not
    change keep the cached response they already have.

    The render tag is folded in for the same reason: it changes whenever the
    proxy changes the shape it hands back, so every device drops the covers it
    cached in the old shape instead of mixing two geometries on one wall.
    """
    seed = f"{COVER_RENDER_TAG}:{source_url or ''}"
    return hashlib.sha1(seed.encode("utf-8")).hexdigest()[:8]


MOVIE_SORT_FIELDS = ("cover", "title", "time")
MOVIE_SORT_DIRECTIONS = ("asc", "desc")


def movie_sort_token(field: str, direction: str = "") -> str:
    """Encode one library's order as the single string the client stores.

    ``cover`` carries no direction because "artwork first" has no meaningful
    reverse; the other two keep whichever arrow the user last tapped.
    """
    if field not in MOVIE_SORT_FIELDS:
        return "cover"
    if field == "cover":
        return "cover"
    if direction not in MOVIE_SORT_DIRECTIONS:
        # Preserve the historical defaults so an untouched library keeps
        # looking exactly the way it always did.
        return field
    return f"{field}:{direction}"


def movie_sort_parts(token: str) -> tuple[str, str] | None:
    """Split a stored sort token into the field and direction the query wants."""
    field, _, direction = str(token or "").partition(":")
    if field not in MOVIE_SORT_FIELDS:
        return None
    if field == "cover":
        return "cover", ""
    if direction in MOVIE_SORT_DIRECTIONS:
        return field, direction
    return field, ""


def _parse_source_sorts(raw: str) -> dict[str, str]:
    """Read the ``id:token`` list the wall sends for its per-library orders."""
    parsed: dict[str, str] = {}
    for chunk in str(raw or "").split(","):
        entry = chunk.strip()
        if not entry:
            continue
        # A token may itself contain a colon ("title:desc"), so only the first
        # one separates the library id from its order.
        source_id, separator, token = entry.partition(":")
        if not separator or not source_id:
            continue
        token = token.strip()
        if movie_sort_parts(token) is None:
            continue
        parsed[source_id.strip()] = token
    return parsed


def public_movie(row: dict[str, Any], *, detail: bool = False) -> dict[str, Any]:
    movie_id = int(row["id"])
    # One title, one picture. The wall, the library page and the detail page all
    # paint the same image, so they have to be handed the same URL: three
    # different strings for one upstream file meant three cache entries, and a
    # device that had drawn the wall cover would still open the detail page on a
    # blank frame while it fetched its own copy of the identical bytes.
    #
    # The fallback chain is the same one the poster endpoint resolves, so a row
    # whose only artwork is the AList thumbnail is served (and cached) under one
    # address rather than two.
    if str(row.get("poster_url") or "").strip():
        cover_url = f"/api/movies/{movie_id}/poster?v={_artwork_token(row.get('poster_url'))}"
    elif str(row.get("backdrop_url") or "").strip():
        cover_url = f"/api/movies/{movie_id}/poster?v={_artwork_token(row.get('backdrop_url'))}"
    elif str(row.get("thumb") or "").strip():
        # A plain AList redirect: it is already a stable, cheap URL and the
        # proxy cannot resize it, so it keeps its own address.
        cover_url = f"/api/videos/{row['video_id']}/poster"
    else:
        cover_url = None
    payload = {
        "id": movie_id,
        "videoId": int(row["video_id"]),
        "title": str(row.get("display_title") or Path(row["name"]).stem),
        "originalTitle": row.get("original_title"),
        "year": row.get("year"),
        "overview": row.get("overview") or "",
        # Keep third-party image URLs server-side.  Mobile clients frequently
        # cannot reach JavBus/DMM CDNs directly or are rejected by hotlink
        # protection, while the API host is already authenticated and reachable.
        "posterUrl": cover_url,
        "backdropUrl": cover_url,
        "wallUrl": cover_url,
        "rating": row.get("rating"),
        "runtimeMinutes": row.get("runtime_minutes"),
        "matchStatus": row.get("match_status") or "pending",
        "matchConfidence": row.get("match_confidence"),
        "playUrl": f"/api/videos/{row['video_id']}/play",
        # Search results are flat, so the client needs the library name to tell
        # two same-titled entries apart.
        "source": row.get("source"),
        "modified": row.get("modified"),
        "duration": row.get("duration_seconds"),
    }
    if detail:
        payload["path"] = row.get("path")
        payload["size"] = row.get("size")
        payload["format"] = row.get("media_format")
        payload["metadataProvider"] = row.get("metadata_provider")
        payload["releaseDate"] = row.get("release_date")
        payload["genres"] = _metadata_list(row.get("genres"))
        payload["performers"] = _metadata_list(row.get("performers"))
        payload["studio"] = row.get("studio")
    return payload


def public_drama(row: dict[str, Any]) -> dict[str, Any]:
    drama_id = str(row["id"])
    has_poster = bool(str(row.get("poster_url") or "").strip())
    return {
        "id": drama_id,
        "title": str(row.get("title") or row.get("folder_title") or ""),
        "category": row.get("category"),
        "code": row.get("code"),
        "overview": row.get("overview") or "",
        "tags": _metadata_list(row.get("tags")),
        "episodeCount": int(row.get("episode_count") or 0),
        # Third-party artwork stays server-side: the picture CDN is not
        # reachable from many phones, and the payload is encrypted anyway.
        "posterUrl": f"/api/dramas/{drama_id}/poster" if has_poster else None,
        "matchStatus": row.get("metadata_status") or "pending",
        "source": row.get("source"),
        "path": row.get("folder"),
    }


def public_drama_episode(video: dict[str, Any], position: int) -> dict[str, Any]:
    video_id = int(video["video_id"])
    return {
        "videoId": video_id,
        "position": position,
        "title": Path(str(video.get("name") or "")).stem,
        "duration": video.get("duration_seconds"),
        "modified": video.get("modified"),
        "playUrl": f"/api/videos/{video_id}/play",
    }


def _movie_image_headers(source_url: str) -> dict[str, str]:
    try:
        parsed = urlsplit(source_url)
        host = (parsed.hostname or "").lower()
    except ValueError:
        host = ""
    headers = {
        "Accept": "image/avif,image/webp,image/apng,image/jpeg,image/png,image/*;q=0.8",
        "User-Agent": MOVIE_IMAGE_USER_AGENT,
    }
    if host.endswith("javbus.com"):
        headers["Referer"] = "https://www.javbus.com/"
    elif host.endswith("dmm.co.jp") or host.endswith("dmm.com"):
        headers["Referer"] = "https://www.dmm.co.jp/"
    elif host and host == _shared_metadata_host():
        # Sidecar artwork lives behind the shared token gateway.
        if settings.shared_metadata_token:
            headers["X-Media-Shared-Token"] = settings.shared_metadata_token
    return headers


def _shared_metadata_host() -> str:
    try:
        return (urlsplit(settings.shared_metadata_base_url).hostname or "").lower()
    except ValueError:
        return ""


def _image_media_type(content_type: str, content: bytes) -> str | None:
    normalized = content_type.split(";", 1)[0].strip().lower()
    if normalized.startswith("image/"):
        return normalized
    # A few CDNs omit Content-Type or return application/octet-stream.  Accept
    # only well-known image signatures in that case; HTML/error pages remain
    # rejected instead of being cached as a poster.
    if content.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if content.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if content.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if content.startswith(b"RIFF") and content[8:12] == b"WEBP":
        return "image/webp"
    return None


def _requested_image_width(value: Any) -> int | None:
    """Snap a client-requested pixel width onto one of the cached sizes.

    The wall, the detail page and the phone's own density all ask for slightly
    different numbers. Snapping them onto a small ladder keeps the persistent
    cache dense - a thousand arbitrary widths would thrash it.
    """
    try:
        width = int(str(value).strip())
    except (TypeError, ValueError):
        return None
    if width <= 0:
        return None
    for candidate in MOVIE_IMAGE_WIDTHS:
        if width <= candidate:
            return candidate
    return MOVIE_IMAGE_WIDTHS[-1]


def _cover_crop_box(width: int, height: int) -> tuple[int, int, int, int] | None:
    """The box that trims a wide cover into the wall's 3:2 frame.

    ``None`` means "leave the artwork alone". That covers the two shapes the
    frame already agrees with: a cover that is 3:2 within a couple of percent,
    and a portrait cover. A tall poster forced into a wide cell would lose most
    of itself - the exact failure the wall used to have - so portrait artwork
    keeps its own proportions and simply sits inside the frame.

    Everything wider than 3:2 is trimmed evenly off both sides, which keeps the
    middle of the shot: for these covers that is where the subject is.
    """
    if width <= 0 or height <= 0:
        return None
    aspect = width / height
    if aspect <= COVER_ASPECT:
        return None
    if abs(aspect - COVER_ASPECT) / COVER_ASPECT <= COVER_ASPECT_TOLERANCE:
        return None
    trimmed_width = max(1, round(height * COVER_ASPECT))
    if trimmed_width >= width:
        return None
    left = (width - trimmed_width) // 2
    return left, 0, left + trimmed_width, height


def _encode_cover_webp(image: Any) -> bytes | None:
    """Re-encode one already-cut cover as WebP."""
    if image.mode not in ("RGB", "RGBA"):
        image = image.convert("RGB")
    buffer = io.BytesIO()
    image.save(buffer, format="WEBP", quality=82, method=4)
    return buffer.getvalue() or None


def _render_cover_bytes(content: bytes, width: int) -> bytes | None:
    """Cut a cover to the wall's 3:2 frame and downscale it to ``width``.

    Returns ``None`` when there is nothing to do - the artwork is already the
    right shape *and* already small enough - or when it cannot be decoded, in
    which case the caller serves the original bytes untouched. A cover that is
    small but the wrong shape is still re-cut: the wall's cell is one fixed
    shape, so a small 16:9 picture handed over whole would sit letterboxed next
    to its neighbours.
    """
    if Image is None:
        return None
    try:
        with Image.open(io.BytesIO(content)) as image:
            image.load()
            crop = _cover_crop_box(image.width, image.height)
            if crop is None and image.width <= width * 1.1:
                return None
            rendered = image.crop(crop) if crop else image
            if rendered.width > width * 1.1:
                height = max(1, round(rendered.height * width / rendered.width))
                rendered = rendered.resize((width, height), Image.Resampling.LANCZOS)
            return _encode_cover_webp(rendered)
    except Exception as exc:
        logger.info("Movie image render failed: %s", exc)
        return None


def _movie_image_cache_key(url: str) -> str:
    return hashlib.sha256(url.encode("utf-8")).hexdigest()


def _movie_image_request_key(url: str, width: int | None) -> str:
    """The identity of one cached cover: the upstream URL plus its target width.

    A scaled copy is a different object, so the wall asking for a 720px strip
    must not evict the full-size cover the detail page wants. Both the proxy and
    the warm-up funnel through here so the two can never disagree about where a
    given picture lives.

    The render tag is part of a scaled entry's identity and deliberately not
    part of the original's: the full-size copy is the upstream file, untouched
    and shared by every shape, while a scaled copy is one particular crop of it.
    """
    return f"{url}#w{width}#{COVER_RENDER_TAG}" if width else url


def _movie_image_failure_remaining(cache_key: str) -> float:
    """Seconds left on a recorded failure, ``0`` when there is nothing on file."""
    now = time.monotonic()
    with movie_image_failure_guard:
        expiry = movie_image_failure_cache.get(cache_key)
        if expiry is None:
            return 0.0
        if expiry <= now:
            movie_image_failure_cache.pop(cache_key, None)
            return 0.0
        return expiry - now


def _remember_movie_image_failure(cache_key: str, source_url: str | None = None) -> None:
    """Memoise one failed cover, in this process and in the library database.

    The in-process copy is what spares the wall a twelve second stall on the
    next scroll; the database copy is what lets "cover first" rank a title whose
    artwork is dead below the titles whose artwork actually arrives.
    """
    now = time.monotonic()
    with movie_image_failure_guard:
        if len(movie_image_failure_cache) > 4096:
            for key, expiry in list(movie_image_failure_cache.items()):
                if expiry <= now:
                    movie_image_failure_cache.pop(key, None)
            while len(movie_image_failure_cache) > 4096:
                soonest = min(movie_image_failure_cache, key=movie_image_failure_cache.__getitem__)
                movie_image_failure_cache.pop(soonest, None)
        movie_image_failure_cache[cache_key] = now + MOVIE_IMAGE_FAILURE_TTL_SECONDS
        url = str(source_url or "").strip()
        already_recorded = url in movie_image_bad_urls
        if url:
            movie_image_bad_urls.add(url)
    if url and not already_recorded:
        spawn_background(
            asyncio.to_thread(
                database.remember_movie_image_failure,
                url,
                ttl_seconds=MOVIE_IMAGE_FAILURE_RANK_SECONDS,
            ),
            name="movie-image-failure-record",
        )


def _forget_movie_image_failure(cache_key: str, source_url: str | None = None) -> None:
    """One cover arrived after all: forget the verdict everywhere."""
    with movie_image_failure_guard:
        movie_image_failure_cache.pop(cache_key, None)
        url = str(source_url or "").strip()
        forget_ledger = bool(url) and url in movie_image_bad_urls
        if url:
            movie_image_bad_urls.discard(url)
    if forget_ledger:
        spawn_background(
            asyncio.to_thread(database.forget_movie_image_failure, url),
            name="movie-image-failure-clear",
        )


def _prime_movie_image_failures(urls: set[str]) -> None:
    """Carry the ledger's verdicts over from the previous process."""
    with movie_image_failure_guard:
        movie_image_bad_urls.update(str(url).strip() for url in urls if str(url).strip())


def _movie_image_cache_paths(url: str) -> tuple[Path, Path]:
    key = _movie_image_cache_key(url)
    return movie_image_cache_dir / f"{key}.bin", movie_image_cache_dir / f"{key}.json"


def _read_movie_image_cache(url: str) -> tuple[bytes, str, str] | None:
    content_path, metadata_path = _movie_image_cache_paths(url)
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        content = content_path.read_bytes()
        media_type = str(metadata["mediaType"])
        etag = str(metadata["etag"])
        if not media_type.startswith("image/") or len(content) > MOVIE_IMAGE_MAX_BYTES:
            return None
    except (OSError, ValueError, KeyError, TypeError):
        return None
    try:
        os.utime(content_path, None)
        os.utime(metadata_path, None)
    except OSError:
        pass
    return content, media_type, etag


def _prune_movie_image_cache() -> None:
    try:
        movie_image_cache_dir.mkdir(parents=True, exist_ok=True)
        files = list(movie_image_cache_dir.glob("*.bin"))
        total = sum(path.stat().st_size for path in files if path.is_file())
        if total <= MOVIE_IMAGE_CACHE_MAX_BYTES:
            return
        for path in sorted(files, key=lambda item: item.stat().st_mtime):
            if total <= MOVIE_IMAGE_CACHE_MAX_BYTES:
                break
            try:
                size = path.stat().st_size
                path.unlink(missing_ok=True)
                path.with_suffix(".json").unlink(missing_ok=True)
                total -= size
            except OSError:
                continue
    except OSError:
        return


def _write_movie_image_cache(url: str, content: bytes, media_type: str, etag: str) -> None:
    content_path, metadata_path = _movie_image_cache_paths(url)
    content_tmp: Path | None = None
    metadata_tmp: Path | None = None
    try:
        movie_image_cache_dir.mkdir(parents=True, exist_ok=True)
        suffix = f".{os.getpid()}.{time.time_ns()}.tmp"
        content_tmp = content_path.with_name(content_path.name + suffix)
        metadata_tmp = metadata_path.with_name(metadata_path.name + suffix)
        content_tmp.write_bytes(content)
        metadata_tmp.write_text(
            json.dumps({"mediaType": media_type, "etag": etag}, separators=(",", ":")),
            encoding="utf-8",
        )
        os.replace(content_tmp, content_path)
        os.replace(metadata_tmp, metadata_path)
        _prune_movie_image_cache()
    except OSError as exc:
        logger.info("Movie image cache write failed for %s: %s", url, exc)
    finally:
        for temporary in (content_tmp, metadata_tmp):
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass


def _movie_image_response(
    content: bytes,
    media_type: str,
    etag: str,
    *,
    cache_status: str,
    if_none_match: str | None = None,
) -> Response:
    headers = {
        # Public on purpose: every image URL carries the ``?v=`` fingerprint of
        # the upstream artwork, so a shared cache (the Cloudflare edge in front
        # of this host) can hold the bytes without ever risking a stale cover.
        # Without it the edge refuses to store anything and every phone on the
        # planet re-downloads the same poster from the origin.
        "Cache-Control": f"public, max-age={MOVIE_IMAGE_CACHE_SECONDS}, immutable",
        "ETag": f'"{etag}"',
        "X-Content-Type-Options": "nosniff",
        "X-Movie-Image-Cache": cache_status,
    }
    if if_none_match:
        client_etags = {value.strip().removeprefix("W/").strip('"') for value in if_none_match.split(",")}
        if etag in client_etags:
            return Response(status_code=304, headers=headers)
    return Response(
        content=content,
        media_type=media_type,
        headers=headers,
    )


async def _proxy_movie_image(
    source_url: Any,
    if_none_match: str | None = None,
    width: int | None = None,
) -> Response:
    url = str(source_url or "").strip()
    try:
        parsed = urlsplit(url)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="Movie image not found") from exc
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise HTTPException(status_code=404, detail="Movie image not found")

    cache_key = _movie_image_request_key(url, width)
    with movie_image_cache_guard:
        lock = movie_image_cache_locks.setdefault(cache_key, asyncio.Lock())
    async with lock:
        cached = await asyncio.to_thread(_read_movie_image_cache, cache_key)
        if cached:
            content, media_type, etag = cached
            _forget_movie_image_failure(cache_key, url)
            return _movie_image_response(
                content,
                media_type,
                etag,
                cache_status="hit",
                if_none_match=if_none_match,
            )

        if _movie_image_failure_remaining(cache_key) > 0:
            # This exact cover failed a moment ago. Re-asking the artwork host
            # costs a twelve second stall per scroll for a picture that is not
            # coming back; the placeholder the client already drew is the honest
            # answer until the recorded window lapses.
            raise HTTPException(status_code=502, detail="Movie image upstream unavailable")

        if width:
            # Every width is cut from the full-size copy, which the first
            # request already paid for. Without this the detail page opened a
            # second stream to an artwork host that drops a fair share of them,
            # and the cover that the wall showed a moment earlier would not
            # load at all.
            original = await asyncio.to_thread(_read_movie_image_cache, url)
            if original:
                derived = await asyncio.to_thread(
                    _render_cover_bytes, original[0], width
                )
                if derived is not None:
                    derived_etag = hashlib.sha256(derived).hexdigest()
                    await asyncio.to_thread(
                        _write_movie_image_cache,
                        cache_key,
                        derived,
                        "image/webp",
                        derived_etag,
                    )
                    _forget_movie_image_failure(cache_key, url)
                    return _movie_image_response(
                        derived,
                        "image/webp",
                        derived_etag,
                        cache_status="derived",
                        if_none_match=if_none_match,
                    )

        client = movie_image_client
        owns_client = client is None
        # Kept so a scaled request can leave the untouched original behind for
        # every other width, instead of fetching the same picture twice.
        original_copy: tuple[bytes, str] | None = None
        if client is None:
            client = httpx.AsyncClient(
                follow_redirects=True,
                timeout=httpx.Timeout(12.0, connect=5.0),
                limits=httpx.Limits(max_connections=16, max_keepalive_connections=8),
            )
        try:
            try:
                async with client.stream(
                    "GET",
                    url,
                    headers=_movie_image_headers(url),
                ) as upstream:
                    if upstream.status_code == 404:
                        raise HTTPException(status_code=404, detail="Movie image not found")
                    if upstream.status_code >= 400:
                        raise HTTPException(status_code=502, detail="Movie image upstream failed")
                    content_length = upstream.headers.get("content-length")
                    try:
                        declared_size = int(content_length) if content_length else None
                    except ValueError:
                        declared_size = None
                    if declared_size is not None and declared_size > MOVIE_IMAGE_MAX_BYTES:
                        raise HTTPException(status_code=502, detail="Movie image is too large")

                    chunks: list[bytes] = []
                    total = 0
                    async for chunk in upstream.aiter_bytes(64 * 1024):
                        if not chunk:
                            continue
                        total += len(chunk)
                        if total > MOVIE_IMAGE_MAX_BYTES:
                            raise HTTPException(status_code=502, detail="Movie image is too large")
                        chunks.append(chunk)
                    content = b"".join(chunks)
                    if is_encrypted_image_url(url):
                        # The picture CDN serves AES-CBC blobs; the real JPEG
                        # only exists after the same decode the site runs in a
                        # browser worker. Without this the proxy would cache
                        # ciphertext and the wall would show broken images.
                        try:
                            content = await asyncio.to_thread(decrypt_media, content)
                        except Exception as exc:
                            # The reason matters: a missing AES backend and a
                            # corrupt payload look identical from the client.
                            logger.warning("Movie image decrypt failed for %s: %s", url, exc)
                            raise HTTPException(
                                status_code=502,
                                detail="Movie image upstream returned an undecodable payload",
                            ) from exc
                    media_type = _image_media_type(
                        upstream.headers.get("content-type", ""),
                        content,
                    )
                    if not media_type:
                        raise HTTPException(status_code=502, detail="Movie image upstream returned non-image data")
                    if width:
                        resized = await asyncio.to_thread(
                            _render_cover_bytes, content, width
                        )
                        if resized is not None:
                            original_copy = (content, media_type)
                            content = resized
                            media_type = "image/webp"
            except HTTPException as exc:
                # 404 means the artwork is gone and 502 is what every upstream
                # hiccup looks like; both are worth remembering, an invalid
                # request is not.
                if exc.status_code in {404, 502}:
                    _remember_movie_image_failure(cache_key, url)
                raise
            except httpx.HTTPError as exc:
                logger.info("Movie image proxy failed for %s: %s", url, exc)
                _remember_movie_image_failure(cache_key, url)
                raise HTTPException(status_code=502, detail="Movie image upstream unavailable") from exc
        finally:
            if owns_client:
                await client.aclose()

        etag = hashlib.sha256(content).hexdigest()
        await asyncio.to_thread(
            _write_movie_image_cache, cache_key, content, media_type, etag
        )
        _forget_movie_image_failure(cache_key, url)
        if original_copy is not None:
            await asyncio.to_thread(
                _write_movie_image_cache,
                url,
                original_copy[0],
                original_copy[1],
                hashlib.sha256(original_copy[0]).hexdigest(),
            )
        return _movie_image_response(
            content,
            media_type,
            etag,
            cache_status="miss",
            if_none_match=if_none_match,
        )


def _metadata_list(value: Any) -> list[str]:
    try:
        decoded = json.loads(str(value or "[]"))
    except (TypeError, ValueError):
        return []
    return [str(item).strip() for item in decoded if str(item).strip()] if isinstance(decoded, list) else []


def _client_key(request: Request) -> str:
    return request.client.host if request.client else "unknown"


@app.get("/api/auth/status")
async def auth_status(request: Request):
    authenticated = session_manager.verify(request.cookies.get(SESSION_COOKIE))
    response = JSONResponse({"authenticated": authenticated})
    if not authenticated and request.cookies.get(SESSION_COOKIE):
        response.delete_cookie(SESSION_COOKIE, path="/", secure=True, httponly=True, samesite="strict")
    return response


@app.post("/api/auth/login")
async def login(payload: LoginRequest, request: Request):
    client_key = _client_key(request)
    retry_after = login_limiter.retry_after(client_key)
    if retry_after:
        return JSONResponse(
            status_code=429,
            content={"detail": "Too many attempts"},
            headers={"Retry-After": str(retry_after)},
        )

    valid = await asyncio.to_thread(verify_password, settings.auth_password_hash, payload.password)
    if not valid:
        retry_after = login_limiter.record_failure(client_key)
        headers = {"Retry-After": str(retry_after)} if retry_after else None
        return JSONResponse(status_code=401, content={"detail": "Invalid password"}, headers=headers)

    login_limiter.record_success(client_key)
    response = JSONResponse({"authenticated": True, "expiresIn": session_manager.max_age_seconds})
    response.set_cookie(
        SESSION_COOKIE,
        session_manager.issue(),
        max_age=session_manager.max_age_seconds,
        path="/",
        secure=True,
        httponly=True,
        samesite="strict",
    )
    return response


@app.post("/api/auth/logout")
async def logout():
    response = JSONResponse({"authenticated": False})
    response.delete_cookie(SESSION_COOKIE, path="/", secure=True, httponly=True, samesite="strict")
    return response


@app.get("/api/health")
async def health() -> dict[str, Any]:
    stats = await asyncio.to_thread(database.stats)
    return {
        "status": "ready" if stats["videos"] else "initializing",
        "version": APP_VERSION,
        "library": stats,
        "scan": scan_state,
    }


def _current_app_update():
    try:
        return app_update_store.load()
    except UpdateNotPublished as exc:
        raise HTTPException(status_code=404, detail="No app update is published") from exc
    except InvalidUpdateManifest as exc:
        logger.warning("Invalid app update manifest: %s", exc)
        raise HTTPException(status_code=503, detail="App update is unavailable") from exc


@app.get("/api/app/update")
async def app_update() -> dict[str, Any]:
    manifest, _ = _current_app_update()
    return manifest.api_payload()


@app.api_route("/api/app/update/apk", methods=["GET", "HEAD"])
async def app_update_apk(versionCode: int | None = Query(default=None, ge=1)):
    manifest, artifact = _current_app_update()
    if versionCode is not None and versionCode != manifest.version_code:
        raise HTTPException(status_code=409, detail="App update version changed; check again")
    return FileResponse(
        artifact,
        filename=manifest.apk_file,
        media_type="application/vnd.android.package-archive",
        headers={
            "Cache-Control": "private, no-store",
            "X-APK-SHA256": manifest.sha256,
        },
    )


@app.get("/api/admin/status")
async def admin_status() -> dict[str, Any]:
    feed_sources = source_registry.ids("feed")
    asmr_sources = source_registry.ids("asmr")
    movie_sources = source_registry.ids("movie")
    drama_sources = source_registry.ids("drama")
    (
        library,
        feed_library,
        asmr_library,
        movie_library,
        drama_library,
        sources,
        summary,
        issues,
    ) = await asyncio.gather(
        asyncio.to_thread(database.stats),
        asyncio.to_thread(database.stats, sources=feed_sources),
        asyncio.to_thread(database.stats, sources=asmr_sources),
        asyncio.to_thread(database.stats, sources=movie_sources),
        asyncio.to_thread(database.stats, sources=drama_sources),
        asyncio.to_thread(database.list_media_sources),
        asyncio.to_thread(database.fast_start_summary, sources=feed_sources),
        asyncio.to_thread(database.fast_start_issues, limit=20, sources=feed_sources),
    )
    movie_sources_with_config = [source for source in sources if source["section"] == "movie"]
    movie_source_summaries = {
        source["id"]: source_summary
        for source, source_summary in zip(
            movie_sources_with_config,
            await asyncio.gather(
                *(
                    asyncio.to_thread(
                        database.movie_metadata_summary,
                        sources=(source["id"],),
                    )
                    for source in movie_sources_with_config
                )
            ),
        )
    }
    drama_sources_with_config = [source for source in sources if source["section"] == "drama"]
    drama_source_summaries = {
        source["id"]: source_summary
        for source, source_summary in zip(
            drama_sources_with_config,
            await asyncio.gather(
                *(
                    asyncio.to_thread(
                        database.drama_metadata_summary,
                        sources=(source["id"],),
                    )
                    for source in drama_sources_with_config
                )
            ),
        )
    }
    return {
        "library": {
            **library,
            "guangya": feed_library,
            "asmr": asmr_library,
            "movie": movie_library,
            "drama": drama_library,
        },
        "scan": dict(scan_state),
        "sources": [
            public_source(
                source,
                movie_metadata=movie_source_summaries.get(source["id"]),
                drama_metadata=drama_source_summaries.get(source["id"]),
            )
            for source in sources
        ],
        "metadata": dict(metadata_state),
        "movieMetadata": dict(movie_metadata_state),
        "dramaMetadata": dict(drama_metadata_state),
        "fastStart": {**fast_start_state, "summary": summary},
        "issues": issues,
    }


@app.post("/api/admin/scan", status_code=202)
async def start_scan(
    source: str = Query(default="guangya", min_length=1, max_length=80),
) -> dict[str, Any]:
    if source not in source_registry.ids():
        raise HTTPException(status_code=404, detail="媒体源不存在或已停用")
    if movie_metadata_state["running"] and movie_metadata_state["source"] == source:
        raise HTTPException(status_code=409, detail="电影资料正在刮削，请完成后再扫描")
    # ``scan_source`` owns the running flag: it raises it once the lock is held
    # and always clears it in its ``finally``. Requesting a scan only decides
    # whether a worker is spawned, so the flag can never outlive the coroutine.
    started = not scan_locks.setdefault(source, asyncio.Lock()).locked()
    if started:
        spawn_background(
            scan_library(sources=(source,)),
            name=f"manual-{source}-scan",
        )
    return {"started": started, "scan": scan_state}


@app.get("/api/admin/sources")
async def list_sources() -> dict[str, Any]:
    sources = await asyncio.to_thread(database.list_media_sources)
    return {"items": [public_source(source) for source in sources]}


@app.post("/api/admin/sources", status_code=201)
async def add_source(payload: MediaSourceRequest) -> dict[str, Any]:
    values = normalize_source_payload(payload)
    try:
        source = await asyncio.to_thread(database.add_media_source, values)
    except sqlite3.IntegrityError as exc:
        raise HTTPException(status_code=409, detail="相同地址、目录和板块的媒体源已存在") from exc
    await source_registry.reload_source(source["id"])
    await asyncio.to_thread(refresh_source_states)
    source = await asyncio.to_thread(database.get_media_source, source["id"])
    return public_source(source)


@app.put("/api/admin/sources/order")
async def reorder_sources(payload: MediaSourceOrderRequest) -> dict[str, Any]:
    """Persist the order the management screen was dragged into.

    The wall groups its sections by this order, so reordering here is also the
    reordering of the movie wall; no separate publish step is needed. Declared
    before the ``/{source_id}`` routes so ``order`` is read as a literal path
    rather than as the id of a library called "order".
    """
    existing = {
        source["id"]
        for source in await asyncio.to_thread(database.list_media_sources)
    }
    unknown = [source_id for source_id in payload.ids if source_id not in existing]
    if unknown:
        raise HTTPException(status_code=404, detail=f"媒体源不存在: {unknown[0]}")
    moved = await asyncio.to_thread(database.reorder_media_sources, payload.ids)
    await source_registry.reload()
    sources = await asyncio.to_thread(database.list_media_sources)
    return {
        "moved": moved,
        "items": [public_source(source) for source in sources],
    }


@app.put("/api/admin/sources/{source_id}")
async def update_source(source_id: str, payload: MediaSourceRequest) -> dict[str, Any]:
    existing = await asyncio.to_thread(database.get_media_source, source_id)
    if not existing:
        raise HTTPException(status_code=404, detail="媒体源不存在")
    if scan_locks.get(source_id) and scan_locks[source_id].locked():
        raise HTTPException(status_code=409, detail="媒体源正在扫描，请完成后再编辑")
    if movie_metadata_state["running"] and movie_metadata_state["source"] == source_id:
        raise HTTPException(status_code=409, detail="电影资料正在刮削，请完成后再编辑")
    values = normalize_source_payload(payload)
    if not values["token"]:
        values["token"] = existing["token"]
    if not values["username"] and existing["username"]:
        values["username"] = existing["username"]
    if not values["password"]:
        values["password"] = existing["password"]
    try:
        source = await asyncio.to_thread(database.update_media_source, source_id, values)
    except sqlite3.IntegrityError as exc:
        raise HTTPException(status_code=409, detail="相同地址、目录和板块的媒体源已存在") from exc
    await source_registry.reload_source(source_id)
    await asyncio.to_thread(refresh_source_states)
    source = await asyncio.to_thread(database.get_media_source, source_id)
    return public_source(source)


@app.delete("/api/admin/sources/{source_id}")
async def delete_source(source_id: str) -> dict[str, Any]:
    existing = await asyncio.to_thread(database.get_media_source, source_id)
    if not existing:
        raise HTTPException(status_code=404, detail="媒体源不存在")
    if scan_locks.get(source_id) and scan_locks[source_id].locked():
        raise HTTPException(status_code=409, detail="媒体源正在扫描，请完成后再删除")
    if movie_metadata_state["running"] and movie_metadata_state["source"] == source_id:
        raise HTTPException(status_code=409, detail="电影资料正在刮削，请完成后再删除")
    deleted = await asyncio.to_thread(database.delete_media_source, source_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="媒体源不存在")
    await source_registry.reload()
    await asyncio.to_thread(refresh_source_states)
    return {"deleted": True, "source": source_id}


@app.post("/api/admin/sources/{source_id}/scan", status_code=202)
async def scan_one_source(source_id: str) -> dict[str, Any]:
    return await start_scan(source=source_id)


@app.post("/api/admin/sources/{source_id}/metadata", status_code=202)
async def start_movie_metadata(
    source_id: str,
    force: bool = False,
    refresh_index: bool = False,
) -> dict[str, Any]:
    if source_id not in source_registry.ids("movie"):
        raise HTTPException(status_code=404, detail="电影媒体源不存在或已停用")
    if not settings.shared_metadata_base_url:
        raise HTTPException(status_code=503, detail="共享元数据接口未配置")
    if scan_locks.get(source_id) and scan_locks[source_id].locked():
        raise HTTPException(status_code=409, detail="媒体源正在扫描，请完成后再匹配封面")
    source_summary = await asyncio.to_thread(
        database.movie_metadata_summary,
        sources=(source_id,),
    )
    if not source_summary["total"]:
        raise HTTPException(status_code=409, detail="请先扫描电影媒体源")
    started = start_movie_metadata_job(
        source_id, force=force, refresh_index=refresh_index
    )
    return {"started": started, "movieMetadata": movie_metadata_state}


@app.post("/api/admin/sources/{source_id}/drama-metadata", status_code=202)
async def start_drama_metadata(source_id: str, force: bool = False) -> dict[str, Any]:
    if source_id not in source_registry.ids("drama"):
        raise HTTPException(status_code=404, detail="短剧媒体源不存在或已停用")
    if scan_locks.get(source_id) and scan_locks[source_id].locked():
        raise HTTPException(status_code=409, detail="媒体源正在扫描，请完成后再匹配封面")
    source_summary = await asyncio.to_thread(
        database.drama_metadata_summary,
        sources=(source_id,),
    )
    if not source_summary["total"]:
        raise HTTPException(status_code=409, detail="请先扫描短剧媒体源")
    started = start_drama_metadata_job(source_id, force=force)
    return {"started": started, "dramaMetadata": drama_metadata_state}


@app.post("/api/admin/fast-start", status_code=202)
async def start_fast_start_check(force: bool = False) -> dict[str, Any]:
    started = not fast_start_lock.locked() and not fast_start_state["running"]
    if started:
        fast_start_state["running"] = True
        spawn_background(check_fast_start(force=force), name="fast-start-check")
    return {"started": started, "fastStart": fast_start_state}


def parse_excluded_ids(value: str | None) -> set[int]:
    if not value:
        return set()
    result: set[int] = set()
    for part in value.split(","):
        try:
            video_id = int(part)
        except ValueError:
            continue
        if video_id > 0:
            result.add(video_id)
        if len(result) >= 100:
            break
    return result


async def prewarm_play_url(video: dict[str, Any]) -> None:
    try:
        runtime = source_registry.get(video["source"])
        await runtime.direct_urls.get(video["id"], video["path"])
    except Exception as exc:
        logger.warning("Direct URL prewarm failed for video %s: %s", video["id"], exc)


async def prewarm_play_urls(videos: list[dict[str, Any]], *, limit: int = 6) -> None:
    """Resolve the first visible items so a tap can skip the AList lookup."""
    semaphore = asyncio.Semaphore(3)

    async def warm(video: dict[str, Any]) -> None:
        async with semaphore:
            await prewarm_play_url(video)

    await asyncio.gather(*(warm(video) for video in videos[:limit]))


async def prewarm_movie_image_at_width(source_url: str | None, width: int) -> None:
    """Resolve one artwork URL into the persistent cache at ``width``.

    The client appends ``?w=`` to every cover it renders, so a warm-up at the
    full size would fill a cache entry nobody ever reads.
    """
    if not source_url:
        return
    if _movie_image_failure_remaining(_movie_image_request_key(source_url, width)) > 0:
        # Warming a cover the proxy just failed on spends a download slot and a
        # twelve second timeout per section for a picture that cannot arrive.
        return
    try:
        await _proxy_movie_image(source_url, width=width)
    except Exception as exc:
        logger.warning("Movie image prewarm failed for %s: %s", source_url, exc)


async def prewarm_movie_wall_images(rows: list[dict[str, Any]], *, limit: int = 6) -> None:
    """Fill the persistent image cache for the first visible wall items."""
    semaphore = asyncio.Semaphore(3)

    async def warm(row: dict[str, Any]) -> None:
        async with semaphore:
            # Warm exactly the URL public_movie advertises, at the width the
            # client will ask for: a warm-up that fills a different cache entry
            # than the one the wall reads is a wasted download.
            #
            # The AList thumbnail branch is served by /api/videos/{id}/poster,
            # which is a plain redirect the client cannot size, so it is warmed
            # unsized to match.
            if row.get("poster_url") or row.get("backdrop_url"):
                await prewarm_movie_image_at_width(
                    row.get("poster_url") or row.get("backdrop_url"),
                    MOVIE_WALL_IMAGE_WIDTH,
                )
            elif row.get("thumb"):
                await prewarm_movie_image_at_width(row.get("thumb"), 0)

    await asyncio.gather(*(warm(row) for row in rows[:limit]))


async def prewarm_drama_posters(rows: list[dict[str, Any]], *, limit: int = 6) -> None:
    """Fill the persistent image cache for the first visible series."""
    semaphore = asyncio.Semaphore(3)

    async def warm(row: dict[str, Any]) -> None:
        async with semaphore:
            await prewarm_movie_image_at_width(row.get("poster_url"), MOVIE_WALL_IMAGE_WIDTH)

    await asyncio.gather(*(warm(row) for row in rows[:limit]))


@app.get("/api/feed")
async def feed(
    limit: int = Query(default=12, ge=1, le=30),
    cursor: str | None = Query(default=None, max_length=256),
    mode: str = Query(default="shuffle", pattern="^(shuffle|newest|oldest)$"),
    exclude: str | None = Query(default=None, max_length=1024),
    start: int | None = Query(default=None, ge=1),
    category: str = Query(default="short", pattern="^(short|long)$"),
) -> dict[str, Any]:
    rows, next_cursor, total = await asyncio.to_thread(
        database.feed,
        limit=limit,
        cursor=cursor,
        mode=mode,
        exclude_ids=parse_excluded_ids(exclude),
        start_id=start,
        sources=source_registry.ids("feed"),
        category=category,
        duration_boundary=settings.duration_boundary_seconds,
    )
    if rows:
        spawn_background(
            prewarm_play_url(rows[0]),
            name=f"prewarm-play-{rows[0]['id']}",
        )
    return {
        "items": [
            {
                "id": row["id"],
                "title": Path(row["name"]).stem,
                "size": row["size"],
                "modified": row["modified"],
                "duration": row.get("duration_seconds"),
                "playUrl": f"/api/videos/{row['id']}/play",
                "posterUrl": f"/api/videos/{row['id']}/poster" if row.get("thumb") else None,
            }
            for row in rows
        ],
        "nextCursor": next_cursor,
        "total": total,
        "scan": scan_state,
    }


@app.api_route("/api/videos/{video_id}/play", methods=["GET", "HEAD"])
async def play(video_id: int, refresh: bool = False):
    video = await asyncio.to_thread(database.get_video, video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")

    try:
        runtime = source_registry.get(video["source"])
        raw_url, requires_headers = await runtime.direct_urls.get(
            video_id,
            video["path"],
            refresh=refresh,
        )
    except (AListError, KeyError) as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    if requires_headers:
        raise HTTPException(status_code=422, detail="This media source requires proxy headers")

    return RedirectResponse(
        raw_url,
        status_code=302,
        headers={
            "Cache-Control": "private, max-age=300",
            "Vary": "Cookie",
            "X-Robots-Tag": "noindex",
        },
    )


class MediaMetadataReport(BaseModel):
    duration: float | None = Field(default=None, gt=0, le=604800)
    media_kind: Literal["audio", "video"] | None = Field(default=None, alias="mediaKind")


@app.post("/api/videos/{video_id}/metadata", status_code=204)
async def report_media_metadata(video_id: int, payload: MediaMetadataReport):
    video = await asyncio.to_thread(database.get_video, video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Media not found")
    await asyncio.to_thread(
        database.update_media_metadata,
        video_id,
        duration_seconds=payload.duration,
        media_kind=payload.media_kind,
        detail="browser metadata",
    )


class WatchProgressRequest(BaseModel):
    videoId: int
    positionMs: int = Field(default=0, ge=0)
    durationMs: int = Field(default=0, ge=0)


@app.post("/api/progress", status_code=204)
async def save_watch_progress(payload: WatchProgressRequest) -> Response:
    """Remember where one device left off.

    The position is stored against the media row rather than the phone, so
    "continue watching" is the same list on every device and survives a
    reinstall. Reporting is deliberately boring: the client sends what it has
    every few seconds and the newest report wins.
    """
    video = await asyncio.to_thread(database.get_video, payload.videoId)
    if not video:
        raise HTTPException(status_code=404, detail="Media not found")
    await asyncio.to_thread(
        database.record_watch_progress,
        video_id=payload.videoId,
        position_ms=payload.positionMs,
        duration_ms=payload.durationMs,
    )
    return Response(status_code=204)


@app.get("/api/asmr/authors")
async def asmr_authors(
    q: str = Query(default="", max_length=100),
    limit: int | None = Query(default=None, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> dict[str, Any]:
    search = q.strip()
    sources = source_registry.ids("asmr")
    rows, total = await asyncio.gather(
        asyncio.to_thread(
            database.asmr_authors,
            search=search,
            limit=limit,
            offset=offset,
            sources=sources,
        ),
        asyncio.to_thread(database.asmr_author_count, search=search, sources=sources),
    )
    next_offset = offset + len(rows) if limit is not None and offset + len(rows) < total else None
    return {
        "items": [
            {
                "name": row["author"],
                "itemCount": int(row["item_count"] or 0),
                "videoCount": int(row["video_count"] or 0),
                "audioCount": int(row["audio_count"] or 0),
                "modified": row["modified"],
            }
            for row in rows
        ],
        "total": total,
        "nextOffset": next_offset,
        "scan": {
            "running": any(scan_state["sources"].get(source, {}).get("running") for source in sources),
        },
    }


@app.get("/api/asmr/authors/{author}/items")
async def asmr_author_items(
    author: str,
    kind: str = Query(default="all", pattern="^(all|video|audio)$"),
    q: str = Query(default="", max_length=100),
    limit: int | None = Query(default=None, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
) -> dict[str, Any]:
    search = q.strip()
    sources = source_registry.ids("asmr")
    rows, total = await asyncio.gather(
        asyncio.to_thread(
            database.asmr_items,
            author=author,
            kind=kind,
            search=search,
            limit=limit,
            offset=offset,
            sources=sources,
        ),
        asyncio.to_thread(
            database.asmr_item_count,
            author=author,
            kind=kind,
            search=search,
            sources=sources,
        ),
    )
    if total == 0 and not await asyncio.to_thread(
        database.asmr_authors,
        search=author,
        limit=1,
        sources=sources,
    ):
        raise HTTPException(status_code=404, detail="Author not found")
    if rows:
        spawn_background(
            prewarm_play_url(rows[0]),
            name=f"prewarm-asmr-play-{rows[0]['id']}",
        )
    return {
        "items": [
            {
                "id": row["id"],
                "title": Path(row["name"]).stem,
                "author": row["author"],
                "size": row["size"],
                "modified": row["modified"],
                "duration": row.get("duration_seconds"),
                "format": row.get("media_format"),
                "kind": "video"
                if (row.get("media_format") or "").lower() == "m3u8"
                else (row.get("media_kind") or "video"),
                "playUrl": f"/api/videos/{row['id']}/play",
                "posterUrl": f"/api/videos/{row['id']}/poster" if row.get("thumb") else None,
            }
            for row in rows
        ],
        "total": total,
        "nextOffset": offset + len(rows) if limit is not None and offset + len(rows) < total else None,
    }


def _movie_group_payload(
    source_id: str,
    rows: list[dict[str, Any]],
    *,
    total: int,
    offset: int,
) -> dict[str, Any]:
    loaded = offset + len(rows)
    return {
        "sourceId": source_id,
        "name": source_registry.name(source_id),
        "items": [public_movie(row) for row in rows],
        "total": total,
        "nextOffset": loaded if loaded < total else None,
    }


@app.get("/api/movies/groups")
async def movie_groups(
    q: str = Query(default="", max_length=100),
    perGroup: int = Query(default=6, ge=1, le=24),
    sort: str = Query(default="cover", pattern="^(cover|title|time)$"),
    dir: str = Query(default="", pattern="^(asc|desc|)$"),
    sorts: str = Query(default="", max_length=2000),
) -> dict[str, Any]:
    """Group the wall by media source so the UI mirrors how libraries are added.

    Counting per source first keeps a source without movies out of the wall
    entirely, instead of rendering an empty section the user cannot explain.

    ``sorts`` carries each library's own order as ``id:token`` pairs. The wall
    previews the first six titles of a library, so it has to preview them in the
    order that library's page is browsed with - otherwise sorting a page and
    going back showed the same six covers in the old arrangement.
    """
    sources = source_registry.ids("movie")
    search = q.strip()
    per_source_sort = _parse_source_sorts(sorts)
    counts = await asyncio.to_thread(
        database.movie_source_counts,
        search=search,
        sources=sources,
    )
    ordered = [source for source in sources if counts.get(source, 0) > 0]

    def sort_for(source_id: str) -> tuple[str, str]:
        token = per_source_sort.get(source_id)
        if not token:
            return sort, dir
        return movie_sort_parts(token) or (sort, dir)

    pages = await asyncio.gather(
        *(
            asyncio.to_thread(
                database.movies,
                search=search,
                limit=perGroup,
                offset=0,
                sources=(source,),
                sort=sort_for(source)[0],
                direction=sort_for(source)[1],
            )
            for source in ordered
        )
    )
    groups = [
        _movie_group_payload(source, rows, total=counts[source], offset=0)
        for source, rows in zip(ordered, pages)
    ]
    if groups:
        # Every section, not just the two that fit on the first screen: the wall
        # is scrolled with a thumb, so a section that starts empty stays empty
        # until the user waits for it. Covers are warmed in section order, which
        # is also the order they come on screen, so the visible ones lead.
        rows = [row for group_rows in pages for row in group_rows]
        spawn_background(
            prewarm_movie_wall_images(
                rows,
                limit=min(len(rows), MOVIE_WALL_PREWARM_MAX_ROWS),
            ),
            name="prewarm-movie-wall-groups",
        )
    return {
        "groups": groups,
        "total": sum(counts.get(source, 0) for source in ordered),
        "scan": {
            "running": any(
                scan_state["sources"].get(source, {}).get("running") for source in sources
            )
        },
    }


@app.get("/api/movies/groups/{source_id}")
async def movie_group_items(
    source_id: str,
    q: str = Query(default="", max_length=100),
    limit: int = Query(default=6, ge=1, le=60),
    offset: int = Query(default=0, ge=0),
    sort: str = Query(default="cover", pattern="^(cover|title|time)$"),
    dir: str = Query(default="", pattern="^(asc|desc|)$"),
) -> dict[str, Any]:
    if source_id not in source_registry.ids("movie"):
        raise HTTPException(status_code=404, detail="Media source not found")
    search = q.strip()
    rows, total = await asyncio.gather(
        asyncio.to_thread(
            database.movies,
            search=search,
            limit=limit,
            offset=offset,
            sources=(source_id,),
            sort=sort,
            direction=dir,
        ),
        asyncio.to_thread(database.movie_count, search=search, sources=(source_id,)),
    )
    if offset == 0 and rows:
        # Opening a library used to draw its first six cards from the network one
        # by one while the rest of the page sat empty. Warming the first screens
        # of the grid in the background is what makes the page appear filled.
        spawn_background(
            prewarm_movie_wall_images(rows, limit=MOVIE_LIBRARY_PREWARM_LIMIT),
            name=f"prewarm-movie-library-{source_id}",
        )
    return _movie_group_payload(source_id, rows, total=total, offset=offset)


@app.get("/api/movies")
async def movies(
    q: str = Query(default="", max_length=100),
    limit: int = Query(default=24, ge=1, le=60),
    offset: int = Query(default=0, ge=0),
    sort: str = Query(default="cover", pattern="^(cover|title|time)$"),
    dir: str = Query(default="", pattern="^(asc|desc|)$"),
) -> dict[str, Any]:
    sources = source_registry.ids("movie")
    search = q.strip()
    rows, total = await asyncio.gather(
        asyncio.to_thread(
            database.movies,
            search=search,
            limit=limit,
            offset=offset,
            sources=sources,
            sort=sort,
            direction=dir,
        ),
        asyncio.to_thread(database.movie_count, search=search, sources=sources),
    )
    if offset == 0 and rows:
        spawn_background(
            prewarm_movie_wall_images(rows, limit=MOVIE_LIBRARY_PREWARM_LIMIT),
            name="prewarm-movie-wall",
        )
    return {
        "items": [public_movie(row) for row in rows],
        "total": total,
        "nextOffset": offset + len(rows) if offset + len(rows) < total else None,
        "scan": {"running": any(scan_state["sources"].get(source, {}).get("running") for source in sources)},
    }


@app.get("/api/movies/recent")
async def recent_movies(
    limit: int = Query(default=10, ge=1, le=24),
) -> dict[str, Any]:
    """The films to pick back up: unfinished, most recently watched first.

    Declared before ``/api/movies/{movie_id}`` so the literal path is matched
    as a path and not read as a movie id.
    """
    sources = source_registry.ids("movie")
    rows = await asyncio.to_thread(
        database.recent_movies,
        sources=sources,
        limit=limit,
    )
    items: list[dict[str, Any]] = []
    for row in rows:
        item = public_movie(row)
        item["resumePositionMs"] = int(row.get("resume_position_ms") or 0)
        item["watchedAt"] = row.get("watched_at")
        items.append(item)
    if rows:
        spawn_background(
            prewarm_movie_wall_images(rows, limit=len(rows)),
            name="prewarm-recent-movies",
        )
    return {"items": items}


@app.get("/api/movies/favorites")
async def movie_favorites(
    limit: int = Query(default=24, ge=1, le=60),
    offset: int = Query(default=0, ge=0),
) -> dict[str, Any]:
    """The hearted titles, most recently hearted first.

    Declared before ``/api/movies/{movie_id}`` for the same reason as the
    "recent" strip: a literal path has to be matched as a path, not read as a
    movie id. The page is paged exactly like a library page so the favourites
    grid can reuse it whole.
    """
    sources = source_registry.ids("movie")
    rows, total = await asyncio.gather(
        asyncio.to_thread(
            database.favorite_movies,
            sources=sources,
            limit=limit,
            offset=offset,
        ),
        asyncio.to_thread(database.favorite_movie_count, sources=sources),
    )
    if offset == 0 and rows:
        spawn_background(
            prewarm_movie_wall_images(rows, limit=MOVIE_LIBRARY_PREWARM_LIMIT),
            name="prewarm-movie-favorites",
        )
    return {
        "items": [public_movie(row) for row in rows],
        "total": total,
        "nextOffset": offset + len(rows) if offset + len(rows) < total else None,
    }


async def _set_movie_favorite(movie_id: int, favorite: bool) -> dict[str, Any]:
    """Heart or un-heart one title and hand the caller the updated record."""
    sources = source_registry.ids("movie")
    row = await asyncio.to_thread(database.get_movie, movie_id, sources=sources)
    if not row:
        raise HTTPException(status_code=404, detail="Movie not found")
    await asyncio.to_thread(
        database.set_movie_favorite,
        int(row["video_id"]),
        favorite,
    )
    payload = public_movie(row, detail=True)
    payload["favorite"] = favorite
    return payload


@app.put("/api/movies/{movie_id}/favorite")
async def add_movie_favorite(movie_id: int) -> dict[str, Any]:
    return await _set_movie_favorite(movie_id, True)


@app.delete("/api/movies/{movie_id}/favorite")
async def remove_movie_favorite(movie_id: int) -> dict[str, Any]:
    return await _set_movie_favorite(movie_id, False)


@app.get("/api/movies/{movie_id}")
async def movie_detail(movie_id: int) -> dict[str, Any]:
    row = await asyncio.to_thread(
        database.get_movie,
        movie_id,
        sources=source_registry.ids("movie"),
    )
    if not row:
        raise HTTPException(status_code=404, detail="Movie not found")
    spawn_background(
        prewarm_play_url(row),
        name=f"prewarm-movie-play-{row['id']}",
    )
    payload = public_movie(row, detail=True)
    payload["favorite"] = await asyncio.to_thread(
        database.is_movie_favorite,
        int(row["video_id"]),
    )
    return payload


async def _movie_image(
    movie_id: int,
    field: str,
    request: Request,
    width: int | None = None,
) -> Response:
    row = await asyncio.to_thread(
        database.get_movie,
        movie_id,
        sources=source_registry.ids("movie"),
    )
    if not row:
        raise HTTPException(status_code=404, detail="Movie not found")
    source_url = row.get(field)
    if field == "poster_url" and not source_url:
        # Same chain public_movie advertises, so the URL the client was given
        # always resolves to the picture it expects.
        source_url = row.get("backdrop_url") or row.get("thumb")
    if not source_url:
        raise HTTPException(status_code=404, detail="Movie image not found")
    return await _proxy_movie_image(
        source_url, request.headers.get("if-none-match"), width
    )


@app.get("/api/movies/{movie_id}/poster")
async def movie_poster(
    movie_id: int,
    request: Request,
    w: str | None = Query(default=None, max_length=8),
) -> Response:
    return await _movie_image(movie_id, "poster_url", request, _requested_image_width(w))


@app.get("/api/movies/{movie_id}/backdrop")
async def movie_backdrop(
    movie_id: int,
    request: Request,
    w: str | None = Query(default=None, max_length=8),
) -> Response:
    return await _movie_image(movie_id, "backdrop_url", request, _requested_image_width(w))


def _drama_group_payload(
    category: str,
    rows: list[dict[str, Any]],
    *,
    total: int,
    offset: int,
) -> dict[str, Any]:
    loaded = offset + len(rows)
    return {
        "groupId": category,
        "name": category,
        "items": [public_drama(row) for row in rows],
        "total": total,
        "nextOffset": loaded if loaded < total else None,
    }


@app.get("/api/dramas/groups")
async def drama_groups(
    q: str = Query(default="", max_length=100),
    perGroup: int = Query(default=6, ge=1, le=24),
) -> dict[str, Any]:
    """Group the drama wall by the downloader's first-level folders.

    The short-drama source is one library of four categories ("短剧", "漫剧",
    …), so the wall mirrors those folders the way the movie wall mirrors the
    media sources. Counting first keeps a category without an indexed series
    out of the wall instead of rendering an empty section.
    """
    sources = source_registry.ids("drama")
    search = q.strip()
    counts = await asyncio.to_thread(
        database.drama_category_counts,
        search=search,
        sources=sources,
    )
    ordered = [category for category, total in counts.items() if total > 0]
    pages = await asyncio.gather(
        *(
            asyncio.to_thread(
                database.dramas,
                search=search,
                limit=perGroup,
                offset=0,
                sources=sources,
                category=category,
            )
            for category in ordered
        )
    )
    groups = [
        _drama_group_payload(category, rows, total=counts[category], offset=0)
        for category, rows in zip(ordered, pages)
    ]
    if groups:
        # Same reasoning as the movie wall: every category is warmed so a section
        # the user scrolls to is already there, capped so a big library list
        # cannot start an unbounded download storm on a phone's first frame.
        rows = [row for group_rows in pages for row in group_rows]
        spawn_background(
            prewarm_drama_posters(
                rows,
                limit=min(len(rows), MOVIE_WALL_PREWARM_MAX_ROWS),
            ),
            name="prewarm-drama-wall-groups",
        )
    return {
        "groups": groups,
        "total": sum(counts.get(category, 0) for category in ordered),
        "scan": {
            "running": any(
                scan_state["sources"].get(source, {}).get("running")
                for source in sources
            )
        },
    }


@app.get("/api/dramas")
async def dramas(
    q: str = Query(default="", max_length=100),
    limit: int = Query(default=24, ge=1, le=60),
    offset: int = Query(default=0, ge=0),
    category: str = Query(default="", max_length=100),
) -> dict[str, Any]:
    sources = source_registry.ids("drama")
    search = q.strip()
    scope = category.strip()
    rows, total = await asyncio.gather(
        asyncio.to_thread(
            database.dramas,
            search=search,
            limit=limit,
            offset=offset,
            sources=sources,
            category=scope,
        ),
        asyncio.to_thread(
            database.drama_count,
            search=search,
            sources=sources,
            category=scope,
        ),
    )
    if offset == 0 and rows:
        spawn_background(
            prewarm_drama_posters(rows, limit=MOVIE_LIBRARY_PREWARM_LIMIT),
            name="prewarm-drama-wall",
        )
    return {
        "items": [public_drama(row) for row in rows],
        "total": total,
        "nextOffset": offset + len(rows) if offset + len(rows) < total else None,
        "scan": {
            "running": any(
                scan_state["sources"].get(source, {}).get("running")
                for source in sources
            )
        },
    }


def next_drama_episode_id(drama_id: str, video_id: int) -> int | None:
    """The episode after ``video_id`` in playback order, or None at the finale.

    Ordering has to happen here rather than in SQL because episode numbers live
    in the file names ("第9集" sorts before "第10集" as text); the read paths
    already agree on :func:`episode_order_key`, so the strip uses it too.
    """
    episodes = database.drama_episodes(drama_id)
    episodes.sort(key=lambda item: episode_order_key(str(item.get("name") or "")))
    for index, episode in enumerate(episodes):
        if int(episode["video_id"]) != video_id:
            continue
        following = episodes[index + 1] if index + 1 < len(episodes) else None
        return int(following["video_id"]) if following else None
    return None


@app.get("/api/dramas/recent")
async def recent_dramas(
    limit: int = Query(default=10, ge=1, le=24),
) -> dict[str, Any]:
    """The series to pick back up, one entry per show, newest watch first.

    Declared before ``/api/dramas/{drama_id}`` so the literal path is matched
    as a path rather than read as a series id.
    """
    sources = source_registry.ids("drama")
    rows = await asyncio.to_thread(
        database.recent_dramas,
        sources=sources,
        limit=limit,
    )
    items: list[dict[str, Any]] = []
    for row in rows:
        item = public_drama(row)
        resume_video_id = row.get("resume_video_id")
        episode_id = int(resume_video_id) if resume_video_id else None
        position = int(row.get("resume_position_ms") or 0)
        if episode_id is not None and row.get("resume_completed"):
            # The episode was watched through, so the interesting place to
            # carry on from is the next one; the finale has nowhere to go and
            # replays from the top instead of resuming on the end credits.
            following = await asyncio.to_thread(
                next_drama_episode_id,
                str(row.get("id") or ""),
                episode_id,
            )
            episode_id = following if following is not None else episode_id
            position = 0
        item["episodeId"] = episode_id
        item["resumePositionMs"] = position
        item["watchedAt"] = row.get("watched_at")
        items.append(item)
    if rows:
        spawn_background(
            prewarm_drama_posters(rows, limit=len(rows)),
            name="prewarm-recent-dramas",
        )
    return {"items": items}


@app.get("/api/dramas/{drama_id}")
async def drama_detail(drama_id: str) -> dict[str, Any]:
    row = await asyncio.to_thread(
        database.get_drama,
        drama_id,
        sources=source_registry.ids("drama"),
    )
    if not row:
        raise HTTPException(status_code=404, detail="Drama not found")
    episodes = await asyncio.to_thread(database.drama_episodes, drama_id)
    # Order at read time with the same key the index used, so a rescan and a
    # running client can never disagree about what "next episode" means.
    episodes.sort(key=lambda item: episode_order_key(str(item.get("name") or "")))
    payload = public_drama(row)
    payload["episodes"] = [
        public_drama_episode(video, position)
        for position, video in enumerate(episodes, start=1)
    ]
    if episodes:
        first = dict(episodes[0])
        first["source"] = row.get("source")
        first["id"] = first["video_id"]
        spawn_background(
            prewarm_play_url(first),
            name=f"prewarm-drama-play-{first['video_id']}",
        )
    return payload


@app.get("/api/dramas/{drama_id}/poster")
async def drama_poster(
    drama_id: str,
    request: Request,
    w: str | None = Query(default=None, max_length=8),
) -> Response:
    row = await asyncio.to_thread(
        database.get_drama,
        drama_id,
        sources=source_registry.ids("drama"),
    )
    if not row or not str(row.get("poster_url") or "").strip():
        raise HTTPException(status_code=404, detail="Drama poster not found")
    return await _proxy_movie_image(
        row["poster_url"],
        request.headers.get("if-none-match"),
        _requested_image_width(w),
    )


@app.get("/api/videos/{video_id}/poster")
async def poster(video_id: int):
    video = await asyncio.to_thread(database.get_video, video_id)
    if not video or not video.get("thumb"):
        raise HTTPException(status_code=404, detail="Poster not found")
    return RedirectResponse(video["thumb"], status_code=302, headers={"Cache-Control": "private, max-age=300"})


static_dir = Path(settings.static_dir)
assets_dir = static_dir / "assets"
if assets_dir.is_dir():
    app.mount("/assets", StaticFiles(directory=assets_dir), name="assets")


@app.api_route("/manifest.webmanifest", methods=["GET", "HEAD"], include_in_schema=False)
async def manifest():
    return FileResponse(static_dir / "manifest.webmanifest", media_type="application/manifest+json")


@app.api_route("/sw.js", methods=["GET", "HEAD"], include_in_schema=False)
async def service_worker():
    return FileResponse(static_dir / "sw.js", media_type="application/javascript", headers={"Cache-Control": "no-cache"})


@app.api_route("/icon.svg", methods=["GET", "HEAD"], include_in_schema=False)
async def icon():
    return FileResponse(static_dir / "icon.svg", media_type="image/svg+xml")


@app.api_route("/{path:path}", methods=["GET", "HEAD"], include_in_schema=False)
async def spa(path: str):
    candidate = static_dir / path
    if path and candidate.is_file() and static_dir in candidate.resolve().parents:
        return FileResponse(candidate)
    index = static_dir / "index.html"
    if not index.is_file():
        raise HTTPException(status_code=503, detail="Frontend not built")
    return FileResponse(index)
