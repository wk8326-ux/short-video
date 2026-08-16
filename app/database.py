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

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=10, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        return connection

    def replace_scan(self, videos: Iterable[dict[str, Any]]) -> int:
        records = list(videos)
        with self._lock, self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("UPDATE videos SET active = 0")
            connection.executemany(
                """
                INSERT INTO videos(path, name, size, modified, thumb, active, last_seen)
                VALUES(:path, :name, :size, :modified, :thumb, 1, CURRENT_TIMESTAMP)
                ON CONFLICT(path) DO UPDATE SET
                    name = excluded.name,
                    size = excluded.size,
                    modified = excluded.modified,
                    thumb = excluded.thumb,
                    active = 1,
                    last_seen = CURRENT_TIMESTAMP
                """,
                records,
            )
            connection.commit()
        return len(records)

    def get_video(self, video_id: int) -> dict[str, Any] | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM videos WHERE id = ? AND active = 1", (video_id,)
            ).fetchone()
        return dict(row) if row else None

    def stats(self) -> dict[str, int]:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "SELECT COUNT(*) AS active, COALESCE(SUM(size), 0) AS bytes FROM videos WHERE active = 1"
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
                WHERE id = ? AND active = 1
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
                WHERE active = 1 AND fast_start IN ('not_optimized', 'inconclusive', 'error')
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
    ) -> tuple[list[dict[str, Any]], str | None, int]:
        excluded = exclude_ids or set()
        filter_key = self._filter_key(excluded, start_id)
        seed, offset, cursor_mode, cursor_filter = self._decode_cursor(cursor)
        if cursor and (cursor_mode != mode or cursor_filter != filter_key):
            seed, offset = secrets.randbits(63), 0

        with self._lock, self._connect() as connection:
            rows = [
                dict(row)
                for row in connection.execute(
                    "SELECT * FROM videos WHERE active = 1 ORDER BY id"
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
    def _filter_key(exclude_ids: set[int], start_id: int | None) -> str:
        value = ",".join(str(video_id) for video_id in sorted(exclude_ids))
        digest = hashlib.blake2s(value.encode(), digest_size=6).hexdigest() if value else ""
        return f"{digest}:{start_id or ''}"
