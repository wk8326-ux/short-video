from __future__ import annotations

import asyncio
import logging
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from app.alist import AListClient, AListError
from app.auth import LoginRateLimiter, SESSION_COOKIE, SessionManager, verify_password
from app.database import LibraryDatabase
from app.direct_urls import DirectUrlCache
from app.faststart import inspect_mp4_prefix
from app.settings import Settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("short-video")

APP_VERSION = "0.3.1"
settings = Settings.from_env()
settings.validate()
database = LibraryDatabase(settings.database_path)
alist = AListClient(settings)
direct_urls = DirectUrlCache(alist.resolve, settings.direct_url_cache_seconds)
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
}
scan_lock = asyncio.Lock()
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


async def scan_library() -> None:
    if scan_lock.locked():
        return
    async with scan_lock:
        scan_state["running"] = True
        try:
            videos, directories = await alist.scan()
            count = await asyncio.to_thread(database.replace_scan, videos)
            scan_state.update(
                {
                    "lastSuccess": int(time.time()),
                    "lastError": None,
                    "directories": directories,
                }
            )
            direct_urls.clear()
            logger.info("Indexed %s videos across %s directories", count, directories)
        except Exception as exc:
            scan_state["lastError"] = str(exc)
            logger.exception("Library scan failed")
        finally:
            scan_state["running"] = False


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
            videos = await asyncio.to_thread(database.fast_start_candidates, force=force)
            fast_start_state["total"] = len(videos)
            semaphore = asyncio.Semaphore(3)

            async def inspect(video: dict[str, Any]) -> None:
                status = "error"
                detail = "check failed"
                try:
                    async with semaphore:
                        prefix = await alist.read_prefix(video["path"], max_bytes=256 * 1024)
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


@asynccontextmanager
async def lifespan(_: FastAPI):
    await asyncio.to_thread(database.initialize)
    stop = asyncio.Event()
    worker = asyncio.create_task(scan_loop(stop), name="library-scan")
    yield
    stop.set()
    worker.cancel()
    await asyncio.gather(worker, return_exceptions=True)
    for task in list(background_tasks):
        task.cancel()
    await asyncio.gather(*background_tasks, return_exceptions=True)
    await alist.close()


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
        "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
    )
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    return response


class LoginRequest(BaseModel):
    password: str = Field(min_length=1, max_length=256)


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


@app.get("/api/admin/status")
async def admin_status() -> dict[str, Any]:
    library, summary, issues = await asyncio.gather(
        asyncio.to_thread(database.stats),
        asyncio.to_thread(database.fast_start_summary),
        asyncio.to_thread(database.fast_start_issues, limit=20),
    )
    return {
        "library": library,
        "scan": dict(scan_state),
        "fastStart": {**fast_start_state, "summary": summary},
        "issues": issues,
    }


@app.post("/api/admin/scan", status_code=202)
async def start_scan() -> dict[str, Any]:
    started = not scan_lock.locked() and not scan_state["running"]
    if started:
        scan_state["running"] = True
        spawn_background(scan_library(), name="manual-library-scan")
    return {"started": started, "scan": scan_state}


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
        await direct_urls.get(video["id"], video["path"])
    except Exception as exc:
        logger.warning("Direct URL prewarm failed for video %s: %s", video["id"], exc)


@app.get("/api/feed")
async def feed(
    limit: int = Query(default=12, ge=1, le=30),
    cursor: str | None = Query(default=None, max_length=256),
    mode: str = Query(default="shuffle", pattern="^(shuffle|newest|oldest)$"),
    exclude: str | None = Query(default=None, max_length=1024),
    start: int | None = Query(default=None, ge=1),
) -> dict[str, Any]:
    rows, next_cursor, total = await asyncio.to_thread(
        database.feed,
        limit=limit,
        cursor=cursor,
        mode=mode,
        exclude_ids=parse_excluded_ids(exclude),
        start_id=start,
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
        raw_url, requires_headers = await direct_urls.get(
            video_id,
            video["path"],
            refresh=refresh,
        )
    except AListError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    if requires_headers:
        raise HTTPException(status_code=422, detail="This media source requires proxy headers")

    return RedirectResponse(
        raw_url,
        status_code=302,
        headers={"Cache-Control": "no-store", "X-Robots-Tag": "noindex"},
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
