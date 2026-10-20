from __future__ import annotations

import re
import secrets
import time
from collections import OrderedDict
from collections.abc import AsyncIterator, Mapping
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urljoin, urlsplit

import httpx
from fastapi import Request
from fastapi.responses import Response, StreamingResponse

from app.direct_urls import normalize_media_headers

MEDIA_PROXY_TTL_SECONDS = 12 * 60 * 60
MEDIA_PROXY_MAX_ENTRIES = 8192
MEDIA_PROXY_MAX_REDIRECTS = 5
HLS_PLAYLIST_MAX_BYTES = 4 * 1024 * 1024
MEDIA_PROXY_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
)
SENSITIVE_REDIRECT_HEADERS = frozenset(
    {"authorization", "cookie", "proxy-authorization"}
)
SAFE_CROSS_ORIGIN_HEADERS = frozenset(
    {"accept", "accept-encoding", "if-range", "range", "user-agent"}
)
CONTENT_RESPONSE_HEADERS = (
    "accept-ranges",
    "content-encoding",
    "content-length",
    "content-range",
    "content-type",
    "etag",
    "last-modified",
)
URI_ATTRIBUTE_PATTERN = re.compile(
    r"(?P<prefix>\bURI\s*=\s*)(?P<quote>[\"'])(?P<uri>.*?)(?P=quote)",
    re.IGNORECASE,
)


class MediaProxyError(RuntimeError):
    pass


class UnknownPlaybackToken(MediaProxyError):
    pass


@dataclass(frozen=True)
class ProxyEntry:
    url: str
    headers: dict[str, str] = field(default_factory=dict)
    playlist_hint: bool = False
    expires_at: float = 0.0


@dataclass(frozen=True)
class UpstreamResponse:
    response: httpx.Response
    url: str
    headers: dict[str, str]


def _is_http_url(value: str) -> bool:
    try:
        parsed = urlsplit(value)
    except ValueError:
        return False
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def _origin(value: str) -> tuple[str, str, int | None]:
    parsed = urlsplit(value)
    return parsed.scheme.lower(), (parsed.hostname or "").lower(), parsed.port


def scoped_headers(
    headers: Mapping[str, str],
    *,
    source_url: str,
    target_url: str,
) -> dict[str, str]:
    """Keep provider credentials on their original origin.

    A redirect or HLS child URI is allowed to point at a CDN, but a bearer token
    or cookie belongs to the provider that issued it. Sending those headers to
    an unrelated host would turn a playback bug into a credential leak.
    """

    scoped = normalize_media_headers(headers)
    if _origin(source_url) == _origin(target_url):
        return scoped
    return {
        name: value
        for name, value in scoped.items()
        if name in SAFE_CROSS_ORIGIN_HEADERS
        and name not in SENSITIVE_REDIRECT_HEADERS
    }


def is_hls_url(value: str, format_hint: str | None = None) -> bool:
    if str(format_hint or "").strip().lower() == "m3u8":
        return True
    try:
        path = urlsplit(value).path.lower()
    except ValueError:
        return False
    return path.endswith(".m3u8") or path.endswith(".m3u")


def _is_playlist_response(
    entry: ProxyEntry,
    response: httpx.Response,
    first_chunk: bytes,
) -> bool:
    if entry.playlist_hint or is_hls_url(str(response.url)):
        return True
    content_type = response.headers.get("content-type", "").lower()
    if "mpegurl" in content_type:
        return True
    head = first_chunk.lstrip(b"\xef\xbb\xbf\x00\t\r\n ")
    return head.startswith(b"#EXTM3U")


def _proxy_path(token: str) -> str:
    return f"/api/playback/{token}"


