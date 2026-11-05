#!/usr/bin/env python3
"""Actual cold-process cosmetic persistence check on the development emulator.

Only the three allowlisted cosmetic AtomicFile paths are temporarily replaced.
Existing bytes are held in mode0600 recovery files and restored byte-for-byte.
No game/history/credential file is read, printed, or included in evidence.
Run with the debug app installed and no instrumentation active.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.szymonbonkowski.apexcompanion"
SERIAL = "emulator-5554"
FILES = ["files/appearance-v1.json" + suffix for suffix in ("", ".bak", ".new")]


def adb(*args, data=None, check=True):
    return subprocess.run(["adb", "-s", SERIAL, *args], input=data, capture_output=True, check=check)


def read(path):
    assert path in FILES
    exists = adb("shell", "-T", "run-as", PACKAGE, "test", "-f", path, check=False)
    if exists.returncode == 1:
        return None
    if exists.returncode != 0:
        raise RuntimeError("Cannot inspect cosmetic file presence")
    return adb("shell", "-T", "run-as", PACKAGE, "cat", path).stdout


def write(path, value):
    assert path in FILES
    adb("shell", "-T", "run-as", PACKAGE, "sh", "-c", f"'cat > {path}'", data=value)
    if read(path) != value:
        raise RuntimeError("Cosmetic file write did not round-trip")


def launch_and_verify(payload):
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        adb("shell", "uiautomator", "dump", "/data/local/tmp/apex-v05-restart-ui.xml")
        xml = adb("exec-out", "cat", "/data/local/tmp/apex-v05-restart-ui.xml").stdout
        if b"Tactical" in xml and b"PERSONAL TELEMETRY" in xml:
            if read(FILES[0]) != payload:
                raise RuntimeError("Cold launch changed committed cosmetic bytes")
            return
        time.sleep(.5)
    raise RuntimeError("Tactical header did not appear after cold launch")


def main():
    if not adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() == b"1":
        raise RuntimeError("This isolated check is restricted to the development emulator")
    recovery = Path(tempfile.mkdtemp(prefix="apex-v05-cosmetic-recovery-"))
    os.chmod(recovery, 0o700)
    saved = {}
    result = {"schemaVersion": 1, "test": "actual-android-cold-process-appearance-restart",
              "syntheticFixture": True, "passed": False, "gameFilesAccessed": False,
              "deviceSerial": SERIAL, "originalFilesRestored": False}
    try:
        adb("shell", "am", "force-stop", PACKAGE)
        saved = {path: read(path) for path in FILES}
        for index, value in enumerate(saved.values()):
            if value is not None:
                target = recovery / str(index)
                target.write_bytes(value)
                os.chmod(target, 0o600)
        modules = ["MAP_ROTATION", "UPCOMING_MAP", "RANKED_PROFILE", "GOAL_PROGRESS", "PREDATOR_THRESHOLD",
                   "RECENT_MATCH", "RP_TREND", "SESSION_SUMMARY", "CAPTURE_STATUS"]
        preferences = {"style": "TACTICAL", "mode": "DARK", "accentHex": "E89564", "density": "COMPACT", "motion": "OFF"}
        fixture = {"schemaVersion": 1, "preferences": preferences,
                   "dashboard": {"items": [{"module": module, "visible": False, "size": "COMPACT"} for module in modules],
                                 "hideUnavailable": True, "mapModeId": "battle_royale"},
                   "presets": [{"id": "synthetic-cold-process", "name": "Synthetic restart fixture", "preferences": preferences}]}
        payload = json.dumps(fixture, separators=(",", ":")).encode()
        adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
        for path in FILES:
            adb("shell", "run-as", PACKAGE, "rm", "-f", path)
        write(FILES[0], payload)
        launch_and_verify(payload)
        adb("shell", "am", "force-stop", PACKAGE)
        launch_and_verify(payload)
        result.update(passed=True, fixtureSha256=hashlib.sha256(payload).hexdigest(), coldLaunches=2)
    finally:
        adb("shell", "am", "force-stop", PACKAGE, check=False)
        restore_errors = []
        for path, value in saved.items():
            try:
                if value is None:
                    adb("shell", "run-as", PACKAGE, "rm", "-f", path)
                else:
                    write(path, value)
            except Exception:
                restore_errors.append(path)
        result["originalFilesRestored"] = bool(saved) and not restore_errors and all(read(path) == value for path, value in saved.items())
        adb("shell", "rm", "-f", "/data/local/tmp/apex-v05-restart-ui.xml", check=False)
        if result["originalFilesRestored"]:
            shutil.rmtree(recovery)
            # Re-publish the restored app appearance to follow-app widgets.
            result["restoredAppRelaunched"] = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", check=False).returncode == 0
        else:
            result["protectedRecoveryDirectory"] = str(recovery)
            result["restoreErrors"] = restore_errors
        (ROOT / "docs/validation/v0.5-restart-evidence.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result))


if __name__ == "__main__":
    main()
