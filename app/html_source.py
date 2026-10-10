from __future__ import annotations

import html as html_module
import json
import re
from dataclasses import dataclass
from typing import Any
from urllib.parse import urljoin, urlsplit

import httpx


class HtmlSourceError(RuntimeError):
    pass


@dataclass(frozen=True)
class HtmlSourceCategory:
    remote_id: str
    name: str


@dataclass(frozen=True)
class HtmlSourceItem:
    remote_id: str
    name: str
    poster_url: str
    year: int | None
    overview: str
    tags: list[str]
    episode_count: int


DEFAULT_CATEGORY_NAMES = {
    "duanju": "成人短剧",
    "manju": "成人漫剧",
    "zhenrenju": "真人剧",
    "shipin": "成人视频",
    "manhua": "成人漫画",
    "xiaoshuo": "成人小说",
}

VIDEO_CATEGORY_IDS = frozenset({"duanju", "manju", "zhenrenju", "shipin"})

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
)


def _unescape(value: str) -> str:
    return html_module.unescape(value or "").strip()


def _absolute_url(base_url: str, value: str) -> str:
    return urljoin(f"{base_url.rstrip('/')}/", value.lstrip("/"))


def _category_from_path(url: str) -> str:
    path = urlsplit(url).path.strip("/")
    return path.split("/", 1)[0] if path else ""


def parse_sitemap(payload: str) -> list[str]:
    """Return every ``<loc>`` URL from a sitemap or sitemap index."""
    return [
        _unescape(match.group(1))
        for match in re.finditer(r"<loc>\s*(.*?)\s*</loc>", payload, re.I | re.S)
        if match.group(1).strip()
    ]


def parse_categories_from_sitemap(payload: str) -> list[HtmlSourceCategory]:
    categories: list[HtmlSourceCategory] = []
    seen: set[str] = set()
    for url in parse_sitemap(payload):
        slug = _category_from_path(url)
        if not slug or slug in seen:
            continue
        seen.add(slug)
        name = DEFAULT_CATEGORY_NAMES.get(slug, slug)
        categories.append(HtmlSourceCategory(remote_id=slug, name=name))
    return categories


def _ld_graph(page: str) -> list[dict[str, Any]]:
    for block in re.findall(
        r'<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>',
        page,
        re.S,
    ):
        try:
            payload = json.loads(block.strip())
        except ValueError:
            continue
        graph = payload.get("@graph") if isinstance(payload, dict) else None
        nodes = graph if isinstance(graph, list) else [payload]
        return [node for node in nodes if isinstance(node, dict)]
    return []


def _episode_anchors(page: str) -> list[tuple[int, str]]:
    """Episode number and href pairs from the ``.ep-grid`` block."""
    grid_match = re.search(
        r'<div[^>]*class="[^"]*ep-grid[^"]*"[^>]*>(.*?)</div>',
        page,
        re.I | re.S,
    )
    block = grid_match.group(1) if grid_match else page
    episodes: list[tuple[int, str]] = []
    for match in re.finditer(
        r'<a[^>]+href="([^"]+)"[^>]*>\s*0*(\d+)\s*</a>',
        block,
        re.I | re.S,
    ):
        href = _unescape(match.group(1))
        number = int(match.group(2))
        if href and number > 0:
            episodes.append((number, href))
    return episodes


def parse_detail_page(page: str, page_url: str) -> HtmlSourceItem:
    """One series from a Typecho-style detail page."""
    path = urlsplit(page_url).path.strip("/")
    if not path:
        raise HtmlSourceError("详情页缺少路径")
    title = ""
    overview = ""
    tags: list[str] = []
    poster_url = ""
    year: int | None = None

    for node in _ld_graph(page):
        if str(node.get("@type") or "").lower() not in {"tvseries", "movie", "videoobject"}:
            continue
        title = str(node.get("name") or "").strip()
        overview = _unescape(str(node.get("description") or ""))
        genres = node.get("genre") or node.get("keywords") or []
        if isinstance(genres, str):
            genres = [part.strip() for part in re.split(r"[,，/|]", genres)]
        tags = [str(item).strip() for item in genres if str(item).strip()]
        date = str(node.get("datePublished") or "")[:4]
        if date.isdigit():
            year = int(date)
        image = node.get("image")
        if isinstance(image, dict):
            poster_url = str(image.get("contentUrl") or "").strip()
        break

    if not title:
        match = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]*)"', page)
        if match:
            title = _unescape(match.group(1))
    if not overview:
        match = re.search(
            r'<meta[^>]+property="og:description"[^>]+content="([^"]*)"', page
        )
        if match:
            overview = _unescape(match.group(1))
    if not poster_url:
        match = re.search(r'<meta[^>]+property="og:image"[^>]+content="([^"]*)"', page)
        if match:
            poster_url = _unescape(match.group(1))

    episodes = _episode_anchors(page)
    return HtmlSourceItem(
        remote_id=path,
        name=title or path.rsplit("/", 2)[-2].replace("-", " "),
        poster_url=poster_url,
        year=year,
        overview=overview,
        tags=tags,
        episode_count=len(episodes),
    )


