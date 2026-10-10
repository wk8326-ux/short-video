import base64
import json

import pytest

from app.tvbox import (
    TVBoxClient,
    TVBoxError,
    decode_config_payload,
    normalize_cms_url,
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
