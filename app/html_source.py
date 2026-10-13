from __future__ import annotations

import html as html_module
import asyncio
import json
import re
from dataclasses import dataclass
from html.parser import HTMLParser
from typing import Any
from urllib.parse import quote, urljoin, urlsplit

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
    published_at: str | None = None


DEFAULT_CATEGORY_NAMES = {
    "duanju": "成人短剧",
    "manju": "成人漫剧",
    "zhenrenju": "真人剧",
    "shipin": "成人视频",
    "manhua": "成人漫画",
    "xiaoshuo": "成人小说",
}

VIDEO_CATEGORY_IDS = frozenset({"duanju", "manju", "zhenrenju", "shipin"})
SITE_VIDEO_CATEGORY_IDS = ("duanju", "manju", "zhenrenju", "shipin")
TRANSIENT_HTTP_STATUS = frozenset({429, 500, 502, 503, 504, 520, 521, 522, 524})

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
)


def _unescape(value: str) -> str:
    return html_module.unescape(value or "").strip()


def _absolute_url(base_url: str, value: str) -> str:
    return urljoin(f"{base_url.rstrip('/')}/", value)


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


class _CategoryPageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.cards: list[dict[str, str]] = []
        self.page_count = 1
        self._card: dict[str, str] | None = None
        self._anchor_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = {key.lower(): value or "" for key, value in attrs}
        classes = set(values.get("class", "").split())
        if self._card is None and tag == "nav" and "pager" in classes:
            try:
                self.page_count = max(1, int(values.get("data-pages", "1")))
            except ValueError:
                self.page_count = 1
        if (
            self._card is None
            and tag == "a"
            and "card" in classes
            and values.get("data-track-item-type") == "video"
        ):
            self._card = {
                "id": values.get("data-track-item-id", ""),
                "name": values.get("data-track-item-name", ""),
                "href": values.get("href", ""),
                "poster": "",
                "text": "",
            }
            self._anchor_depth = 1
            return
        if self._card is None:
            return
        if tag == "a":
            self._anchor_depth += 1
        if tag == "img" or "poster" in classes:
            image = values.get("data-src") or values.get("data-original") or values.get("src")
            if image and not image.startswith("data:"):
                self._card["poster"] = image

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_data(self, data: str) -> None:
        if self._card is not None:
            self._card["text"] += f" {data.strip()}"

    def handle_endtag(self, tag: str) -> None:
        if self._card is None or tag != "a":
            return
        self._anchor_depth -= 1
        if self._anchor_depth <= 0:
            self.cards.append(self._card)
            self._card = None
            self._anchor_depth = 0


def parse_91cr_category_page(
    page: str, base_url: str, category: str
) -> tuple[list[HtmlSourceItem], int]:
    parser = _CategoryPageParser()
    parser.feed(page)
    items: list[HtmlSourceItem] = []
    seen: set[str] = set()
    for card in parser.cards:
        remote_id = card["id"].strip()
        if not remote_id:
            match = re.search(r"/(?:duanju|manju|zhenrenju|shipin)/(\d+)(?:-|/)", card["href"])
            remote_id = match.group(1) if match else ""
        if not remote_id.isdigit() or remote_id in seen:
            continue
        seen.add(remote_id)
        name = _unescape(card["name"])
        text = " ".join(card["text"].split())
        episode_match = re.search(r"(?:更新至|已更新至|全)?\s*(\d{1,4})\s*集", text)
        items.append(
            HtmlSourceItem(
                remote_id=remote_id,
                name=name or remote_id,
                poster_url=(
                    _absolute_url(base_url, card["poster"])
                    if card["poster"]
                    else ""
                ),
                year=None,
                overview="",
                tags=[],
                episode_count=max(1, int(episode_match.group(1))) if episode_match else 1,
            )
        )
    page_count = parser.page_count
    if page_count <= 1:
        # Cached pages can omit the pager node while keeping the canonical next
        # link or numbered page links. Do not silently index only the first
        # page in that case. The sentinel is stopped by list_items at the first
        # empty page.
        page_numbers = [
            int(match.group(1))
            for match in re.finditer(
                rf"/{re.escape(category)}/page/(\d+)/", page
            )
        ]
        if page_numbers:
            page_count = max(page_numbers)
        elif re.search(r'<link[^>]+rel=["\']next["\']', page, re.I):
            page_count = 1000
    return items, page_count


