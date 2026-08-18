import hashlib
import json

import pytest

from app.app_update import AppUpdateStore, InvalidUpdateManifest


def write_manifest(directory, **overrides):
    apk = directory / "short-video.apk"
    apk.write_bytes(b"signed-apk")
    payload = {
        "versionCode": 130,
        "versionName": "1.3.0",
        "apkFile": apk.name,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "size": apk.stat().st_size,
        "notes": "In-app updates",
        **overrides,
    }
    (directory / "manifest.json").write_text(json.dumps(payload), encoding="utf-8")
    return payload, apk


def test_update_store_reads_a_valid_manifest(tmp_path):
    payload, apk = write_manifest(tmp_path)

    manifest, artifact = AppUpdateStore(tmp_path).load()

    assert artifact == apk.resolve()
    assert manifest.version_code == 130
    assert manifest.api_payload()["downloadUrl"] == "/api/app/update/apk?versionCode=130"
    assert manifest.sha256 == payload["sha256"]


@pytest.mark.parametrize(
    "overrides",
    [
        {"apkFile": "../outside.apk"},
        {"apkFile": "folder\\outside.apk"},
        {"sha256": "not-a-hash"},
        {"size": 99},
        {"versionCode": True},
    ],
)
def test_update_store_rejects_unsafe_or_inconsistent_manifests(tmp_path, overrides):
    write_manifest(tmp_path, **overrides)

    with pytest.raises(InvalidUpdateManifest):
        AppUpdateStore(tmp_path).load()
