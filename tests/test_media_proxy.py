from __future__ import annotations

import httpx
import pytest
import re
from starlette.requests import Request
from starlette.responses import StreamingResponse

from app.media_proxy import MediaPlaybackProxy, UnknownPlaybackToken


def make_request(
    method: str = "GET",
    headers: tuple[tuple[str, str], ...] = (),
) -> Request:
    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    return Request(
        {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": method,
            "scheme": "http",
            "path": "/api/playback/test",
            "raw_path": b"/api/playback/test",
            "query_string": b"",
            "root_path": "",
            "headers": [(name.lower().encode(), value.encode()) for name, value in headers],
            "server": ("testserver", 80),
            "client": ("testclient", 12345),
        },
        receive,
    )


async def response_body(response) -> bytes:
    if isinstance(response, StreamingResponse):
        return b"".join([chunk async for chunk in response.body_iterator])
    return response.body


@pytest.mark.asyncio
async def test_unknown_playback_token_is_rejected():
    proxy = MediaPlaybackProxy()

    with pytest.raises(UnknownPlaybackToken):
        await proxy.handle("not-a-token", make_request())


@pytest.mark.asyncio
async def test_range_response_preserves_partial_content_headers():
    seen_range: list[str | None] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen_range.append(request.headers.get("range"))
        return httpx.Response(
            206,
            stream=httpx.ByteStream(b"video-data"),
            headers={
                "accept-ranges": "bytes",
                "content-range": "bytes 4-13/100",
                "content-type": "video/mp4",
            },
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    proxy = MediaPlaybackProxy(client=client)
    token = proxy.register("https://media.example/video.mp4")
    response = await proxy.handle(
        token,
        make_request(headers=(("Range", "bytes=4-13"),)),
    )
    body = await response_body(response)
    await proxy.close()

    assert seen_range == ["bytes=4-13"]
    assert response.status_code == 206
    assert response.headers["content-range"] == "bytes 4-13/100"
    assert response.headers["accept-ranges"] == "bytes"
    assert body == b"video-data"


@pytest.mark.asyncio
async def test_unsatisfied_range_preserves_416_content_range():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            416,
            headers={"content-range": "bytes */100", "content-length": "0"},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    proxy = MediaPlaybackProxy(client=client)
    token = proxy.register("https://media.example/video.mp4")
    response = await proxy.handle(token, make_request(headers=(("Range", "bytes=200-"),)))
    await proxy.close()

    assert response.status_code == 416
    assert response.headers["content-range"] == "bytes */100"
    assert response.body == b""


@pytest.mark.asyncio
async def test_hls_playlist_rewrites_relative_segments_and_uri_attributes():
    playlist = (
        b"#EXTM3U\n"
        b'#EXT-X-KEY:METHOD=AES-128,URI="keys/session.key"\n'
        b"variants/720p.m3u8\n"
        b"segment-001.ts\n"
    )

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            stream=httpx.ByteStream(playlist),
            headers={"content-type": "application/vnd.apple.mpegurl"},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    proxy = MediaPlaybackProxy(client=client)
    token = proxy.register(
        "https://media.example/live/master.m3u8",
        {"authorization": "Bearer secret"},
        playlist_hint=True,
    )
    response = await proxy.handle(token, make_request())
    body = await response_body(response)
    text = body.decode()
    await proxy.close()

    rewritten = re.findall(r"/api/playback/([A-Za-z0-9_-]+)", text)
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/vnd.apple.mpegurl"
    assert len(rewritten) == 3
    assert all("range" not in proxy.resolve(item).headers for item in rewritten)
    assert all("if-range" not in proxy.resolve(item).headers for item in rewritten)
    assert [proxy.resolve(item).url for item in rewritten] == [
        "https://media.example/live/keys/session.key",
        "https://media.example/live/variants/720p.m3u8",
        "https://media.example/live/segment-001.ts",
    ]


@pytest.mark.asyncio
async def test_cross_origin_redirect_does_not_forward_provider_headers():
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.host == "provider.example":
            return httpx.Response(302, headers={"location": "https://cdn.example/video.mp4"})
        return httpx.Response(200, stream=httpx.ByteStream(b"video"))

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    proxy = MediaPlaybackProxy(client=client)
    token = proxy.register(
        "https://provider.example/video.mp4",
        {
            "authorization": "Bearer secret",
            "cookie": "session=secret",
            "x-provider-token": "secret",
            "referer": "https://provider.example/watch",
        },
    )
    response = await proxy.handle(token, make_request())
    body = await response_body(response)
    await proxy.close()

    assert len(seen) == 2
    assert seen[0].headers["authorization"] == "Bearer secret"
    assert seen[1].url.host == "cdn.example"
    assert "authorization" not in seen[1].headers
    assert "cookie" not in seen[1].headers
    assert "x-provider-token" not in seen[1].headers
    assert "referer" not in seen[1].headers
    assert body == b"video"