class MediaPlaybackProxy:
    def __init__(
        self,
        *,
        ttl_seconds: int = MEDIA_PROXY_TTL_SECONDS,
        max_entries: int = MEDIA_PROXY_MAX_ENTRIES,
        client: httpx.AsyncClient | None = None,
        clock: Any = time.monotonic,
    ) -> None:
        self._ttl_seconds = max(1, int(ttl_seconds))
        self._max_entries = max(1, int(max_entries))
        self._client = client
        self._clock = clock
        self._entries: OrderedDict[str, ProxyEntry] = OrderedDict()

    async def open(self) -> None:
        if self._client is None:
            self._client = httpx.AsyncClient(
                follow_redirects=False,
                timeout=httpx.Timeout(30.0, connect=8.0, read=45.0),
                limits=httpx.Limits(
                    max_connections=64,
                    max_keepalive_connections=24,
                ),
            )

    async def close(self) -> None:
        client = self._client
        self._client = None
        if client is not None:
            await client.aclose()

    def register(
        self,
        url: str,
        headers: Mapping[str, str] | None = None,
        *,
        playlist_hint: bool = False,
    ) -> str:
        if not _is_http_url(url):
            raise MediaProxyError("Playback URL must be an absolute HTTP(S) URL")
        self._prune()
        token = secrets.token_urlsafe(24)
        self._entries[token] = ProxyEntry(
            url=url,
            headers=normalize_media_headers(headers),
            playlist_hint=bool(playlist_hint),
            expires_at=self._clock() + self._ttl_seconds,
        )
        while len(self._entries) > self._max_entries:
            self._entries.popitem(last=False)
        return token

    def resolve(self, token: str) -> ProxyEntry:
        entry = self._entries.get(token)
        if entry is None:
            raise UnknownPlaybackToken("Playback token not found")
        if entry.expires_at <= self._clock():
            self._entries.pop(token, None)
            raise UnknownPlaybackToken("Playback token expired")
        self._entries.move_to_end(token)
        return entry

    def clear(self) -> None:
        self._entries.clear()

    def _prune(self) -> None:
        now = self._clock()
        expired = [
            token
            for token, entry in self._entries.items()
            if entry.expires_at <= now
        ]
        for token in expired:
            self._entries.pop(token, None)

    def _client_or_raise(self) -> httpx.AsyncClient:
        if self._client is None:
            raise MediaProxyError("Playback proxy is not ready")
        return self._client

    @staticmethod
    def _request_headers(
        entry: ProxyEntry,
        request: Request,
    ) -> dict[str, str]:
        headers = {
            "accept": "*/*",
            "accept-encoding": "identity",
            "user-agent": MEDIA_PROXY_USER_AGENT,
        }
        headers.update(entry.headers)
        for name in ("accept", "if-range", "range"):
            value = request.headers.get(name)
            if value:
                headers[name] = value
        headers["accept-encoding"] = "identity"
        return headers

    async def _send(
        self,
        method: str,
        url: str,
        headers: Mapping[str, str],
        provider_headers: Mapping[str, str],
    ) -> UpstreamResponse:
        current_url = url
        current_headers = dict(headers)
        current_provider_headers = dict(provider_headers)
        client = self._client_or_raise()
        for redirect_count in range(MEDIA_PROXY_MAX_REDIRECTS + 1):
            upstream = await client.send(
                client.build_request(method, current_url, headers=current_headers),
                stream=True,
            )
            location = upstream.headers.get("location")
            if upstream.status_code not in {301, 302, 303, 307, 308} or not location:
                return UpstreamResponse(
                    upstream,
                    str(upstream.url),
                    current_provider_headers,
                )
            await upstream.aclose()
            if redirect_count >= MEDIA_PROXY_MAX_REDIRECTS:
                raise MediaProxyError("Playback upstream redirected too many times")
            next_url = urljoin(str(upstream.url), location)
            if not _is_http_url(next_url):
                raise MediaProxyError("Playback upstream returned an invalid redirect")
            current_headers = scoped_headers(
                current_headers,
                source_url=str(upstream.url),
                target_url=next_url,
            )
            current_provider_headers = scoped_headers(
                current_provider_headers,
                source_url=str(upstream.url),
                target_url=next_url,
            )
            if method == "HEAD" and upstream.status_code == 303:
                method = "GET"
            current_url = next_url
        raise MediaProxyError("Playback upstream redirected too many times")

    async def handle(
        self,
        token: str,
        request: Request,
    ) -> Response:
        entry = self.resolve(token)
        method = "HEAD" if request.method.upper() == "HEAD" else "GET"
        upstream: UpstreamResponse | None = None
        try:
            upstream = await self._send(
                method,
                entry.url,
                self._request_headers(entry, request),
                entry.headers,
            )
            response = upstream.response
            if response.status_code >= 500:
                raise MediaProxyError(
                    f"Playback upstream returned HTTP {response.status_code}"
                )
            if method == "HEAD" or response.status_code == 416:
                content = await response.aread()
                await response.aclose()
                return Response(
                    content=content,
                    status_code=response.status_code,
                    headers=self._response_headers(
                        upstream=response,
                        playlist=False,
                    ),
                )

            chunks = response.aiter_raw(chunk_size=64 * 1024)
            first_chunk = bytearray()
            async for chunk in chunks:
                first_chunk.extend(chunk)
                if len(first_chunk) >= 64 * 1024:
                    break

            if _is_playlist_response(entry, response, bytes(first_chunk)):
                playlist_bytes = await self._read_playlist(bytes(first_chunk), chunks)
                await response.aclose()
                playlist = self.rewrite_playlist(
                    playlist_bytes,
                    upstream.url,
                    upstream.headers,
                )
                headers = self._response_headers(
                    upstream=response,
                    playlist=True,
                )
                headers["content-length"] = str(len(playlist))
                headers["content-type"] = "application/vnd.apple.mpegurl"
                return Response(
                    content=playlist,
                    status_code=response.status_code,
                    headers=headers,
                )

            body = self._stream_body(response, bytes(first_chunk), chunks)
            return StreamingResponse(
                body,
                status_code=response.status_code,
                headers=self._response_headers(
                    upstream=response,
                    playlist=False,
                ),
            )
        except httpx.HTTPError as exc:
            if upstream is not None:
                await upstream.response.aclose()
            raise MediaProxyError(f"Playback upstream unavailable: {exc}") from exc
        except Exception:
            if upstream is not None and not upstream.response.is_closed:
                await upstream.response.aclose()
            raise

    async def _read_playlist(
        self,
        first_chunk: bytes,
        chunks: AsyncIterator[bytes],
    ) -> bytes:
        playlist = bytearray(first_chunk)
        if len(playlist) > HLS_PLAYLIST_MAX_BYTES:
            raise MediaProxyError("HLS playlist is too large")
        async for chunk in chunks:
            playlist.extend(chunk)
            if len(playlist) > HLS_PLAYLIST_MAX_BYTES:
                raise MediaProxyError("HLS playlist is too large")
        return bytes(playlist)

    async def _stream_body(
        self,
        response: httpx.Response,
        first_chunk: bytes,
        chunks: AsyncIterator[bytes],
    ) -> AsyncIterator[bytes]:
        try:
            if first_chunk:
                yield first_chunk
            async for chunk in chunks:
                if chunk:
                    yield chunk
        finally:
            await response.aclose()

    def _response_headers(
        self,
        *,
        upstream: httpx.Response,
        playlist: bool,
    ) -> dict[str, str]:
        headers = {
            name: upstream.headers[name]
            for name in CONTENT_RESPONSE_HEADERS
            if name in upstream.headers
        }
        if playlist:
            headers.pop("content-encoding", None)
            headers.pop("content-length", None)
            headers["cache-control"] = "private, no-store, no-transform"
        else:
            headers["cache-control"] = "private, max-age=300"
        if "accept-ranges" not in headers and upstream.status_code in {200, 206}:
            headers["accept-ranges"] = "bytes"
        headers["vary"] = "Cookie"
        headers["x-robots-tag"] = "noindex"
        headers["x-content-type-options"] = "nosniff"
        return headers

    def rewrite_playlist(
        self,
        content: bytes,
        playlist_url: str,
        headers: Mapping[str, str],
    ) -> bytes:
        text = content.decode("utf-8", errors="replace")
        rewritten_tokens: dict[str, str] = {}

        def proxy_uri(raw_uri: str) -> str:
            uri = raw_uri.strip()
            if not uri or uri.startswith(("data:", "blob:", "#")):
                return raw_uri
            absolute = urljoin(playlist_url, uri)
            if not _is_http_url(absolute):
                return raw_uri
            child_headers = scoped_headers(
                headers,
                source_url=playlist_url,
                target_url=absolute,
            )
            cache_key = absolute
            token = rewritten_tokens.get(cache_key)
            if token is None:
                token = self.register(
                    absolute,
                    child_headers,
                    playlist_hint=is_hls_url(absolute),
                )
                rewritten_tokens[cache_key] = token
            return _proxy_path(token)

        def replace_attribute(match: re.Match[str]) -> str:
            return (
                f"{match.group('prefix')}{match.group('quote')}"
                f"{proxy_uri(match.group('uri'))}{match.group('quote')}"
            )

        output: list[str] = []
        for line in text.splitlines(keepends=True):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                output.append(URI_ATTRIBUTE_PATTERN.sub(replace_attribute, line))
                continue
            leading = line[: len(line) - len(line.lstrip())]
            trailing = line[len(line.rstrip()) :]
            output.append(f"{leading}{proxy_uri(stripped)}{trailing}")
        return "".join(output).encode("utf-8")
