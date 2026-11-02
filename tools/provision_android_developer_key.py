#!/usr/bin/env python3
"""Provision/remove a local debug-only ALS credential without putting it in APKs or command arguments."""
import argparse
import os
from pathlib import Path
import stat
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--key-file', type=Path, help='Owner-only key file outside the repository; never a literal credential')
parser.add_argument('--remove', action='store_true', help='Remove the private app credential and restart its process')
parser.add_argument('--serial', default='emulator-5554')
args = parser.parse_args()
package = 'io.github.szymonbonkowski.apexcompanion'
adb = ['adb', '-s', args.serial]

def command(parts, **kwargs):
    result = subprocess.run(adb + parts, capture_output=True, **kwargs)
    if result.returncode:
        raise SystemExit('ADB operation failed; install the debug APK and check the selected device. No credential output emitted.')
    return result

command(['shell', 'am', 'force-stop', package])
if args.remove:
    command(['shell', 'run-as', package, 'rm', '-f', 'files/developer-als-key.private'])
    print('Private developer credential removed from app data. Restart opens the unconfigured provider.')
else:
    if args.key_file is None:
        parser.error('--key-file is required unless --remove is used')
    source = args.key_file.expanduser().resolve()
    repository = Path(__file__).resolve().parents[1]
    if source.is_relative_to(repository):
        raise SystemExit('Key file must be outside the repository.')
    mode = source.stat().st_mode
    if not stat.S_ISREG(mode) or mode & 0o077 or source.stat().st_uid != os.getuid():
        raise SystemExit('Key file must be an owner-only regular file owned by the current user.')
    key = source.read_bytes().strip()
    if not key or len(key) > 4096 or any(c < 33 or c > 126 for c in key):
        raise SystemExit('Invalid private credential file; no values printed.')
    # STDIN only; never interpolated in a shell argument, environment variable or tool log.
    command(['shell', 'run-as', package, 'sh', '-c', "'umask 077; mkdir -p files; cat > files/developer-als-key.private'"], input=key)
    command(['shell', 'run-as', package, 'chmod', '600', 'files/developer-als-key.private'])
    print('Private developer credential provisioned in debug app data. No credential stored in the APK.')
