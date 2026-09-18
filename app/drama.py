"""Short-drama (短剧) indexing.

A drama library is a two-level tree: the first level is the category folder,
the second level is one folder per series, and the series folder holds the
episodes::

    /光鸭/下载器/CR短剧/短剧/交换游戏 [91crdj-1172]/交换游戏 第01集 [91crdj-1172-1].mp4

Both the series folder and the episode files carry a ``[91crdj-<id>]`` token, so
grouping and ordering never have to guess.  The episodes themselves stay in
``videos`` rows (playback, progress and prefetch keep working untouched); this
module only adds the series grouping on top and the 91crdj metadata lookup.
"""

from __future__ import annotations

import hashlib
import html
import json
import posixpath
import re
from dataclasses import dataclass, field
from typing import Any

# 91crdj folder names are `<title> [91crdj-<id>]`, episode files add `-<n>`.
SERIES_CODE_PATTERN = re.compile(r"\[91crdj-(\d+)\]")
EPISODE_CODE_PATTERN = re.compile(r"\[91crdj-\d+-(\d+)\]")
EPISODE_PATTERN = re.compile(r"第\s*0*(\d+)\s*[集话]")

# The downloader sometimes grabs the play-button label instead of the title.
PLACEHOLDER_TITLE_PATTERN = re.compile(r"^[\s▶▷►·•\-—]*立即观看[\s▶▷►·•\-—]*$")

CATEGORY_SLUGS = {
    "短剧": "duanju",
    "漫剧": "manju",
    "真人剧": "zhenrenju",
    "视频": "shipin",
}

# 91crdj ships its artwork as AES-CBC encrypted blobs behind a picture CDN.
ENCRYPTED_IMAGE_HOSTS = frozenset({"pic.tuafjz.cn"})
_MEDIA_KEY = b"f5d965df75336270"
_MEDIA_IV = b"97b60394abc2fbe1"


class DramaError(RuntimeError):
    """Raised when a drama metadata lookup cannot be completed."""


def is_encrypted_image_url(url: str) -> bool:
    match = re.match(r"https?://([^/]+)/", str(url or ""))
    if not match:
        return False
    host = match.group(1).split("@")[-1].split(":")[0].lower()
    return host in ENCRYPTED_IMAGE_HOSTS


def decrypt_media(blob: bytes) -> bytes:
    """Undo the site's AES-CBC wrapper around its pictures.

    The page decrypts in a web worker with CryptoJS (``mode: CBC``, no padding);
    the same call maps onto pycryptodome or ``cryptography`` here.

    A missing AES backend used to surface as a bare ``ModuleNotFoundError``
    inside the poster proxy, which turned every drama cover into a 502.  Say
    what is wrong instead of letting the caller guess.
    """
    if not blob or len(blob) % 16:
        raise DramaError("encrypted payload is not a whole number of AES blocks")
    try:
        from Crypto.Cipher import AES

        return AES.new(_MEDIA_KEY, AES.MODE_CBC, _MEDIA_IV).decrypt(blob)
    except ImportError:
        pass
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    except ImportError as exc:
        raise DramaError(
            "no AES backend available: install pycryptodome or cryptography"
        ) from exc

    decryptor = Cipher(algorithms.AES(_MEDIA_KEY), modes.CBC(_MEDIA_IV)).decryptor()
    return decryptor.update(blob) + decryptor.finalize()


def strip_code(name: str) -> tuple[str, str | None]:
    """Split ``交换游戏 [91crdj-1172]`` into ``("交换游戏", "91crdj-1172")``."""
    code_match = SERIES_CODE_PATTERN.search(name)
    code = f"91crdj-{code_match.group(1)}" if code_match else None
    cleaned = SERIES_CODE_PATTERN.sub("", name)
    cleaned = EPISODE_CODE_PATTERN.sub("", cleaned)
    cleaned = EPISODE_PATTERN.sub("", cleaned)
    return cleaned.strip().strip("-_·•").strip(), code


def is_placeholder_title(title: str) -> bool:
    return not title or bool(PLACEHOLDER_TITLE_PATTERN.match(title))


def parse_episode_number(name: str) -> int | None:
    """Episode number, preferring the token the downloader appends."""
    match = EPISODE_CODE_PATTERN.search(name) or EPISODE_PATTERN.search(name)
    if not match:
        return None
    try:
        return int(match.group(1))
    except (TypeError, ValueError):
        return None


def drama_id(source: str, folder: str) -> str:
    return hashlib.sha1(f"{source}\x00{folder}".encode("utf-8")).hexdigest()[:20]


