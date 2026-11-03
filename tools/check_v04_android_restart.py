#!/usr/bin/env python3
"""Cold-process synthetic match test; preserve/restore existing private match files byte-for-byte.
Run only on a dedicated development emulator with the debug APK installed, no instrumentation active.
Never prints existing private history. Protected temporary recovery copies are removed after verified restoration. Generated screenshots contain synthetic fields only.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import time
import tempfile
import shutil
import os

PACKAGE = 'io.github.szymonbonkowski.apexcompanion'
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/validation/v0.4-captures'
FILES = ['no_backup/matches-v1.json' + suffix for suffix in ['', '.bak', '.new']]

def adb(*args, data=None, check=True):
    return subprocess.run(['adb', *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check)

def private_read(path):
    exists = adb('shell', '-T', 'run-as', PACKAGE, 'test', '-f', path, check=False)
    if exists.returncode == 1:
        return None
    if exists.returncode != 0:
        raise RuntimeError('Cannot inspect private file presence')
    # shell v2 preserves remote exit codes/stderr; exec-out merges cat errors into stdout.
    result = adb('shell', '-T', 'run-as', PACKAGE, 'cat', path, check=False)
    if result.returncode != 0:
        raise RuntimeError('Cannot preserve an existing private file')
    return result.stdout

def private_write(path, value):
    # Fixed allowlisted paths only, no user-controlled shell text.
    assert path in FILES
    adb('shell', '-T', 'run-as', PACKAGE, 'sh', '-c', f"'cat > {path}'", data=value)
    assert private_read(path) == value

def launch():
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    time.sleep(3)

def tap_text(text):
    import xml.etree.ElementTree as ET
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/apex-v04-ui.xml')
    raw = adb('exec-out', 'cat', '/data/local/tmp/apex-v04-ui.xml').stdout
    root = ET.fromstring(raw)
    parents = {child:parent for parent in root.iter() for child in parent}
    for node in root.iter('node'):
        if node.get('text') == text:
            while node.get('clickable') != 'true' and node in parents:
                node = parents[node]
            if node.get('clickable') != 'true': continue
            import re
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            time.sleep(1)
            return
    raise RuntimeError('Required UI destination missing: ' + text)

saved = {}
backup = Path(tempfile.mkdtemp(prefix='apex-v04-restart-backup-'))
os.chmod(backup, 0o700)
result = {'test': 'actual-android-cold-process-match-restart', 'synthetic': True, 'passed': False}
try:
    adb('shell', 'am', 'force-stop', PACKAGE)
    saved = {path: private_read(path) for path in FILES}
    for index, value in enumerate(saved.values()):
        if value is not None:
            target = backup / str(index)
            target.write_bytes(value); os.chmod(target, 0o600)
    adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'no_backup')
    stamp = int(time.time()) - 100
    match = dict(schemaVersion=1,id='cold-process-synthetic-v04',identity=None,mode='RANKED',map='Synthetic map',legend='Synthetic legend',
                 kills=3,assists=2,damage=750,placement=4,rpDelta=25,timestampSeconds=stamp,timestampSource='USER_ENTERED',source='MANUAL',
                 quality='USER_CONFIRMED',evidenceEventId=None,parserVersion=None,fieldConfidence={},revision=0)
    store = dict(schemaVersion=1,records=[match],originals=[],cursors={},syncTimes={},deletedIds=[],deletedEvents=[])
    payload = json.dumps(store,separators=(',',':')).encode()
    for path in FILES:
        adb('shell','run-as',PACKAGE,'rm','-f',path)
    private_write(FILES[0], payload)
    launch(); tap_text('Matches')
    before = private_read(FILES[0]); assert before == payload
    adb('shell','am','force-stop',PACKAGE)
    launch(); tap_text('Matches')
    after = private_read(FILES[0]); assert after == before
    adb('shell','uiautomator','dump','/data/local/tmp/apex-v04-ui.xml')
    xml = adb('exec-out','cat','/data/local/tmp/apex-v04-ui.xml').stdout.decode()
    for _ in range(10):
        if 'Synthetic map' in xml and 'Kills 3' in xml: break
        adb('shell','input','swipe','900','1900','900','1100','500')
        time.sleep(.4)
        adb('shell','uiautomator','dump','/data/local/tmp/apex-v04-ui.xml')
        xml = adb('exec-out','cat','/data/local/tmp/apex-v04-ui.xml').stdout.decode()
    assert 'Synthetic map' in xml and 'Kills 3' in xml and 'USER_CONFIRMED' in xml
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'v04-cold-process-matches.png').write_bytes(adb('exec-out','screencap','-p').stdout)
    result.update(passed=True,syntheticStoreSha256=hashlib.sha256(payload).hexdigest())
finally:
    adb('shell','am','force-stop',PACKAGE,check=False)
    for path,value in saved.items():
        if value is None: adb('shell','run-as',PACKAGE,'rm','-f',path,check=False)
        else: private_write(path,value)
    result['originalFilesRestored'] = all(private_read(path) == value for path,value in saved.items()) if saved else False
    adb('shell','rm','-f','/data/local/tmp/apex-v04-ui.xml',check=False)
    report = ROOT/'docs/validation/v0.4-restart-evidence.json'
    report.write_text(json.dumps(result,indent=2)+'\n')
    if result['originalFilesRestored']: shutil.rmtree(backup)
    else: result['protectedRecoveryDirectory'] = str(backup)
    print(json.dumps(result))
    launch()
