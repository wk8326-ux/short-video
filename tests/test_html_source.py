from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from app.database import LibraryDatabase
from app.html_source import (
    HtmlSourceClient,
    HtmlSourceItem,
    parse_91cr_category_page,
    parse_91cr_video_metadata,
)


def test_parse_91cr_category_page_reads_cards_cover_count_and_pager():
    page = """
    <nav class="pager" data-pages="3"></nav>
    <a class="card pm-ssp-content-card" data-track-item-id="3010"
       data-track-item-type="video" data-track-item-name="测试短剧"
       href="/duanju/3010-test/">
      <img class="p-img" src="data:image/gif;base64,AA" data-src="//img.example/poster.jpg">
      <span class="eps-flag">更新至 12 集</span>
    </a>
    """

    items, pages = parse_91cr_category_page(page, "https://91crdjai.com", "duanju")

    assert pages == 3
    assert len(items) == 1
    assert items[0].remote_id == "3010"
    assert items[0].name == "测试短剧"
    assert items[0].poster_url == "https://img.example/poster.jpg"
    assert items[0].episode_count == 12


def test_parse_91cr_metadata_ignores_signed_video_url():
    fallback = HtmlSourceItem(
        remote_id="3010",
        name="列表标题",
        poster_url="https://img.example/card.jpg",
        year=None,
        overview="",
        tags=[],
        episode_count=3,
    )

    item = parse_91cr_video_metadata(
        {
            "status": 1,
            "data": {
                "id": 3010,
                "title": "详情标题",
                "description": "剧情简介",
                "cover": "/covers/3010.jpg",
                "created_at": "2026-08-01T00:00:00Z",
                "tags": [{"name": "都市"}, "复仇"],
                "video_url": "https://cdn.example/expired.m3u8?auth_key=secret",
            },
        },
        fallback,
    )

    assert item.name == "详情标题"
    assert item.year == 2026
    assert item.poster_url == "https://91crdjai.com/covers/3010.jpg"
    assert item.overview == "剧情简介"
    assert item.tags == ["都市", "复仇"]
    assert item.episode_count == 3
    assert not hasattr(item, "video_url")


@pytest.mark.asyncio
async def test_91cr_client_paginates_and_resolves_a_fresh_playback_url():
    requests: list[httpx.Request] = []

    async def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/duanju/":
            return httpx.Response(
                200,
                text=(
                    '<nav class="pager" data-pages="2"></nav>'
                    '<a class="card" data-track-item-id="3010" '
                    'data-track-item-type="video" data-track-item-name="第一部" '
                    'href="/duanju/3010-a/"><img data-src="/a.jpg">1集</a>'
                ),
            )
        if request.url.path == "/duanju/page/2/":
            return httpx.Response(
                200,
                text=(
                    '<nav class="pager" data-pages="2"></nav>'
                    '<a class="card" data-track-item-id="3011" '
                    'data-track-item-type="video" data-track-item-name="第二部" '
                    'href="/duanju/3011-b/"><img data-src="/b.jpg">2集</a>'
                ),
            )
        if request.url.path == "/videos/3010/episodes/2/playback":
            return httpx.Response(
                200,
                json={"status": 1, "data": {"n": 2, "src": "https://cdn.example/one.m3u8?auth_key=fresh"}},
            )
        return httpx.Response(404)

    client = HtmlSourceClient(None, "https://91crdjai.com")
    await client._client.aclose()
    client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        items = await client.list_items("duanju")
        first, requires_headers = await client.resolve("/html/duanju/3010/2")
        second, _ = await client.resolve("/html/duanju/3010/2")
    finally:
        await client.close()

    assert [item.remote_id for item in items] == ["3010", "3011"]
    assert first.endswith("auth_key=fresh")
    assert second == first
    playback_requests = [request for request in requests if request.url.path.endswith("/playback")]
    assert len(playback_requests) == 2
    assert all(request.headers["referer"] == "https://91crdjai.com/" for request in requests)


