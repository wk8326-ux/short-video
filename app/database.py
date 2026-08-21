from __future__ import annotations

import base64
import hashlib
import json
import os
import random
import secrets
import sqlite3
import threading
from collections.abc import Iterable, Sequence
from typing import Any


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
                    last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
            connection.commit()
            self._migrate_video_path_uniqueness(connection)
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
                """
            )

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
                last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
                fast_start, fast_start_checked_at, fast_start_detail, source,
                author, duration_seconds, media_format, media_kind,
                metadata_checked_at, metadata_detail
            )
            SELECT
                id, path, name, size, modified, thumb, active, last_seen,
                fast_start, fast_start_checked_at, fast_start_detail, source,
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
        count = 0
        scan_marker = secrets.token_hex(16)

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
                    active = 1,
                    last_seen = excluded.last_seen
                """,
                records(),
            )
            connection.execute(
                """
                UPDATE videos
                SET active = 0
                WHERE source = ? AND active = 1 AND last_seen != ?
                """,
                (source, scan_marker),
            )
            connection.commit()
        return count

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

    def movies(
        self,
        *,
        search: str = "",
        limit: int = 24,
        offset: int = 0,
        sources: Sequence[str] = (),
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
        params.extend([max(1, limit), max(0, offset)])
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT m.*, v.name, v.path, v.size, v.modified, v.duration_seconds,
                       v.thumb, v.media_format, v.media_kind
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                WHERE {' AND '.join(conditions)}
                ORDER BY m.display_title COLLATE NOCASE, m.year, m.id
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

    def movie_metadata_candidates(
        self,
        *,
        sources: Sequence[str] = (),
        force: bool = False,
    ) -> list[dict[str, Any]]:
        source_clause, source_params = self._sources_clause(sources)
        source_clause = source_clause.replace("source IN", "m.source IN")
        status_clause = "" if force else "AND m.match_status IN ('pending', 'ambiguous', 'unmatched')"
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT m.*
                FROM movies m
                JOIN videos v ON v.id = m.video_id
                WHERE v.active = 1 AND {source_clause} {status_clause}
                ORDER BY CASE m.match_status WHEN 'pending' THEN 0 ELSE 1 END, m.id
                """,
                source_params,
            ).fetchall()
        return [dict(row) for row in rows]

    def update_movie_metadata(self, movie_id: int, **values: Any) -> None:
        allowed = {
            "display_title", "normalized_title", "original_title", "year", "overview",
            "poster_url", "backdrop_url", "rating", "runtime_minutes", "tmdb_id",
            "match_status", "match_confidence",
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
            connection.executemany(
                """
                INSERT INTO media_sources(
                    id, name, provider, base_url, root_path, section, scan_mode,
                    anonymous, token, username, password, enabled
                )
                VALUES(
                    :id, :name, :provider, :base_url, :root_path, :section, :scan_mode,
                    :anonymous, :token, :username, :password, :enabled
                )
                ON CONFLICT(id) DO NOTHING
                """,
                [self._source_record(source) for source in records],
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
            connection.execute(
                """
                INSERT INTO media_sources(
                    id, name, provider, base_url, root_path, section, scan_mode,
                    anonymous, token, username, password, enabled
                )
                VALUES(
                    :id, :name, :provider, :base_url, :root_path, :section, :scan_mode,
                    :anonymous, :token, :username, :password, :enabled
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
                ORDER BY s.section, s.created_at, s.name COLLATE NOCASE
                """
            ).fetchall()
        return [self._normalize_source(row) for row in rows]

    def source_ids_for_section(self, section: str) -> tuple[str, ...]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT id FROM media_sources
                WHERE section = ? AND enabled = 1
                ORDER BY created_at, id
                """,
                (section,),
            ).fetchall()
        return tuple(str(row["id"]) for row in rows)

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
        return {
            "id": str(source["id"]),
            "name": str(source["name"]),
            "provider": str(source.get("provider") or "alist"),
            "base_url": str(source["base_url"]).rstrip("/"),
            "root_path": "/" + str(source["root_path"]).strip("/"),
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
                conditions.append("(duration_seconds < ? OR duration_seconds IS NULL)")
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
