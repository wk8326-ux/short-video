from __future__ import annotations

import asyncio
import posixpath
from collections import deque
from pathlib import PurePosixPath
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx

from app.settings import Settings


class AListError(RuntimeError):
    pass


class AListClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None):
        self.settings = settings
        self._client = client or httpx.AsyncClient(
            base_url=settings.alist_base_url,
            follow_redirects=False,
            timeout=httpx.Timeout(20.0, connect=10.0),
        )
        self._owns_client = client is None
        self._token = settings.alist_token
        self._auth_lock = asyncio.Lock()

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _login(self) -> None:
        if not self.settings.alist_username:
            return
        async with self._auth_lock:
            response = await self._client.post(
                "/api/auth/login",
                json={
                    "username": self.settings.alist_username,
                    "password": self.settings.alist_password,
                },
            )
            payload = self._payload(response)
            token = str((payload.get("data") or {}).get("token") or "")
            if not token:
                raise AListError("AList login did not return a token")
            self._token = token

    async def _post(self, endpoint: str, body: dict[str, Any], retry: bool = True) -> dict[str, Any]:
        headers = {"Authorization": self._token} if self._token else {}
        try:
            response = await self._client.post(endpoint, json=body, headers=headers)
        except httpx.HTTPError as exc:
            raise AListError(f"AList request failed: {exc}") from exc

        payload = self._payload(response)
        code = int(payload.get("code", response.status_code))
        if code == 401 and retry and self.settings.alist_username:
            await self._login()
            return await self._post(endpoint, body, retry=False)
        if code not in (0, 200):
            raise AListError(str(payload.get("message") or f"AList error {code}"))
        return payload

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

    async def scan(self) -> tuple[list[dict[str, Any]], int]:
        root = "/" + self.settings.alist_media_path.strip("/")
        queue: deque[str] = deque([root])
        videos: list[dict[str, Any]] = []
        directories = 0

        while queue:
            directory = queue.popleft()
            directories += 1
            if directories > 5000:
                raise AListError("Directory limit exceeded")
            for entry in await self.list_directory(directory):
                name = str(entry.get("name") or "")
                if not name or "/" in name:
                    continue
                child = posixpath.join(directory, name)
                if bool(entry.get("is_dir")):
                    queue.append(child)
                    continue
                extension = PurePosixPath(name).suffix.lower()
                if extension not in self.settings.video_extensions:
                    continue
                videos.append(
                    {
                        "path": child,
                        "name": name,
                        "size": int(entry.get("size") or 0),
                        "modified": entry.get("modified") or entry.get("created"),
                        "thumb": self._absolute_url(str(entry.get("thumb") or "")),
                    }
                )
        return videos, directories

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
        headers = {
            "Accept": "*/*",
            "Accept-Encoding": "identity",
            "Range": f"bytes=0-{max_bytes - 1}",
            "User-Agent": (
                "Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
            ),
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
        return urljoin(f"{self.settings.alist_base_url}/", value)
