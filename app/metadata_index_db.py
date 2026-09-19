"""The folded cover catalogue, kept in SQLite instead of in Python dicts.

The shared service publishes one row per media file (~29k and growing). Folding
those rows into three dictionaries -- folder name, release code, full path --
cost around 200 MB inside a container that was capped at 256 MB, so the
catalogue was one upload away from being killed on every restart. The folded
tables now live in a file: one row per catalogue entry, one row per usable key,
and a lookup is an indexed ``SELECT`` instead of a dict hit. Memory stays flat
whatever the catalogue does, which is the whole point -- it only ever grows.

Nothing here knows what a release code or a cloud path is; the caller derives
the keys and hands over ready rows, so this module stays free of the matching
rules that ``shared_metadata`` owns.
"""

from __future__ import annotations

import json
import logging
import os
import sqlite3
import threading
import time
import urllib.parse
from collections.abc import Iterable, Iterator, Mapping
from pathlib import Path
from typing import Any, NamedTuple

logger = logging.getLogger("short-video")

# The three folded tables a lookup may consult, in the order the client tries
# them: the full media path is the catalogue's primary identity, the folder name
# and the release code are the conservative fallbacks.
NAME_TABLE = "name_keys"
CODE_TABLE = "code_keys"
PATH_TABLE = "path_keys"
TABLES = (NAME_TABLE, CODE_TABLE, PATH_TABLE)

# A key that lost a tie stays in the table as ``state = 0`` so that a later row
# cannot resurrect it. Only ``state = 1`` rows are ever served or counted.
_KEY_COLUMNS = (
    "key TEXT PRIMARY KEY, state INTEGER NOT NULL, row_id INTEGER, "
    "identity TEXT, rank INTEGER"
)

_SCHEMA = f"""
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE rows (id INTEGER PRIMARY KEY, payload TEXT NOT NULL);
CREATE TABLE {NAME_TABLE} ({_KEY_COLUMNS});
CREATE TABLE {CODE_TABLE} ({_KEY_COLUMNS});
CREATE TABLE {PATH_TABLE} ({_KEY_COLUMNS});
"""

# The mirror is rewritten from scratch on every walk, so durability during the
# build buys nothing: one transaction per walk replaces the per-batch fsync that
# used to make a rebuild a hundred seconds of waiting. The page cache is capped
# in blocks (negative = KiB) because a large one would defeat the whole point.
_BUILD_PRAGMAS = (
    "PRAGMA journal_mode = MEMORY",
    "PRAGMA synchronous = OFF",
    "PRAGMA temp_store = MEMORY",
    "PRAGMA cache_size = -8192",
)
_READ_PRAGMAS = ("PRAGMA cache_size = -4096",)


class PreparedRow(NamedTuple):
    """One catalogue row with the keys the caller already derived for it."""

    payload: dict[str, Any]
    identity: str
    name_key: str
    code_keys: tuple[str, ...]
    path_keys: tuple[str, ...]
    rank: int


def _connect(target: str, *, read_only: bool) -> sqlite3.Connection:
    """One connection, tolerant of being handed between threads.

    The catalogue is opened on whichever worker the caller happened to use and
    then queried from the event loop, so the connection is created for shared
    use and every statement here runs under the owner's lock.
    """
    if read_only:
        target = f"file:{urllib.parse.quote(str(target), safe='/')}?mode=ro"
    connection = sqlite3.connect(target, uri=read_only, check_same_thread=False, timeout=30.0)
    for pragma in (_READ_PRAGMAS if read_only else _BUILD_PRAGMAS):
        connection.execute(pragma)
    return connection


class CatalogueIndex:
    """Read-only view of one published catalogue mirror."""

    def __init__(self, connection: sqlite3.Connection, path: Path | None = None):
        self._db = connection
        self._lock = threading.RLock()
        self.path = path
        self._meta: dict[str, str] = {}
        self._version = ""
        self._counts: dict[str, int] = {}

    @classmethod
    def open(cls, path: Path) -> "CatalogueIndex":
        index = cls(_connect(str(path), read_only=True), path)
        index._read_meta()
        return index

    def _read_meta(self) -> None:
        with self._lock:
            rows = dict(self._db.execute("SELECT key, value FROM meta").fetchall())
            counts = {
                table: self._db.execute(
                    f"SELECT COUNT(*) FROM {table} WHERE state = 1"
                ).fetchone()[0]
                for table in TABLES
            }
        self._meta = {str(key): str(value) for key, value in rows.items()}
        self._version = self._meta.get("version", "")
        self._counts = counts

    @property
    def version(self) -> str:
        return self._version

    @property
    def fetched_at(self) -> float:
        try:
            return float(self._meta.get("fetchedAt") or 0.0)
        except ValueError:
            return 0.0

    @property
    def row_count(self) -> int:
        try:
            return int(self._meta.get("rows") or 0)
        except ValueError:
            return 0

    def count(self, table: str) -> int:
        return self._counts.get(table, 0)

    def row(self, table: str, key: str) -> dict[str, Any] | None:
        """The catalogue row filed under one key, or ``None`` when it lost."""
        if not key:
            return None
        with self._lock:
            found = self._db.execute(
                f"SELECT r.payload FROM {table} k JOIN rows r ON r.id = k.row_id "
                "WHERE k.key = ? AND k.state = 1",
                (key,),
            ).fetchone()
        if found is None:
            return None
        payload = json.loads(found[0])
        return payload if isinstance(payload, dict) else None

    def keys(self, table: str) -> Iterator[str]:
        with self._lock:
            rows = self._db.execute(
                f"SELECT key FROM {table} WHERE state = 1 ORDER BY key"
            ).fetchall()
        return iter([str(row[0]) for row in rows])

    def close(self) -> None:
        with self._lock:
            try:
                self._db.close()
            except sqlite3.Error:
                pass


