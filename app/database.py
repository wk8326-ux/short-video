from __future__ import annotations

import base64
import hashlib
import json
import os
import random
import secrets
import sqlite3
import threading
import time
from collections.abc import Iterable, Sequence
from typing import Any


# A directory that keeps failing (AList hiccup, deleted path, oversized listing)
# is retried on later manual scans, but only this many times. Past that budget it
# is skipped for good so one bad directory can never stall an entire library.
SCAN_STEP_MAX_ATTEMPTS = 3

# A series' category is the first folder under the scan root ("短剧", "漫剧",
# "真人剧", …). Rows indexed before the column existed, or folders the
# downloader dropped straight under the root, have no category: they read as
# the short-drama label so the wall never shows an unnamed section. The client
# groups by name, so the label has to be the same wherever a listing is built.
DRAMA_CATEGORY_EXPR = "COALESCE(NULLIF(TRIM(d.category), ''), '短剧')"


def normalize_root_paths(value: Any, *, fallback: str = "/") -> list[str]:
    """Coerce every shape a root list arrives in into clean, unique paths.

    The management screen sends a list, a row written today carries a JSON
    string, and a row written before the column existed only has the single
    ``root_path``. All three end up as the same normalised list, so the
    scanner and the registry never have to ask which one they were handed.
    A pasted multi-line block is accepted too: it is how someone copies two
    folders out of the AList UI.
    """
    candidates: list[Any]
    if isinstance(value, str):
        text = value.strip()
        parsed: Any = None
        if text.startswith("["):
            try:
                parsed = json.loads(text)
            except ValueError:
                parsed = None
        candidates = parsed if isinstance(parsed, list) else text.splitlines()
    elif isinstance(value, (list, tuple, set)):
        candidates = list(value)
    else:
        candidates = []

    roots: list[str] = []
    for candidate in candidates:
        if not isinstance(candidate, str):
            continue
        # A blank entry is a field the caller left alone, not a folder: the
        # client posts both the list and the single legacy path, so one of the
        # two is always empty and must not turn into the server root.
        if not candidate.strip():
            continue
        root = "/" + candidate.strip().strip("/")
        if root not in roots:
            roots.append(root)
    if not roots:
        roots.append("/" + str(fallback).strip().strip("/"))
    return roots


