from __future__ import annotations

import os
from dataclasses import dataclass, field


DEFAULT_ASMR_AUTHOR_GROUP_PATHS = (
    "/asmr/\u4e2d\u6587\u97f3\u58f0",
    "/asmr/\u4e9a\u592a",
    "/asmr/\u5973\u6027\u5411",
    "/asmr/\u672a\u5206\u7c7b",
    "/asmr/\u82f1\u8bed\u97f3\u58f0",
    "/asmr/\u975eASMR\u8d44\u6e90",
    "/asmr/\u97f3\u58f0\u6c49\u5316",
)


def _positive_int(name: str, default: int, minimum: int) -> int:
    raw = os.getenv(name, str(default))
    try:
        return max(minimum, int(raw))
    except ValueError:
        return default


def _positive_float(name: str, default: float, minimum: float) -> float:
    raw = os.getenv(name, str(default))
    try:
        return max(minimum, float(raw))
    except ValueError:
        return default


def _paths(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    raw = os.getenv(name)
    values = default if raw is None else tuple(raw.split(","))
    return tuple(
        "/" + value.strip().strip("/")
        for value in values
        if value.strip() and value.strip() != "/"
    )


@dataclass(frozen=True)
class Settings:
    alist_base_url: str
    alist_media_path: str
    alist_token: str
    alist_username: str
    alist_password: str
    database_path: str
    static_dir: str
    direct_url_cache_seconds: int
    video_extensions: frozenset[str]
    auth_password_hash: str
    session_secret: str
    session_days: int
    movie_extensions: frozenset[str] = field(
        default_factory=lambda: frozenset(
            {".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".ts", ".m2ts", ".flv"}
        )
    )
    asmr_base_url: str = "https://www.asmrgay.com"
    asmr_media_path: str = "/asmr6"
    asmr_extensions: frozenset[str] = field(
        default_factory=lambda: frozenset(
            {".m3u8", ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".mp4", ".m4v", ".mov", ".webm"}
        )
    )
    duration_boundary_seconds: int = 180
    metadata_probe_batch_size: int = 30
    asmr_request_interval_seconds: float = 0.25
    asmr_search_paths: tuple[str, ...] = ("/asmr",)
    asmr_author_group_paths: frozenset[str] = field(
        default_factory=lambda: frozenset(DEFAULT_ASMR_AUTHOR_GROUP_PATHS)
    )
    asmr_search_result_limit: int = 100000
    shared_metadata_base_url: str = ""
    shared_metadata_token: str = ""

    @classmethod
    def from_env(cls) -> "Settings":
        extensions = {
            value.strip().lower()
            for value in os.getenv("VIDEO_EXTENSIONS", ".mp4,.m4v,.mov,.webm").split(",")
            if value.strip()
        }
        movie_extensions = {
            value.strip().lower()
            for value in os.getenv(
                "MOVIE_EXTENSIONS",
                ".mp4,.m4v,.mov,.webm,.mkv,.avi,.ts,.m2ts,.flv",
            ).split(",")
            if value.strip()
        }
        asmr_extensions = {
            value.strip().lower()
            for value in os.getenv(
                "ASMR_EXTENSIONS",
                ".m3u8,.mp3,.m4a,.aac,.flac,.wav,.ogg,.opus,.mp4,.m4v,.mov,.webm",
            ).split(",")
            if value.strip()
        }
        return cls(
            alist_base_url=os.getenv("ALIST_BASE_URL", "").rstrip("/"),
            alist_media_path=os.getenv("ALIST_MEDIA_PATH", "/"),
            alist_token=os.getenv("ALIST_TOKEN", "").strip(),
            alist_username=os.getenv("ALIST_USERNAME", "").strip(),
            alist_password=os.getenv("ALIST_PASSWORD", ""),
            database_path=os.getenv("DATABASE_PATH", "/data/library.db"),
            static_dir=os.getenv("STATIC_DIR", "/app/static"),
            direct_url_cache_seconds=_positive_int("DIRECT_URL_CACHE_SECONDS", 600, 0),
            video_extensions=frozenset(extensions),
            movie_extensions=frozenset(movie_extensions),
            auth_password_hash=os.getenv("AUTH_PASSWORD_HASH", "").strip(),
            session_secret=os.getenv("SESSION_SECRET", "").strip(),
            session_days=_positive_int("SESSION_DAYS", 180, 1),
            asmr_base_url=os.getenv("ASMR_BASE_URL", "https://www.asmrgay.com").rstrip("/"),
            asmr_media_path=os.getenv("ASMR_MEDIA_PATH", "/asmr6"),
            asmr_extensions=frozenset(asmr_extensions),
            duration_boundary_seconds=_positive_int("DURATION_BOUNDARY_SECONDS", 180, 1),
            metadata_probe_batch_size=_positive_int("METADATA_PROBE_BATCH_SIZE", 30, 1),
            asmr_request_interval_seconds=_positive_float(
                "ASMR_REQUEST_INTERVAL_SECONDS", 0.25, 0.0
            ),
            asmr_search_paths=_paths("ASMR_SEARCH_PATHS", ("/asmr",)),
            asmr_author_group_paths=frozenset(
                _paths("ASMR_AUTHOR_GROUP_PATHS", DEFAULT_ASMR_AUTHOR_GROUP_PATHS)
            ),
            asmr_search_result_limit=_positive_int(
                "ASMR_SEARCH_RESULT_LIMIT", 100000, 1
            ),
            shared_metadata_base_url=os.getenv("SHARED_METADATA_BASE_URL", "").strip().rstrip("/"),
            shared_metadata_token=os.getenv("SHARED_METADATA_TOKEN", "").strip(),
        )

    def validate(self) -> None:
        if not self.alist_base_url.startswith(("http://", "https://")):
            raise ValueError("ALIST_BASE_URL must be an absolute HTTP(S) URL")
        if not self.alist_media_path.startswith("/"):
            raise ValueError("ALIST_MEDIA_PATH must start with /")
        if not self.asmr_base_url.startswith(("http://", "https://")):
            raise ValueError("ASMR_BASE_URL must be an absolute HTTP(S) URL")
        if not self.asmr_media_path.startswith("/"):
            raise ValueError("ASMR_MEDIA_PATH must start with /")
        if any(not path.startswith("/") for path in self.asmr_search_paths):
            raise ValueError("ASMR_SEARCH_PATHS entries must start with /")
        if any(not path.startswith("/") for path in self.asmr_author_group_paths):
            raise ValueError("ASMR_AUTHOR_GROUP_PATHS entries must start with /")
        if self.shared_metadata_base_url and not self.shared_metadata_base_url.startswith(
            ("http://", "https://")
        ):
            raise ValueError("SHARED_METADATA_BASE_URL must be an absolute HTTP(S) URL")
        if not self.auth_password_hash.startswith("scrypt:"):
            raise ValueError("AUTH_PASSWORD_HASH is missing; run: python app/auth.py init-env .env")
        if len(self.session_secret) < 32:
            raise ValueError("SESSION_SECRET must contain at least 32 characters")