class CatalogueIndexBuilder:
    """Folds the rows of one catalogue walk into a fresh SQLite mirror."""

    def __init__(self, path: Path | None):
        self._path = path
        self._usable = {table: 0 for table in TABLES}
        self._handed_over = False
        self._lock = threading.RLock()
        if path is None:
            self._temporary: Path | None = None
            self._db = _connect(":memory:", read_only=False)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            suffix = f".{os.getpid()}.{time.time_ns()}.tmp"
            self._temporary = path.with_name(path.name + suffix)
            self._db = _connect(str(self._temporary), read_only=False)
        self._db.executescript(_SCHEMA)
        self._db.execute("BEGIN")

    def __enter__(self) -> "CatalogueIndexBuilder":
        return self

    def __exit__(self, *_: Any) -> None:
        self.discard()

    def add(self, row: PreparedRow) -> None:
        """File one row under every key it can answer to.

        This is the same rule the dictionaries used: a key claimed by two
        different releases is dropped for good rather than guessed at, and a key
        already held by the same release is upgraded by the row that carries
        more of it (artwork, NFO, a finished status).
        """
        with self._lock:
            cursor = self._db.execute(
                "INSERT INTO rows (payload) VALUES (?)",
                (json.dumps(row.payload, ensure_ascii=False, separators=(",", ":")),),
            )
            row_id = int(cursor.lastrowid or 0)
            self._claim(NAME_TABLE, row.name_key, row_id, row)
            for key in row.code_keys:
                self._claim(CODE_TABLE, key, row_id, row)
            for key in row.path_keys:
                self._claim(PATH_TABLE, key, row_id, row)

    def add_many(self, rows: Iterable[PreparedRow]) -> int:
        added = 0
        for row in rows:
            self.add(row)
            added += 1
        return added

    def _claim(self, table: str, key: str, row_id: int, row: PreparedRow) -> None:
        if not key:
            return
        current = self._db.execute(
            f"SELECT state, identity, rank FROM {table} WHERE key = ?", (key,)
        ).fetchone()
        if current is None:
            self._db.execute(
                f"INSERT INTO {table} (key, state, row_id, identity, rank) "
                "VALUES (?, 1, ?, ?, ?)",
                (key, row_id, row.identity, row.rank),
            )
            self._usable[table] += 1
            return
        state, identity, rank = current
        if not state:
            # Already given up on: a later row may not bring it back.
            return
        if identity != row.identity:
            self._db.execute(
                f"UPDATE {table} SET state = 0, row_id = NULL WHERE key = ?", (key,)
            )
            self._usable[table] -= 1
            return
        if row.rank > rank:
            self._db.execute(
                f"UPDATE {table} SET row_id = ?, rank = ? WHERE key = ?",
                (row_id, row.rank, key),
            )

    def usable_keys(self) -> int:
        return sum(self._usable.values())

    def publish(self, *, version: str, fetched_at: float, rows: int) -> CatalogueIndex | None:
        """Commit the build and put it in place; ``None`` when it is empty.

        An empty build means the catalogue answered without a single usable
        row, which is not a reason to throw away a mirror that still works.
        """
        with self._lock:
            if not self.usable_keys():
                self.discard()
                return None
            self._db.executemany(
                "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
                (
                    ("version", version or ""),
                    ("fetchedAt", repr(float(fetched_at))),
                    ("rows", str(int(rows))),
                    ("builtAt", repr(time.time())),
                ),
            )
            self._db.commit()
            if self._temporary is None:
                self._handed_over = True
                index = CatalogueIndex(self._db, None)
                index._read_meta()
                return index
            self._db.close()
            assert self._path is not None
            os.replace(self._temporary, self._path)
            self._temporary = None
            return CatalogueIndex.open(self._path)

    def discard(self) -> None:
        with self._lock:
            if self._handed_over:
                return
            self._handed_over = True
            try:
                self._db.close()
            except sqlite3.Error:
                pass
            if self._temporary is not None:
                try:
                    self._temporary.unlink(missing_ok=True)
                except OSError:
                    pass
                self._temporary = None


class FoldedKeyTable(Mapping[str, dict[str, Any]]):
    """Dict-shaped read access to one folded table, without holding it.

    The matching code reads its tables as mappings, and keeping that shape means
    the rules stay readable while the storage behind them stops being a dict.
    """

    def __init__(self, index: CatalogueIndex, table: str):
        self._index = index
        self._table = table

    def __getitem__(self, key: str) -> dict[str, Any]:
        row = self._index.row(self._table, key)
        if row is None:
            raise KeyError(key)
        return row

    def __contains__(self, key: object) -> bool:
        return isinstance(key, str) and self._index.row(self._table, key) is not None

    def __iter__(self) -> Iterator[str]:
        return self._index.keys(self._table)

    def __len__(self) -> int:
        return self._index.count(self._table)
