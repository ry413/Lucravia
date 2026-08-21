from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


MAX_APK_BYTES = 250 * 1024 * 1024


class UpdateNotPublished(FileNotFoundError):
    pass


class InvalidUpdateManifest(ValueError):
    pass


class UpdateRepository:
    """Reads a published Android release entirely from server/updates."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def latest(self) -> tuple[dict[str, Any], Path]:
        manifest_path = self.root / "latest.json"
        if not manifest_path.is_file():
            raise UpdateNotPublished("尚未发布客户端更新")
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise InvalidUpdateManifest("更新清单无法读取") from error
        if not isinstance(manifest, dict):
            raise InvalidUpdateManifest("更新清单必须是 JSON 对象")

        version_code = manifest.get("version_code")
        version_name = manifest.get("version_name")
        apk_file = manifest.get("apk_file")
        apk_sha256 = manifest.get("apk_sha256")
        apk_size_bytes = manifest.get("apk_size_bytes")
        release_notes = manifest.get("release_notes", "")
        required = manifest.get("required", False)
        if not isinstance(version_code, int) or version_code <= 0:
            raise InvalidUpdateManifest("version_code 无效")
        if not isinstance(version_name, str) or not version_name.strip():
            raise InvalidUpdateManifest("version_name 无效")
        if (
            not isinstance(apk_file, str)
            or Path(apk_file).name != apk_file
            or not apk_file.endswith(".apk")
        ):
            raise InvalidUpdateManifest("apk_file 无效")
        if (
            not isinstance(apk_sha256, str)
            or len(apk_sha256) != 64
            or any(character not in "0123456789abcdef" for character in apk_sha256)
        ):
            raise InvalidUpdateManifest("apk_sha256 无效")
        if (
            not isinstance(apk_size_bytes, int)
            or apk_size_bytes <= 0
            or apk_size_bytes > MAX_APK_BYTES
        ):
            raise InvalidUpdateManifest("apk_size_bytes 无效")
        if not isinstance(release_notes, str) or not isinstance(required, bool):
            raise InvalidUpdateManifest("更新说明或 required 无效")

        apk_path = self.root / apk_file
        if not apk_path.is_file() or apk_path.stat().st_size != apk_size_bytes:
            raise InvalidUpdateManifest("更新 APK 不存在或大小与清单不一致")
        public_manifest = {
            "version_code": version_code,
            "version_name": version_name.strip(),
            "apk_sha256": apk_sha256,
            "apk_size_bytes": apk_size_bytes,
            "release_notes": release_notes.strip(),
            "required": required,
        }
        return public_manifest, apk_path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
