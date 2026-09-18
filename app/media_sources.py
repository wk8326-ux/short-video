from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.alist import AListClient
from app.database import LibraryDatabase
from app.direct_urls import DirectUrlCache
from app.settings import Settings


@dataclass
class SourceRuntime:
    config: dict[str, Any]
    client: AListClient
    direct_urls: DirectUrlCache


def default_media_sources(settings: Settings) -> list[dict[str, Any]]:
    asmr_root = settings.asmr_search_paths[0] if settings.asmr_search_paths else "/asmr"
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
    ]


class MediaSourceRegistry:
    def __init__(self, database: LibraryDatabase, settings: Settings):
        self.database = database
        self.settings = settings
        self._runtimes: dict[str, SourceRuntime] = {}

    async def initialize(self) -> None:
        self.database.seed_media_sources(default_media_sources(self.settings))
        await self.reload()

    async def reload(self) -> None:
        source_ids = {
            source["id"]
            for source in self.database.list_media_sources(include_disabled=False)
        }
        for source_id in set(self._runtimes) - source_ids:
            await self._close(source_id)
        for source_id in source_ids:
            await self.reload_source(source_id)

    async def reload_source(self, source_id: str) -> None:
        await self._close(source_id)
        config = self.database.get_media_source(source_id)
        if not config or not config["enabled"]:
            return
        extensions = (
            self.settings.asmr_extensions
            if config["section"] == "asmr"
            else self.settings.movie_extensions
            if config["section"] in ("movie", "drama")
            else self.settings.video_extensions
        )
        client = AListClient(
            self.settings,
            base_url=config["base_url"],
            media_path=config["root_path"],
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
        self._runtimes[source_id] = SourceRuntime(
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

    def ids(self, section: str | None = None) -> tuple[str, ...]:
        return tuple(
            source_id
            for source_id, runtime in self._runtimes.items()
            if section is None or runtime.config["section"] == section
        )

    async def close(self) -> None:
        for source_id in list(self._runtimes):
            await self._close(source_id)

    async def _close(self, source_id: str) -> None:
        runtime = self._runtimes.pop(source_id, None)
        if runtime:
            await runtime.client.close()
