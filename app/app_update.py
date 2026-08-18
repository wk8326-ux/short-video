from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


APK_FILE_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,199}\.apk$", re.IGNORECASE)
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")


class UpdateNotPublished(FileNotFoundError):
    pass


class InvalidUpdateManifest(ValueError):
    pass


@dataclass(frozen=True)
class AppUpdateManifest:
    version_code: int
    version_name: str
    apk_file: str
    sha256: str
    size: int
    notes: str

    def api_payload(self) -> dict[str, Any]:
        return {
            "versionCode": self.version_code,
            "versionName": self.version_name,
            "apkFile": self.apk_file,
            "sha256": self.sha256,
            "size": self.size,
            "notes": self.notes,
            "downloadUrl": f"/api/app/update/apk?versionCode={self.version_code}",
        }


class AppUpdateStore:
    def __init__(self, directory: Path):
        self.directory = directory
        self.manifest_path = directory / "manifest.json"

    def load(self) -> tuple[AppUpdateManifest, Path]:
        try:
            raw = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise UpdateNotPublished("No app update is published") from exc
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise InvalidUpdateManifest("Update manifest cannot be read") from exc

        if not isinstance(raw, dict):
            raise InvalidUpdateManifest("Update manifest must be an object")

        version_code = self._positive_int(raw, "versionCode")
        size = self._positive_int(raw, "size")
        version_name = self._text(raw, "versionName", maximum=64)
        apk_file = self._text(raw, "apkFile", maximum=204)
        notes = self._text(raw, "notes", maximum=10_000, allow_empty=True)
        sha256 = self._text(raw, "sha256", maximum=64).lower()

        if not APK_FILE_PATTERN.fullmatch(apk_file):
            raise InvalidUpdateManifest("Update APK filename is invalid")
        if not SHA256_PATTERN.fullmatch(sha256):
            raise InvalidUpdateManifest("Update SHA-256 is invalid")

        artifact = self.directory / apk_file
        try:
            resolved_directory = self.directory.resolve()
            resolved_artifact = artifact.resolve(strict=True)
        except (FileNotFoundError, OSError) as exc:
            raise InvalidUpdateManifest("Update APK is missing") from exc
        if resolved_artifact.parent != resolved_directory or not resolved_artifact.is_file():
            raise InvalidUpdateManifest("Update APK path is invalid")
        if resolved_artifact.stat().st_size != size:
            raise InvalidUpdateManifest("Update APK size does not match the manifest")

        return (
            AppUpdateManifest(
                version_code=version_code,
                version_name=version_name,
                apk_file=apk_file,
                sha256=sha256,
                size=size,
                notes=notes,
            ),
            resolved_artifact,
        )

    @staticmethod
    def _positive_int(payload: dict[str, Any], name: str) -> int:
        value = payload.get(name)
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise InvalidUpdateManifest(f"{name} must be a positive integer")
        return value

    @staticmethod
    def _text(
        payload: dict[str, Any],
        name: str,
        *,
        maximum: int,
        allow_empty: bool = False,
    ) -> str:
        value = payload.get(name)
        if not isinstance(value, str):
            raise InvalidUpdateManifest(f"{name} must be text")
        value = value.strip()
        if (not value and not allow_empty) or len(value) > maximum:
            raise InvalidUpdateManifest(f"{name} has an invalid length")
        return value