def episode_order_key(name: str) -> tuple[int, str]:
    """Playback order for one episode.

    The downloader's ``[91crdj-<id>-<n>]`` token is authoritative, ``第NN集``
    is the fallback, and a file without either sorts last so an incomplete
    series still plays its numbered episodes in order.
    """
    number = parse_episode_number(name)
    return (number if number is not None else 10**9, str(name).casefold())


def series_folder(root_path: str, path: str) -> str | None:
    """The series folder a media path belongs to, relative to the scan root.

    Returns ``None`` for files sitting directly in the root (they are not part
    of any series) and for anything outside the root.
    """
    root = "/" + str(root_path or "").strip("/")
    folder = posixpath.dirname("/" + str(path or "").lstrip("/"))
    if folder == root or not folder.startswith(root + "/"):
        return None
    relative = folder[len(root) + 1 :]
    return relative or None


def category_of(folder: str) -> str | None:
    head = folder.split("/", 1)[0].strip()
    return head or None


def build_series(
    root_path: str,
    videos: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Group active video rows into dramas.

    ``videos`` are ``videos`` table rows; each needs ``id``, ``path`` and
    ``name``.  Episodes come back in播放 order — by the episode number when the
    name carries one, by the code token otherwise, and by name as the last
    resort, so a partially downloaded series still plays in the right order.
    """
    grouped: dict[str, dict[str, Any]] = {}
    for video in videos:
        folder = series_folder(root_path, str(video.get("path") or ""))
        if not folder:
            continue
        entry = grouped.get(folder)
        if entry is None:
            folder_name = posixpath.basename(folder)
            title, code = strip_code(folder_name)
            entry = {
                "id": drama_id(str(video.get("source") or ""), folder),
                "source": str(video.get("source") or ""),
                "root_path": "/" + str(root_path).strip("/"),
                "folder": folder,
                "category": category_of(folder),
                "folder_title": title or folder_name,
                "code": code,
                "episodes": [],
            }
            grouped[folder] = entry
        name = str(video.get("name") or "")
        entry["episodes"].append(
            {
                "video_id": int(video["id"]),
                "order": episode_order_key(name)[0],
                "name": name,
            }
        )

    series: list[dict[str, Any]] = []
    for entry in grouped.values():
        episodes = sorted(entry["episodes"], key=lambda item: episode_order_key(item["name"]))
        for position, episode in enumerate(episodes, start=1):
            episode["position"] = position
        entry["episodes"] = episodes
        entry["episode_count"] = len(episodes)
        series.append(entry)
    series.sort(key=lambda item: item["folder_title"].casefold())
    return series


@dataclass
class SeriesMetadata:
    code: str
    title: str = ""
    overview: str = ""
    tags: list[str] = field(default_factory=list)
    poster_url: str = ""
    episode_total: int | None = None
    page_url: str = ""


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


def parse_series_metadata(code: str, page: str, page_url: str = "") -> SeriesMetadata:
    """Pull title, synopsis, tags and artwork out of a 91crdj detail page."""
    metadata = SeriesMetadata(code=code, page_url=page_url)
    nodes = _ld_graph(page)
    for node in nodes:
        if str(node.get("@type") or "").lower() not in {"tvseries", "movie", "videoobject"}:
            continue
        metadata.title = str(node.get("name") or "").strip()
        metadata.overview = html.unescape(str(node.get("description") or "")).strip()
        genres = node.get("genre") or node.get("keywords") or []
        if isinstance(genres, str):
            genres = [part.strip() for part in re.split(r"[,，/|]", genres)]
        metadata.tags = [str(item).strip() for item in genres if str(item).strip()]
        try:
            metadata.episode_total = int(node.get("numberOfEpisodes") or 0) or None
        except (TypeError, ValueError):
            metadata.episode_total = None
        image = node.get("image")
        if isinstance(image, dict):
            metadata.poster_url = str(image.get("contentUrl") or "").strip()
        break

    if not metadata.title:
        match = re.search(r'<meta[^>]+property="og:title"[^>]+content="([^"]*)"', page)
        if match:
            metadata.title = html.unescape(match.group(1)).strip()
    if not metadata.overview:
        match = re.search(r'<meta[^>]+property="og:description"[^>]+content="([^"]*)"', page)
        if match:
            metadata.overview = html.unescape(match.group(1)).strip()

    # The ld+json artwork lives on a CDN that hands out already-expired links,
    # while the picture CDN used by the page itself is the one that works. The
    # page escapes its JSON, so unescape before scanning for the real cover.
    cover = re.search(
        r"https://pic\.tuafjz\.cn/[^\"'\s\\<>()]+\.(?:jpe?g|png|webp)[^\"'\s\\<>()]*",
        html.unescape(page),
    )
    if cover:
        metadata.poster_url = cover.group(0)
    return metadata
