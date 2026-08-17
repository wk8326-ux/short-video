from __future__ import annotations

import os
from dataclasses import dataclass, field


def _positive_int(name: str, default: int, minimum: int) -> int:
    raw = os.getenv(name, str(default))
    try:
        return max(minimum, int(raw))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    alist_base_url: str
    alist_media_path: str
    alist_token: str
    alist_username: str
    alist_password: str
    database_path: str
    static_dir: str
    scan_interval_seconds: int
    direct_url_cache_seconds: int
    video_extensions: frozenset[str]
    auth_password_hash: str
    session_secret: str
    session_days: int
    asmr_base_url: str = "https://www.asmrgay.com"
    asmr_media_path: str = "/asmr6"
    asmr_extensions: frozenset[str] = field(
        default_factory=lambda: frozenset(
            {".m3u8", ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".mp4", ".m4v", ".mov", ".webm"}
        )
    )
    duration_boundary_seconds: int = 180

    @classmethod
    def from_env(cls) -> "Settings":
        extensions = {
            value.strip().lower()
            for value in os.getenv("VIDEO_EXTENSIONS", ".mp4,.m4v,.mov,.webm").split(",")
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
            scan_interval_seconds=_positive_int("SCAN_INTERVAL_SECONDS", 1800, 60),
            direct_url_cache_seconds=_positive_int("DIRECT_URL_CACHE_SECONDS", 600, 0),
            video_extensions=frozenset(extensions),
            auth_password_hash=os.getenv("AUTH_PASSWORD_HASH", "").strip(),
            session_secret=os.getenv("SESSION_SECRET", "").strip(),
            session_days=_positive_int("SESSION_DAYS", 180, 1),
            asmr_base_url=os.getenv("ASMR_BASE_URL", "https://www.asmrgay.com").rstrip("/"),
            asmr_media_path=os.getenv("ASMR_MEDIA_PATH", "/asmr6"),
            asmr_extensions=frozenset(asmr_extensions),
            duration_boundary_seconds=_positive_int("DURATION_BOUNDARY_SECONDS", 180, 1),
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
        if not self.auth_password_hash.startswith("scrypt:"):
            raise ValueError("AUTH_PASSWORD_HASH is missing; run: python app/auth.py init-env .env")
        if len(self.session_secret) < 32:
            raise ValueError("SESSION_SECRET must contain at least 32 characters")
