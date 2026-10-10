from __future__ import annotations

import base64
import binascii
import json
import re
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote, urljoin, urlsplit, urlunsplit

import httpx


class TVBoxError(RuntimeError):
    pass


@dataclass(frozen=True)
class TVBoxCategory:
    remote_id: str
    name: str


@dataclass(frozen=True)
class TVBoxEpisode:
    name: str
    url: str


@dataclass(frozen=True)
class TVBoxItem:
    remote_id: str
    name: str
    poster_url: str
    year: int | None
    remarks: str
    overview: str
    actors: list[str]
    directors: list[str]
    genres: list[str]
    play_from: list[str]
    episodes: list[TVBoxEpisode]


def decode_config_payload(payload: str | bytes) -> dict[str, Any]:
    """Read a TVBox config in JSON, Base64 or hex form.

    Some providers wrap the JSON in Base64 or hex to keep plain-text hosts from
    casually hot-linking it. Only encoding is unwrapped here; encrypted and
    spider-based configurations are deliberately out of scope because the
    server must never execute remote JavaScript or JAR code.
    """
    if isinstance(payload, bytes):
        text = payload.decode("utf-8-sig", errors="replace")
    else:
        text = payload.strip()
    for decoder in (_decode_plain, _decode_base64, _decode_hex):
        result = decoder(text)
        if result is not None:
            return result
    raise TVBoxError("TVBox 配置格式不支持，请提供 JSON、Base64 或 HEX 编码的配置")


def _decode_plain(text: str) -> dict[str, Any] | None:
    try:
        value = json.loads(text)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def _decode_base64(text: str) -> dict[str, Any] | None:
    compact = re.sub(r"\s+", "", text)
    if not compact or not re.fullmatch(r"[A-Za-z0-9+/_=-]+", compact):
        return None
    try:
        raw = base64.urlsafe_b64decode(compact + "=" * (-len(compact) % 4))
        value = json.loads(raw.decode("utf-8-sig", errors="replace"))
    except (ValueError, UnicodeDecodeError, binascii.Error):
        return None
    return value if isinstance(value, dict) else None


def _decode_hex(text: str) -> dict[str, Any] | None:
    compact = re.sub(r"\s+", "", text)
    if not compact or not re.fullmatch(r"[0-9a-fA-F]+", compact) or len(compact) % 2:
        return None
    try:
        value = json.loads(bytes.fromhex(compact).decode("utf-8-sig", errors="replace"))
    except (ValueError, UnicodeDecodeError):
        return None
    return value if isinstance(value, dict) else None


def parse_categories(config: dict[str, Any]) -> list[TVBoxCategory]:
    categories: list[TVBoxCategory] = []
    seen: set[str] = set()

    def add(value: Any) -> None:
        if isinstance(value, dict):
            remote_id = str(value.get("type_id") or value.get("class_id") or "").strip()
            name = str(value.get("type_name") or value.get("class_name") or "").strip()
        else:
            remote_id = str(value or "").strip()
            name = remote_id
        if not remote_id or not name or remote_id in seen:
            return
        seen.add(remote_id)
        categories.append(TVBoxCategory(remote_id=remote_id, name=name))

    for block in ("class", "classes"):
        value = config.get(block)
        if isinstance(value, list):
            for item in value:
                add(item)
    sites = config.get("sites")
    if isinstance(sites, list) and not categories:
        for site in sites:
            if isinstance(site, dict):
                add(site.get("type"))
    return categories


def parse_site(config: dict[str, Any]) -> tuple[str, str]:
    sites = config.get("sites")
    if not isinstance(sites, list):
        raise TVBoxError("TVBox 配置缺少 sites")
    preferred = [site for site in sites if isinstance(site, dict) and str(site.get("api") or "").lower().startswith("http")]
    site = preferred[0] if preferred else next((site for site in sites if isinstance(site, dict)), None)
    if not site:
        raise TVBoxError("TVBox 配置缺少可用的站点")
    api = str(site.get("api") or "").strip()
    spider = str(site.get("spider") or "").strip()
    return api, spider


def normalize_cms_url(base_url: str, *, category: str | None = None, page: int = 1) -> str:
    parsed = urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise TVBoxError("TVBox 站点地址必须是有效的 HTTP(S) 地址")
    query: list[tuple[str, str]] = []
    for key, value in [
        item.split("=", 1) for item in parsed.query.split("&") if "=" in item
    ]:
        if key != "ac":
            query.append((key, value))
    query.insert(0, ("ac", "list"))
    if category:
        query.extend([("t", category), ("pg", str(page))])
    else:
        query.append(("pg", str(page)))
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "&".join(f"{key}={quote(value)}" for key, value in query), parsed.fragment))


def parse_list(payload: dict[str, Any]) -> list[dict[str, Any]]:
    value = payload.get("list")
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def parse_detail(payload: dict[str, Any]) -> TVBoxItem:
    rows = parse_list(payload)
    if not rows:
        raise TVBoxError("TVBox 站点没有返回详情")
    return parse_item(rows[0])


