#!/usr/bin/env python3
"""Bounded local source/APK privacy regression audit; never prints known secret values."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, check=True).stdout

def digest(value):
    return hashlib.sha256(value).hexdigest()

def permissions(value):
    return sorted(n.get('{http://schemas.android.com/apk/res/android}name')
                  for n in ET.fromstring(value).findall('uses-permission'))

known = []
mock = Path('/tmp/apex-v05-mock-data/pairing.json')
if mock.exists():
    known.append(('syntheticTlsMockToken', json.loads(mock.read_text())['token'].encode()))
provider = subprocess.run(['adb', 'shell', '-T', 'run-as', 'io.github.szymonbonkowski.apexcompanion',
                           'cat', 'files/developer-als-key.private'], capture_output=True)
if provider.returncode == 0 and len(provider.stdout.strip()) >= 8:
    known.append(('existingDeveloperProviderCredential', provider.stdout.strip()))

hits = []
def inspect(name, value):
    for label, secret in known:
        if secret in value:
            hits.append({'file': name, 'kind': label})
    if re.search(rb'-----BEGIN (?:RSA |EC )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]{64,}-----END', value):
        hits.append({'file': name, 'kind': 'privateKeyBlock'})

files = [p.decode() for p in git('ls-files', '-co', '--exclude-standard', '-z').split(b'\0') if p]
for name in files:
    path = ROOT / name
    if path.is_file():
        inspect(name, path.read_bytes())
artifacts = []
for path in sorted((ROOT / 'androidApp/build/outputs/apk').rglob('*.apk')):
    reader = False
    entries = 0
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            data = archive.read(name)
            entries += 1
            inspect(str(path.relative_to(ROOT)) + ':' + name, data)
            if 'release' in path.name and name.endswith('.dex') and b'developer-als-key.private' in data:
                reader = True
    artifacts.append({'path': str(path.relative_to(ROOT)), 'sha256': digest(path.read_bytes()),
                      'bytes': path.stat().st_size, 'entries': entries, 'releasePrivateReaderPresent': reader})
manifest = 'androidApp/src/main/AndroidManifest.xml'
before = permissions(git('show', 'HEAD:' + manifest))
after = permissions((ROOT / manifest).read_bytes())
acceptance = ROOT / 'companion/src/ApexCompanion.Capture.Core/AcceptancePolicy.cs'
result = dict(schemaVersion=1, repositoryFilesScanned=len(files), artifacts=artifacts, hits=hits,
              knownValueKinds=[label for label, _ in known],
              newAndroidPermissions=sorted(set(after) - set(before)),
              versionCatalogChanged=bool(git('diff', '--', 'gradle/libs.versions.toml').strip()),
              productionCapturePolicyUnchanged=acceptance.read_bytes() == git('show', 'HEAD:' + str(acceptance.relative_to(ROOT))),
              appleArtifactsAudited=False,
              scope='Known-value/private-key-block source and APK scan; manifest/catalog/capture-policy comparison. No proof about arbitrary unknown secrets, no Apple binary claim.')
output = ROOT / 'docs/validation/v0.5-privacy-audit.json'
output.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({key: result[key] for key in ['repositoryFilesScanned', 'hits', 'newAndroidPermissions', 'versionCatalogChanged', 'productionCapturePolicyUnchanged']}))
assert not hits and not result['newAndroidPermissions'] and not result['versionCatalogChanged']
assert result['productionCapturePolicyUnchanged'] and not any(a['releasePrivateReaderPresent'] for a in artifacts)
