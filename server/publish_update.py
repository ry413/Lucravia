#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

try:
    from server.app_updates import MAX_APK_BYTES, sha256_file
except ModuleNotFoundError:
    from app_updates import MAX_APK_BYTES, sha256_file


UPDATE_DIR = Path(__file__).resolve().parent / "updates"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
EXPECTED_APPLICATION_ID = "com.lucravia.xiaozhuiot"
EXPECTED_CERTIFICATE_SHA256 = (
    "0e35c5c488de1ba6de6978e1149614c4a0f2f4f0aa4640d556dc897cb390ada7"
)


def android_tool(name: str) -> Path:
    roots: list[Path] = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(variable)
        if value:
            roots.append(Path(value))
    local_properties = PROJECT_ROOT / "android" / "local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                roots.append(Path(line.split("=", 1)[1].replace("\\:", ":")))
    roots.append(Path.home() / "Library" / "Android" / "sdk")
    for root in roots:
        candidates = sorted((root / "build-tools").glob(f"*/{name}"), reverse=True)
        if candidates:
            return candidates[0]
    executable = shutil.which(name)
    if executable:
        return Path(executable)
    raise SystemExit(f"找不到 Android SDK {name}，无法安全验证发布包")


def verify_apk(apk: Path, version_code: int, version_name: str) -> None:
    badging = subprocess.run(
        [str(android_tool("aapt")), "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.splitlines()[0]
    match = re.search(
        r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        badging,
    )
    if not match:
        raise SystemExit("无法读取 APK 包名与版本")
    application_id, actual_code, actual_name = match.groups()
    if application_id != EXPECTED_APPLICATION_ID:
        raise SystemExit(f"APK 包名错误：{application_id}")
    if int(actual_code) != version_code or actual_name != version_name:
        raise SystemExit(
            f"发布参数与 APK 不一致：APK 是 {actual_name}+{actual_code}",
        )

    certificates = subprocess.run(
        [str(android_tool("apksigner")), "verify", "--print-certs", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    fingerprint = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", certificates)
    if not fingerprint or fingerprint.group(1) != EXPECTED_CERTIFICATE_SHA256:
        raise SystemExit("APK 未使用 Lucravia 永久 Release Key 签名")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Publish a signed Android APK into server/updates",
    )
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--notes", default="")
    parser.add_argument("--required", action="store_true")
    args = parser.parse_args()

    source = args.apk.resolve()
    if not source.is_file() or source.suffix.lower() != ".apk":
        raise SystemExit(f"APK 不存在：{source}")
    size = source.stat().st_size
    if size <= 0 or size > MAX_APK_BYTES:
        raise SystemExit("APK 大小无效或超过 250 MB")
    if args.version_code <= 0 or not args.version_name.strip():
        raise SystemExit("版本号无效")
    verify_apk(source, args.version_code, args.version_name.strip())

    UPDATE_DIR.mkdir(parents=True, exist_ok=True)
    apk_name = f"lucravia-{args.version_code}.apk"
    destination = UPDATE_DIR / apk_name
    with tempfile.NamedTemporaryFile(dir=UPDATE_DIR, delete=False) as temporary:
        temporary_path = Path(temporary.name)
        with source.open("rb") as apk_source:
            shutil.copyfileobj(apk_source, temporary)
    temporary_path.replace(destination)

    manifest = {
        "version_code": args.version_code,
        "version_name": args.version_name.strip(),
        "apk_file": apk_name,
        "apk_sha256": sha256_file(destination),
        "apk_size_bytes": size,
        "release_notes": args.notes.strip(),
        "required": args.required,
    }
    manifest_temporary = UPDATE_DIR / "latest.json.tmp"
    manifest_temporary.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    manifest_temporary.replace(UPDATE_DIR / "latest.json")
    print(
        f"已发布 {args.version_name}+{args.version_code}："
        f"{destination} ({size} bytes)",
    )


if __name__ == "__main__":
    main()
