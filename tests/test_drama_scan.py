"""Short-drama indexing: series grouping, rescan stability and title recovery.

Episodes reuse the ordinary ``videos`` table, so the risk here is not playback
but grouping: one ungrouped episode shows up as its own one-episode series on
the wall, and an episode that keeps a stale ``series_id`` keeps a deleted
series alive. Both are pinned down below by driving the real ``scan_source``
against an in-memory AList.
"""

from __future__ import annotations

import sqlite3

import pytest

from test_scan import (  # noqa: F401 - boot/serve are the shared AList harness
    BASE_URL,
    FakeAList,
    boot,
    build_tree,
    serve,
)

DRAMA_ROOT = "/guangya-root/下载器/CR短剧"
CATEGORY_SHORT = f"{DRAMA_ROOT}/短剧"
CATEGORY_MANGA = f"{DRAMA_ROOT}/漫剧"
SERIES_SWAP_DIR = f"{CATEGORY_SHORT}/交换游戏 [91crdj-1172]"
SERIES_PLACEHOLDER_DIR = f"{CATEGORY_SHORT}/▶ 立即观看 [91crdj-1192]"
SERIES_DESERT_DIR = f"{CATEGORY_MANGA}/大漠遗孤 [91crdj-1301]"

# ``dramas.folder`` is stored relative to the source root, so the wall keys on
# ``<category>/<series>`` rather than on the absolute path.
SERIES_SWAP = "短剧/交换游戏 [91crdj-1172]"
SERIES_PLACEHOLDER = "短剧/▶ 立即观看 [91crdj-1192]"
SERIES_DESERT = "漫剧/大漠遗孤 [91crdj-1301]"


async def add_drama_source(main, root_path: str = DRAMA_ROOT) -> str:
    source = main.database.add_media_source(
        {
            "name": "光鸭 / CR短剧",
            "provider": "alist",
            "base_url": BASE_URL,
            "root_path": root_path,
            "section": "drama",
            "scan_mode": "tree",
            "anonymous": False,
            "token": "",
            "username": "",
            "password": "",
            "enabled": True,
        }
    )
    await main.source_registry.reload_source(source["id"])
    return str(source["id"])


def drama_rows(main, source: str) -> dict[str, dict]:
    connection = sqlite3.connect(main.database.path)
    connection.row_factory = sqlite3.Row
    try:
        return {
            str(row["folder"]): dict(row)
            for row in connection.execute(
                "SELECT * FROM dramas WHERE source = ?", (source,)
            ).fetchall()
        }
    finally:
        connection.close()


def episode_links(main, source: str) -> dict[str, str | None]:
    connection = sqlite3.connect(main.database.path)
    connection.row_factory = sqlite3.Row
    try:
        return {
            str(row["path"]): row["series_id"]
            for row in connection.execute(
                "SELECT path, series_id FROM videos WHERE source = ? AND active = 1",
                (source,),
            ).fetchall()
        }
    finally:
        connection.close()


def drama_tree() -> dict[str, list[dict]]:
    return build_tree(
        {
            # The source root must answer even though it only holds category
            # folders; a scan that cannot list the root never reaches a series.
            DRAMA_ROOT: [],
            CATEGORY_SHORT: [],
            CATEGORY_MANGA: [],
            SERIES_SWAP_DIR: [
                "交换游戏 第01集 [91crdj-1172-1].mp4",
                "交换游戏 第02集 [91crdj-1172-2].mp4",
                "交换游戏 第03集 [91crdj-1172-3].mp4",
                # A half-finished download and a stray sidecar must never be
                # counted as episodes.
                "交换游戏 第04集 [91crdj-1172-4].mp4.pending",
                "交换游戏.html",
            ],
            SERIES_PLACEHOLDER_DIR: ["▶ 立即观看 第01集 [91crdj-1192-1].mp4"],
            SERIES_DESERT_DIR: [
                "大漠遗孤 第01集 [91crdj-1301-1].mp4",
                "大漠遗孤 第02集 [91crdj-1301-2].mp4",
            ],
        }
    )


