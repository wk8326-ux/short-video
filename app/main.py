from __future__ import annotations

import asyncio
import logging
import sqlite3
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from app.alist import AListError
from app.app_update import AppUpdateStore, InvalidUpdateManifest, UpdateNotPublished
from app.auth import LoginRateLimiter, SESSION_COOKIE, SessionManager, verify_password
from app.database import LibraryDatabase
from app.faststart import inspect_mp4_prefix
from app.media_metadata import mp4_duration_seconds
from app.media_sources import MediaSourceRegistry
from app.settings import Settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
logger = logging.getLogger("short-video")

APP_VERSION = "1.4.1"
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
fast_start_state: dict[str, Any] = {
    "running": False,
    "checked": 0,
    "total": 0,
    "lastSuccess": None,
    "lastError": None,
}
fast_start_lock = asyncio.Lock()
background_tasks: set[asyncio.Task[Any]] = set()


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


def refresh_source_states() -> None:
    current_ids: set[str] = set()
    for source in database.list_media_sources():
        source_id = str(source["id"])
        current_ids.add(source_id)
        previous = scan_state["sources"].get(source_id, {})
        scan_state["sources"][source_id] = {
            "running": bool(previous.get("running", False)),
            "lastSuccess": source.get("last_scan_success"),
            "lastError": source.get("last_scan_error"),
            "directories": int(source.get("directories") or 0),
        }
        scan_locks.setdefault(source_id, asyncio.Lock())
    for source_id in set(scan_state["sources"]) - current_ids:
        scan_state["sources"].pop(source_id, None)
        scan_locks.pop(source_id, None)
    refresh_scan_summary()


async def scan_source(source: str) -> bool:
    try:
        runtime = source_registry.get(source)
    except KeyError:
        return False
    lock = scan_locks.setdefault(source, asyncio.Lock())
    if lock.locked():
        return False
    async with lock:
        source_state = scan_state["sources"].setdefault(
            source,
            {"running": False, "lastSuccess": None, "lastError": None, "directories": 0},
        )
        source_state["running"] = True
        refresh_scan_summary()
        try:
            scan_mode = runtime.config["scan_mode"]
            if scan_mode in {"authors", "authors_recursive"}:
                videos, directories = await runtime.client.scan_authors(
                    search_paths=(runtime.config["root_path"],)
                    if scan_mode == "authors_recursive"
                    else (),
                    author_group_paths=settings.asmr_author_group_paths
                    if source == "asmr"
                    else frozenset(),
                    search_result_limit=settings.asmr_search_result_limit,
                )
            else:
                videos, directories = await runtime.client.scan()
            count = await asyncio.to_thread(
                database.replace_scan,
                videos,
                source=source,
            )
            now = int(time.time())
            source_state.update(
                {
                    "lastSuccess": now,
                    "lastError": None,
                    "directories": directories,
                }
            )
            await asyncio.to_thread(
                database.update_source_scan_state,
                source,
                last_success=now,
                last_error=None,
                directories=directories,
            )
            runtime.direct_urls.clear()
            logger.info(
                "Indexed %s %s media items across %s directories",
                count,
                source,
                directories,
            )
            if runtime.config["section"] == "feed" and not metadata_lock.locked():
                spawn_background(
                    check_media_metadata(force=False),
                    name="media-metadata-check",
                )
            return True
        except Exception as exc:
            source_state["lastError"] = str(exc)
            await asyncio.to_thread(
                database.update_source_scan_state,
                source,
                last_success=source_state["lastSuccess"],
                last_error=str(exc),
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


async def scan_loop(stop: asyncio.Event) -> None:
    while not stop.is_set():
        await scan_library()
        try:
            await asyncio.wait_for(stop.wait(), timeout=settings.scan_interval_seconds)
        except TimeoutError:
            continue


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
            metadata_state["total"] = len(videos)
            semaphore = asyncio.Semaphore(3)

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

            await asyncio.gather(*(inspect(video) for video in videos))
            metadata_state["lastSuccess"] = int(time.time())
            logger.info("Checked media duration for %s videos", len(videos))
        except Exception as exc:
            metadata_state["lastError"] = str(exc)
            logger.exception("Media metadata check failed")
        finally:
            metadata_state["running"] = False


@asynccontextmanager
async def lifespan(_: FastAPI):
    await asyncio.to_thread(database.initialize)
    await source_registry.initialize()
    await asyncio.to_thread(refresh_source_states)
    stop = asyncio.Event()
    worker = asyncio.create_task(scan_loop(stop), name="library-scan")
    yield
    stop.set()
    worker.cancel()
    await asyncio.gather(worker, return_exceptions=True)
    for task in list(background_tasks):
        task.cancel()
    await asyncio.gather(*background_tasks, return_exceptions=True)
    await source_registry.close()


app = FastAPI(
    title="Private short-video player",
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
    section: Literal["feed", "asmr"]
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
    scan_mode = payload.scanMode or ("tree" if payload.section == "feed" else "authors_recursive")
    if payload.section == "feed" and scan_mode != "tree":
        raise HTTPException(status_code=422, detail="短视频/长视频来源必须使用递归目录扫描")
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


def public_source(source: dict[str, Any]) -> dict[str, Any]:
    state = scan_state["sources"].get(
        source["id"],
        {"running": False, "lastSuccess": None, "lastError": None, "directories": 0},
    )
    return {
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
    library, feed_library, asmr_library, sources, summary, issues = await asyncio.gather(
        asyncio.to_thread(database.stats),
        asyncio.to_thread(database.stats, sources=feed_sources),
        asyncio.to_thread(database.stats, sources=asmr_sources),
        asyncio.to_thread(database.list_media_sources),
        asyncio.to_thread(database.fast_start_summary, sources=feed_sources),
        asyncio.to_thread(database.fast_start_issues, limit=20, sources=feed_sources),
    )
    return {
        "library": {**library, "guangya": feed_library, "asmr": asmr_library},
        "scan": dict(scan_state),
        "sources": [public_source(source) for source in sources],
        "metadata": dict(metadata_state),
        "fastStart": {**fast_start_state, "summary": summary},
        "issues": issues,
    }


@app.post("/api/admin/scan", status_code=202)
async def start_scan(
    source: str = Query(default="guangya", min_length=1, max_length=80),
) -> dict[str, Any]:
    if source not in source_registry.ids():
        raise HTTPException(status_code=404, detail="媒体源不存在或已停用")
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


@app.post("/api/admin/sources/{source_id}/scan", status_code=202)
async def scan_one_source(source_id: str) -> dict[str, Any]:
    return await start_scan(source=source_id)


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
