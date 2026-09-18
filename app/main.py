from __future__ import annotations

import asyncio
import hashlib
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
from app.database import LibraryDatabase
from app.faststart import inspect_mp4_prefix
from app.media_metadata import mp4_duration_seconds
from app.media_sources import MediaSourceRegistry
from app.scanner import ResumableScanner, initial_steps
from app.movie_metadata import movie_search_candidates, parse_movie_filename
from app.shared_metadata import SharedMetadataClient
from app.tmdb import TmdbClient
from app.settings import Settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
logger = logging.getLogger("short-video")

APP_VERSION = "1.6.0-beta.12"
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
MOVIE_IMAGE_CACHE_MAX_BYTES = 256 * 1024 * 1024
movie_image_cache_dir = Path(settings.database_path).resolve().parent / "movie-images"
movie_image_cache_locks: dict[str, asyncio.Lock] = {}
movie_image_cache_guard = threading.RLock()


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
        previous = scan_state["sources"].get(source_id, {})
        state = empty_source_scan_state()
        state["running"] = bool(previous.get("running", False))
        state["lastSuccess"] = source.get("last_scan_success")
        job = jobs.get(source_id) or {}
        error = job.get("error") or source.get("last_scan_error")
        state["status"] = job.get("status")
        state["lastError"] = error
        state["directories"] = int(source.get("directories") or 0)
        if error:
            state["pendingErrors"] = [str(error)]
        if job and not state["running"]:
            apply_scan_progress(
                state,
                database.scan_step_progress(
                    job_id=str(job["id"]),
                    source=source_id,
                    marker=str(job["marker"]),
                ),
            )
        scan_state["sources"][source_id] = state
        scan_locks.setdefault(source_id, asyncio.Lock())
    for source_id in set(scan_state["sources"]) - current_ids:
        scan_state["sources"].pop(source_id, None)
        scan_locks.pop(source_id, None)
    refresh_scan_summary()