def test_remote_drama_index_is_idempotent_and_links_active_episodes(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    database.replace_scan(
        [
            {"path": "/html/duanju/3010/1", "name": "第一部 第01集.m3u8", "thumb": "https://img/1.jpg"},
            {"path": "/html/duanju/3010/2", "name": "第一部 第02集.m3u8", "thumb": "https://img/1.jpg"},
            {"path": "/html/duanju/3011/1", "name": "第二部 第01集.m3u8", "thumb": "https://img/2.jpg"},
        ],
        source="html-91crdjai-duanju",
    )
    items = [
        {"remote_id": "3010", "name": "第一部", "poster_url": "https://img/1.jpg"},
        {"remote_id": "3011", "name": "第二部", "poster_url": "https://img/2.jpg"},
    ]

    assert database.sync_remote_drama_index("html-91crdjai-duanju", "duanju", items) == 2
    first = database.dramas(sources=("html-91crdjai-duanju",), limit=10)
    assert database.sync_remote_drama_index("html-91crdjai-duanju", "duanju", items) == 2
    second = database.dramas(sources=("html-91crdjai-duanju",), limit=10)

    assert {item["id"] for item in first} == {item["id"] for item in second}
    assert {item["title"] for item in second} == {"第一部", "第二部"}
    drama_id = next(item["id"] for item in second if item["title"] == "第一部")
    assert len(database.drama_episodes(str(drama_id))) == 2


def test_legacy_cr_drama_replacement_is_narrow_and_one_time(tmp_path):
    database = LibraryDatabase(str(tmp_path / "library.db"))
    database.initialize()
    base = {
        "provider": "alist",
        "base_url": "https://media.example",
        "root_path": "/",
        "section": "drama",
        "enabled": True,
    }
    old = database.add_media_source({**base, "id": "old", "name": "光鸭 / CR短剧"})
    retained = database.add_media_source(
        {**base, "id": "retained", "name": "个人短剧", "root_path": "/personal"}
    )

    assert database.remove_legacy_cr_drama_sources() == 1
    assert database.get_media_source(str(old["id"])) is None
    assert database.get_media_source(str(retained["id"])) is not None

    added_later = database.add_media_source(
        {**base, "id": "added-later", "name": "CR短剧归档", "root_path": "/archive"}
    )
    assert database.remove_legacy_cr_drama_sources() == 0
    assert database.get_media_source(str(added_later["id"])) is not None


@pytest.mark.asyncio
async def test_drama_wall_exposes_parent_source_and_filters_virtual_category(
    monkeypatch, tmp_path
):
    from test_scan import boot

    main = await boot(monkeypatch, tmp_path)
    child_id = "html-91crdjai-duanju"
    main.database.add_media_source(
        {
            "id": child_id,
            "name": "成人短剧",
            "provider": "html",
            "base_url": "https://91crdjai.com",
            "root_path": "/html/duanju",
            "root_paths": ["/html/duanju"],
            "section": "drama",
            "scan_mode": "remote_pages",
            "anonymous": True,
            "token": "",
            "username": "",
            "password": "",
            "enabled": True,
            "parent_source_id": "91crdjai",
            "virtual_category_id": "duanju",
            "virtual_category_name": "成人短剧",
            "virtual_category_type": "html",
        }
    )
    main.database.replace_scan(
        [
            {
                "path": "/html/duanju/3010/1",
                "name": "测试短剧 第01集.m3u8",
                "thumb": "https://img.example/3010.jpg",
            }
        ],
        source=child_id,
    )
    main.database.sync_remote_drama_index(
        child_id,
        "duanju",
        [{"remote_id": "3010", "name": "测试短剧", "poster_url": "https://img.example/3010.jpg"}],
    )
    await main.source_registry.reload()

    with TestClient(main.app) as client:
        headers = {"Cookie": f"short_session={main.session_manager.issue()}"}
        wall = client.get("/api/dramas/groups", headers=headers).json()
        selected = client.get(
            "/api/dramas/groups",
            params={"sourceId": "parent:91crdjai"},
            headers=headers,
        )
        page = client.get(
            "/api/dramas",
            params={
                "sourceId": "parent:91crdjai",
                "category": "duanju",
            },
            headers=headers,
        )

    assert selected.status_code == 200
    assert wall["sources"][0]["id"] == "parent:91crdjai"
    assert wall["sources"][0]["libraryIds"] == [child_id]
    assert selected.json()["selectedSourceId"] == "parent:91crdjai"
    assert selected.json()["groups"][0]["groupId"] == f"{child_id}|duanju"
    assert page.status_code == 200
    assert page.json()["total"] == 1


@pytest.mark.asyncio
async def test_91cr_scan_materializes_all_four_drama_libraries(monkeypatch, tmp_path):
    from test_scan import boot

    main = await boot(monkeypatch, tmp_path)
    runtime = main.source_registry.get("91crdjai")
    await runtime.client._client.aclose()

    async def handler(request: httpx.Request) -> httpx.Response:
        category = next(
            (slug for slug in ("duanju", "manju", "zhenrenju", "shipin") if request.url.path.startswith(f"/{slug}/")),
            None,
        )
        if category:
            return httpx.Response(
                200,
                text=(
                    '<nav class="pager" data-pages="1"></nav>'
                    f'<a class="card" data-track-item-id="{3000 + len(category)}" '
                    f'data-track-item-type="video" data-track-item-name="{category}测试" '
                    f'href="/{category}/{3000 + len(category)}-test/">'
                    '<img data-src="/poster.jpg">1集</a>'
                ),
            )
        return httpx.Response(404)

    runtime.client._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        assert await main.scan_html_source("91crdjai") is True
        child_ids = {
            source_id
            for source_id in main.source_registry.ids("drama")
            if source_id.startswith("html-91crdjai-")
        }
        assert child_ids == {
            "html-91crdjai-duanju",
            "html-91crdjai-manju",
            "html-91crdjai-zhenrenju",
            "html-91crdjai-shipin",
        }
        assert all(
            main.database.drama_count(sources=(source_id,)) == 1
            for source_id in child_ids
        )
        children = [
            main.database.get_media_source(source_id)
            for source_id in child_ids
        ]
        assert all(child and child["parent_source_id"] == "91crdjai" for child in children)
    finally:
        await main.source_registry.close()
