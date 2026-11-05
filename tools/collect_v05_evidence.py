#!/usr/bin/env python3
"""Collect inspectable v0.5 Android/shared results without inferring unrun checks.

Pass --command-meta JSON files produced by the command runner (command, exitCode,
startedUtc, finishedUtc). Screenshot inspection is opt-in through --inspection;
mere existence of a capture never means it has been visually reviewed.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative(path):
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path.resolve())


def command(args):
    completed = subprocess.run(args, cwd=ROOT, capture_output=True, check=False)
    return {"command": args, "exitCode": completed.returncode,
            "output": completed.stdout.decode(errors="replace").strip()}


def suites(paths):
    totals = dict(total=0, passed=0, failures=0, errors=0, skipped=0)
    files = []
    for path in sorted(paths):
        root = ET.parse(path).getroot()
        for suite in ([root] if root.tag == "testsuite" else root.findall("testsuite")):
            values = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            totals["total"] += values["tests"]
            totals["passed"] += values["tests"] - values["failures"] - values["errors"] - values["skipped"]
            for key in ("failures", "errors", "skipped"):
                totals[key] += values[key]
        files.append({"path": relative(path), "sha256": digest(path),
                      "modifiedUtc": datetime.datetime.fromtimestamp(path.stat().st_mtime, datetime.timezone.utc).isoformat()})
    return {**totals, "files": files, "executedEvidencePresent": bool(files),
            "limitation": "XML may be retained from earlier runs; correlate timestamps with the supplied exact command metadata."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--command-meta", type=Path, action="append", default=[])
    parser.add_argument("--inspection", type=Path,
                        help="JSON mapping screenshot basename to {inspected: true, findings: [...]} after actual review")
    parser.add_argument("--captures", type=Path, default=ROOT / "docs/validation/v0.5-captures")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/validation/v0.5-evidence.json")
    args = parser.parse_args()
    inspections = json.loads(args.inspection.read_text()) if args.inspection else {}
    commands = []
    for path in args.command_meta:
        value = json.loads(path.read_text())
        if "command" not in value or "exitCode" not in value:
            raise ValueError(f"Missing exact command/exitCode in {path}")
        commands.append({**value, "metadataPath": relative(path), "metadataSha256": digest(path)})
    captures = []
    for path in sorted(args.captures.glob("*.png")):
        inspection = inspections.get(path.name, {})
        captures.append({"path": relative(path), "sha256": digest(path), "bytes": path.stat().st_size,
                         "visuallyInspected": inspection.get("inspected") is True and
                            ("sha256" not in inspection or inspection["sha256"] == digest(path)),
                         "findings": inspection.get("findings", []),
                         "context": json.loads(path.with_suffix(".json").read_text()) if path.with_suffix(".json").exists() else None})
    lint = {}
    for variant in ("debug", "release"):
        path = ROOT / f"androidApp/build/reports/lint-results-{variant}.xml"
        if path.exists():
            issues = ET.parse(path).getroot().findall("issue")
            lint[variant] = {"errors": sum(i.get("severity") in ("Error", "Fatal") for i in issues),
                             "warnings": sum(i.get("severity") == "Warning" for i in issues),
                             "path": relative(path), "sha256": digest(path)}
    evidence = {
        "schemaVersion": 1, "generatedUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "baseline": command(["git", "rev-parse", "HEAD"])["output"],
        "branch": command(["git", "branch", "--show-current"])["output"],
        "commands": commands,
        "shared": suites((ROOT / "shared/build/test-results/testAndroidHostTest").glob("TEST-*.xml")),
        "android": suites((ROOT / "androidApp/build/outputs/androidTest-results/connected/debug").glob("*.xml")),
        "lint": lint,
        "artifacts": [{"path": relative(path), "sha256": digest(path), "bytes": path.stat().st_size}
                      for path in sorted((ROOT / "androidApp/build/outputs/apk").rglob("*.apk"))],
        "screenshots": captures,
        "ios": {"status": "Implemented — Not Verified", "appleToolsExecuted": False},
        "v04": {"automaticCaptureAccepted": False, "productionAutoAcceptanceEnabled": False},
        "limitations": ["Physical Android device validation is separate from emulator evidence.",
                        "NO-SOURCE tasks are not counted as passed test suites.",
                        "Visual inspection and screen reader usability require explicit observed evidence."]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"output": relative(args.output), "shared": evidence["shared"]["passed"],
                      "android": evidence["android"]["passed"], "captures": len(captures),
                      "inspected": sum(c["visuallyInspected"] for c in captures)}))


if __name__ == "__main__":
    main()
