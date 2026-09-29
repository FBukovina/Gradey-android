"""Validate the untouched v8 baseline; optionally compare a fresh read-only export.

No network access, deployment, database write, or credential output.
"""
import hashlib
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parent
manifest = json.loads((root / "upstream/source.json").read_text())
for relative, expected in manifest["files"].items():
    actual = hashlib.sha256((root / "upstream" / relative).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"Saved baseline changed: {relative}")

if len(sys.argv) > 1:
    fresh = json.loads(Path(sys.argv[1]).read_text())
    if isinstance(fresh, dict) and "content" in fresh:
        fresh = json.loads(next(item["text"] for item in fresh["content"] if item.get("type") == "text"))
    if fresh.get("slug") != manifest["function"] or fresh.get("version") != manifest["version"]:
        raise SystemExit("Hosted function/version changed: rebase and review; do not deploy this patch.")
    if fresh.get("ezbr_sha256") != manifest["ezbr_sha256"]:
        raise SystemExit("Hosted bundle hash changed: rebase and review; do not deploy this patch.")
    actual_files = {item["name"]: hashlib.sha256(item["content"].encode()).hexdigest() for item in fresh["files"]}
    if actual_files != manifest["files"]:
        raise SystemExit("Hosted source files changed: rebase and review; do not deploy this patch.")
    print("Saved baseline and supplied hosted export match send-apns v8.")
else:
    print("Saved baseline hashes match. A fresh hosted export is still required before deployment.")
