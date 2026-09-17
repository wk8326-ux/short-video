from __future__ import annotations

import asyncio
import posixpath
from collections.abc import AsyncIterator
from collections import deque
from pathlib import PurePosixPath
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx

from app.settings import Settings

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
)
TRANSIENT_DATABASE_RETRIES = 4
AUDIO_EXTENSIONS = frozenset({".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"})
TRANSIENT_HTTP_STATUS = frozenset({500, 502, 503, 504, 520, 521, 522, 524, 529})
TRANSIENT_HTTP_RETRIES = 4
NON_MEDIA_EXTENSIONS = frozenset(
    {
        ".7z",
        ".ass",
        ".bmp",
        ".chm",
        ".doc",
        ".docx",
        ".epub",
        ".gif",
        ".gz",
        ".htm",
        ".html",
        ".idx",
        ".iso",
        ".jpeg",
        ".jpg",
        ".lnk",
        ".json",
        ".mht",
        ".mhtml",
        ".nfo",
        ".png",
        ".pdf",
        ".rar",
        ".srt",
        ".ssa",
        ".sub",
        ".tar",
        ".torrent",
        ".txt",
        ".url",
        ".vtt",
        ".webp",
        ".xml",
        ".zip",
    }
)


class AListError(RuntimeError):
    pass


