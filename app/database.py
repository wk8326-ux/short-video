from __future__ import annotations

import base64
import hashlib
import json
import os
import random
import secrets
import sqlite3
import threading
from collections.abc import Iterable
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
                    path TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    size INTEGER NOT NULL DEFAULT 0,
                    modified TEXT,
                    thumb TEXT,
                    active INTEGER NOT NULL DEFAULT 1,
                    last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE INDEX IF NOT EXISTS idx_videos_active ON videos(active, id);
                CREATE INDEX IF NOT EXISTS idx_videos_modified ON videos(active, modified);
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
            connection.executescript(
                """
                CREATE INDEX IF NOT EXISTS idx_videos_source_active
                    ON videos(source, active, id);
                CREATE INDEX IF NOT EXISTS idx_videos_source_duration
                    ON videos(source, active, duration_seconds);
                CREATE INDEX IF NOT EXISTS idx_videos_author
                    ON videos(source, active, author, media_kind);
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
                ON CONFLICT(path) DO UPDATE SET
                    name = excluded.name,
                    size = excluded.size,
                    modified = excluded.modified,
                    thumb = excluded.thumb,
                    source = excluded.source,
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

    def stats(self, *, source: str | None = None) -> dict[str, int]:
        where = "WHERE active = 1"
        params: tuple[Any, ...] = ()
        if source:
            where += " AND source = ?"
            params = (source,)
        with self._lock, self._connect() as connection:
            row = connection.execute(
                f"SELECT COUNT(*) AS active, COALESCE(SUM(size), 0) AS bytes FROM videos {where}",
                params,
            ).fetchone()
        return {"videos": int(row["active"]), "bytes": int(row["bytes"])}

    def fast_start_candidates(self, *, force: bool = False) -> list[dict[str, Any]]:
        condition = "" if force else "AND fast_start IS NULL"
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT id, path, name
                FROM videos
                WHERE active = 1
                  AND source = 'guangya'
                  AND (lower(name) LIKE '%.mp4' OR lower(name) LIKE '%.m4v' OR lower(name) LIKE '%.mov')
                  {condition}
                ORDER BY id
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def update_fast_start(self, video_id: int, status: str, detail: str) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                UPDATE videos
                SET fast_start = ?, fast_start_detail = ?, fast_start_checked_at = CURRENT_TIMESTAMP
                WHERE id = ? AND active = 1 AND source = 'guangya'
                """,
                (status, detail[:240], video_id),
            )

    def fast_start_summary(self) -> dict[str, int]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT fast_start, COUNT(*) AS count
                FROM videos
                WHERE active = 1
                  AND source = 'guangya'
                  AND (lower(name) LIKE '%.mp4' OR lower(name) LIKE '%.m4v' OR lower(name) LIKE '%.mov')
                GROUP BY fast_start
                """
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

    def fast_start_issues(self, *, limit: int) -> list[dict[str, Any]]:
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                """
                SELECT id, name, path, fast_start, fast_start_detail, fast_start_checked_at
                FROM videos
                WHERE active = 1
                  AND source = 'guangya'
                  AND fast_start IN ('not_optimized', 'inconclusive', 'error')
                ORDER BY CASE fast_start WHEN 'not_optimized' THEN 0 WHEN 'error' THEN 1 ELSE 2 END, id
                LIMIT ?
                """,
                (limit,),
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
        category: str | None = None,
        duration_boundary: float = 180.0,
    ) -> tuple[list[dict[str, Any]], str | None, int]:
        excluded = exclude_ids or set()
        filter_key = self._filter_key(excluded, start_id, f"{source}:{category or 'all'}")
        seed, offset, cursor_mode, cursor_filter = self._decode_cursor(cursor)
        if cursor and (cursor_mode != mode or cursor_filter != filter_key):
            seed, offset = secrets.randbits(63), 0

        with self._lock, self._connect() as connection:
            conditions = ["active = 1", "source = ?"]
            params: list[Any] = [source]
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
    ) -> list[dict[str, Any]]:
        retry_condition = "" if force else (
            "AND (metadata_checked_at IS NULL "
            "OR metadata_checked_at < datetime('now', '-1 day'))"
        )
        with self._lock, self._connect() as connection:
            rows = connection.execute(
                f"""
                SELECT id, path, name, size
                FROM videos
                WHERE active = 1
                  AND source = 'guangya'
                  AND duration_seconds IS NULL
                  AND (lower(name) LIKE '%.mp4' OR lower(name) LIKE '%.m4v' OR lower(name) LIKE '%.mov')
                  {retry_condition}
                ORDER BY size DESC, id
                LIMIT ?
                """,
                (max(1, limit),),
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
    ) -> list[dict[str, Any]]:
        conditions = ["active = 1", "source = 'asmr'", "author IS NOT NULL"]
        params: list[Any] = []
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

    def asmr_author_count(self, *, search: str = "") -> int:
        conditions = ["active = 1", "source = 'asmr'", "author IS NOT NULL"]
        params: list[Any] = []
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
    ) -> list[dict[str, Any]]:
        conditions = ["active = 1", "source = 'asmr'", "author = ?"]
        params: list[Any] = [author]
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
    ) -> int:
        conditions = ["active = 1", "source = 'asmr'", "author = ?"]
        params: list[Any] = [author]
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
    def _escape_like(value: str) -> str:
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