class LibraryDatabase:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.RLock()

    def initialize(self) -> None:
        parent = os.path.dirname(self.path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with self._connect() as connection:
            connection.executescript(
                """
                PRAGMA journal_mode=WAL;
                PRAGMA synchronous=NORMAL;
                CREATE TABLE IF NOT EXISTS videos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL,
                    name TEXT NOT NULL,
                    size INTEGER NOT NULL DEFAULT 0,
                    modified TEXT,
                    thumb TEXT,
                    active INTEGER NOT NULL DEFAULT 1,
                    hidden INTEGER NOT NULL DEFAULT 0,
                    last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    scan_id TEXT,
                    fast_start TEXT,
                    fast_start_checked_at TEXT,
                    fast_start_detail TEXT,
                    source TEXT NOT NULL DEFAULT 'guangya',
                    author TEXT,
                    duration_seconds REAL,
                    media_format TEXT,
                    media_kind TEXT NOT NULL DEFAULT 'video',
                    metadata_checked_at TEXT,
                    metadata_detail TEXT,
                    UNIQUE(source, path)
                );
                """
            )
            columns = {
                str(row["name"])
                for row in connection.execute("PRAGMA table_info(videos)").fetchall()
            }
            if "fast_start" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN fast_start TEXT")
            if "fast_start_checked_at" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN fast_start_checked_at TEXT")
            if "fast_start_detail" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN fast_start_detail TEXT")
            if "source" not in columns:
                connection.execute(
                    "ALTER TABLE videos ADD COLUMN source TEXT NOT NULL DEFAULT 'guangya'"
                )
            if "author" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN author TEXT")
            if "duration_seconds" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN duration_seconds REAL")
            if "media_format" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN media_format TEXT")
            if "media_kind" not in columns:
                connection.execute(
                    "ALTER TABLE videos ADD COLUMN media_kind TEXT NOT NULL DEFAULT 'video'"
                )
            if "metadata_checked_at" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN metadata_checked_at TEXT")
            if "metadata_detail" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN metadata_detail TEXT")
            if "hidden" not in columns:
                connection.execute(
                    "ALTER TABLE videos ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0"
                )
            connection.commit()
            if "scan_id" not in columns:
                connection.execute("ALTER TABLE videos ADD COLUMN scan_id TEXT")
            self._migrate_video_path_uniqueness(connection)
            # Short-drama episodes stay ordinary ``videos`` rows so playback,
            # progress and prefetch are untouched; the series they belong to is
            # just a nullable pointer back into ``dramas``.
            video_columns = {
                str(row["name"])
                for row in connection.execute("PRAGMA table_info(videos)").fetchall()
            }
            if "series_id" not in video_columns:
                connection.execute("ALTER TABLE videos ADD COLUMN series_id TEXT")
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS media_scan_jobs (
                    id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    marker TEXT NOT NULL,
                    root_path TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    scan_mode TEXT NOT NULL,
                    section TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'queued',
                    error TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    completed_at TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_media_scan_jobs_source
                    ON media_scan_jobs(source, status, updated_at);
                CREATE TABLE IF NOT EXISTS media_scan_directories (
                    job_id TEXT NOT NULL,
                    kind TEXT NOT NULL DEFAULT 'dir',
                    path TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending',
                    attempts INTEGER NOT NULL DEFAULT 0,
                    items INTEGER NOT NULL DEFAULT 0,
                    error TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(job_id, kind, path),
                    FOREIGN KEY(job_id) REFERENCES media_scan_jobs(id) ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS idx_media_scan_directories_job
                    ON media_scan_directories(job_id, status, path);
            """);
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS media_sources (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    provider TEXT NOT NULL DEFAULT 'alist',
                    base_url TEXT NOT NULL,
                    root_path TEXT NOT NULL,
                    section TEXT NOT NULL,
                    scan_mode TEXT NOT NULL DEFAULT 'tree',
                    anonymous INTEGER NOT NULL DEFAULT 1,
                    token TEXT NOT NULL DEFAULT '',
                    username TEXT NOT NULL DEFAULT '',
                    password TEXT NOT NULL DEFAULT '',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    last_scan_success INTEGER,
                    last_scan_error TEXT,
                    directories INTEGER NOT NULL DEFAULT 0,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(base_url, root_path, section)
                );
                CREATE INDEX IF NOT EXISTS idx_media_sources_section
                    ON media_sources(section, enabled, name);
                CREATE INDEX IF NOT EXISTS idx_videos_active ON videos(active, id);
                CREATE INDEX IF NOT EXISTS idx_videos_modified ON videos(active, modified);
                CREATE INDEX IF NOT EXISTS idx_videos_source_active
                    ON videos(source, active, id);
                CREATE INDEX IF NOT EXISTS idx_videos_source_duration
                    ON videos(source, active, duration_seconds);
                CREATE INDEX IF NOT EXISTS idx_videos_author
                    ON videos(source, active, author, media_kind);
                CREATE TABLE IF NOT EXISTS movies (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    video_id INTEGER NOT NULL UNIQUE,
                    source TEXT NOT NULL,
                    display_title TEXT NOT NULL,
                    normalized_title TEXT NOT NULL,
                    original_title TEXT,
                    year INTEGER,
                    overview TEXT,
                    poster_url TEXT,
                    backdrop_url TEXT,
                    rating REAL,
                    runtime_minutes INTEGER,
                    tmdb_id INTEGER,
                    metadata_provider TEXT,
                    release_date TEXT,
                    genres TEXT,
                    performers TEXT,
                    studio TEXT,
                    match_status TEXT NOT NULL DEFAULT 'pending',
                    match_confidence REAL,
                    scraped_at TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE INDEX IF NOT EXISTS idx_movies_source_title
                    ON movies(source, display_title COLLATE NOCASE);
                CREATE INDEX IF NOT EXISTS idx_movies_match_status
                    ON movies(source, match_status, updated_at);
                CREATE INDEX IF NOT EXISTS idx_videos_series
                    ON videos(series_id, active, id);
                CREATE TABLE IF NOT EXISTS dramas (
                    id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    root_path TEXT NOT NULL,
                    folder TEXT NOT NULL,
                    title TEXT NOT NULL,
                    folder_title TEXT NOT NULL,
                    category TEXT,
                    code TEXT,
                    poster_url TEXT,
                    overview TEXT,
                    tags TEXT,
                    episode_count INTEGER NOT NULL DEFAULT 0,
                    metadata_status TEXT NOT NULL DEFAULT 'pending',
                    scraped_at TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(source, folder)
                );
                CREATE INDEX IF NOT EXISTS idx_dramas_source
                    ON dramas(source, title COLLATE NOCASE);
                CREATE TABLE IF NOT EXISTS deleted_media_sources (
                    id TEXT PRIMARY KEY,
                    deleted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                -- Where every device left off. One row per media row, keyed by
                -- the row the player actually reports, so "recently played"
                -- needs no per-device bookkeeping and survives a reinstall.
                CREATE TABLE IF NOT EXISTS watch_progress (
                    video_id INTEGER PRIMARY KEY,
                    position_ms INTEGER NOT NULL DEFAULT 0,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE INDEX IF NOT EXISTS idx_watch_progress_updated
                    ON watch_progress(updated_at DESC);
                -- Where every device hearted a title. It lives with the
                -- library rather than the phone for the same reason the
                -- playhead does: one account, one list, reinstall-proof.
                CREATE TABLE IF NOT EXISTS movie_favorites (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    video_id INTEGER NOT NULL UNIQUE,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE INDEX IF NOT EXISTS idx_movie_favorites_recent
                    ON movie_favorites(id DESC);
                -- A poster_url whose host answers 404/502 is not artwork: the
                -- wall paints the same empty placeholder for it as for a row
                -- with no cover at all. The proxy records every failure it
                -- meets here (and deletes the row on the first success), so
                -- "cover first" can rank a dead picture last without spending
                -- a network round trip inside the list query.
                CREATE TABLE IF NOT EXISTS movie_image_failures (
                    url TEXT PRIMARY KEY,
                    failed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_movie_image_failures_expiry
                    ON movie_image_failures(expires_at);
                """
            )
            self._migrate_media_source_roots(connection)
            self._migrate_media_source_order(connection)
            movie_columns = {
                str(row["name"])
                for row in connection.execute("PRAGMA table_info(movies)").fetchall()
            }
            for name, definition in (
                ("metadata_provider", "TEXT"),
                ("release_date", "TEXT"),
                ("genres", "TEXT"),
                ("performers", "TEXT"),
                ("studio", "TEXT"),
            ):
                if name not in movie_columns:
                    connection.execute(f"ALTER TABLE movies ADD COLUMN {name} {definition}")

    @staticmethod
    def _migrate_media_source_roots(connection: sqlite3.Connection) -> None:
        """Let one media source summarise several root folders.

        A library that is spread over more than one directory (an active
        downloader folder plus an archive, say) used to require one source per
        folder, which then showed up as unrelated sections on the wall. The
        table is rebuilt instead of altered because the single-root UNIQUE
        constraint no longer describes the data: two sources may share a first
        root as long as their full root lists differ. ``root_path`` keeps
        holding the first root so readers that predate this column, and rows
        that only ever had one, keep working unchanged.
        """
        columns = {
            str(row["name"])
            for row in connection.execute("PRAGMA table_info(media_sources)").fetchall()
        }
        if not columns or "root_paths" in columns:
            return
        rows = [
            dict(row)
            for row in connection.execute("SELECT * FROM media_sources").fetchall()
        ]
        connection.executescript(
            """
            ALTER TABLE media_sources RENAME TO media_sources_legacy;
            CREATE TABLE media_sources (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                provider TEXT NOT NULL DEFAULT 'alist',
                base_url TEXT NOT NULL,
                root_path TEXT NOT NULL,
                root_paths TEXT NOT NULL DEFAULT '[]',
                section TEXT NOT NULL,
                scan_mode TEXT NOT NULL DEFAULT 'tree',
                anonymous INTEGER NOT NULL DEFAULT 1,
                token TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL DEFAULT '',
                password TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                last_scan_success INTEGER,
                last_scan_error TEXT,
                directories INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(base_url, root_paths, section)
            );
            """
        )
        connection.executemany(
            """
            INSERT INTO media_sources(
                id, name, provider, base_url, root_path, root_paths, section,
                scan_mode, anonymous, token, username, password, enabled,
                last_scan_success, last_scan_error, directories,
                created_at, updated_at
            )
            VALUES(
                :id, :name, :provider, :base_url, :root_path, :root_paths, :section,
                :scan_mode, :anonymous, :token, :username, :password, :enabled,
                :last_scan_success, :last_scan_error, :directories,
                :created_at, :updated_at
            )
            """,
            [
                {**row, "root_paths": json.dumps([str(row["root_path"])])}
                for row in rows
            ],
        )
        connection.executescript(
            """
            DROP TABLE media_sources_legacy;
            CREATE INDEX IF NOT EXISTS idx_media_sources_section
                ON media_sources(section, enabled, name);
            """
        )

    @staticmethod
    def _migrate_media_source_order(connection: sqlite3.Connection) -> None:
        """Give every library a place in a user-controlled order.

        The management screen lets a library be dragged above another one, and
        the wall renders its sections in the same order, so the position has to
        outlive the process rather than being a client-side arrangement. New
        rows keep the column's default and sort last inside their section,
        which is where an added library used to land anyway.
        """
        columns = {
            str(row["name"])
            for row in connection.execute("PRAGMA table_info(media_sources)").fetchall()
        }
        if not columns or "sort_order" in columns:
            return
        connection.execute(
            "ALTER TABLE media_sources ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0"
        )
        # Backfill from the ordering the column replaces, so the first launch
        # after the upgrade shows exactly the list the user saw before it.
        rows = connection.execute(
            """
            SELECT id, section FROM media_sources
            ORDER BY section, created_at, rowid
            """
        ).fetchall()
        position: dict[str, int] = {}
        updates: list[tuple[int, str]] = []
        for row in rows:
            section = str(row["section"])
            index = position.get(section, 0)
            position[section] = index + 1
            updates.append((index, str(row["id"])))
        connection.executemany(
            "UPDATE media_sources SET sort_order = ? WHERE id = ?",
            updates,
        )
        connection.commit()

    @staticmethod
    def _migrate_video_path_uniqueness(connection: sqlite3.Connection) -> None:
        has_path_only_unique = False
        for index in connection.execute("PRAGMA index_list(videos)").fetchall():
            if not int(index["unique"]):
                continue
            columns = [
                str(row["name"])
                for row in connection.execute(
                    f"PRAGMA index_info('{index['name']}')"
                ).fetchall()
            ]
            if columns == ["path"]:
                has_path_only_unique = True
                break
        if not has_path_only_unique:
            return

        connection.executescript(
            """
            CREATE TABLE videos_source_scoped (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL,
                name TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                modified TEXT,
                thumb TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                hidden INTEGER NOT NULL DEFAULT 0,
                last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                scan_id TEXT,
                fast_start TEXT,
                fast_start_checked_at TEXT,
                fast_start_detail TEXT,
                source TEXT NOT NULL DEFAULT 'guangya',
                author TEXT,
                duration_seconds REAL,
                media_format TEXT,
                media_kind TEXT NOT NULL DEFAULT 'video',
                metadata_checked_at TEXT,
                metadata_detail TEXT,
                UNIQUE(source, path)
            );
            INSERT INTO videos_source_scoped(
                id, path, name, size, modified, thumb, active, last_seen,
                hidden, fast_start, fast_start_checked_at, fast_start_detail, source,
                author, duration_seconds, media_format, media_kind,
                metadata_checked_at, metadata_detail
            )
            SELECT
                id, path, name, size, modified, thumb, active, last_seen,
                hidden, fast_start, fast_start_checked_at, fast_start_detail, source,
                author, duration_seconds, media_format, media_kind,
                metadata_checked_at, metadata_detail
            FROM videos;
            DROP TABLE videos;
            ALTER TABLE videos_source_scoped RENAME TO videos;
            """
        )

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=10, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        return connection

    def replace_scan(self, videos: Iterable[dict[str, Any]], *, source: str = "guangya") -> int:
        """Atomically replace a source with a complete listing.

        Only valid when the caller already holds the *whole* listing in memory;
        streaming scans stage batches through :meth:`commit_scan_step` and call
        :meth:`finalize_scan` once every directory succeeded.
        """
        scan_marker = secrets.token_hex(16)
        return self._replace_scan_locked(
            videos, source=source, scan_marker=scan_marker, finalize=True
        )

    def _set_scan_id(
        self,
        connection: sqlite3.Connection,
        *,
        source: str,
        marker: str,
    ) -> None:
        connection.execute(
            "UPDATE videos SET scan_id = ? WHERE source = ? AND last_seen = ?",
            (marker, source, marker),
        )

    def finalize_scan(self, *, source: str, marker: str) -> int:
        """Retire media this source no longer reports after a complete scan."""
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            retired = self._finalize_locked(connection, source=source, marker=marker)
            connection.commit()
        return retired

    def _finalize_locked(
        self,
        connection: sqlite3.Connection,
        *,
        source: str,
        marker: str,
    ) -> int:
        self._set_scan_id(connection, source=source, marker=marker)
        cursor = connection.execute(
            """
            UPDATE videos
            SET active = 0
            WHERE source = ? AND active = 1 AND scan_id IS NOT ?
            """,
            (source, marker),
        )
        return int(cursor.rowcount)

    def abort_scan(self, *, source: str, marker: str) -> None:
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            self._set_scan_id(connection, source=source, marker=marker)
            connection.execute(
                """
                UPDATE videos
                SET active = 0, scan_id = NULL
                WHERE source = ? AND active = 1 AND scan_id = ?
                """,
                (source, marker),
            )
            connection.commit()

    def create_scan_job(
        self,
        *,
        source: str,
        root_path: str,
        base_url: str,
        scan_mode: str,
        section: str,
    ) -> dict[str, Any]:
        job_id = secrets.token_hex(12)
        marker = secrets.token_hex(16)
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                """
                UPDATE media_scan_jobs
                SET status = 'superseded', updated_at = CURRENT_TIMESTAMP
                WHERE source = ? AND status != 'superseded'
                """,
                (source,),
            )
            connection.execute(
                """
                DELETE FROM media_scan_directories
                WHERE job_id IN (
                    SELECT id FROM media_scan_jobs
                    WHERE source = ? AND status = 'superseded'
                )
                """,
                (source,),
            )
            connection.execute(
                """
                INSERT INTO media_scan_jobs(
                    id, source, marker, root_path, base_url, scan_mode, section, status
                )
                VALUES(?, ?, ?, ?, ?, ?, ?, 'queued')
                """,
                (job_id, source, marker, root_path, base_url, scan_mode, section),
            )
            connection.commit()
        return self.get_scan_job(job_id)

    def get_scan_job(self, job_id: str) -> dict[str, Any] | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM media_scan_jobs WHERE id = ?", (job_id,)
            ).fetchone()
        return dict(row) if row else None

    def latest_incomplete_scan_job(self, *, source: str) -> dict[str, Any] | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                """
                SELECT * FROM media_scan_jobs
                WHERE source = ? AND status IN ('queued', 'running', 'interrupted')
                ORDER BY created_at DESC, id DESC LIMIT 1
                """,
                (source,),
            ).fetchone()
        return dict(row) if row else None

    def latest_scan_job(self, *, source: str) -> dict[str, Any] | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                """
                SELECT * FROM media_scan_jobs
                WHERE source = ? AND status != 'superseded'
                ORDER BY created_at DESC, id DESC LIMIT 1
                """,
                (source,),
            ).fetchone()
        return dict(row) if row else None

    def reconcile_orphaned_scan_jobs(self) -> list[str]:
        """Reopen scans whose worker disappeared with the process.

        A scan only runs inside the application process, so a job still marked
        ``running`` at startup cannot have an owner any more. Leaving it that
        way made the management view show a scan that never advanced and could
        never finish. Marking it ``interrupted`` keeps the recorded progress and
        lets the next manual scan continue from the pending directories.
        """
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                """
                SELECT id FROM media_scan_jobs
                WHERE status = 'running'
                ORDER BY created_at
                """
            ).fetchall()
            job_ids = [str(row["id"]) for row in rows]
            if not job_ids:
                return []
            placeholders = ",".join("?" for _ in job_ids)
            connection.execute(
                f"""
                UPDATE media_scan_jobs
                SET status = 'interrupted',
                    error = COALESCE(error, '扫描进程重启，重新扫描即可续扫'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id IN ({placeholders})
                """,
                job_ids,
            )
            connection.execute(
                f"""
                UPDATE media_scan_directories
                SET status = 'pending', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'working' AND job_id IN ({placeholders})
                """,
                job_ids,
            )
            connection.commit()
        return job_ids

    def update_scan_job_status(
        self,
        job_id: str,
        status: str,
        *,
        error: str | None = None,
        count_attempt: bool = True,
    ) -> None:
        """Record the new status of a scan job."""
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                UPDATE media_scan_jobs
                SET status = ?, error = ?,
                    attempts = attempts + ?,
                    updated_at = CURRENT_TIMESTAMP,
                    completed_at = CASE
                        WHEN ? = 'completed' THEN CURRENT_TIMESTAMP
                        ELSE completed_at
                    END
                WHERE id = ?
                """,
                (status, error, 1 if count_attempt else 0, status, job_id),
            )

    def resume_scan_job(
        self, job_id: str, *, max_attempts: int = SCAN_STEP_MAX_ATTEMPTS
    ) -> int:
        """Requeue claimed and previously failed steps for a manual rescan.

        Failed steps are left alone while a run is in progress, so one bad
        directory ends the run instead of being retried in a tight loop; the
        next manual scan of the source requeues them here. Steps that already
        burned through their attempt budget stay failed: the scan then finishes
        with a warning instead of retrying the same directory forever.
        """
        with self._lock, self._connect() as connection:
            cursor = connection.execute(
                """
                UPDATE media_scan_directories
                SET status = 'pending', updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ?
                  AND (
                    status = 'working'
                    OR (status = 'failed' AND attempts < ?)
                  )
                """,
                (job_id, max(1, int(max_attempts))),
            )
        return int(cursor.rowcount)

    def exhausted_scan_step_paths(
        self, job_id: str, *, max_attempts: int = SCAN_STEP_MAX_ATTEMPTS
    ) -> list[str]:
        """Directories that failed often enough for us to stop retrying them."""
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT path FROM media_scan_directories
                WHERE job_id = ? AND status = 'failed' AND attempts >= ?
                ORDER BY path
                """,
                (job_id, max(1, int(max_attempts))),
            ).fetchall()
        return [str(row["path"]) for row in rows]

    def carry_over_scan_paths(
        self, *, source: str, marker: str, paths: Sequence[str]
    ) -> int:
        """Keep media under unreadable directories from being retired.

        A directory we could not list tells us nothing about its contents, so
        its already indexed media stays active instead of looking deleted.
        """
        prefixes = [str(path).rstrip("/") for path in paths if str(path).strip("/")]
        if not prefixes:
            return 0
        carried = 0
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            for prefix in prefixes:
                escaped = (
                    prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                )
                cursor = connection.execute(
                    """
                    UPDATE videos
                    SET scan_id = ?
                    WHERE source = ? AND active = 1
                      AND (path = ? OR path LIKE ? ESCAPE '\\')
                    """,
                    (marker, source, prefix, f"{escaped}/%"),
                )
                carried += int(cursor.rowcount)
            connection.commit()
        return carried

    def seed_scan_directories(self, job_id: str, steps: Sequence[tuple[str, str]]) -> int:
        records = [(job_id, kind, path) for kind, path in steps if path]
        if not records:
            return 0
        with self._lock, self._connect() as connection:
            connection.executemany(
                """
                INSERT INTO media_scan_directories(job_id, kind, path, status)
                VALUES(?, ?, ?, 'pending')
                ON CONFLICT(job_id, kind, path) DO UPDATE SET
                    status = CASE
                        WHEN media_scan_directories.status = 'completed'
                            THEN media_scan_directories.status
                        ELSE 'pending'
                    END,
                    updated_at = CURRENT_TIMESTAMP
                """,
                records,
            )
        return len(records)

    def next_scan_step(self, job_id: str) -> dict[str, Any] | None:
        """Claim the next pending step, never a step that already failed."""
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT * FROM media_scan_directories
                WHERE job_id = ? AND status = 'pending'
                ORDER BY path LIMIT 1
                """,
                (job_id,),
            ).fetchone()
            if not row:
                connection.commit()
                return None
            claimed = dict(row)
            connection.execute(
                """
                UPDATE media_scan_directories
                SET status = 'working', attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND kind = ? AND path = ?
                """,
                (job_id, claimed["kind"], claimed["path"]),
            )
            connection.commit()
        return claimed

    def commit_scan_step(
        self,
        job_id: str,
        *,
        source: str,
        marker: str,
        kind: str,
        path: str,
        videos: Iterable[dict[str, Any]] = (),
        steps: Sequence[tuple[str, str]] = (),
    ) -> int:
        """Persist one finished directory atomically.

        Writes the discovered media, enqueues child steps and marks the step
        complete in a single transaction, so a crash never loses progress and a
        resumed run never re-lists a directory that already committed cleanly.
        """
        count = 0

        def records():
            nonlocal count
            for video in videos:
                count += 1
                yield {
                    **video,
                    "name": str(video.get("name") or ""),
                    "size": int(video.get("size") or 0),
                    "modified": video.get("modified"),
                    "thumb": str(video.get("thumb") or ""),
                    "source": source,
                    "author": video.get("author"),
                    "duration_seconds": video.get("duration_seconds"),
                    "media_format": video.get("media_format"),
                    "media_kind": video.get("media_kind") or "video",
                    "scan_marker": marker,
                }

        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            rows = list(records())
            if rows:
                connection.executemany(
                    """
                    INSERT INTO videos(
                        path, name, size, modified, thumb, source, author,
                        duration_seconds, media_format, media_kind, active, last_seen
                    )
                    VALUES(
                        :path, :name, :size, :modified, :thumb, :source, :author,
                        :duration_seconds, :media_format, :media_kind, 1, :scan_marker
                    )
                    ON CONFLICT(source, path) DO UPDATE SET
                        name = excluded.name,
                        size = excluded.size,
                        modified = excluded.modified,
                        thumb = excluded.thumb,
                        author = excluded.author,
                        duration_seconds = COALESCE(
                            videos.duration_seconds, excluded.duration_seconds
                        ),
                        media_format = excluded.media_format,
                        media_kind = CASE
                            WHEN LOWER(COALESCE(excluded.media_format, '')) = 'm3u8' THEN 'video'
                            WHEN videos.metadata_checked_at IS NOT NULL THEN videos.media_kind
                            ELSE excluded.media_kind
                        END,
                        active = CASE WHEN videos.hidden = 1 THEN 0 ELSE 1 END,
                        last_seen = excluded.last_seen
                    """,
                    rows,
                )
                self._set_scan_id(connection, source=source, marker=marker)
            connection.executemany(
                """
                INSERT INTO media_scan_directories(job_id, kind, path, status)
                VALUES(?, ?, ?, 'pending')
                ON CONFLICT(job_id, kind, path) DO NOTHING
                """,
                [(job_id, step_kind, step_path) for step_kind, step_path in steps if step_path],
            )
            connection.execute(
                """
                UPDATE media_scan_directories
                SET status = 'completed', items = ?, error = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND kind = ? AND path = ?
                """,
                (count, job_id, kind, path),
            )
            connection.commit()
        return count

    def fail_scan_step(self, job_id: str, kind: str, path: str, error: str) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                UPDATE media_scan_directories
                SET status = 'failed', error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE job_id = ? AND kind = ? AND path = ?
                """,
                (error, job_id, kind, path),
            )

    def scan_step_progress(
        self,
        *,
        job_id: str,
        source: str,
        marker: str,
        max_attempts: int = SCAN_STEP_MAX_ATTEMPTS,
    ) -> dict[str, int]:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                """
                SELECT
                    (SELECT COUNT(*) FROM media_scan_directories WHERE job_id = ?) AS total,
                    (SELECT COUNT(*) FROM media_scan_directories WHERE job_id = ? AND status = 'completed') AS completed,
                    (SELECT COUNT(*) FROM media_scan_directories WHERE job_id = ? AND status = 'pending') AS pending,
                    (SELECT COUNT(*) FROM media_scan_directories WHERE job_id = ? AND status = 'failed') AS failed,
                    (SELECT COUNT(*) FROM media_scan_directories WHERE job_id = ? AND status = 'failed' AND attempts < ?) AS retryable,
                    (SELECT COUNT(*) FROM media_scan_directories WHERE job_id = ? AND status = 'failed' AND attempts >= ?) AS exhausted,
                    (SELECT COUNT(*) FROM videos WHERE source = ? AND last_seen = ?) AS files
                """,
                (
                    job_id,
                    job_id,
                    job_id,
                    job_id,
                    job_id,
                    max(1, int(max_attempts)),
                    job_id,
                    max(1, int(max_attempts)),
                    source,
                    marker,
                ),
            ).fetchone()
        return {
            "directories_total": int(row["total"]),
            "directories_completed": int(row["completed"]),
            "directories_pending": int(row["pending"]),
            "directories_failed": int(row["failed"]),
            "directories_retryable": int(row["retryable"]),
            "directories_exhausted": int(row["exhausted"]),
            "files_discovered": int(row["files"]),
        }

    def pending_scan_step_count(self, job_id: str) -> int:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                """
                SELECT COUNT(*) FROM media_scan_directories
                WHERE job_id = ? AND status IN ('pending', 'working', 'failed')
                """,
                (job_id,),
            ).fetchone()
        return int(row[0])

    def active_media_count(self, *, source: str) -> int:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT COUNT(*) FROM videos WHERE source = ? AND active = 1",
                (source,),
            ).fetchone()
        return int(row[0])

    def source_scan_states(self) -> dict[str, dict[str, Any]]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT * FROM media_scan_jobs
                WHERE status != 'superseded'
                ORDER BY created_at DESC, id DESC
                """
            ).fetchall()
        states: dict[str, dict[str, Any]] = {}
        for row in rows:
            source = str(row["source"])
            if source in states:
                continue
            states[source] = dict(row)
        return states


    def _replace_scan_locked(
        self,
        videos: Iterable[dict[str, Any]],
        *,
        source: str,
        scan_marker: str,
        commit: bool = True,
        finalize: bool = False,
    ) -> int:
        """Insert or refresh every record for one scan pass.

        ``last_seen`` holds ``scan_marker`` for every row touched by this pass, so
        callers can stage rows in batches. Batches never retire rows: only an
        explicit :meth:`finalize_scan` (or ``finalize=True`` here) may do that.
        """

        count = 0

        def records():
            nonlocal count
            for video in videos:
                count += 1
                yield {
                    **video,
                    "name": str(video.get("name") or ""),
                    "size": int(video.get("size") or 0),
                    "modified": video.get("modified"),
                    "thumb": str(video.get("thumb") or ""),
                    "source": source,
                    "author": video.get("author"),
                    "duration_seconds": video.get("duration_seconds"),
                    "media_format": video.get("media_format"),
                    "media_kind": video.get("media_kind") or "video",
                    "scan_marker": scan_marker,
                }

        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.executemany(
                """
                INSERT INTO videos(
                    path, name, size, modified, thumb, source, author,
                    duration_seconds, media_format, media_kind, active, last_seen
                )
                VALUES(
                    :path, :name, :size, :modified, :thumb, :source, :author,
                    :duration_seconds, :media_format, :media_kind, 1, :scan_marker
                )
                ON CONFLICT(source, path) DO UPDATE SET
                    name = excluded.name,
                    size = excluded.size,
                    modified = excluded.modified,
                    thumb = excluded.thumb,
                    author = excluded.author,
                    duration_seconds = COALESCE(videos.duration_seconds, excluded.duration_seconds),
                    media_format = excluded.media_format,
                    media_kind = CASE
                        WHEN LOWER(COALESCE(excluded.media_format, '')) = 'm3u8' THEN 'video'
                        WHEN videos.metadata_checked_at IS NOT NULL THEN videos.media_kind
                        ELSE excluded.media_kind
                    END,
                    active = CASE WHEN videos.hidden = 1 THEN 0 ELSE 1 END,
                    last_seen = excluded.last_seen
                """,
                records(),
            )
            if finalize:
                self._finalize_locked(connection, source=source, marker=scan_marker)
            else:
                self._set_scan_id(connection, source=source, marker=scan_marker)
            if commit:
                connection.commit()
        return count

    def hide_videos(self, video_ids: Sequence[int]) -> int:
        ids = sorted({int(video_id) for video_id in video_ids})
        if not ids:
            return 0
        placeholders = ",".join("?" for _ in ids)
        with self._lock, self._connect() as connection:
            cursor = connection.execute(
                f"""
                UPDATE videos
                SET active = 0, hidden = 1
                WHERE id IN ({placeholders})
                """,
                ids,
            )
            connection.execute(
                f"DELETE FROM movies WHERE video_id IN ({placeholders})",
                ids,
            )
            connection.commit()
        return cursor.rowcount

    def get_video(self, video_id: int) -> dict[str, Any] | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM videos WHERE id = ? AND active = 1", (video_id,)
            ).fetchone()
        return dict(row) if row else None

    def sync_movie_index(self, source: str, parser: Any) -> int:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT id, source, name
                FROM videos
                WHERE source = ? AND active = 1
                ORDER BY id
                """,
                (source,),
            ).fetchall()
            parsed_rows = []
            for row in rows:
                parsed = parser(str(row["name"]))
                parsed_rows.append(
                    {
                        "video_id": int(row["id"]),
                        "source": source,
                        "display_title": parsed.display_title,
                        "normalized_title": parsed.normalized_title,
                        "year": parsed.year,
                    }
                )
            connection.executemany(
                """
                INSERT INTO movies(video_id, source, display_title, normalized_title, year)
                VALUES(:video_id, :source, :display_title, :normalized_title, :year)
                ON CONFLICT(video_id) DO UPDATE SET
                    source = excluded.source,
                    display_title = CASE
                        WHEN movies.match_status IN ('matched', 'manual') THEN movies.display_title
                        ELSE excluded.display_title
                    END,
                    normalized_title = CASE
                        WHEN movies.match_status IN ('matched', 'manual') THEN movies.normalized_title
                        ELSE excluded.normalized_title
                    END,
                    year = CASE
                        WHEN movies.match_status IN ('matched', 'manual') THEN movies.year
                        ELSE excluded.year
                    END,
                    updated_at = CURRENT_TIMESTAMP
                """,
                parsed_rows,
            )
            connection.execute(
                """
                DELETE FROM movies
                WHERE source = ?
                  AND video_id NOT IN (
                      SELECT id FROM videos WHERE source = ? AND active = 1
                  )
                """,
                (source, source),
            )
            connection.commit()
        return len(rows)

    def sync_drama_index(
        self,
        source: str,
        root_paths: Sequence[str] | str,
        builder: Any,
    ) -> int:
        """Rebuild the series index for one short-drama source.

        The resumable scan has already put every episode into ``videos``; this
        only groups them into series, keeps each episode's ``series_id``
        pointer in sync, and drops series whose folder no longer holds an
        active episode. Artwork and synopses survive a rescan: re-indexing a
        folder is not a reason to re-fetch what the site already gave us. A
        source may summarise several roots; the builder is handed all of them
        and reports which one each series came from.
        """
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT id, source, path, name
                FROM videos
                WHERE source = ? AND active = 1
                ORDER BY id
                """,
                (source,),
            ).fetchall()
            series = builder(root_paths, [dict(row) for row in rows])
            # Drop every pointer first so episodes of a series that disappeared
            # cannot keep an orphan ``series_id`` alive.
            connection.execute(
                "UPDATE videos SET series_id = NULL WHERE source = ? AND series_id IS NOT NULL",
                (source,),
            )
            connection.executemany(
                """
                INSERT INTO dramas(
                    id, source, root_path, folder, title, folder_title, category,
                    code, episode_count
                )
                VALUES(
                    :id, :source, :root_path, :folder, :title, :folder_title,
                    :category, :code, :episode_count
                )
                ON CONFLICT(id) DO UPDATE SET
                    root_path = excluded.root_path,
                    folder = excluded.folder,
                    folder_title = excluded.folder_title,
                    category = excluded.category,
                    code = COALESCE(excluded.code, dramas.code),
                    -- Some downloader folders are named after the play button
                    -- ("▶ 立即观看"); a metadata pass replaces those titles with
                    -- the real one, and a rescan must not undo that.
                    title = CASE
                        WHEN dramas.metadata_status = 'matched' THEN dramas.title
                        ELSE excluded.title
                    END,
                    episode_count = excluded.episode_count,
                    updated_at = CURRENT_TIMESTAMP
                """,
                [
                    {
                        "id": str(entry["id"]),
                        "source": str(entry["source"]),
                        "root_path": str(entry["root_path"]),
                        "folder": str(entry["folder"]),
                        "title": str(entry["folder_title"]),
                        "folder_title": str(entry["folder_title"]),
                        "category": entry.get("category"),
                        "code": entry.get("code"),
                        "episode_count": int(entry["episode_count"]),
                    }
                    for entry in series
                ],
            )
            episodes = [
                {"series_id": str(entry["id"]), "video_id": int(episode["video_id"])}
                for entry in series
                for episode in entry["episodes"]
            ]
            if episodes:
                connection.executemany(
                    "UPDATE videos SET series_id = :series_id WHERE id = :video_id",
                    episodes,
                )
            drama_ids = [str(entry["id"]) for entry in series]
            if drama_ids:
                placeholders = ",".join("?" for _ in drama_ids)
                connection.execute(
                    f"DELETE FROM dramas WHERE source = ? AND id NOT IN ({placeholders})",
                    [source, *drama_ids],
                )
            else:
                connection.execute("DELETE FROM dramas WHERE source = ?", (source,))
            connection.commit()
        return len(series)

    def dramas(
        self,
        *,
        search: str = "",
        limit: int = 24,
        offset: int = 0,
        sources: Sequence[str] = (),
        category: str = "",
    ) -> list[dict[str, Any]]:
        conditions, params = self._drama_conditions(
            search=search,
            sources=sources,
            category=category,
        )
        cover_rank = "CASE WHEN LOWER(COALESCE(d.poster_url, '')) <> '' THEN 0 ELSE 1 END"
        params.extend([max(1, limit), max(0, offset)])
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT d.*
                FROM dramas d
                WHERE {' AND '.join(conditions)}
                ORDER BY {cover_rank}, d.title COLLATE NOCASE, d.id
                LIMIT ? OFFSET ?
                """,
                params,
            ).fetchall()
        return [dict(row) for row in rows]

    def drama_count(
        self,
        *,
        search: str = "",
        sources: Sequence[str] = (),
        category: str = "",
    ) -> int:
        conditions, params = self._drama_conditions(
            search=search,
            sources=sources,
            category=category,
        )
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"""
                SELECT COUNT(*) FROM dramas d
                WHERE {' AND '.join(conditions)}
                """,
                params,
            ).fetchone()
        return int(row[0] or 0)

    def drama_category_counts(
        self,
        *,
        search: str = "",
        sources: Sequence[str] = (),
    ) -> dict[str, int]:
        """Count the indexed series of every category in one query.

        The wall is grouped by the downloader's first-level folder, so it needs
        a per-category total before it can decide which sections to render.
        Ordering here (biggest library first, then by name) is what the wall
        shows, so the client never has to sort sections itself.
        """
        conditions, params = self._drama_conditions(search=search, sources=sources)
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT {DRAMA_CATEGORY_EXPR} AS category, COUNT(*) AS total
                FROM dramas d
                WHERE {' AND '.join(conditions)}
                GROUP BY category
                ORDER BY total DESC, category COLLATE NOCASE
                """,
                params,
            ).fetchall()
        return {str(row[0]): int(row[1] or 0) for row in rows}

    def _drama_conditions(
        self,
        *,
        search: str,
        sources: Sequence[str],
        category: str = "",
    ) -> tuple[list[str], list[Any]]:
        """Shared WHERE for every drama listing, so paging cannot drift.

        A folder with no active episode is not a browsable series: it would show
        up as an empty poster and only make the wall look broken.
        """
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "d.source IN")
        conditions = ["d.episode_count > 0", source_clause]
        params: list[Any] = list(source_params)
        if category:
            conditions.append(f"{DRAMA_CATEGORY_EXPR} = ?")
            params.append(category)
        if search:
            escaped = self._escape_like(search)
            conditions.append(
                "(d.title LIKE ? ESCAPE '\\' OR d.folder_title LIKE ? ESCAPE '\\'"
                " OR d.code LIKE ? ESCAPE '\\')"
            )
            params.extend([f"%{escaped}%"] * 3)
        return conditions, params

    def get_drama(self, drama_id: str, *, sources: Sequence[str] = ()) -> dict[str, Any] | None:
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "d.source IN")
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"""
                SELECT d.* FROM dramas d
                WHERE d.id = ? AND {source_clause}
                """,
                [drama_id, *source_params],
            ).fetchone()
        return dict(row) if row else None

    def drama_episodes(self, drama_id: str) -> list[dict[str, Any]]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT id AS video_id, name, path, size, modified, duration_seconds,
                       thumb, media_format, media_kind
                FROM videos
                WHERE series_id = ? AND active = 1
                """,
                (drama_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    def drama_metadata_candidates(
        self,
        *,
        sources: Sequence[str] = (),
        force: bool = False,
    ) -> list[dict[str, Any]]:
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "d.source IN")
        status_clause = (
            ""
            if force
            else "AND d.metadata_status IN ('pending', 'unmatched')"
        )
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT d.* FROM dramas d
                WHERE d.episode_count > 0 AND {source_clause} {status_clause}
                ORDER BY CASE d.metadata_status WHEN 'pending' THEN 0 ELSE 1 END, d.id
                """,
                source_params,
            ).fetchall()
        return [dict(row) for row in rows]

    def drama_metadata_summary(
        self,
        *,
        sources: Sequence[str] = (),
    ) -> dict[str, int | None]:
        if not sources:
            return {"total": 0, "pending": 0, "matched": 0, "unmatched": 0, "lastSuccess": None}
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "d.source IN")
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"""
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN d.metadata_status = 'pending' THEN 1 ELSE 0 END) AS pending,
                    SUM(CASE WHEN d.metadata_status = 'matched' THEN 1 ELSE 0 END) AS matched,
                    SUM(CASE WHEN d.metadata_status = 'unmatched' THEN 1 ELSE 0 END) AS unmatched,
                    MAX(CAST(strftime('%s', d.scraped_at) AS INTEGER)) AS last_success
                FROM dramas d
                WHERE d.episode_count > 0 AND {source_clause}
                """,
                source_params,
            ).fetchone()
        summary: dict[str, int | None] = {
            key: int(row[key] or 0)
            for key in ("total", "pending", "matched", "unmatched")
        }
        summary["lastSuccess"] = int(row["last_success"]) if row["last_success"] else None
        return summary

    def update_drama_metadata(self, drama_id: str, **values: Any) -> None:
        allowed = {"title", "overview", "tags", "poster_url", "code", "category", "metadata_status"}
        updates = {key: value for key, value in values.items() if key in allowed}
        if not updates:
            return
        updates["scraped_at"] = "CURRENT_TIMESTAMP"
        assignments = ", ".join(
            f"{key} = {value}" if value == "CURRENT_TIMESTAMP" else f"{key} = ?"
            for key, value in updates.items()
        )
        params = [value for value in updates.values() if value != "CURRENT_TIMESTAMP"]
        params.append(drama_id)
        with self._lock, self._connect() as connection:
            connection.execute(
                f"UPDATE dramas SET {assignments}, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                params,
            )
            connection.commit()

    def movies(
        self,
        *,
        search: str = "",
        limit: int = 24,
        offset: int = 0,
        sources: Sequence[str] = (),
        sort: str = "cover",
        direction: str = "",
    ) -> list[dict[str, Any]]:
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        conditions = ["v.active = 1", source_clause]
        params: list[Any] = list(source_params)
        if search:
            escaped = self._escape_like(search)
            conditions.append(
                "(m.display_title LIKE ? ESCAPE '\\' OR m.original_title LIKE ? ESCAPE '\\')"
            )
            params.extend([f"%{escaped}%", f"%{escaped}%"])
        has_poster = "(LOWER(COALESCE(m.poster_url, '')) <> '')"
        has_thumb = "(LOWER(COALESCE(v.thumb, '')) <> '')"
        has_backdrop = "(LOWER(COALESCE(m.backdrop_url, '')) <> '')"
        # The picture ``public_movie`` actually hands the client: the poster,
        # or the backdrop when the row has no poster. The ranking below and the
        # recorded failures have to agree on this one expression, or the order
        # describes a URL nobody ever asks for.
        effective_cover = (
            "(CASE WHEN TRIM(COALESCE(m.poster_url, '')) <> '' THEN m.poster_url "
            "ELSE m.backdrop_url END)"
        )
        # "Has a cover" means the client will actually be handed an image: the
        # shared catalogue writes poster_url, the older AList scan wrote thumb,
        # and a few rows only ever had a backdrop. Ranking on one of the three
        # alone left titles that do render a picture sitting behind titles that
        # do not.
        #
        # A row can also declare a cover whose host no longer serves it - a 404
        # or a 502 - and then the wall paints the very same empty placeholder it
        # paints for a row with no artwork at all. Ranking those two the same is
        # the whole point of "cover first": a library page that led with four
        # broken tiles was promising pictures that never arrive.
        declared_cover = f"({has_poster} OR {has_backdrop} OR {has_thumb})"
        cover_rank = (
            f"CASE WHEN ({declared_cover}) AND mif.url IS NULL THEN 0 ELSE 1 END"
        )
        descending = str(direction).lower() == "desc"
        ascending = str(direction).lower() == "asc"
        if sort == "title":
            # Tapping the same option again flips the arrow, so both directions
            # have to be expressible here; the secondary keys follow along so
            # the order is still total and stable.
            order = (
                "m.display_title COLLATE NOCASE DESC, m.year DESC, m.id DESC"
                if descending
                else "m.display_title COLLATE NOCASE, m.year, m.id"
            )
        elif sort == "time":
            # Newest first by default: a library page is browsed by what arrived
            # last, and v.modified is the downloader's own timestamp for the
            # file. "asc" asks for oldest first.
            order = (
                "v.modified ASC, m.id ASC"
                if ascending
                else "v.modified DESC, m.id DESC"
            )
        else:
            # "Cover first" has no meaningful reverse: the whole point of the
            # order is that artwork leads, so the direction is ignored here.
            order = f"{cover_rank}, m.display_title COLLATE NOCASE, m.year, m.id"
        params.extend([max(1, limit), max(0, offset)])
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT m.*, v.name, v.path, v.size, v.modified, v.duration_seconds,
                       v.thumb, v.media_format, v.media_kind
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                LEFT JOIN movie_image_failures mif
                    ON mif.url = {effective_cover}
                   AND mif.expires_at > CAST(strftime('%s', 'now') AS INTEGER)
                WHERE {' AND '.join(conditions)}
                ORDER BY {order}
                LIMIT ? OFFSET ?
                """,
                params,
            ).fetchall()
        return [dict(row) for row in rows]

    def movie_count(self, *, search: str = "", sources: Sequence[str] = ()) -> int:
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        conditions = ["v.active = 1", source_clause]
        params: list[Any] = list(source_params)
        if search:
            escaped = self._escape_like(search)
            conditions.append(
                "(m.display_title LIKE ? ESCAPE '\\' OR m.original_title LIKE ? ESCAPE '\\')"
            )
            params.extend([f"%{escaped}%", f"%{escaped}%"])
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"""
                SELECT COUNT(*)
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                WHERE {' AND '.join(conditions)}
                """,
                params,
            ).fetchone()
        return int(row[0] or 0)

    def movie_source_counts(
        self,
        *,
        search: str = "",
        sources: Sequence[str] = (),
    ) -> dict[str, int]:
        """Count the indexed movies of every media source in one query.

        The grouped wall needs a per-source total before it decides which
        sections to render, so counting per source in the client would mean
        one round trip per section.
        """
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        conditions = ["v.active = 1", source_clause]
        params: list[Any] = list(source_params)
        if search:
            escaped = self._escape_like(search)
            conditions.append(
                "(m.display_title LIKE ? ESCAPE '\\' OR m.original_title LIKE ? ESCAPE '\\')"
            )
            params.extend([f"%{escaped}%", f"%{escaped}%"])
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT m.source AS source, COUNT(*) AS total
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                WHERE {' AND '.join(conditions)}
                GROUP BY m.source
                """,
                params,
            ).fetchall()
        return {str(row["source"]): int(row["total"] or 0) for row in rows}

    def get_movie(self, movie_id: int, *, sources: Sequence[str] = ()) -> dict[str, Any] | None:
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"""
                SELECT m.*, v.name, v.path, v.size, v.modified, v.duration_seconds,
                       v.thumb, v.media_format, v.media_kind
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                WHERE m.id = ? AND v.active = 1 AND {source_clause}
                """,
                [movie_id, *source_params],
            ).fetchone()
        return dict(row) if row else None

    def set_movie_favorite(self, video_id: int, favorite: bool) -> bool:
        """Add or drop one title from the account-wide favourites list.

        One list for the account, kept beside the library rather than on the
        phone, so a second device and a reinstall both see the same hearts.
        """
        with self._lock, self._connect() as connection:
            if favorite:
                connection.execute(
                    """
                    INSERT INTO movie_favorites(video_id, created_at)
                    VALUES(?, CURRENT_TIMESTAMP)
                    ON CONFLICT(video_id) DO NOTHING
                    """,
                    (int(video_id),),
                )
            else:
                connection.execute(
                    "DELETE FROM movie_favorites WHERE video_id = ?",
                    (int(video_id),),
                )
            connection.commit()
        return bool(favorite)

    def is_movie_favorite(self, video_id: int) -> bool:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT 1 FROM movie_favorites WHERE video_id = ?",
                (int(video_id),),
            ).fetchone()
        return row is not None

    def favorite_movies(
        self,
        *,
        sources: Sequence[str] = (),
        limit: int = 24,
        offset: int = 0,
    ) -> list[dict[str, Any]]:
        """The hearted titles, most recently hearted first.

        The row shape matches :meth:`movies` so the favourites page draws it
        with the grid the library pages already use. Ordering is by the
        favourite's own row id rather than its timestamp: two hearts tapped in
        the same second still land in the order they were tapped, and the
        newest always leads.
        """
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT m.*, v.name, v.path, v.size, v.modified, v.duration_seconds,
                       v.thumb, v.media_format, v.media_kind,
                       f.created_at AS favorited_at
                FROM movie_favorites f
                JOIN movies m ON m.video_id = f.video_id
                JOIN videos v ON v.id = m.video_id
                WHERE v.active = 1 AND {source_clause}
                ORDER BY f.id DESC
                LIMIT ? OFFSET ?
                """,
                [*source_params, max(1, int(limit)), max(0, int(offset))],
            ).fetchall()
        return [dict(row) for row in rows]

    def favorite_movie_count(self, *, sources: Sequence[str] = ()) -> int:
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"""
                SELECT COUNT(*)
                FROM movie_favorites f
                JOIN movies m ON m.video_id = f.video_id
                JOIN videos v ON v.id = m.video_id
                WHERE v.active = 1 AND {source_clause}
                """,
                list(source_params),
            ).fetchone()
        return int(row[0] or 0)

    def remember_movie_image_failure(self, url: str, *, ttl_seconds: int) -> None:
        """Record that one artwork URL could not be fetched.

        The picture proxy answers 502 for a dead cover and the wall then draws
        an empty placeholder, so the ranking in :meth:`movies` needs to know
        which declared covers are actually dead. This is that ledger.
        """
        target = str(url or "").strip()
        if not target:
            return
        expires_at = int(time.time()) + max(60, int(ttl_seconds))
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO movie_image_failures(url, failed_at, expires_at)
                VALUES(?, CURRENT_TIMESTAMP, ?)
                ON CONFLICT(url) DO UPDATE SET
                    failed_at = CURRENT_TIMESTAMP,
                    expires_at = excluded.expires_at
                """,
                (target, expires_at),
            )
            connection.commit()

    def forget_movie_image_failure(self, url: str) -> None:
        """Drop one URL from the ledger: it served a picture after all."""
        target = str(url or "").strip()
        if not target:
            return
        with self._lock, self._connect() as connection:
            connection.execute(
                "DELETE FROM movie_image_failures WHERE url = ?",
                (target,),
            )
            connection.commit()

    def movie_image_failed_urls(self) -> set[str]:
        """Every unexpired failure, for priming the proxy's own memory."""
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT url FROM movie_image_failures
                WHERE expires_at > CAST(strftime('%s', 'now') AS INTEGER)
                """,
            ).fetchall()
        return {str(row["url"]) for row in rows}

    # A title counts as watched once the player reached the very end of it. The
    # margin absorbs the trailing seconds players routinely skip, and keeps a
    # finished title from parking itself at the front of "continue watching"
    # because the last report happened to be at 99% of the file.
    WATCH_COMPLETE_RATIO = 0.95

    def record_watch_progress(
        self,
        *,
        video_id: int,
        position_ms: int,
        duration_ms: int,
    ) -> dict[str, int]:
        """Store where one device left off, without touching playback.

        One row per media row: the new devices on the same account agree on a
        position instead of keeping parallel copies of it, and the row survives
        a reinstall because it lives with the library rather than the phone.
        """
        position = max(0, int(position_ms))
        duration = max(0, int(duration_ms))
        if duration > 0:
            position = min(position, duration)
        completed = int(
            duration > 0 and position >= duration * self.WATCH_COMPLETE_RATIO
        )
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                INSERT INTO watch_progress(
                    video_id, position_ms, duration_ms, completed, updated_at
                )
                VALUES(?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(video_id) DO UPDATE SET
                    position_ms = excluded.position_ms,
                    duration_ms = excluded.duration_ms,
                    completed = excluded.completed,
                    updated_at = CURRENT_TIMESTAMP
                """,
                (int(video_id), position, duration, completed),
            )
            connection.commit()
        return {
            "videoId": int(video_id),
            "positionMs": position,
            "durationMs": duration,
            "completed": completed,
        }

    def recent_movies(
        self,
        *,
        sources: Sequence[str] = (),
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        """The films the user is part-way through, most recent first.

        The row shape matches :meth:`movies` so the wall renders these tiles
        with the code it already has; the position rides along so tapping a
        tile resumes instead of restarting.
        """
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT m.*, v.name, v.path, v.size, v.modified, v.duration_seconds,
                       v.thumb, v.media_format, v.media_kind,
                       w.position_ms AS resume_position_ms,
                       w.duration_ms AS watched_duration_ms,
                       w.updated_at AS watched_at
                FROM watch_progress w
                JOIN movies m ON m.video_id = w.video_id
                JOIN videos v ON v.id = m.video_id
                WHERE w.completed = 0 AND v.active = 1 AND {source_clause}
                ORDER BY w.updated_at DESC, w.video_id DESC
                LIMIT ?
                """,
                [*source_params, max(1, int(limit))],
            ).fetchall()
        return [dict(row) for row in rows]

    def recent_dramas(
        self,
        *,
        sources: Sequence[str] = (),
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        """The series the user watched most recently, one row per series.

        Every episode shares its series id, so only the most recent position of
        each series is interesting: it names both the series to show and the
        episode to resume. A finished episode does *not* drop the series the way
        a finished film leaves ``recent_movies``: an episode of a short drama is
        minutes long, so watching one through is how a viewer normally leaves a
        show, and the strip is the row they come back to in order to carry on.
        The caller advances a finished episode to the next one; all this query
        has to report is which episode came last and whether it was seen
        through.
        """
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "v.source IN")
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                WITH ranked AS (
                    SELECT v.series_id AS series_id,
                           w.video_id AS video_id,
                           w.position_ms AS resume_position_ms,
                           w.duration_ms AS watched_duration_ms,
                           w.completed AS completed,
                           w.updated_at AS watched_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY v.series_id
                               ORDER BY w.updated_at DESC, w.video_id DESC
                           ) AS recency
                    FROM watch_progress w
                    JOIN videos v ON v.id = w.video_id
                    WHERE v.active = 1 AND v.series_id IS NOT NULL
                      AND {source_clause}
                )
                SELECT d.*, ranked.video_id AS resume_video_id,
                       ranked.resume_position_ms, ranked.watched_duration_ms,
                       ranked.completed AS resume_completed,
                       ranked.watched_at
                FROM ranked
                JOIN dramas d ON d.id = ranked.series_id
                WHERE ranked.recency = 1 AND d.episode_count > 0
                ORDER BY ranked.watched_at DESC, ranked.video_id DESC
                LIMIT ?
                """,
                [*source_params, max(1, int(limit))],
            ).fetchall()
        return [dict(row) for row in rows]

    def movie_metadata_candidates(
        self,
        *,
        sources: Sequence[str] = (),
        force: bool = False,
    ) -> list[dict[str, Any]]:
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        # A normal pass re-reads everything the catalogue has not resolved yet,
        # every row a retired scraper filled in, and every row the catalogue
        # matched but could not hand artwork to. That last group is the reason a
        # rescan has to be more than "retry the misses": the catalogue gains
        # covers for titles it already knows, and a row stamped matched carries
        # an empty poster that no amount of scanning would ever look at again.
        # Leaving the retired scrapers out of the pass is what let a stale
        # provider's cover sit in a library forever.
        status_clause = (
            ""
            if force
            else (
                "AND (m.match_status IN ('pending', 'ambiguous', 'unmatched')"
                " OR (m.match_status = 'matched' AND COALESCE(m.poster_url, '') = '')"
                " OR (m.metadata_provider IS NOT NULL AND m.metadata_provider <> 'shared'))"
            )
        )
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT m.*, v.name, v.path AS media_path
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                WHERE v.active = 1 AND {source_clause} {status_clause}
                ORDER BY CASE m.match_status WHEN 'pending' THEN 0 ELSE 1 END, m.id
                """,
                source_params,
            ).fetchall()
        return [dict(row) for row in rows]

    def movie_metadata_summary(
        self,
        *,
        sources: Sequence[str] = (),
    ) -> dict[str, int | None]:
        if not sources:
            return {
                "total": 0,
                "pending": 0,
                "matched": 0,
                "ambiguous": 0,
                "unmatched": 0,
                "lastSuccess": None,
            }
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"""
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN m.match_status = 'pending' THEN 1 ELSE 0 END) AS pending,
                    SUM(CASE WHEN m.match_status IN ('matched', 'manual') THEN 1 ELSE 0 END) AS matched,
                    SUM(CASE WHEN m.match_status = 'ambiguous' THEN 1 ELSE 0 END) AS ambiguous,
                    SUM(CASE WHEN m.match_status = 'unmatched' THEN 1 ELSE 0 END) AS unmatched,
                    MAX(CAST(strftime('%s', m.scraped_at) AS INTEGER)) AS last_success
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                WHERE v.active = 1 AND {source_clause}
                """,
                source_params,
            ).fetchone()
        summary: dict[str, int | None] = {
            key: int(row[key] or 0)
            for key in ("total", "pending", "matched", "ambiguous", "unmatched")
        }
        summary["lastSuccess"] = int(row["last_success"]) if row["last_success"] else None
        return summary

    def movie_metadata_pending_sources(self) -> list[str]:
        """Movie sources that still hold rows the cover pass has not resolved.

        The cover pass is single-flight, so a request that arrives while it runs
        is queued in memory. A restart loses that queue, and a library whose
        scan finished during the previous process is exactly the one that would
        stay coverless forever: the rows are already in the database, and
        nothing else in the app would ever look at them again.
        """
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT DISTINCT m.source AS source
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                WHERE v.active = 1
                  AND (
                      m.match_status IN ('pending', 'ambiguous')
                      OR (m.match_status = 'matched' AND COALESCE(m.poster_url, '') = '')
                  )
                ORDER BY m.source
                """
            ).fetchall()
        return [str(row["source"]) for row in rows]

    def update_movie_metadata(self, movie_id: int, **values: Any) -> None:
        allowed = {
            "display_title", "normalized_title", "original_title", "year", "overview",
            "poster_url", "backdrop_url", "rating", "runtime_minutes", "tmdb_id",
            "metadata_provider", "release_date",
            "genres", "performers", "studio", "match_status", "match_confidence",
        }
        updates = {key: value for key, value in values.items() if key in allowed}
        if not updates:
            return
        updates["scraped_at"] = "CURRENT_TIMESTAMP"
        assignments = ", ".join(
            f"{key} = {value}" if value == "CURRENT_TIMESTAMP" else f"{key} = ?"
            for key, value in updates.items()
        )
        params = [value for value in updates.values() if value != "CURRENT_TIMESTAMP"]
        params.append(movie_id)
        with self._lock, self._connect() as connection:
            connection.execute(
                f"UPDATE movies SET {assignments}, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                params,
            )

    def seed_media_sources(self, sources: Iterable[dict[str, Any]]) -> None:
        records = list(sources)
        with self._lock, self._connect() as connection:
            deleted_ids = {
                str(row["id"])
                for row in connection.execute("SELECT id FROM deleted_media_sources").fetchall()
            }
            connection.executemany(
                """
                INSERT INTO media_sources(
                    id, name, provider, base_url, root_path, root_paths, section,
                    scan_mode, anonymous, token, username, password, enabled
                )
                VALUES(
                    :id, :name, :provider, :base_url, :root_path, :root_paths, :section,
                    :scan_mode, :anonymous, :token, :username, :password, :enabled
                )
                ON CONFLICT(id) DO NOTHING
                """,
                [self._source_record(source) for source in records if source["id"] not in deleted_ids],
            )
            asmr6 = next((source for source in records if source["id"] == "asmr6"), None)
            if asmr6:
                root = "/" + str(asmr6["root_path"]).strip("/")
                connection.execute(
                    """
                    UPDATE videos
                    SET source = 'asmr6'
                    WHERE source = 'asmr' AND (path = ? OR path LIKE ?)
                    """,
                    (root, f"{root}/%"),
                )

    def add_media_source(self, source: dict[str, Any]) -> dict[str, Any]:
        record = self._source_record(
            {**source, "id": source.get("id") or f"source-{secrets.token_hex(6)}"}
        )
        with self._lock, self._connect() as connection:
            # A new library belongs at the end of its own section, not at the
            # top: ``sort_order`` defaults to zero, and every existing row has
            # been numbered, so the next free slot has to be asked for.
            row = connection.execute(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM media_sources WHERE section = ?",
                (record["section"],),
            ).fetchone()
            record["sort_order"] = int(row[0] or 0)
            connection.execute(
                """
                INSERT INTO media_sources(
                    id, name, provider, base_url, root_path, root_paths, section,
                    scan_mode, anonymous, token, username, password, enabled,
                    sort_order
                )
                VALUES(
                    :id, :name, :provider, :base_url, :root_path, :root_paths, :section,
                    :scan_mode, :anonymous, :token, :username, :password, :enabled,
                    :sort_order
                )
                """,
                record,
            )
        return self.get_media_source(str(record["id"])) or record

    def update_media_source(self, source_id: str, values: dict[str, Any]) -> dict[str, Any] | None:
        allowed = {
            "name",
            "provider",
            "base_url",
            "root_path",
            "root_paths",
            "section",
            "scan_mode",
            "anonymous",
            "token",
            "username",
            "password",
            "enabled",
        }
        updates = {key: value for key, value in values.items() if key in allowed}
        if not updates:
            return self.get_media_source(source_id)
        if "root_paths" in updates:
            roots = normalize_root_paths(updates["root_paths"])
            updates["root_paths"] = json.dumps(roots)
            # The first root is mirrored into the legacy column so nothing that
            # still reads a single path can disagree with the list.
            updates["root_path"] = roots[0]
        elif "root_path" in updates:
            # A caller that names a single folder means exactly that folder.
            # Keeping the old list around would leave the row describing a
            # library the user has since replaced, and the next scan would
            # resume the traversal of the folder that is no longer configured.
            roots = normalize_root_paths(updates["root_path"])
            updates["root_path"] = roots[0]
            updates["root_paths"] = json.dumps(roots)
        if "anonymous" in updates:
            updates["anonymous"] = int(bool(updates["anonymous"]))
        if "enabled" in updates:
            updates["enabled"] = int(bool(updates["enabled"]))
        assignments = ", ".join(f"{key} = ?" for key in updates)
        with self._lock, self._connect() as connection:
            connection.execute(
                f"UPDATE media_sources SET {assignments}, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                [*updates.values(), source_id],
            )
        return self.get_media_source(source_id)

    def delete_media_source(self, source_id: str) -> bool:
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            existing = connection.execute(
                "SELECT id FROM media_sources WHERE id = ?", (source_id,)
            ).fetchone()
            if not existing:
                connection.rollback()
                return False
            connection.execute("DELETE FROM movies WHERE source = ?", (source_id,))
            connection.execute("DELETE FROM dramas WHERE source = ?", (source_id,))
            # Watch history follows its rows out: a removed library must not
            # keep feeding "recently played" entries whose media is gone.
            connection.execute(
                """
                DELETE FROM watch_progress
                WHERE video_id IN (SELECT id FROM videos WHERE source = ?)
                """,
                (source_id,),
            )
            connection.execute("DELETE FROM videos WHERE source = ?", (source_id,))
            # A removed library must not leave its traversal history behind:
            # orphaned jobs kept reporting a half-finished scan for a source the
            # management screen no longer knows about.
            connection.execute(
                """
                DELETE FROM media_scan_directories
                WHERE job_id IN (SELECT id FROM media_scan_jobs WHERE source = ?)
                """,
                (source_id,),
            )
            connection.execute(
                "DELETE FROM media_scan_jobs WHERE source = ?", (source_id,)
            )
            connection.execute(
                "INSERT OR REPLACE INTO deleted_media_sources(id) VALUES(?)", (source_id,)
            )
            connection.execute("DELETE FROM media_sources WHERE id = ?", (source_id,))
            connection.commit()
        return True

    def get_media_source(self, source_id: str) -> dict[str, Any] | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM media_sources WHERE id = ?",
                (source_id,),
            ).fetchone()
        return self._normalize_source(row) if row else None

    def list_media_sources(self, *, include_disabled: bool = True) -> list[dict[str, Any]]:
        enabled_clause = "" if include_disabled else "WHERE s.enabled = 1"
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT s.*, COUNT(v.id) AS videos, COALESCE(SUM(v.size), 0) AS bytes
                FROM media_sources s
                LEFT JOIN videos v ON v.source = s.id AND v.active = 1
                {enabled_clause}
                GROUP BY s.id
                -- ``sort_order`` is the order the user dragged the libraries
                -- into; the timestamp and rowid only break ties for rows that
                -- never took part in a reorder, so sections keep the order
                -- they were added in until somebody changes it.
                ORDER BY s.section, s.sort_order, s.created_at, s.rowid
                """
            ).fetchall()
        return [self._normalize_source(row) for row in rows]

    def source_ids_for_section(self, section: str) -> tuple[str, ...]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT id FROM media_sources
                WHERE section = ? AND enabled = 1
                ORDER BY sort_order, created_at, rowid
                """,
                (section,),
            ).fetchall()
        return tuple(str(row["id"]) for row in rows)

    def reorder_media_sources(self, order: Sequence[str]) -> int:
        """Apply a dragged order and return how many rows moved.

        The list arrives from one screen that shows every section at once, so
        the position is assigned per section: dragging a library past one from
        another section must not silently move it between the wall tabs.
        Libraries the caller did not mention keep their relative order and
        follow the ones it did.
        """
        wanted = [str(source_id) for source_id in order]
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                "SELECT id, section, sort_order FROM media_sources"
            ).fetchall()
            by_id = {str(row["id"]): row for row in rows}
            positions: dict[str, int] = {}
            updates: list[tuple[int, str]] = []
            seen: set[str] = set()
            for source_id in wanted:
                row = by_id.get(source_id)
                if row is None or source_id in seen:
                    continue
                seen.add(source_id)
                section = str(row["section"])
                index = positions.get(section, 0)
                positions[section] = index + 1
                if int(row["sort_order"] or 0) != index:
                    updates.append((index, source_id))
            for row in rows:
                source_id = str(row["id"])
                if source_id in seen:
                    continue
                section = str(row["section"])
                index = positions.get(section, 0)
                positions[section] = index + 1
                if int(row["sort_order"] or 0) != index:
                    updates.append((index, source_id))
            if updates:
                connection.executemany(
                    "UPDATE media_sources SET sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    updates,
                )
                connection.commit()
        return len(updates)

    def update_source_scan_state(
        self,
        source_id: str,
        *,
        last_success: int | None,
        last_error: str | None,
        directories: int,
    ) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                UPDATE media_sources
                SET last_scan_success = ?, last_scan_error = ?, directories = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                (last_success, last_error, max(0, directories), source_id),
            )

    @staticmethod
    def _source_record(source: dict[str, Any]) -> dict[str, Any]:
        roots = normalize_root_paths(
            source.get("root_paths"),
            fallback=source.get("root_path") or "/",
        )
        return {
            "id": str(source["id"]),
            "name": str(source["name"]),
            "provider": str(source.get("provider") or "alist"),
            "base_url": str(source["base_url"]).rstrip("/"),
            "root_path": roots[0],
            "root_paths": json.dumps(roots),
            "section": str(source["section"]),
            "scan_mode": str(source.get("scan_mode") or "tree"),
            "anonymous": int(bool(source.get("anonymous", True))),
            "token": str(source.get("token") or ""),
            "username": str(source.get("username") or ""),
            "password": str(source.get("password") or ""),
            "enabled": int(bool(source.get("enabled", True))),
        }

    @staticmethod
    def _normalize_source(row: sqlite3.Row | dict[str, Any]) -> dict[str, Any]:
        source = dict(row)
        source["anonymous"] = bool(source.get("anonymous"))
        source["enabled"] = bool(source.get("enabled"))
        source["root_paths"] = normalize_root_paths(
            source.get("root_paths"),
            fallback=source.get("root_path") or "/",
        )
        if "videos" in source:
            source["videos"] = int(source["videos"] or 0)
            source["bytes"] = int(source["bytes"] or 0)
        return source

    def stats(
        self,
        *,
        source: str | None = None,
        sources: Sequence[str] | None = None,
    ) -> dict[str, int]:
        where = "WHERE active = 1"
        params: list[Any] = []
        selected_sources = tuple(sources) if sources is not None else ((source,) if source else ())
        if selected_sources:
            source_clause, source_params = self._sources_clause(selected_sources)
            where += f" AND {source_clause}"
            params.extend(source_params)
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"SELECT COUNT(*) AS active, COALESCE(SUM(size), 0) AS bytes FROM videos {where}",
                params,
            ).fetchone()
        return {"videos": int(row["active"]), "bytes": int(row["bytes"])}

    def fast_start_candidates(
        self,
        *,
        force: bool = False,
        sources: Sequence[str] = ("guangya",),
    ) -> list[dict[str, Any]]:
        condition = "" if force else "AND fast_start IS NULL"
        source_clause, source_params = self._sources_clause(sources)
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT id, path, name, source
                FROM videos
                WHERE active = 1
                  AND {source_clause}
                  AND (lower(name) LIKE '%.mp4' OR lower(name) LIKE '%.m4v' OR lower(name) LIKE '%.mov')
                  {condition}
                ORDER BY id
                """,
                source_params,
            ).fetchall()
        return [dict(row) for row in rows]

    def update_fast_start(self, video_id: int, status: str, detail: str) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                UPDATE videos
                SET fast_start = ?, fast_start_detail = ?, fast_start_checked_at = CURRENT_TIMESTAMP
                WHERE id = ? AND active = 1
                """,
                (status, detail[:240], video_id),
            )

    def fast_start_summary(self, *, sources: Sequence[str] = ("guangya",)) -> dict[str, int]:
        source_clause, source_params = self._sources_clause(sources)
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT fast_start, COUNT(*) AS count
                FROM videos
                WHERE active = 1
                  AND {source_clause}
                  AND (lower(name) LIKE '%.mp4' OR lower(name) LIKE '%.m4v' OR lower(name) LIKE '%.mov')
                GROUP BY fast_start
                """,
                source_params,
            ).fetchall()
        counts = {str(row["fast_start"]): int(row["count"]) for row in rows if row["fast_start"]}
        total = sum(int(row["count"]) for row in rows)
        checked = sum(counts.values())
        return {
            "total": total,
            "checked": checked,
            "optimized": counts.get("optimized", 0),
            "notOptimized": counts.get("not_optimized", 0),
            "inconclusive": counts.get("inconclusive", 0),
            "errors": counts.get("error", 0),
            "pending": total - checked,
        }

    def fast_start_issues(
        self,
        *,
        limit: int,
        sources: Sequence[str] = ("guangya",),
    ) -> list[dict[str, Any]]:
        source_clause, source_params = self._sources_clause(sources)
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT id, name, path, fast_start, fast_start_detail, fast_start_checked_at
                FROM videos
                WHERE active = 1
                  AND {source_clause}
                  AND fast_start IN ('not_optimized', 'inconclusive', 'error')
                ORDER BY CASE fast_start WHEN 'not_optimized' THEN 0 WHEN 'error' THEN 1 ELSE 2 END, id
                LIMIT ?
                """,
                [*source_params, limit],
            ).fetchall()
        return [dict(row) for row in rows]

    def feed(
        self,
        *,
        limit: int,
        cursor: str | None,
        mode: str,
        exclude_ids: set[int] | None = None,
        start_id: int | None = None,
        source: str = "guangya",
        sources: Sequence[str] | None = None,
        category: str | None = None,
        duration_boundary: float = 180.0,
    ) -> tuple[list[dict[str, Any]], str | None, int]:
        excluded = exclude_ids or set()
        selected_sources = tuple(sources) if sources is not None else (source,)
        scope = ",".join(sorted(selected_sources))
        filter_key = self._filter_key(excluded, start_id, f"{scope}:{category or 'all'}")
        seed, offset, cursor_mode, cursor_filter = self._decode_cursor(cursor)
        if cursor and (cursor_mode != mode or cursor_filter != filter_key):
            seed, offset = secrets.randbits(63), 0

        with self._lock, self._connect() as connection:
            source_clause, source_params = self._sources_clause(selected_sources)
            conditions = ["active = 1", source_clause]
            params: list[Any] = list(source_params)
            if category == "short":
                conditions.append("duration_seconds < ?")
                params.append(duration_boundary)
            elif category == "long":
                conditions.append("duration_seconds >= ?")
                params.append(duration_boundary)
            rows = [
                dict(row)
                for row in connection.execute(
                    f"SELECT * FROM videos WHERE {' AND '.join(conditions)} ORDER BY id",
                    params,
                ).fetchall()
            ]

        total = len(rows)
        filtered = [row for row in rows if row["id"] not in excluded or row["id"] == start_id]
        if not filtered:
            filtered = rows

        if mode == "newest":
            filtered.sort(key=lambda row: (row.get("modified") or "", row["id"]), reverse=True)
        elif mode == "oldest":
            filtered.sort(key=lambda row: (row.get("modified") or "", row["id"]))
        else:
            random.Random(seed).shuffle(filtered)

        if start_id is not None:
            for index, row in enumerate(filtered):
                if row["id"] == start_id:
                    filtered.insert(0, filtered.pop(index))
                    break

        page = filtered[offset : offset + limit]
        next_offset = offset + len(page)
        next_cursor = None
        if next_offset < len(filtered):
            next_cursor = self._encode_cursor(seed, next_offset, mode, filter_key)
        return page, next_cursor, total

    @staticmethod
    def _encode_cursor(seed: int, offset: int, mode: str, filter_key: str) -> str:
        payload = json.dumps(
            {"s": seed, "o": offset, "m": mode, "x": filter_key}, separators=(",", ":")
        )
        return base64.urlsafe_b64encode(payload.encode()).decode().rstrip("=")

    @staticmethod
    def _decode_cursor(cursor: str | None) -> tuple[int, int, str, str]:
        if not cursor:
            return secrets.randbits(63), 0, "shuffle", ""
        try:
            padded = cursor + "=" * (-len(cursor) % 4)
            data = json.loads(base64.urlsafe_b64decode(padded).decode())
            return (
                int(data["s"]),
                max(0, int(data["o"])),
                str(data["m"]),
                str(data.get("x") or ""),
            )
        except (ValueError, KeyError, TypeError, json.JSONDecodeError):
            return secrets.randbits(63), 0, "shuffle", ""

    @staticmethod
    def _filter_key(exclude_ids: set[int], start_id: int | None, scope: str = "") -> str:
        value = ",".join(str(video_id) for video_id in sorted(exclude_ids))
        digest = hashlib.blake2s(value.encode(), digest_size=6).hexdigest() if value else ""
        return f"{scope}:{digest}:{start_id or ''}"

    def duration_candidates(
        self,
        *,
        force: bool = False,
        limit: int = 30,
        sources: Sequence[str] = ("guangya",),
    ) -> list[dict[str, Any]]:
        retry_condition = "" if force else (
            "AND (metadata_checked_at IS NULL "
            "OR metadata_checked_at < datetime('now', '-1 day'))"
        )
        source_clause, source_params = self._sources_clause(sources)
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT id, path, name, size, source
                FROM videos
                WHERE active = 1
                  AND {source_clause}
                  AND duration_seconds IS NULL
                  AND (lower(name) LIKE '%.mp4' OR lower(name) LIKE '%.m4v' OR lower(name) LIKE '%.mov')
                  {retry_condition}
                ORDER BY size DESC, id
                LIMIT ?
                """,
                [*source_params, max(1, limit)],
            ).fetchall()
        return [dict(row) for row in rows]

    def update_media_metadata(
        self,
        video_id: int,
        *,
        duration_seconds: float | None = None,
        media_kind: str | None = None,
        detail: str = "browser",
    ) -> None:
        assignments = ["metadata_checked_at = CURRENT_TIMESTAMP", "metadata_detail = ?"]
        params: list[Any] = [detail[:240]]
        if duration_seconds is not None and duration_seconds > 0:
            assignments.append("duration_seconds = ?")
            params.append(float(duration_seconds))
        if media_kind in {"audio", "video"}:
            assignments.append(
                "media_kind = CASE "
                "WHEN LOWER(COALESCE(media_format, '')) = 'm3u8' THEN 'video' "
                "ELSE ? END"
            )
            params.append(media_kind)
        params.append(video_id)
        with self._lock, self._connect() as connection:
            connection.execute(
                f"UPDATE videos SET {', '.join(assignments)} WHERE id = ? AND active = 1",
                params,
            )

    def asmr_authors(
        self,
        *,
        search: str = "",
        limit: int | None = None,
        offset: int = 0,
        sources: Sequence[str] = ("asmr",),
    ) -> list[dict[str, Any]]:
        source_clause, source_params = self._sources_clause(sources)
        conditions = ["active = 1", source_clause, "author IS NOT NULL"]
        params: list[Any] = list(source_params)
        if search:
            conditions.append("author LIKE ? ESCAPE '\\'")
            params.append(f"%{self._escape_like(search)}%")
        with self._lock, self._connect() as connection:
            pagination = ""
            query_params = list(params)
            if limit is not None:
                pagination = " LIMIT ? OFFSET ?"
                query_params.extend([limit, offset])
            rows = connection.execute(
                f"""
                SELECT author,
                       COUNT(*) AS item_count,
                       SUM(CASE
                           WHEN LOWER(COALESCE(media_format, '')) = 'm3u8'
                                OR media_kind = 'video' THEN 1 ELSE 0
                       END) AS video_count,
                       SUM(CASE
                           WHEN LOWER(COALESCE(media_format, '')) != 'm3u8'
                                AND media_kind = 'audio' THEN 1 ELSE 0
                       END) AS audio_count,
                       MAX(modified) AS modified
                FROM videos
                WHERE {' AND '.join(conditions)}
                GROUP BY author
                ORDER BY author COLLATE NOCASE{pagination}
                """,
                query_params,
            ).fetchall()
        return [dict(row) for row in rows]

    def asmr_author_count(
        self,
        *,
        search: str = "",
        sources: Sequence[str] = ("asmr",),
    ) -> int:
        source_clause, source_params = self._sources_clause(sources)
        conditions = ["active = 1", source_clause, "author IS NOT NULL"]
        params: list[Any] = list(source_params)
        if search:
            conditions.append("author LIKE ? ESCAPE '\\'")
            params.append(f"%{self._escape_like(search)}%")
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"SELECT COUNT(DISTINCT author) FROM videos WHERE {' AND '.join(conditions)}",
                params,
            ).fetchone()
        return int(row[0] or 0)

    def asmr_items(
        self,
        *,
        author: str,
        kind: str = "all",
        search: str = "",
        limit: int | None = None,
        offset: int = 0,
        sources: Sequence[str] = ("asmr",),
    ) -> list[dict[str, Any]]:
        source_clause, source_params = self._sources_clause(sources)
        conditions = ["active = 1", source_clause, "author = ?"]
        params: list[Any] = [*source_params, author]
        if kind == "video":
            conditions.append(
                "(LOWER(COALESCE(media_format, '')) = 'm3u8' OR media_kind = 'video')"
            )
        elif kind == "audio":
            conditions.append(
                "LOWER(COALESCE(media_format, '')) != 'm3u8' AND media_kind = 'audio'"
            )
        if search:
            conditions.append("name LIKE ? ESCAPE '\\'")
            params.append(f"%{self._escape_like(search)}%")
        with self._lock, self._connect() as connection:
            pagination = ""
            query_params = list(params)
            if limit is not None:
                pagination = " LIMIT ? OFFSET ?"
                query_params.extend([limit, offset])
            rows = connection.execute(
                f"""
                SELECT *
                FROM videos
                WHERE {' AND '.join(conditions)}
                ORDER BY name COLLATE NOCASE, id{pagination}
                """,
                query_params,
            ).fetchall()
        return [dict(row) for row in rows]

    def asmr_item_count(
        self,
        *,
        author: str,
        kind: str = "all",
        search: str = "",
        sources: Sequence[str] = ("asmr",),
    ) -> int:
        source_clause, source_params = self._sources_clause(sources)
        conditions = ["active = 1", source_clause, "author = ?"]
        params: list[Any] = [*source_params, author]
        if kind == "video":
            conditions.append(
                "(LOWER(COALESCE(media_format, '')) = 'm3u8' OR media_kind = 'video')"
            )
        elif kind == "audio":
            conditions.append(
                "LOWER(COALESCE(media_format, '')) != 'm3u8' AND media_kind = 'audio'"
            )
        if search:
            conditions.append("name LIKE ? ESCAPE '\\'")
            params.append(f"%{self._escape_like(search)}%")
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"SELECT COUNT(*) FROM videos WHERE {' AND '.join(conditions)}",
                params,
            ).fetchone()
        return int(row[0] or 0)

    @staticmethod
    def _sources_clause(sources: Sequence[str]) -> tuple[str, list[str]]:
        selected = tuple(dict.fromkeys(str(source) for source in sources if source))
        if not selected:
            return "0 = 1", []
        placeholders = ", ".join("?" for _ in selected)
        return f"source IN ({placeholders})", list(selected)

    @staticmethod
    def _escape_like(value: str) -> str:
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