def parse_item(row: dict[str, Any]) -> TVBoxItem:
    play_from = _string_list(row.get("vod_play_from") or row.get("vod_from"))
    episodes = parse_episode_urls(str(row.get("vod_play_url") or row.get("vod_url") or ""))
    year_text = str(row.get("vod_year") or row.get("year") or "").strip()
    year_match = re.search(r"(19|20)\d{2}", year_text)
    return TVBoxItem(
        remote_id=str(row.get("vod_id") or row.get("id") or "").strip(),
        name=str(row.get("vod_name") or row.get("name") or "").strip(),
        poster_url=str(row.get("vod_pic") or row.get("pic") or "").strip(),
        year=int(year_match.group(0)) if year_match else None,
        remarks=str(row.get("vod_remarks") or row.get("remarks") or "").strip(),
        overview=str(row.get("vod_content") or row.get("content") or "").strip(),
        actors=_string_list(row.get("vod_actor") or row.get("actor")),
        directors=_string_list(row.get("vod_director") or row.get("director")),
        genres=_string_list(row.get("vod_class") or row.get("type_name")),
        play_from=play_from,
        episodes=episodes,
    )


def parse_episode_urls(raw: str) -> list[TVBoxEpisode]:
    """Split the CMS ``group$episode`` and ``name#url`` notations."""
    episodes: list[TVBoxEpisode] = []
    for group in raw.split("$$$"):
        for token in group.split("#"):
            if "$" in token:
                name, url = token.split("$", 1)
            else:
                name, url = "", token
            name, url = name.strip(), url.strip()
            if not url or not re.match(r"^https?://", url, re.I):
                continue
            episodes.append(TVBoxEpisode(name=name or f"第{len(episodes) + 1}集", url=url))
        if episodes:
            break
    return episodes


def _string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        raw = [str(item) for item in value]
    else:
        raw = re.split(r"[,，/|、]", str(value))
    seen: set[str] = set()
    result: list[str] = []
    for item in raw:
        cleaned = item.strip()
        if cleaned and cleaned.lower() not in seen:
            seen.add(cleaned.lower())
            result.append(cleaned)
    return result


def absolute_url(base_url: str, value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme in {"http", "https"}:
        return value
    if not value:
        return ""
    return urljoin(f"{base_url.rstrip('/')}/", value.lstrip("/"))


class TVBoxClient:
    def __init__(
        self,
        settings: Any,
        client: httpx.AsyncClient | None = None,
        *,
        config_url: str | None = None,
        config_json: dict[str, Any] | None = None,
    ):
        self.settings = settings
        self.config_url = (config_url or "").strip()
        self.config_json = config_json
        self._client = client or httpx.AsyncClient(
            follow_redirects=True,
            timeout=httpx.Timeout(20.0, connect=10.0),
            headers={"User-Agent": "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"},
        )
        self._owns_client = client is None
        self._config: dict[str, Any] | None = None
        self._api: str | None = None

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _load_config(self) -> dict[str, Any]:
        if self._config is not None:
            return self._config
        if self.config_json is not None:
            self._config = self.config_json
        elif self.config_url:
            try:
                response = await self._client.get(self.config_url)
                response.raise_for_status()
                self._config = decode_config_payload(response.content)
            except httpx.HTTPError as exc:
                raise TVBoxError(f"TVBox 配置请求失败: {exc}") from exc
        else:
            raise TVBoxError("缺少 TVBox 配置")
        self._api, _ = parse_site(self._config)
        return self._config

    async def categories(self) -> list[TVBoxCategory]:
        config = await self._load_config()
        categories = parse_categories(config)
        if not categories:
            raise TVBoxError("TVBox 站点没有返回分类")
        return categories

    async def list_items(self, category: str, page: int = 1) -> list[dict[str, Any]]:
        await self._load_config()
        return parse_list(await self._request(normalize_cms_url(self._api, category=category, page=page)))

    async def detail(self, remote_id: str) -> TVBoxItem:
        await self._load_config()
        url = self._api + ("&" if "?" in self._api else "?") + f"ac=detail&ids={quote(remote_id)}"
        return parse_detail(await self._request(url))

    async def search(self, query: str) -> list[dict[str, Any]]:
        await self._load_config()
        url = self._api + ("&" if "?" in self._api else "?") + f"ac=detail&wd={quote(query)}"
        return parse_list(await self._request(url))

    async def resolve(self, remote_id: str, episode_index: int = 0) -> tuple[str, bool]:
        item = await self.detail(remote_id)
        if episode_index < 0 or episode_index >= len(item.episodes):
            raise TVBoxError("TVBox 剧集不存在")
        url = item.episodes[episode_index].url
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise TVBoxError("TVBox 站点没有返回有效的播放链接")
        return url, False

    async def _request(self, url: str) -> dict[str, Any]:
        try:
            response = await self._client.get(url)
            response.raise_for_status()
            try:
                return response.json()
            except ValueError:
                return decode_config_payload(response.content)
        except httpx.HTTPError as exc:
            raise TVBoxError(f"TVBox 站点请求失败: {exc}") from exc