@pytest.mark.asyncio
async def test_scan_groups_episodes_into_series(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await add_drama_source(main)
    await serve(main, source, FakeAList(drama_tree()))

    assert await main.scan_source(source) is True

    series = drama_rows(main, source)
    assert {row["title"] for row in series.values()} == {
        "交换游戏",
        "大漠遗孤",
        "▶ 立即观看",
    }
    assert series[SERIES_SWAP]["episode_count"] == 3
    assert series[SERIES_SWAP]["category"] == "短剧"
    assert series[SERIES_SWAP]["code"] == "91crdj-1172"
    assert series[SERIES_DESERT]["category"] == "漫剧"
    assert series[SERIES_DESERT]["episode_count"] == 2

    links = episode_links(main, source)
    assert (
        links[f"{SERIES_SWAP_DIR}/交换游戏 第01集 [91crdj-1172-1].mp4"]
        == series[SERIES_SWAP]["id"]
    )
    assert stats_episode_count(main, source) == 6
    await main.source_registry.close()


def stats_episode_count(main, source: str) -> int:
    connection = sqlite3.connect(main.database.path)
    try:
        row = connection.execute(
            "SELECT COUNT(*) FROM videos WHERE source = ? AND active = 1", (source,)
        ).fetchone()
        return int(row[0])
    finally:
        connection.close()


@pytest.mark.asyncio
async def test_detail_lists_episodes_in_play_order(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await add_drama_source(main)
    await serve(main, source, FakeAList(drama_tree()))
    assert await main.scan_source(source) is True

    series = drama_rows(main, source)[SERIES_SWAP]
    episodes = main.database.drama_episodes(str(series["id"]))
    episodes.sort(key=lambda item: main.episode_order_key(str(item["name"])))
    assert [item["name"] for item in episodes] == [
        "交换游戏 第01集 [91crdj-1172-1].mp4",
        "交换游戏 第02集 [91crdj-1172-2].mp4",
        "交换游戏 第03集 [91crdj-1172-3].mp4",
    ]
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_rescan_keeps_series_and_recovers_a_play_button_title(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await add_drama_source(main)
    await serve(main, source, FakeAList(drama_tree()))
    assert await main.scan_source(source) is True

    placeholder = drama_rows(main, source)[SERIES_PLACEHOLDER]
    main.database.update_drama_metadata(
        str(placeholder["id"]),
        title="临时夫妻",
        overview="简介",
        tags='["AI短剧"]',
        poster_url="https://pic.tuafjz.cn/upload/x.jpeg",
        metadata_status="matched",
    )

    assert await main.scan_source(source) is True

    series = drama_rows(main, source)
    assert len(series) == 3
    recovered = series[SERIES_PLACEHOLDER]
    # The folder is named after the site's play button; a rescan must not undo
    # the title (and artwork) the metadata pass recovered for it.
    assert recovered["title"] == "临时夫妻"
    assert recovered["folder_title"] == "▶ 立即观看"
    assert recovered["poster_url"] == "https://pic.tuafjz.cn/upload/x.jpeg"
    assert recovered["overview"] == "简介"
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_a_removed_folder_retires_its_series(monkeypatch, tmp_path):
    main = await boot(monkeypatch, tmp_path)
    source = await add_drama_source(main)
    await serve(main, source, FakeAList(drama_tree()))
    assert await main.scan_source(source) is True

    trimmed = build_tree(
        {
            DRAMA_ROOT: [],
            CATEGORY_SHORT: [],
            SERIES_SWAP_DIR: [
                "交换游戏 第01集 [91crdj-1172-1].mp4",
                "交换游戏 第02集 [91crdj-1172-2].mp4",
            ],
        }
    )
    await serve(main, source, FakeAList(trimmed))
    assert await main.scan_source(source) is True

    series = drama_rows(main, source)
    assert list(series) == [SERIES_SWAP]
    assert series[SERIES_SWAP]["episode_count"] == 2
    assert set(episode_links(main, source).values()) == {series[SERIES_SWAP]["id"]}
    await main.source_registry.close()


@pytest.mark.asyncio
async def test_a_library_without_series_folders_indexes_nothing(monkeypatch, tmp_path):
    """Files sitting directly in the root are not a series."""
    main = await boot(monkeypatch, tmp_path)
    source = await add_drama_source(main)
    await serve(main, source, FakeAList(build_tree({DRAMA_ROOT: ["loose.mp4"]})))

    assert await main.scan_source(source) is True

    assert drama_rows(main, source) == {}
    assert episode_links(main, source) == {
        f"{DRAMA_ROOT}/loose.mp4": None,
    }
    await main.source_registry.close()