def parse_91cr_video_metadata(payload: Any, fallback: HtmlSourceItem) -> HtmlSourceItem:
    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, dict):
        raise HtmlSourceError("91crdjai 元数据响应格式无效")
    if payload.get("status") not in (None, 1, 200, "1", "200"):
        raise HtmlSourceError("91crdjai 元数据接口返回失败")
    raw_tags = data.get("tags") or []
    if not isinstance(raw_tags, list):
        raw_tags = [raw_tags]
    tags = [
        str(tag.get("name") or tag.get("title") or "").strip()
        if isinstance(tag, dict)
        else str(tag).strip()
        for tag in raw_tags
    ]
    tags = list(
        dict.fromkeys(
            part.strip()
            for tag in tags
            for part in re.split(r"[,，、|/]+", tag)
            if part.strip()
        )
    )
    try:
        year = int(str(data.get("created_at") or "")[:4])
    except ValueError:
        year = None
    raw_episode_count = (
        data.get("episode_count")
        or data.get("episodeCount")
        or data.get("numberOfEpisodes")
    )
    if raw_episode_count is None and isinstance(data.get("episodes"), list):
        raw_episode_count = len(data["episodes"])
    try:
        episode_count = max(fallback.episode_count, int(raw_episode_count or 0))
    except (TypeError, ValueError):
        episode_count = fallback.episode_count
    cover = str(data.get("cover") or "").strip()
    poster_url = (
        _absolute_url("https://91crdjai.com", cover)
        if cover and not urlsplit(cover).scheme
        else cover or fallback.poster_url
    )
    return HtmlSourceItem(
        remote_id=str(data.get("id") or fallback.remote_id),
        name=str(data.get("title") or fallback.name).strip(),
        poster_url=poster_url,
        year=year,
        overview=_unescape(str(data.get("description") or fallback.overview)),
        tags=tags or fallback.tags,
        episode_count=episode_count,
        published_at=str(data.get("created_at") or "") or fallback.published_at,
    )


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
        self._host = (urlsplit(self.base_url).hostname or "").lower().removeprefix("www.")

    @property
    def is_91crdjai(self) -> bool:
        return self._host == "91crdjai.com"

    async def close(self) -> None:
        await self._client.aclose()

    async def _request_text(self, url: str) -> str:
        for attempt in range(3):
            try:
                response = await self._client.get(
                    url,
                    headers={"Referer": f"{self.base_url}/"},
                )
                if response.status_code in TRANSIENT_HTTP_STATUS and attempt < 2:
                    retry_after = response.headers.get("Retry-After")
                    try:
                        delay = min(8.0, max(0.5, float(retry_after))) if retry_after else 0.5 * (2**attempt)
                    except ValueError:
                        delay = 0.5 * (2**attempt)
                    await asyncio.sleep(delay)
                    continue
                response.raise_for_status()
                return response.content.decode("utf-8-sig", errors="replace")
            except httpx.HTTPError as exc:
                if attempt < 2 and not isinstance(exc, httpx.HTTPStatusError):
                    await asyncio.sleep(0.5 * (2**attempt))
                    continue
                raise HtmlSourceError(f"HTML 源请求失败: {exc}") from exc
        raise HtmlSourceError("HTML 源请求失败: 重试次数已耗尽")

    async def _request_json(self, url: str) -> Any:
        for attempt in range(3):
            try:
                response = await self._client.get(
                    url,
                    headers={"Referer": f"{self.base_url}/", "Accept": "application/json"},
                )
                if response.status_code in TRANSIENT_HTTP_STATUS and attempt < 2:
                    await asyncio.sleep(0.5 * (2**attempt))
                    continue
                response.raise_for_status()
                return response.json()
            except httpx.HTTPError as exc:
                if attempt < 2 and not isinstance(exc, httpx.HTTPStatusError):
                    await asyncio.sleep(0.5 * (2**attempt))
                    continue
                raise HtmlSourceError(f"HTML 源 JSON 请求失败: {exc}") from exc
            except ValueError as exc:
                raise HtmlSourceError(f"HTML 源 JSON 响应格式无效: {exc}") from exc
        raise HtmlSourceError("HTML 源 JSON 请求失败: 重试次数已耗尽")

    async def _sitemap_urls(self) -> list[str]:
        if self._sitemap is None:
            payload = await self._request_text(f"{self.base_url}/sitemap.xml")
            self._sitemap = parse_sitemap(payload)
        return self._sitemap

    async def categories(self) -> list[HtmlSourceCategory]:
        if self.is_91crdjai:
            return [
                HtmlSourceCategory(slug, DEFAULT_CATEGORY_NAMES[slug])
                for slug in SITE_VIDEO_CATEGORY_IDS
            ]
        urls = await self._sitemap_urls()
        slugs = {_category_from_path(url) for url in urls}
        wanted = [slug for slug in VIDEO_CATEGORY_IDS if slug in slugs]
        categories = [HtmlSourceCategory(slug, DEFAULT_CATEGORY_NAMES.get(slug, slug)) for slug in wanted]
        if not categories:
            raise HtmlSourceError("HTML 源没有视频分类")
        return categories

    async def list_items(self, category: str) -> list[HtmlSourceItem]:
        if self.is_91crdjai:
            if category not in SITE_VIDEO_CATEGORY_IDS:
                raise HtmlSourceError("91crdjai 视频分类无效")
            items: dict[str, HtmlSourceItem] = {}
            page_count = 1
            for page_number in range(1, 1001):
                if page_number == 1:
                    url = f"{self.base_url}/{category}/"
                else:
                    url = f"{self.base_url}/{category}/page/{page_number}/"
                    await asyncio.sleep(0.15)
                page = await self._request_text(url)
                page_items, page_count = parse_91cr_category_page(page, self.base_url, category)
                if not page_items:
                    # When no total was published, an empty page is the normal
                    # end marker after the sentinel page count.
                    if page_number > 1 and page_count >= 1000:
                        return list(items.values())
                    raise HtmlSourceError(
                        f"91crdjai 分类 {category} 第 {page_number} 页没有解析到视频卡片"
                    )
                for item in page_items:
                    items.setdefault(item.remote_id, item)
                if page_number >= page_count:
                    return list(items.values())
            raise HtmlSourceError(f"91crdjai 分类 {category} 分页数量异常")
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

        results = await asyncio.gather(*(fetch(url) for url in urls))
        return [item for item in results if item is not None]

    async def resolve(self, path: str, episode: int = 0) -> tuple[str, bool]:
        """Resolve one stored episode path to a signed playback URL."""
        parts = [part for part in path.strip("/").split("/") if part]
        if len(parts) < 3 or parts[0] != "html":
            raise HtmlSourceError("HTML 源路径无效")
        if self.is_91crdjai:
            if len(parts) != 4 or parts[1] not in SITE_VIDEO_CATEGORY_IDS:
                raise HtmlSourceError("91crdjai 媒体路径无效")
            remote_id = parts[2]
            try:
                episode_number = max(1, int(episode or parts[3]))
            except ValueError as exc:
                raise HtmlSourceError("91crdjai 集数无效") from exc
            endpoint = (
                f"{self.base_url}/videos/{quote(remote_id, safe='')}/episodes/"
                f"{episode_number}/playback"
            )
            payload = await self._request_json(endpoint)
            data = payload.get("data") if isinstance(payload, dict) else None
            source = str((data or {}).get("src") or "").strip() if isinstance(data, dict) else ""
            parsed = urlsplit(source)
            if not source or parsed.scheme not in {"http", "https"} or not parsed.netloc:
                raise HtmlSourceError("91crdjai 没有返回有效的播放地址")
            status = payload.get("status")
            if status not in (None, 1, 200, "1", "200"):
                raise HtmlSourceError("91crdjai 播放接口返回失败")
            return source, False
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

    async def video_metadata(self, remote_id: str, fallback: HtmlSourceItem) -> HtmlSourceItem:
        if not self.is_91crdjai:
            raise HtmlSourceError("当前 HTML 源不支持远程视频元数据")
        payload = await self._request_json(
            f"{self.base_url}/api/videos/{quote(str(remote_id), safe='')}"
        )
        return parse_91cr_video_metadata(payload, fallback)
