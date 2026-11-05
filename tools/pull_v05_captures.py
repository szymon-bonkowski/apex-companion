#!/usr/bin/env python3
"""Pull only synthetic v0.5 instrumentation captures with matching fixture metadata."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REMOTE = "/sdcard/Android/media/io.github.szymonbonkowski.apexcompanion/additional_test_output"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/validation/v0.5-captures")
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]
    listing = subprocess.run(adb + ["shell", "-T", "ls", REMOTE], capture_output=True, check=True, text=True)
    args.output.mkdir(parents=True, exist_ok=True)
    names = listing.stdout.splitlines()
    copied = []
    for name in sorted(names):
        if re.fullmatch(r"v05-[a-z0-9-]+-gfxinfo\.txt", name):
            content = subprocess.run(adb + ["exec-out", "cat", f"{REMOTE}/{name}"], capture_output=True, check=True).stdout
            (args.output / name).write_bytes(content)
            continue
        if not re.fullmatch(r"v05-[a-z0-9-]+\.json", name):
            continue
        metadata = subprocess.run(adb + ["exec-out", "cat", f"{REMOTE}/{name}"], capture_output=True, check=True).stdout
        context = json.loads(metadata)
        if context.get("syntheticData") is not True:
            raise ValueError(f"Refusing capture without synthetic-data fixture marker: {name}")
        evidence_names = {"v05-talkback-evidence.json": "v0.5-talkback-evidence.json", "v05-keyboard-evidence.json": "v0.5-keyboard-evidence.json"}
        if name in evidence_names:
            (args.output.parent / evidence_names[name]).write_bytes(metadata)
            continue
        png = name.removesuffix(".json") + ".png"
        if png not in names:
            raise ValueError(f"Missing pixels for fixture metadata: {name}")
        result = subprocess.run(adb + ["exec-out", "cat", f"{REMOTE}/{png}"], capture_output=True, check=True).stdout
        if not result.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError(f"Invalid PNG: {png}")
        (args.output / name).write_bytes(metadata)
        (args.output / png).write_bytes(result)
        copied.append({"file": png, "sha256": hashlib.sha256(result).hexdigest(),
                       "context": context, "visuallyInspected": False})
    manifest = {"schemaVersion": 1, "deviceSerial": args.serial, "captures": copied,
                "notice": "Actual device captures of isolated synthetic test repositories; review is not inferred from successful capture."}
    (args.output / "capture-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Pulled {len(copied)} synthetic captures; visual inspection remains separate.")


if __name__ == "__main__":
    main()
