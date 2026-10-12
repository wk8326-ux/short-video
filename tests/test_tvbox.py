import base64
import json
import asyncio

import httpx
import pytest

from app.tvbox import (
    TVBoxClient,
    TVBoxError,
    decode_config_payload,
    normalize_cms_url,
    parse_page_count,
    parse_detail,
)


def test_decode_plain_base64_and_hex():
    config = {"sites": [{"api": "https://example.com/api.php"}], "class": [{"type_id": 1, "type_name": "电影"}]}
    raw = json.dumps(config).encode()
    assert decode_config_payload(raw) == config
    assert decode_config_payload(base64.b64encode(raw)) == config
    assert decode_config_payload(raw.hex()) == config


def test_decode_rejects_bad_payload():
    with pytest.raises(TVBoxError):
        decode_config_payload("not-a-config")


def test_normalize_cms_url():
    url = normalize_cms_url("https://example.com/api.php?ac=old&token=x", category="1", page=2)
    assert url == "https://example.com/api.php?ac=list&token=x&t=1&pg=2"


def test_parse_page_count():
    assert parse_page_count({"pagecount": 7}) == 7
    assert parse_page_count({"total": 45, "limit": 20}) == 3
    assert parse_page_count({"list": []}) == 0


def test_client_walks_all_list_pages():
    async def run():
        client = TVBoxClient(
            None,
            httpx.AsyncClient(
                transport=httpx.MockTransport(
                    lambda request: httpx.Response(
                        200,
                        json={
                            "page": int(request.url.params["pg"]),
                            "pagecount": 3,
                            "total": 45,
                            "list": [
                                {
                                    "vod_id": f"id-{request.url.params['pg']}",
                                    "vod_name": f"Page {request.url.params['pg']}",
                                    "vod_pic": "https://example.com/cover.jpg",
                                }
                            ],
                        },
                    )
                ),
            ),
            config_url="https://example.com/api.php/provide/vod/?ac=list",
        )
        try:
            return await client.list_items_all("1")
        finally:
            await client.close()

    items = asyncio.run(run())
    assert [item["vod_id"] for item in items] == ["id-1", "id-2", "id-3"]
    assert items[0]["vod_pic"] == "https://example.com/cover.jpg"


def test_client_reports_all_list_page_progress():
    async def run():
        client = TVBoxClient(
            None,
            httpx.AsyncClient(
                transport=httpx.MockTransport(
                    lambda request: httpx.Response(
                        200,
                        json={
                            "pagecount": 3,
                            "total": 45,
                            "list": [
                                {
                                    "vod_id": f"id-{request.url.params['pg']}",
                                    "vod_name": f"Page {request.url.params['pg']}",
                                }
                            ],
                        },
                    )
                ),
            ),
            config_url="https://example.com/api.php/provide/vod/?ac=list",
        )
        progress = []
        try:
            await client.list_items_all(
                "1",
                on_progress=lambda page, pages, count: progress.append((page, pages, count)),
            )
        finally:
            await client.close()
        return progress

    assert asyncio.run(run()) == [(1, 3, 1), (2, 3, 2), (3, 3, 3)]


def test_request_retries_transient_520(monkeypatch):
    calls = {"count": 0}

    def handler(request):
        calls["count"] += 1
        if calls["count"] < 3:
            return httpx.Response(520)
        return httpx.Response(200, json={"list": []})

    async def no_sleep(_seconds):
        return None

    monkeypatch.setattr("app.tvbox.asyncio.sleep", no_sleep)

    async def run():
        client = TVBoxClient(
            None,
            httpx.AsyncClient(transport=httpx.MockTransport(handler)),
            config_url="https://example.com/api.php/provide/vod/?ac=list",
        )
        try:
            return await client.list_page("1")
        finally:
            await client.close()

    assert asyncio.run(run()).items == []
    assert calls["count"] == 3


def test_list_items_all_waits_between_pages(monkeypatch):
    sleeps = []

    async def fake_sleep(seconds):
        sleeps.append(seconds)

    monkeypatch.setattr("app.tvbox.asyncio.sleep", fake_sleep)

    async def run():
        client = TVBoxClient(
            None,
            httpx.AsyncClient(
                transport=httpx.MockTransport(
                    lambda request: httpx.Response(
                        200,
                        json={
                            "pagecount": 3,
                            "total": 45,
                            "list": [
                                {
                                    "vod_id": f"id-{request.url.params['pg']}",
                                    "vod_name": f"Page {request.url.params['pg']}",
                                }
                            ],
                        },
                    )
                ),
            ),
            config_url="https://example.com/api.php/provide/vod/?ac=list",
            page_interval_seconds=0.5,
        )
        try:
            return await client.list_items_all("1")
        finally:
            await client.close()

    items = asyncio.run(run())
    assert [item["vod_id"] for item in items] == ["id-1", "id-2", "id-3"]
    assert sleeps == [0.5, 0.5]


def test_parse_detail_episode_groups():
    item = parse_detail(
        {
            "list": [
                {
                    "vod_id": "7",
                    "vod_name": "示例剧",
                    "vod_pic": "https://example.com/poster.jpg",
                    "vod_year": "2024",
                    "vod_actor": "Alice, Bob",
                    "vod_director": "Carol",
                    "vod_class": "剧情/动作",
                    "vod_play_from": "site1$$$site2",
                    "vod_play_url": "第1集$https://example.com/1.m3u8#第2集$https://example.com/2.m3u8",
                }
            ]
        }
    )
    assert item.remote_id == "7"
    assert item.name == "示例剧"
    assert item.year == 2024
    assert item.actors == ["Alice", "Bob"]
    assert item.genres == ["剧情", "动作"]
    assert [episode.url for episode in item.episodes] == [
        "https://example.com/1.m3u8",
        "https://example.com/2.m3u8",
    ]


def test_client_resolves_first_episode(monkeypatch):
    config = {"sites": [{"api": "https://example.com/api.php"}]}
    client = TVBoxClient(None, config_json=config)
    monkeypatch.setattr(
        TVBoxClient,
        "detail",
        __import__("app.tvbox", fromlist=["TVBoxClient"]).TVBoxClient.detail,
    )

    async def fake_detail(self, remote_id):
        return parse_detail(
            {
                "list": [
                    {
                        "vod_id": remote_id,
                        "vod_play_url": "第1集$https://example.com/1.m3u8",
                    }
                ]
            }
        )

    monkeypatch.setattr(TVBoxClient, "detail", fake_detail)

    import asyncio

    url, direct = asyncio.run(client.resolve("7", 0))
    assert url == "https://example.com/1.m3u8"
    assert direct is False
