#!/usr/bin/env python3
"""Create gradey-update.json from an already-built phone APK. Does not publish anything."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET


PACKAGE_NAME = "com.bukovinafilip.gradey"
MAX_APK_BYTES = 200 * 1024 * 1024
ANDROID = "{http://schemas.android.com/apk/res/android}"


def validate_apk_url(value):
    """Keep this aligned with UpdateReleaseParser.isTrustedReleaseAssetUrl."""
    if any(ord(char) <= 32 or ord(char) == 127 for char in value) or "\\" in value:
        raise ValueError("APK URL must be an HTTPS asset URL in FBukovina/Gradey-android.")
    parsed = urlsplit(value)
    parts = [unquote(part) for part in parsed.path.split("/")]
    if (
        parsed.scheme != "https"
        or parsed.netloc.lower() != "github.com"
        or parsed.query
        or parsed.fragment
        or "?" in value
        or "#" in value
        or len(parts) != 7
        or parts[0] != ""
        or parts[1].lower() != "fbukovina"
        or parts[2].lower() != "gradey-android"
        or parts[3:5] != ["releases", "download"]
        or any(not part.strip() or "/" in part or "\\" in part or part in (".", "..") for part in parts[5:])
        or not parsed.path.lower().endswith(".apk")
    ):
        raise ValueError("APK URL must be an HTTPS release APK in FBukovina/Gradey-android, without ports, credentials, queries or fragments.")
    return value


def sdk_roots(explicit):
    roots = [explicit, os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    roots += [Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"]
    return list(dict.fromkeys(Path(root).expanduser() for root in roots if root))


def tool_candidates(name, roots):
    paths = []
    on_path = shutil.which(name)
    if on_path:
        paths.append(Path(on_path))
    for root in roots:
        if name == "apkanalyzer":
            paths += [root / "cmdline-tools/latest/bin/apkanalyzer", root / "tools/bin/apkanalyzer"]
            paths += sorted(root.glob("cmdline-tools/*/bin/apkanalyzer"), reverse=True)
        else:
            versions = sorted(root.glob("build-tools/*/aapt2"), key=lambda item: tuple(int(n) for n in re.findall(r"\d+", item.parent.name)), reverse=True)
            paths += versions
    return list(dict.fromkeys(path for path in paths if path.is_file() and os.access(path, os.X_OK)))


def xml_metadata(source):
    manifest = ET.fromstring(source)
    uses_sdk = manifest.find("uses-sdk")
    if uses_sdk is None:
        raise ValueError("APK has no declared minimum Android SDK.")
    if manifest.get(ANDROID + "versionCodeMajor", "0") != "0":
        raise ValueError("APK versionCodeMajor is unsupported by this feed.")
    if manifest.get("split") or manifest.get(ANDROID + "isSplitRequired") == "true" or manifest.find("uses-split") is not None:
        raise ValueError("Use a complete standalone APK, not a split APK.")
    features = {node.get(ANDROID + "name") for node in manifest.findall("uses-feature")}
    return {
        "packageName": manifest.get("package"),
        "versionCode": int(manifest.get(ANDROID + "versionCode", "0")),
        "versionName": manifest.get(ANDROID + "versionName"),
        "minSdk": int(uses_sdk.get(ANDROID + "minSdkVersion", "0")),
        "isWatch": "android.hardware.type.watch" in features,
    }


def badging_metadata(source):
    package_line = next((line for line in source.splitlines() if line.startswith("package:")), "")
    package = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'", source, re.MULTILINE)
    if not package or minimum is None:
        raise ValueError("Could not read version and SDK metadata from the APK.")
    if package.get("split") or package.get("versionCodeMajor", "0") != "0":
        raise ValueError("Use a complete standalone APK with a standard Android versionCode.")
    return {
        "packageName": package.get("name"),
        "versionCode": int(package.get("versionCode", "0")),
        "versionName": package.get("versionName"),
        "minSdk": int(minimum.group(1)),
        "isWatch": bool(re.search(r"uses-feature(?:-not-required)?:\s+name='android.hardware.type.watch'", source)),
    }


def read_metadata(apk, roots):
    failures = []
    for name, arguments, parser in (
        ("apkanalyzer", ["manifest", "print"], xml_metadata),
        ("aapt2", ["dump", "badging"], badging_metadata),
    ):
        for tool in tool_candidates(name, roots):
            try:
                result = subprocess.run([str(tool), *arguments, str(apk)], check=True, capture_output=True, text=True, timeout=30)
                return parser(result.stdout)
            except (subprocess.SubprocessError, OSError, ET.ParseError, ValueError) as error:
                failures.append(f"{tool}: {error}")
    detail = "; ".join(failures) if failures else "No apkanalyzer or aapt2 executable was found."
    raise ValueError(f"Cannot inspect APK. Install Android SDK command-line or build tools, or pass --android-sdk. {detail}")


def create_manifest(apk, apk_url, roots, notes):
    before = apk.stat()
    if not apk.is_file() or not 1 <= before.st_size <= MAX_APK_BYTES:
        raise ValueError("APK must be a file between 1 byte and 200 MiB.")
    metadata = read_metadata(apk, roots)
    if metadata.pop("isWatch"):
        raise ValueError("This is a Wear OS APK. The phone update feed must contain the phone APK.")
    if metadata["packageName"] != PACKAGE_NAME:
        raise ValueError(f"APK package must be {PACKAGE_NAME}.")
    if not 1 <= metadata["versionCode"] <= 2_147_483_647 or not 1 <= metadata["minSdk"] <= 10_000:
        raise ValueError("APK versionCode or minimum SDK is invalid.")
    version_name = metadata["versionName"]
    if not version_name or not version_name.strip() or len(version_name) > 64:
        raise ValueError("APK must have a nonempty versionName of at most 64 characters.")
    if notes is not None and len(notes.encode("utf-16-le")) // 2 > 8_000:
        raise ValueError("Release notes must be no more than 8,000 UTF-16 code units.")
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    after = apk.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise ValueError("APK changed while it was being inspected. Retry after the build finishes.")
    result = {"target": "phone", **metadata, "apkUrl": validate_apk_url(apk_url), "sha256": digest.hexdigest(), "sizeBytes": after.st_size}
    if notes is not None:
        result["releaseNotes"] = notes
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="Already-built, signed, standalone phone APK")
    parser.add_argument("--apk-url", required=True, help="Future public GitHub release asset URL for this exact APK")
    parser.add_argument("--output", type=Path, required=True, help="Output manifest; publish as gradey-update.json")
    parser.add_argument("--notes-file", type=Path, help="Optional UTF-8 plain-text release notes")
    parser.add_argument("--android-sdk", type=Path, help="SDK directory containing command-line tools or build-tools")
    args = parser.parse_args()
    temporary = None
    try:
        validate_apk_url(args.apk_url)
        if args.output.resolve() == args.apk.resolve():
            raise ValueError("Manifest output must not overwrite the APK.")
        notes = args.notes_file.read_text(encoding="utf-8").strip() if args.notes_file else None
        result = create_manifest(args.apk.resolve(), args.apk_url, sdk_roots(args.android_sdk), notes)
        payload = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
        if len(payload.encode("utf-8")) > 64 * 1024:
            raise ValueError("Manifest exceeds the updater's 64 KiB limit.")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.output.parent, delete=False) as output:
            temporary = Path(output.name)
            output.write(payload)
        os.replace(temporary, args.output)
        print(f"Created {args.output}: Gradey {result['versionName']} ({result['versionCode']}), {result['sizeBytes']} bytes, target phone.")
    except (OSError, ValueError) as error:
        parser.exit(1, f"error: {error}\n")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
