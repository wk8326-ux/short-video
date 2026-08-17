import httpx
import pytest

from app.alist import AListClient, AListError
from app.settings import Settings


def _settings() -> Settings:
    return Settings(
        alist_base_url="https://alist.example",
        alist_media_path="/media",
        alist_token="",
        alist_username="",
        alist_password="",
        database_path=":memory:",
        static_dir="/tmp/static",
        scan_interval_seconds=1800,
        direct_url_cache_seconds=90,
        video_extensions=frozenset({".mp4"}),
        auth_password_hash="scrypt:test",
        session_secret="test-session-secret-with-at-least-32-characters",
        session_days=180,
    )


@pytest.mark.asyncio
async def test_recursive_scan_and_resolve():
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["user-agent"].startswith("Mozilla/5.0")
        body = __import__("json").loads(request.content)
        if request.url.path == "/api/fs/list" and body["path"] == "/media":
            content = [
                {"name": "nested", "is_dir": True},
                {"name": "one.mp4", "is_dir": False, "size": 12, "modified": "2026-01-01"},
                {"name": "notes.txt", "is_dir": False},
            ]
        elif request.url.path == "/api/fs/list":
            content = [{"name": "two.mp4", "is_dir": False, "size": 34, "thumb": "/thumb.jpg"}]
        else:
            return httpx.Response(200, json={"code": 200, "data": {"raw_url": "https://cdn.example/video.mp4", "header": ""}})
        return httpx.Response(200, json={"code": 200, "data": {"content": content, "total": len(content)}})

    http_client = httpx.AsyncClient(base_url="https://alist.example", transport=httpx.MockTransport(handler))
    client = AListClient(_settings(), http_client)
    videos, directories = await client.scan()
    raw_url, requires_headers = await client.resolve("/media/one.mp4")
    await http_client.aclose()

    assert directories == 2
    assert [video["path"] for video in videos] == ["/media/one.mp4", "/media/nested/two.mp4"]
    assert videos[1]["thumb"] == "https://alist.example/thumb.jpg"
    assert raw_url == "https://cdn.example/video.mp4"
    assert requires_headers is False


@pytest.mark.asyncio
async def test_resolve_rejects_non_http_url():
    transport = httpx.MockTransport(
        lambda _: httpx.Response(200, json={"code": 200, "data": {"raw_url": "file:///secret.mp4"}})
    )
    http_client = httpx.AsyncClient(base_url="https://alist.example", transport=transport)
    client = AListClient(_settings(), http_client)

    with pytest.raises(AListError, match="valid raw URL"):
        await client.resolve("/media/one.mp4")
    await http_client.aclose()


@pytest.mark.asyncio
async def test_read_prefix_requests_range_and_caps_response():
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/fs/get":
            return httpx.Response(
                200,
                json={"code": 200, "data": {"raw_url": "https://cdn.example/video.mp4"}},
            )
        assert request.headers["range"] == "bytes=0-15"
        assert request.headers["user-agent"].startswith("Mozilla/5.0")
        return httpx.Response(200, content=b"a" * 100)

    http_client = httpx.AsyncClient(
        base_url="https://alist.example", transport=httpx.MockTransport(handler)
    )
    client = AListClient(_settings(), http_client)

    prefix = await client.read_prefix("/media/one.mp4", max_bytes=16)
    await http_client.aclose()

    assert prefix == b"a" * 16


@pytest.mark.asyncio
async def test_author_scan_only_indexes_direct_media_files():
    seen_paths: list[str] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        body = __import__("json").loads(request.content)
        seen_paths.append(body["path"])
        if body["path"] == "/asmr6":
            content = [
                {"name": "Author B", "is_dir": True},
                {"name": "loose.mp3", "is_dir": False},
            ]
        else:
            content = [
                {"name": "show.m3u8", "is_dir": False, "size": 100},
                {"name": "voice.mp3", "is_dir": False, "size": 20},
                {"name": "segments", "is_dir": True},
                {"name": "cover.jpg", "is_dir": False},
            ]
        return httpx.Response(
            200,
            json={"code": 200, "data": {"content": content, "total": len(content)}},
        )

    http_client = httpx.AsyncClient(
        base_url="https://asmr.example", transport=httpx.MockTransport(handler)
    )
    client = AListClient(
        _settings(),
        http_client,
        base_url="https://asmr.example",
        media_path="/asmr6",
        extensions=frozenset({".m3u8", ".mp3"}),
        anonymous=True,
    )

    records, directories = await client.scan_authors()
    await http_client.aclose()

    assert seen_paths == ["/asmr6", "/asmr6/Author B"]
    assert directories == 2
    assert [(row["name"], row["media_kind"]) for row in records] == [
        ("show.m3u8", "video"),
        ("voice.mp3", "audio"),
    ]
