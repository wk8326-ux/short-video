from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

from app.alist import AListClient
from app.database import LibraryDatabase
from app.direct_urls import DirectUrlCache
from app.html_source import HtmlSourceClient
from app.settings import Settings
from app.tvbox import TVBoxClient


@dataclass
class SourceRuntime:
    config: dict[str, Any]
    client: AListClient | TVBoxClient | HtmlSourceClient
    direct_urls: DirectUrlCache
    active_users: int = 0
    retired: bool = False
    closed: bool = False


def default_media_sources(settings: Settings) -> list[dict[str, Any]]:
    asmr_roots = list(settings.asmr_search_paths) or ["/asmr"]
    asmr_root = asmr_roots[0]
    return [
        {
            "id": "guangya",
            "name": "光鸭",
            "provider": "alist",
            "base_url": settings.alist_base_url,
            "root_path": settings.alist_media_path,
            "section": "feed",
            "scan_mode": "tree",
            "anonymous": False,
            "token": settings.alist_token,
            "username": settings.alist_username,
            "password": settings.alist_password,
            "enabled": True,
        },
        {
            "id": "asmr",
            "name": "asmrgay / asmr",
            "provider": "alist",
            "base_url": settings.asmr_base_url,
            "root_path": asmr_root,
            "root_paths": asmr_roots,
            "section": "asmr",
            "scan_mode": "authors_recursive",
            "anonymous": True,
            "token": "",
            "username": "",
            "password": "",
            "enabled": True,
        },
        {
            "id": "asmr6",
            "name": "asmrgay / asmr6",
            "provider": "alist",
            "base_url": settings.asmr_base_url,
            "root_path": settings.asmr_media_path,
            "section": "asmr",
            "scan_mode": "authors",
            "anonymous": True,
            "token": "",
            "username": "",
            "password": "",
            "enabled": True,
        },
        {
            "id": "91crdjai",
            "name": "91crdjai 短剧",
            "provider": "html",
            "base_url": "https://91crdjai.com",
            "root_path": "/",
            "root_paths": ["/"],
            "section": "drama",
            "scan_mode": "remote_pages",
            "anonymous": True,
            "token": "",
            "username": "",
            "password": "",
            "enabled": True,
        },
    ]


class MediaSourceRegistry:
    def __init__(self, database: LibraryDatabase, settings: Settings):
        self.database = database
        self.settings = settings
        self._runtimes: dict[str, SourceRuntime] = {}

    async def initialize(self) -> None:
        self.database.seed_media_sources(default_media_sources(self.settings))
        self.database.remove_legacy_cr_drama_sources()
        await self.reload()

    async def reload(self) -> None:
        # Order matters: the wall and the management list render sections in the
        # order the user added them, so the registry must keep the database's
        # ordering instead of collapsing it into a set.
        source_ids = tuple(
            source["id"]
            for source in self.database.list_media_sources(include_disabled=False)
        )
        # Rebuild in database order so the dict's insertion order always mirrors
        # the stored sort_order.
        previous_runtimes = self._runtimes
        runtimes: dict[str, SourceRuntime] = {}
        self._runtimes = runtimes
        for source_id in source_ids:
            runtime = await self._build_runtime(source_id)
            if runtime is not None:
                runtimes[source_id] = runtime
        for runtime in previous_runtimes.values():
            await self._retire_runtime(runtime)

    async def reload_source(self, source_id: str) -> None:
        """Rebuild one source without disturbing where it sits in the order.

        Editing a source (or adding another root folder to it) must never move
        it to the end of the wall: only brand new sources land last.
        """
        runtime = await self._build_runtime(source_id)
        if runtime is None:
            await self._close(source_id)
            return
        # Assigning over an existing key keeps its original position; a new key
        # is appended, which is exactly what a freshly added source should do.
        if source_id in self._runtimes:
            previous = self._runtimes[source_id]
            self._runtimes[source_id] = runtime
            await self._retire_runtime(previous)
        else:
            self._runtimes[source_id] = runtime

    async def _build_runtime(self, source_id: str) -> SourceRuntime | None:
        config = self.database.get_media_source(source_id)
        if not config or not config["enabled"]:
            return None
        extensions = (
            self.settings.asmr_extensions
            if config["section"] == "asmr"
            else self.settings.movie_extensions
            if config["section"] in ("movie", "drama")
            else self.settings.video_extensions
        )
        if config["provider"] == "html":
            client = HtmlSourceClient(self.settings, config["base_url"])
        elif config["provider"] == "tvbox":
            client = TVBoxClient(
                self.settings,
                config_url=config["base_url"],
                config_json=config.get("config_json"),
            )
        else:
            # One client serves every folder this source was given: the scan
            # steps and the direct-URL resolver always name an absolute path, so
            # the "current" folder is only a fallback for callers that predate
            # them.
            roots = list(config.get("root_paths") or [config["root_path"]])
            client = AListClient(
                self.settings,
                base_url=config["base_url"],
                media_path=roots[0] if roots else "/",
                extensions=extensions,
                anonymous=config["anonymous"],
                token=config["token"],
                username=config["username"],
                password=config["password"],
                request_interval_seconds=(
                    self.settings.asmr_request_interval_seconds
                    if config["section"] == "asmr"
                    else 0.0
                ),
                # Only the movie wall tolerates unknown file types (sidecar
                # artwork). A drama library is strictly video, so stray .html or
                # .nfo files must never be indexed as an episode.
                include_unknown_files=config["section"] == "movie",
            )
        return SourceRuntime(
            config=config,
            client=client,
            direct_urls=DirectUrlCache(
                client.resolve,
                self.settings.direct_url_cache_seconds,
            ),
        )

    def get(self, source_id: str) -> SourceRuntime:
        runtime = self._runtimes.get(source_id)
        if runtime is None:
            raise KeyError(source_id)
        return runtime

    def retain(self, runtime: SourceRuntime) -> None:
        """Keep a runtime alive while a long-running operation uses it."""
        runtime.active_users += 1

    def release(self, runtime: SourceRuntime) -> None:
        """Release a use and close a retired runtime after its last user exits."""
        runtime.active_users = max(0, runtime.active_users - 1)
        if runtime.active_users == 0 and runtime.retired and not runtime.closed:
            asyncio.create_task(self._close_runtime(runtime))

    async def _retire_runtime(self, runtime: SourceRuntime) -> None:
        runtime.retired = True
        if runtime.active_users == 0:
            await self._close_runtime(runtime)

    async def _close_runtime(self, runtime: SourceRuntime) -> None:
        if runtime.closed:
            return
        runtime.closed = True
        await runtime.client.close()

    def ids(self, section: str | None = None) -> tuple[str, ...]:
        return tuple(
            source_id
            for source_id, runtime in self._runtimes.items()
            if section is None or runtime.config["section"] == section
        )

    def name(self, source_id: str) -> str:
        """Return the label the user gave this source, for grouping in the UI."""
        runtime = self._runtimes.get(source_id)
        return str(runtime.config["name"]) if runtime else source_id

    async def close(self) -> None:
        for source_id in list(self._runtimes):
            await self._close(source_id)

    async def _close(self, source_id: str) -> None:
        runtime = self._runtimes.pop(source_id, None)
        if runtime:
            await self._retire_runtime(runtime)
