#!/usr/bin/env python3
"""Linux synthetic mock process test; no game, private user journal or secrets in output."""
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time

if os.name != 'posix' or len(sys.argv) != 3:
    raise SystemExit('Usage on Linux: check_mock_lifecycle.py <self-contained-mock-executable> <evidence-json>')
exe = str(Path(sys.argv[1]).resolve())
with socket.socket() as probe:
    probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
with tempfile.TemporaryDirectory(prefix='apex-capture-lifecycle-') as value:
    directory = Path(value); os.chmod(directory, 0o700)
    processes = []
    with (directory/'private-process.log').open('wb') as log:
        def start():
            p = subprocess.Popen([exe, '127.0.0.1', str(port), value], stdout=log, stderr=subprocess.STDOUT)
            processes.append(p)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if p.poll() is not None: raise RuntimeError('Synthetic mock exited before listening')
                with socket.socket() as check:
                    check.settimeout(.1)
                    if (directory/'pairing.json').exists() and check.connect_ex(('127.0.0.1', port)) == 0: return p
                time.sleep(.05)
            raise TimeoutError('Synthetic mock did not start')
        try:
            first = start(); original = (directory/'pairing.json').read_bytes()
            duplicate = subprocess.run([exe, '127.0.0.1', str(port), value], stdout=log, stderr=subprocess.STDOUT, timeout=5)
            assert duplicate.returncode != 0
            assert (directory/'pairing.json').read_bytes() == original
            first.send_signal(signal.SIGTERM); assert first.wait(timeout=5) == 0
            before = json.loads(original); restarted = start(); after = json.loads((directory/'pairing.json').read_bytes())
            assert all(before[k] == after[k] for k in ['token', 'certificatePin', 'companionId', 'identity'])
            restarted.send_signal(signal.SIGTERM); assert restarted.wait(timeout=5) == 0
        finally:
            for p in processes:
                if p.poll() is None: p.kill(); p.wait(timeout=5)
result = dict(linuxMockSigtermExit=0, duplicateWriterRejected=True, credentialsUnchangedOnDuplicate=True,
              pairingStableAcrossProcessRestart=True, passed=True)
Path(sys.argv[2]).write_text(json.dumps(result, indent=2)+'\n')
print('PASS: synthetic Linux mock shutdown, single writer and pairing persistence; no Windows claim.')