class AListClient:
    def __init__(
        self,
        settings: Settings,
        client: httpx.AsyncClient | None = None,
        *,
        base_url: str | None = None,
        media_path: str | None = None,
        extensions: frozenset[str] | None = None,
        anonymous: bool = False,
        token: str | None = None,
        username: str | None = None,
        password: str | None = None,
        request_interval_seconds: float = 0.0,
        include_unknown_files: bool = False,
    ):
        self.settings = settings
        self.base_url = (base_url or settings.alist_base_url).rstrip("/")
        self.media_path = media_path or settings.alist_media_path
        self.extensions = extensions or settings.video_extensions
        self._client = client or httpx.AsyncClient(
            base_url=self.base_url,
            follow_redirects=False,
            timeout=httpx.Timeout(20.0, connect=10.0),
        )
        self._owns_client = client is None
        self._token = "" if anonymous else (settings.alist_token if token is None else token)
        self._username = "" if anonymous else (
            settings.alist_username if username is None else username
        )
        self._password = "" if anonymous else (
            settings.alist_password if password is None else password
        )
        self._auth_lock = asyncio.Lock()
        self._request_lock = asyncio.Lock()
        self._request_interval_seconds = max(0.0, request_interval_seconds)
        self._last_request_at = 0.0
        self._include_unknown_files = include_unknown_files

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _login(self) -> None:
        if not self._username:
            return
        async with self._auth_lock:
            response = await self._client.post(
                "/api/auth/login",
                json={
                    "username": self._username,
                    "password": self._password,
                },
                headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
            )
            payload = self._payload(response)
            token = str((payload.get("data") or {}).get("token") or "")
            if not token:
                raise AListError("AList login did not return a token")
            self._token = token

    async def _post(
        self,
        endpoint: str,
        body: dict[str, Any],
        retry: bool = True,
        rate_limit_retries: int = 3,
        transient_database_retries: int = TRANSIENT_DATABASE_RETRIES,
        transient_http_retries: int = TRANSIENT_HTTP_RETRIES,
    ) -> dict[str, Any]:
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        if self._token:
            headers["Authorization"] = self._token
        try:
            if self._request_interval_seconds:
                async with self._request_lock:
                    loop = asyncio.get_running_loop()
                    remaining = (
                        self._request_interval_seconds
                        - (loop.time() - self._last_request_at)
                    )
                    if remaining > 0:
                        await asyncio.sleep(remaining)
                    response = await self._client.post(endpoint, json=body, headers=headers)
                    self._last_request_at = loop.time()
            else:
                response = await self._client.post(endpoint, json=body, headers=headers)
        except httpx.HTTPError as exc:
            raise AListError(f"AList request failed: {exc}") from exc

        if (
            response.status_code in TRANSIENT_HTTP_STATUS
            and transient_http_retries > 0
        ):
            await asyncio.sleep(self._transient_http_delay(transient_http_retries))
            return await self._post(
                endpoint,
                body,
                retry=retry,
                rate_limit_retries=rate_limit_retries,
                transient_database_retries=transient_database_retries,
                transient_http_retries=transient_http_retries - 1,
            )

        if response.status_code == 429 and rate_limit_retries > 0:
            await asyncio.sleep(self._retry_after(response, rate_limit_retries))
            return await self._post(
                endpoint,
                body,
                retry=retry,
                rate_limit_retries=rate_limit_retries - 1,
                transient_database_retries=transient_database_retries,
                transient_http_retries=transient_http_retries,
            )
        try:
            payload = self._payload(response)
        except AListError as exc:
            if transient_database_retries > 0 and self._is_database_connection_exhaustion(str(exc)):
                await asyncio.sleep(self._database_retry_delay(transient_database_retries))
                return await self._post(
                    endpoint,
                    body,
                    retry=retry,
                    rate_limit_retries=rate_limit_retries,
                    transient_database_retries=transient_database_retries - 1,
                )
            raise
        code = int(payload.get("code", response.status_code))
        if code == 401 and retry and self._username:
            await self._login()
            return await self._post(
                endpoint,
                body,
                retry=False,
                rate_limit_retries=rate_limit_retries,
                transient_database_retries=transient_database_retries,
                transient_http_retries=transient_http_retries,
            )
        if code == 429 and rate_limit_retries > 0:
            await asyncio.sleep(self._retry_after(response, rate_limit_retries))
            return await self._post(
                endpoint,
                body,
                retry=retry,
                rate_limit_retries=rate_limit_retries - 1,
                transient_database_retries=transient_database_retries,
                transient_http_retries=transient_http_retries,
            )
        if code not in (0, 200):
            message = str(payload.get("message") or f"AList error {code}")
            if transient_database_retries > 0 and self._is_database_connection_exhaustion(message):
                await asyncio.sleep(self._database_retry_delay(transient_database_retries))
                return await self._post(
                    endpoint,
                    body,
                    retry=retry,
                    rate_limit_retries=rate_limit_retries,
                    transient_database_retries=transient_database_retries - 1,
                )
            raise AListError(message)
        return payload

    @staticmethod
    def _retry_after(response: httpx.Response, remaining_retries: int) -> float:
        try:
            return min(5.0, max(0.0, float(response.headers.get("Retry-After", ""))))
        except ValueError:
            return 0.5 * (4 - remaining_retries)

    @staticmethod
    def _is_database_connection_exhaustion(message: str) -> bool:
        normalized = message.casefold()
        return "error 1040" in normalized or "too many connections" in normalized

    @staticmethod
    def _transient_http_delay(remaining_retries: int) -> float:
        attempt = TRANSIENT_HTTP_RETRIES - remaining_retries
        return min(16.0, float(2**attempt))

    @staticmethod
    def _database_retry_delay(remaining_retries: int) -> float:
        attempt = TRANSIENT_DATABASE_RETRIES - remaining_retries
        return min(8.0, float(2**attempt))

    @staticmethod
    def _payload(response: httpx.Response) -> dict[str, Any]:
        try:
            payload = response.json()
        except ValueError as exc:
            raise AListError(f"AList returned HTTP {response.status_code} without JSON") from exc
        if response.status_code >= 400:
            raise AListError(str(payload.get("message") or f"AList HTTP {response.status_code}"))
        if not isinstance(payload, dict):
            raise AListError("AList returned an invalid response")
        return payload

    async def list_directory(self, path: str) -> list[dict[str, Any]]:
        entries: list[dict[str, Any]] = []
        page = 1
        while True:
            payload = await self._post(
                "/api/fs/list",
                {
                    "path": path,
                    "password": "",
                    "page": page,
                    "per_page": 200,
                    "refresh": False,
                },
            )
            data = payload.get("data") or {}
            content = data.get("content") if isinstance(data, dict) else data
            batch = list(content or [])
            entries.extend(batch)
            total = int(data.get("total") or len(entries)) if isinstance(data, dict) else len(entries)
            if len(entries) >= total or len(batch) < 200:
                break
            page += 1
        return entries

    async def search_files(self, parent: str, keyword: str) -> list[dict[str, Any]]:
        return [entry async for entry in self.iter_search_files(parent, keyword)]

    async def iter_search_files(
        self,
        parent: str,
        keyword: str,
    ) -> AsyncIterator[dict[str, Any]]:
        page = 1
        yielded = 0
        while True:
            payload = await self._post(
                "/api/fs/search",
                {
                    "parent": parent,
                    "keywords": keyword,
                    "scope": 0,
                    "page": page,
                    "per_page": 200,
                    "password": "",
                },
            )
            data = payload.get("data") or {}
            content = data.get("content") if isinstance(data, dict) else data
            batch = list(content or [])
            for entry in batch:
                yield entry
                yielded += 1
            total = int(data.get("total") or yielded) if isinstance(data, dict) else yielded
            if yielded >= total or len(batch) < 200:
                break
            page += 1

    def build_video_record(self, directory: str, entry: dict[str, Any]) -> dict[str, Any] | None:
        """Turn one AList listing entry into a ``videos`` row candidate.

        Returns ``None`` for directories and for entries that are not playable
        media, so both the legacy and the resumable traversal share one rule.
        """
        name = str(entry.get("name") or "")
        if not name or "/" in name or bool(entry.get("is_dir")):
            return None
        extension = PurePosixPath(name).suffix.lower()
        if extension not in self.extensions and (
            not self._include_unknown_files or extension in NON_MEDIA_EXTENSIONS
        ):
            return None
        return {
            "path": posixpath.join(directory, name),
            "name": name,
            "size": int(entry.get("size") or 0),
            "modified": entry.get("modified") or entry.get("created"),
            "thumb": self._absolute_url(str(entry.get("thumb") or "")),
            "media_format": (
                extension.lstrip(".") if extension in self.extensions else "unknown"
            ),
            "media_kind": "video",
        }

    async def child_directories(self, path: str) -> list[str]:
        children: list[str] = []
        for entry in await self.list_directory(path):
            name = str(entry.get("name") or "")
            if not name or "/" in name or not bool(entry.get("is_dir")):
                continue
            children.append(posixpath.join(path, name))
        return children

    async def scan_directory(self, path: str) -> tuple[list[str], list[dict[str, Any]]]:
        """List one directory once, returning its subdirectories and media files."""
        entries = await self.list_directory(path)
        children: list[str] = []
        videos: list[dict[str, Any]] = []
        for entry in entries:
            if bool(entry.get("is_dir")):
                name = str(entry.get("name") or "")
                if name and "/" not in name:
                    children.append(posixpath.join(path, name))
                continue
            record = self.build_video_record(path, entry)
            if record is not None:
                videos.append(record)
        return children, videos

    async def iter_scan(self) -> AsyncIterator[tuple[str, Any]]:
        root = "/" + self.media_path.strip("/")
        queue: deque[str] = deque([root])
        directories = 0

        while queue:
            directory = queue.popleft()
            directories += 1
            yield "directories", directories
            children, videos = await self.scan_directory(directory)
            queue.extend(children)
            for record in videos:
                yield "video", record

    def build_author_record(
        self, directory: str, author: str, entry: dict[str, Any]
    ) -> dict[str, Any] | None:
        """Turn one author-directory entry into a media row candidate."""
        name = str(entry.get("name") or "")
        if not name or "/" in name or bool(entry.get("is_dir")):
            return None
        extension = PurePosixPath(name).suffix.lower()
        if extension not in self.extensions:
            return None
        return {
            "path": posixpath.join(directory, name),
            "name": name,
            "size": int(entry.get("size") or 0),
            "modified": entry.get("modified") or entry.get("created"),
            "thumb": self._absolute_url(str(entry.get("thumb") or "")),
            "author": author,
            "media_format": extension.lstrip("."),
            "media_kind": "audio" if extension in AUDIO_EXTENSIONS else "video",
        }

    def build_search_record(
        self,
        root: str,
        entry: dict[str, Any],
        *,
        author_group_paths: frozenset[str] = frozenset(),
    ) -> dict[str, Any] | None:
        """Turn one `/api/fs/search` hit into a media row candidate."""
        if bool(entry.get("is_dir")):
            return None
        name = str(entry.get("name") or "")
        parent = "/" + str(entry.get("parent") or "").strip("/")
        if not name or "/" in name:
            return None
        extension = PurePosixPath(name).suffix.lower()
        if extension not in self.extensions:
            return None
        if parent != root and not parent.startswith(root + "/"):
            return None
        author = self._search_author(
            root,
            parent,
            author_group_paths=author_group_paths,
        )
        if not author:
            return None
        return {
            "path": posixpath.join(parent, name),
            "name": name,
            "size": int(entry.get("size") or 0),
            "modified": entry.get("modified") or entry.get("created"),
            "thumb": self._absolute_url(str(entry.get("thumb") or "")),
            "author": author,
            "media_format": extension.lstrip("."),
            "media_kind": "audio" if extension in AUDIO_EXTENSIONS else "video",
        }

    async def scan(self) -> tuple[list[dict[str, Any]], int]:
        videos: list[dict[str, Any]] = []
        directories = 0
        async for event, value in self.iter_scan():
            if event == "video":
                videos.append(value)
            else:
                directories = value
        return videos, directories

    async def scan_authors(
        self,
        *,
        search_paths: tuple[str, ...] = (),
        author_group_paths: frozenset[str] = frozenset(),
        search_result_limit: int = 100000,
    ) -> tuple[list[dict[str, Any]], int]:
        root = "/" + self.media_path.strip("/")
        authors = sorted(
            (
                entry
                for entry in await self.list_directory(root)
                if bool(entry.get("is_dir")) and str(entry.get("name") or "")
            ),
            key=lambda entry: str(entry.get("name") or "").casefold(),
        )
        semaphore = asyncio.Semaphore(6)

        async def scan_author(entry: dict[str, Any]) -> list[dict[str, Any]]:
            author = str(entry.get("name") or "")
            if "/" in author:
                return []
            directory = posixpath.join(root, author)
            async with semaphore:
                children = await self.list_directory(directory)
            records: list[dict[str, Any]] = []
            audio_extensions = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"}
            for child_entry in children:
                name = str(child_entry.get("name") or "")
                if not name or "/" in name or bool(child_entry.get("is_dir")):
                    continue
                extension = PurePosixPath(name).suffix.lower()
                if extension not in self.extensions:
                    continue
                records.append(
                    {
                        "path": posixpath.join(directory, name),
                        "name": name,
                        "size": int(child_entry.get("size") or 0),
                        "modified": child_entry.get("modified") or child_entry.get("created"),
                        "thumb": self._absolute_url(str(child_entry.get("thumb") or "")),
                        "author": author,
                        "media_format": extension.lstrip("."),
                        "media_kind": "audio" if extension in audio_extensions else "video",
                    }
                )
            return records

        groups = await asyncio.gather(*(scan_author(author) for author in authors))
        records = [record for group in groups for record in group]
        directory_count = len(authors) + 1

        normalized_groups = {
            "/" + path.strip("/") for path in author_group_paths if path.strip("/")
        }
        searched_paths: set[str] = set()
        searched_parents: set[str] = set()
        audio_extensions = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"}
        for configured_root in search_paths:
            root = "/" + configured_root.strip("/")
            for extension in sorted(self.extensions):
                async for entry in self.iter_search_files(root, extension):
                    if bool(entry.get("is_dir")):
                        continue
                    name = str(entry.get("name") or "")
                    parent = "/" + str(entry.get("parent") or "").strip("/")
                    if not name or "/" in name or PurePosixPath(name).suffix.lower() != extension:
                        continue
                    if parent != root and not parent.startswith(root + "/"):
                        continue
                    path = posixpath.join(parent, name)
                    if path in searched_paths:
                        continue
                    author = self._search_author(
                        root,
                        parent,
                        author_group_paths=normalized_groups,
                    )
                    if not author:
                        continue
                    searched_paths.add(path)
                    searched_parents.add(parent)
                    records.append(
                        {
                            "path": path,
                            "name": name,
                            "size": int(entry.get("size") or 0),
                            "modified": entry.get("modified") or entry.get("created"),
                            "thumb": self._absolute_url(str(entry.get("thumb") or "")),
                            "author": author,
                            "media_format": extension.lstrip("."),
                            "media_kind": "audio" if extension in audio_extensions else "video",
                        }
                    )
                    if len(searched_paths) > search_result_limit:
                        raise AListError(
                            f"ASMR search result limit exceeded ({search_result_limit})"
                        )
            directory_count += len(searched_parents) + 1

        deduplicated = {record["path"]: record for record in records}
        return list(deduplicated.values()), directory_count

    @staticmethod
    def _search_author(
        root: str,
        parent: str,
        *,
        author_group_paths: set[str],
    ) -> str:
        relative = parent.removeprefix(root).strip("/")
        parts = [part for part in relative.split("/") if part]
        if not parts:
            return ""
        first_path = posixpath.join(root, parts[0])
        if first_path in author_group_paths:
            return parts[1] if len(parts) > 1 else ""
        return parts[0]

    async def resolve(self, path: str) -> tuple[str, bool]:
        payload = await self._post("/api/fs/get", {"path": path, "password": ""})
        data = payload.get("data") or {}
        raw_url = str(data.get("raw_url") or "")
        parsed = urlparse(raw_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise AListError("AList did not return a valid raw URL")
        return raw_url, bool(data.get("header"))

    async def read_prefix(self, path: str, *, max_bytes: int) -> bytes:
        raw_url, requires_headers = await self.resolve(path)
        if requires_headers:
            raise AListError("This media source requires proxy headers")
        return await self.read_url_range(
            raw_url,
            range_header=f"bytes=0-{max_bytes - 1}",
            max_bytes=max_bytes,
        )

    async def read_url_range(
        self,
        raw_url: str,
        *,
        range_header: str,
        max_bytes: int,
    ) -> bytes:
        headers = {
            "Accept": "*/*",
            "Accept-Encoding": "identity",
            "Range": range_header,
            "User-Agent": USER_AGENT,
        }
        try:
            async with self._client.stream(
                "GET", raw_url, headers=headers, follow_redirects=True
            ) as response:
                if response.status_code not in (200, 206):
                    raise AListError(f"Media prefix returned HTTP {response.status_code}")
                prefix = bytearray()
                async for chunk in response.aiter_bytes():
                    remaining = max_bytes - len(prefix)
                    if remaining <= 0:
                        break
                    prefix.extend(chunk[:remaining])
                    if len(prefix) >= max_bytes:
                        break
                return bytes(prefix)
        except httpx.HTTPError as exc:
            raise AListError(f"Media prefix request failed: {exc}") from exc

    def _absolute_url(self, value: str) -> str:
        if not value:
            return ""
        return urljoin(f"{self.base_url}/", value)