def _scan_job_matches(job: dict[str, Any], config: dict[str, Any]) -> bool:
    return (
        str(job["base_url"]) == str(config["base_url"])
        and str(job["root_path"]) == str(config["root_path"])
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
                    root_path=str(config["root_path"]),
                    base_url=str(config["base_url"]),
                    scan_mode=str(config["scan_mode"]),
                    section=str(config["section"]),
                )
                await asyncio.to_thread(
                    database.seed_scan_directories,
                    str(job["id"]),
                    initial_steps(
                        str(config["scan_mode"]),
                        str(config["root_path"]),
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
                # loop without a second manual button.
                start_movie_metadata_job(source)
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


async def _mark_movie_unmatched_if_empty(movie: dict[str, Any]) -> None:
    """Record a miss without discarding metadata an earlier run already found."""
    if movie.get("metadata_provider") or str(movie.get("poster_url") or "").strip():
        return
    await asyncio.to_thread(
        database.update_movie_metadata,
        int(movie["id"]),
        match_status="unmatched",
        match_confidence=0.0,
    )


def start_movie_metadata_job(source: str, *, force: bool = False) -> bool:
    """Kick off a metadata pass and report whether this call started it."""
    if movie_metadata_lock.locked() or movie_metadata_state["running"]:
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
        scrape_movie_metadata(source, force=force),
        name=f"movie-metadata-{source}",
    )
    return True


async def scrape_movie_metadata(source: str, *, force: bool = False) -> None:
    if movie_metadata_lock.locked():
        return
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
            has_tmdb = bool(settings.tmdb_api_read_token or settings.tmdb_api_key)
            has_shared_metadata = bool(settings.shared_metadata_base_url)
            if not has_tmdb and not has_shared_metadata:
                raise RuntimeError("共享元数据接口与 TMDB 均未配置")
            source_ids = (source,)
            movies = await asyncio.to_thread(
                database.movie_metadata_candidates,
                sources=source_ids,
                force=force,
            )
            movie_metadata_state["total"] = len(movies)
            client = TmdbClient(
                read_token=settings.tmdb_api_read_token,
                api_key=settings.tmdb_api_key,
                language=settings.tmdb_language,
            ) if has_tmdb else None
            shared_metadata = SharedMetadataClient(
                base_url=settings.shared_metadata_base_url,
                token=settings.shared_metadata_token,
            ) if has_shared_metadata else None
            try:
                for movie in movies:
                    parsed_name = parse_movie_filename(str(movie.get("name") or ""))
                    # The shared service already holds the sidecar covers and NFO
                    # metadata produced for the Emby stack, so it is both faster
                    # and more complete than re-scraping the same title here.
                    shared_match = (
                        await shared_metadata.lookup(
                            str(movie.get("name") or ""),
                            str(movie.get("media_path") or ""),
                        )
                        if shared_metadata is not None
                        else None
                    )
                    if shared_match is not None:
                        await asyncio.to_thread(
                            database.update_movie_metadata,
                            int(movie["id"]),
                            display_title=shared_match.title or movie["display_title"],
                            original_title=shared_match.original_title or None,
                            year=shared_match.year or movie.get("year"),
                            overview=shared_match.overview or None,
                            poster_url=shared_match.poster_url
                            or movie.get("poster_url"),
                            backdrop_url=shared_match.backdrop_url
                            or movie.get("backdrop_url"),
                            tmdb_id=None,
                            metadata_provider="shared",
                            release_date=str(shared_match.year) if shared_match.year else None,
                            genres="[]",
                            performers=json.dumps(shared_match.performers, ensure_ascii=False),
                            studio=shared_match.studio or None,
                            match_status="matched",
                            match_confidence=shared_match.confidence,
                        )
                        movie_metadata_state["matched"] += 1
                        movie_metadata_state["checked"] += 1
                        continue
                    if parsed_name.code:
                        # A coded title belongs to the shared catalogue. A generic
                        # movie database would happily match a coincidental title,
                        # so an unmatched code is left exactly as it stands.
                        await _mark_movie_unmatched_if_empty(movie)
                        movie_metadata_state["checked"] += 1
                        continue
                    match = None
                    if parsed_name.tmdb_id and client:
                        match = await client.movie_by_id(parsed_name.tmdb_id)
                    if match is None and client:
                        queries = movie_search_candidates(
                            str(movie.get("normalized_title") or movie.get("display_title") or "")
                        )
                        attempts = []
                        for query in queries:
                            attempts.extend([(query, movie.get("year")), (query, None)])
                        best = None
                        for query, year in attempts:
                            candidate = await client.search_movie(query, year=year)
                            if candidate is None:
                                continue
                            if candidate.status == "matched":
                                best = candidate
                                break
                            if best is None or candidate.confidence > best.confidence:
                                best = candidate
                        match = best
                    if match is None:
                        await _mark_movie_unmatched_if_empty(movie)
                    else:
                        await asyncio.to_thread(
                            database.update_movie_metadata,
                            int(movie["id"]),
                            original_title=match.original_title,
                            display_title=match.title or movie["display_title"],
                            year=match.year or movie.get("year"),
                            overview=match.overview,
                            poster_url=match.poster_url,
                            backdrop_url=match.backdrop_url,
                            rating=match.rating,
                            runtime_minutes=match.runtime_minutes,
                            tmdb_id=match.tmdb_id,
                            metadata_provider="tmdb",
                            release_date=str(match.year) if match.year else None,
                            genres="[]",
                            performers="[]",
                            studio=None,
                            match_status=match.status,
                            match_confidence=match.confidence,
                        )
                        movie_metadata_state["matched"] += 1
                    movie_metadata_state["checked"] += 1
            finally:
                if client:
                    await client.close()
                if shared_metadata:
                    await shared_metadata.close()
            movie_metadata_state["lastSuccess"] = int(time.time())
        except Exception as exc:
            movie_metadata_state["lastError"] = str(exc)
            logger.exception("Movie metadata scrape failed for %s", source)
        finally:
            movie_metadata_state["running"] = False


@asynccontextmanager
async def lifespan(_: FastAPI):
    global movie_image_client
    await asyncio.to_thread(database.initialize)
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
        await source_registry.close()


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
    rootPath: str = Field(min_length=1, max_length=500)
    section: Literal["feed", "asmr", "movie"]
    scanMode: Literal["tree", "authors", "authors_recursive"] | None = None
    anonymous: bool = True
    token: str = Field(default="", max_length=2000)
    username: str = Field(default="", max_length=200)
    password: str = Field(default="", max_length=500)
    enabled: bool = True


def normalize_source_payload(payload: MediaSourceRequest) -> dict[str, Any]:
    base_url = payload.baseUrl.strip().rstrip("/")
    parsed = urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username:
        raise HTTPException(status_code=422, detail="AList 地址必须是有效的 HTTP(S) 地址")
    root_path = "/" + payload.rootPath.strip().strip("/")
    scan_mode = payload.scanMode or ("tree" if payload.section in {"feed", "movie"} else "authors_recursive")
    if payload.section in {"feed", "movie"} and scan_mode != "tree":
        raise HTTPException(status_code=422, detail="该板块来源必须使用递归目录扫描")
    if payload.section == "asmr" and scan_mode == "tree":
        raise HTTPException(status_code=422, detail="ASMR 来源必须使用作者目录扫描")
    return {
        "name": payload.name.strip(),
        "provider": payload.provider,
        "base_url": base_url,
        "root_path": root_path,
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
    return payload


def public_movie(row: dict[str, Any], *, detail: bool = False) -> dict[str, Any]:
    movie_id = int(row["id"])
    has_metadata_poster = bool(str(row.get("poster_url") or "").strip())
    has_thumb = bool(str(row.get("thumb") or "").strip())
    has_backdrop = bool(str(row.get("backdrop_url") or "").strip())
    poster_url = (
        f"/api/movies/{movie_id}/poster"
        if has_metadata_poster
        else f"/api/videos/{row['video_id']}/poster"
        if has_thumb
        else None
    )
    backdrop_url = f"/api/movies/{movie_id}/backdrop" if has_backdrop else None
    # JavBus cover_url is the primary landscape wall image. The tiny
    # thumb_url remains separately available as backdrop_url and is used
    # only as the blurred detail-page background.
    wall_url = poster_url or backdrop_url
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
        "posterUrl": poster_url,
        "backdropUrl": backdrop_url,
        # The wall is intentionally landscape-first. Keep posterUrl and
        # backdropUrl separate so detail screens retain their existing layout.
        "wallUrl": wall_url,
        "rating": row.get("rating"),
        "runtimeMinutes": row.get("runtime_minutes"),
        "matchStatus": row.get("match_status") or "pending",
        "matchConfidence": row.get("match_confidence"),
        "playUrl": f"/api/videos/{row['video_id']}/play",
        "modified": row.get("modified"),
        "duration": row.get("duration_seconds"),
    }
    if detail:
        payload["source"] = row.get("source")
        payload["path"] = row.get("path")
        payload["size"] = row.get("size")
        payload["format"] = row.get("media_format")
        payload["metadataProvider"] = row.get("metadata_provider")
        payload["releaseDate"] = row.get("release_date")
        payload["genres"] = _metadata_list(row.get("genres"))
        payload["performers"] = _metadata_list(row.get("performers"))
        payload["studio"] = row.get("studio")
    return payload


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


def _movie_image_cache_key(url: str) -> str:
    return hashlib.sha256(url.encode("utf-8")).hexdigest()


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
        "Cache-Control": f"private, max-age={MOVIE_IMAGE_CACHE_SECONDS}",
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


async def _proxy_movie_image(source_url: Any, if_none_match: str | None = None) -> Response:
    url = str(source_url or "").strip()
    try:
        parsed = urlsplit(url)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail="Movie image not found") from exc
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise HTTPException(status_code=404, detail="Movie image not found")

    with movie_image_cache_guard:
        lock = movie_image_cache_locks.setdefault(url, asyncio.Lock())
    async with lock:
        cached = await asyncio.to_thread(_read_movie_image_cache, url)
        if cached:
            content, media_type, etag = cached
            return _movie_image_response(
                content,
                media_type,
                etag,
                cache_status="hit",
                if_none_match=if_none_match,
            )

        client = movie_image_client
        owns_client = client is None
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
                    media_type = _image_media_type(
                        upstream.headers.get("content-type", ""),
                        content,
                    )
                    if not media_type:
                        raise HTTPException(status_code=502, detail="Movie image upstream returned non-image data")
            except HTTPException:
                raise
            except httpx.HTTPError as exc:
                logger.info("Movie image proxy failed for %s: %s", url, exc)
                raise HTTPException(status_code=502, detail="Movie image upstream unavailable") from exc
        finally:
            if owns_client:
                await client.aclose()

        etag = hashlib.sha256(content).hexdigest()
        await asyncio.to_thread(_write_movie_image_cache, url, content, media_type, etag)
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
    library, feed_library, asmr_library, movie_library, sources, summary, issues = await asyncio.gather(
        asyncio.to_thread(database.stats),
        asyncio.to_thread(database.stats, sources=feed_sources),
        asyncio.to_thread(database.stats, sources=asmr_sources),
        asyncio.to_thread(database.stats, sources=movie_sources),
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
    return {
        "library": {**library, "guangya": feed_library, "asmr": asmr_library, "movie": movie_library},
        "scan": dict(scan_state),
        "sources": [
            public_source(
                source,
                movie_metadata=movie_source_summaries.get(source["id"]),
            )
            for source in sources
        ],
        "metadata": dict(metadata_state),
        "movieMetadata": dict(movie_metadata_state),
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
    source_state = scan_state["sources"][source]
    started = not scan_locks[source].locked() and not source_state["running"]
    if started:
        source_state["running"] = True
        refresh_scan_summary()
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
async def start_movie_metadata(source_id: str, force: bool = False) -> dict[str, Any]:
    if source_id not in source_registry.ids("movie"):
        raise HTTPException(status_code=404, detail="电影媒体源不存在或已停用")
    if (
        not settings.tmdb_api_read_token
        and not settings.tmdb_api_key
        and not settings.shared_metadata_base_url
    ):
        raise HTTPException(status_code=503, detail="共享元数据接口与 TMDB 均未配置")
    if scan_locks.get(source_id) and scan_locks[source_id].locked():
        raise HTTPException(status_code=409, detail="媒体源正在扫描，请完成后再匹配封面")
    source_summary = await asyncio.to_thread(
        database.movie_metadata_summary,
        sources=(source_id,),
    )
    if not source_summary["total"]:
        raise HTTPException(status_code=409, detail="请先扫描电影媒体源")
    started = start_movie_metadata_job(source_id, force=force)
    return {"started": started, "movieMetadata": movie_metadata_state}


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


async def prewarm_movie_image(source_url: str | None) -> None:
    if not source_url:
        return
    try:
        await _proxy_movie_image(source_url)
    except Exception as exc:
        logger.warning("Movie image prewarm failed for %s: %s", source_url, exc)


async def prewarm_movie_wall_images(rows: list[dict[str, Any]], *, limit: int = 6) -> None:
    """Fill the persistent image cache for the first visible wall items."""
    semaphore = asyncio.Semaphore(3)

    async def warm(row: dict[str, Any]) -> None:
        async with semaphore:
            await prewarm_movie_image(
                row.get("backdrop_url") or row.get("poster_url") or row.get("thumb")
            )

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


@app.get("/api/movies")
async def movies(
    q: str = Query(default="", max_length=100),
    limit: int = Query(default=24, ge=1, le=60),
    offset: int = Query(default=0, ge=0),
    sort: str = Query(default="cover", pattern="^(cover|title)$"),
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
        ),
        asyncio.to_thread(database.movie_count, search=search, sources=sources),
    )
    if offset == 0 and rows:
        spawn_background(
            prewarm_movie_wall_images(rows),
            name="prewarm-movie-wall",
        )
    return {
        "items": [public_movie(row) for row in rows],
        "total": total,
        "nextOffset": offset + len(rows) if offset + len(rows) < total else None,
        "scan": {"running": any(scan_state["sources"].get(source, {}).get("running") for source in sources)},
    }


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
    return public_movie(row, detail=True)


async def _movie_image(movie_id: int, field: str, request: Request) -> Response:
    row = await asyncio.to_thread(
        database.get_movie,
        movie_id,
        sources=source_registry.ids("movie"),
    )
    if not row:
        raise HTTPException(status_code=404, detail="Movie not found")
    source_url = row.get(field)
    if field == "poster_url" and not source_url:
        source_url = row.get("thumb")
    if not source_url:
        raise HTTPException(status_code=404, detail="Movie image not found")
    return await _proxy_movie_image(source_url, request.headers.get("if-none-match"))


@app.get("/api/movies/{movie_id}/poster")
async def movie_poster(movie_id: int, request: Request) -> Response:
    return await _movie_image(movie_id, "poster_url", request)


@app.get("/api/movies/{movie_id}/backdrop")
async def movie_backdrop(movie_id: int, request: Request) -> Response:
    return await _movie_image(movie_id, "backdrop_url", request)


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
