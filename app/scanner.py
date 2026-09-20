"""Resumable AList library traversal.

A scan is a persistent job: every directory (or author directory, or search
query) is one step recorded in SQLite. Listing results, child steps and the
step's completion flag commit in a single transaction, so an interrupted scan
resumes where it stopped instead of restarting from the root, and a partially
failed scan never retires media that is still available.
"""

from __future__ import annotations

import asyncio
import posixpath
from collections.abc import Awaitable, Callable, Sequence
from typing import Any

from app.alist import AListClient, AListError
from app.database import LibraryDatabase

STEP_DIRECTORY = "directory"
STEP_AUTHORS = "authors"
STEP_AUTHOR = "author"
STEP_SEARCH = "search"

MAX_ERROR_LENGTH = 400


class StepPlan:
    __slots__ = ("records", "steps")

    def __init__(
        self,
        records: list[dict[str, Any]] | None = None,
        steps: list[tuple[str, str]] | None = None,
    ) -> None:
        self.records = records or []
        self.steps = steps or []


def initial_steps(
    scan_mode: str,
    root_paths: Sequence[str] | str,
    *,
    search_paths: tuple[str, ...] = (),
) -> list[tuple[str, str]]:
    """The first steps of a scan, one set per root folder.

    A source may summarise several folders, and each folder is a root in its
    own right: it earns its own traversal step so its resume pointer, retry
    budget and error report stay independent of the others. Duplicate steps
    collapse, which is why an ASMR source that also carries the global search
    paths costs nothing when the search path is already one of its roots.
    """
    steps: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()

    def add(kind: str, path: str) -> None:
        step = (kind, path)
        if step not in seen:
            seen.add(step)
            steps.append(step)

    for root_path in _as_roots(root_paths):
        root = "/" + root_path.strip("/")
        if scan_mode == "authors":
            add(STEP_AUTHORS, root)
        elif scan_mode == "authors_recursive":
            add(STEP_AUTHORS, root)
            for path in search_paths or (root,):
                add(STEP_SEARCH, "/" + str(path).strip("/"))
        else:
            add(STEP_DIRECTORY, root)
    return steps


def _as_roots(root_paths: Sequence[str] | str) -> list[str]:
    """Every accepted shape of "which folders" as a non-empty list of paths."""
    raw = [root_paths] if isinstance(root_paths, str) else [str(item) for item in root_paths]
    roots = [path for path in raw if str(path).strip()]
    return roots or ["/"]


class ResumableScanner:
    def __init__(
        self,
        *,
        database: LibraryDatabase,
        client: AListClient,
        job_id: str,
        source: str,
        marker: str,
        author_group_paths: frozenset[str] = frozenset(),
        search_result_limit: int = 100_000,
        on_progress: Callable[[dict[str, Any]], None] | None = None,
    ) -> None:
        self.database = database
        self.client = client
        self.job_id = job_id
        self.source = source
        self.marker = marker
        self.author_group_paths = author_group_paths
        self.search_result_limit = search_result_limit
        self.on_progress = on_progress

    async def run(self) -> dict[str, Any]:
        errors: list[str] = []
        while True:
            step = await asyncio.to_thread(self.database.next_scan_step, self.job_id)
            if step is None:
                break
            kind = str(step["kind"])
            path = str(step["path"])
            try:
                plan = await self._handle(kind, path)
            except Exception as exc:
                message = str(exc) or exc.__class__.__name__
                await asyncio.to_thread(
                    self.database.fail_scan_step,
                    self.job_id,
                    kind,
                    path,
                    message[:MAX_ERROR_LENGTH],
                )
                errors.append(f"{path}: {message[:MAX_ERROR_LENGTH]}")
                if self.on_progress:
                    self.on_progress(await self._progress())
                continue
            await asyncio.to_thread(
                self.database.commit_scan_step,
                self.job_id,
                source=self.source,
                marker=self.marker,
                kind=kind,
                path=path,
                videos=plan.records,
                steps=plan.steps,
            )
            if self.on_progress:
                self.on_progress(await self._progress())
        progress = await self._progress()
        progress["errors"] = errors
        return progress

    async def _progress(self) -> dict[str, Any]:
        return await asyncio.to_thread(
            self.database.scan_step_progress,
            job_id=self.job_id,
            source=self.source,
            marker=self.marker,
        )

    async def _handle(self, kind: str, path: str) -> StepPlan:
        if kind == STEP_DIRECTORY:
            children, videos = await self.client.scan_directory(path)
            return StepPlan(
                videos,
                [(STEP_DIRECTORY, child) for child in children],
            )
        if kind == STEP_AUTHORS:
            entries = await self.client.list_directory(path)
            return StepPlan(
                steps=[
                    (STEP_AUTHOR, posixpath.join(path, str(entry.get("name") or "")))
                    for entry in entries
                    if bool(entry.get("is_dir"))
                    and str(entry.get("name") or "")
                    and "/" not in str(entry.get("name") or "")
                ]
            )
        if kind == STEP_AUTHOR:
            author = posixpath.basename(path)
            if not author:
                return StepPlan()
            entries = await self.client.list_directory(path)
            records = [
                record
                for entry in entries
                if (record := self.client.build_author_record(path, author, entry))
                is not None
            ]
            return StepPlan(records)
        if kind == STEP_SEARCH:
            return await self._search(path)
        raise AListError(f"Unknown scan step {kind}")

    async def _search(self, root: str) -> StepPlan:
        records: list[dict[str, Any]] = []
        seen: set[str] = set()
        for extension in sorted(self.client.extensions):
            async for entry in self.client.iter_search_files(root, extension):
                record = self.client.build_search_record(
                    root, entry,
                    author_group_paths=self.author_group_paths,
                )
                if record is None or record["path"] in seen:
                    continue
                seen.add(str(record["path"]))
                records.append(record)
                if len(records) > self.search_result_limit:
                    raise AListError(
                        f"Search result limit exceeded ({self.search_result_limit})"
                    )
        return StepPlan(records)