def parse_play_page(page: str) -> str:
    """The signed m3u8 URL from one episode page."""
    match = re.search(
        r'https?://[^"\'\s<>\\]+?\.m3u8(?:\?[^"\'\s<>\\]*)?',
        html_module.unescape(page),
    )
    if not match:
        raise HtmlSourceError("播放页没有可用的 m3u8 链接")
    return match.group(0)


class HtmlSourceClient:
    """A generic HTML scraper provider for Typecho-style drama sites."""

    def __init__(self, settings: Any, base_url: str):
        self.settings = settings
        self.base_url = base_url.rstrip("/")
        self._client = httpx.AsyncClient(
            follow_redirects=True,
            timeout=httpx.Timeout(20.0, connect=10.0),
            headers={"User-Agent": USER_AGENT},
            limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
        )
        self._sitemap: list[str] | None = None

    async def close(self) -> None:
        await self._client.aclose()

    async def _request_text(self, url: str) -> str:
        try:
            response = await self._client.get(url)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise HtmlSourceError(f"HTML 源请求失败: {exc}") from exc
        return response.text

    async def _sitemap_urls(self) -> list[str]:
        if self._sitemap is None:
            payload = await self._request_text(f"{self.base_url}/sitemap.xml")
            self._sitemap = parse_sitemap(payload)
        return self._sitemap

    async def categories(self) -> list[HtmlSourceCategory]:
        urls = await self._sitemap_urls()
        slugs = {_category_from_path(url) for url in urls}
        wanted = [slug for slug in VIDEO_CATEGORY_IDS if slug in slugs]
        categories = [HtmlSourceCategory(slug, DEFAULT_CATEGORY_NAMES.get(slug, slug)) for slug in wanted]
        if not categories:
            raise HtmlSourceError("HTML 源没有视频分类")
        return categories

    async def list_items(self, category: str) -> list[HtmlSourceItem]:
        urls = [
            url
            for url in await self._sitemap_urls()
            if _category_from_path(url) == category
        ]
        semaphore = __import__("asyncio").Semaphore(4)

        async def fetch(url: str) -> HtmlSourceItem | None:
            async with semaphore:
                try:
                    page = await self._request_text(url)
                    return parse_detail_page(page, url)
                except (HtmlSourceError, ValueError):
                    return None

        import asyncio

        results = await asyncio.gather(*(fetch(url) for url in urls))
        return [item for item in results if item is not None]

    async def resolve(self, path: str, episode: int = 0) -> tuple[str, bool]:
        """Resolve one stored episode path to a signed playback URL."""
        parts = [part for part in path.strip("/").split("/") if part]
        if len(parts) < 3 or parts[0] != "html":
            raise HtmlSourceError("HTML 源路径无效")
        remote_id = "/".join(parts[1:-1])
        try:
            episode_number = int(parts[-1])
        except ValueError as exc:
            raise HtmlSourceError("HTML 源集数无效") from exc
        detail_url = _absolute_url(self.base_url, remote_id)
        if not detail_url.endswith("/"):
            detail_url += "/"
        detail_page = await self._request_text(detail_url)
        episodes = dict(_episode_anchors(detail_page))
        href = episodes.get(episode_number)
        if not href:
            raise HtmlSourceError("HTML 源剧集不存在")
        episode_url = _absolute_url(detail_url, href)
        play_page = await self._request_text(episode_url)
        return parse_play_page(play_page), False
