#!/usr/bin/env python3
"""Collect executed v0.4 results and private-value absence checks; never print secret values."""
from pathlib import Path
import datetime
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def suites(paths):
    totals=dict(total=0,passed=0,failed=0,errors=0,skipped=0)
    files=[]
    for p in sorted(paths):
        root=ET.parse(p).getroot()
        for s in ([root] if root.tag=='testsuite' else root.findall('testsuite')):
            total=int(s.get('tests','0')); failed=int(s.get('failures','0')); errors=int(s.get('errors','0')); skipped=int(s.get('skipped','0'))
            for k,v in dict(total=total,passed=total-failed-errors-skipped,failed=failed,errors=errors,skipped=skipped).items(): totals[k]+=v
        files.append(dict(path=str(p.relative_to(ROOT)),sha256=digest(p)))
    return dict(**totals,files=files)

def cmd(args): return subprocess.run(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout
known=[]
mock=Path('/tmp/apex-v04-mock-data/pairing.json')
if mock.exists(): known.append(('generatedSyntheticPairingToken',json.loads(mock.read_text())['token'].encode()))
provider=subprocess.run(['adb','shell','-T','run-as','io.github.szymonbonkowski.apexcompanion','cat','files/developer-als-key.private'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
provider_available=provider.returncode==0 and len(provider.stdout.strip())>=8
if provider_available: known.append(('existingDeveloperProviderCredential',provider.stdout.strip()))
files=sorted({p for p in cmd(['git','ls-files','-co','--exclude-standard','-z']).decode().split('\0') if p})
hits=[]; scanned=0
for name in files:
    p=ROOT/name
    if p.is_file():
        value=p.read_bytes(); scanned+=1
        for label,secret in known:
            if secret in value: hits.append(dict(file=name,kind=label))
        if re.search(rb'-----BEGIN (?:RSA )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]{64,}-----END', value):
            hits.append(dict(file=name,kind='privateKeyMaterialMarker'))
artifacts=[]
for p in sorted((ROOT/'androidApp/build/outputs/apk').rglob('*.apk')):
    entries=0
    with zipfile.ZipFile(p) as z:
        release_reader=False
        for name in z.namelist():
            value=z.read(name); entries+=1
            for label,secret in known:
                if secret in value: hits.append(dict(file=str(p.relative_to(ROOT))+':'+name,kind=label))
            if p.name.endswith('release-unsigned.apk') and name.endswith('.dex') and b'developer-als-key.private' in value: release_reader=True
    artifacts.append(dict(path=str(p.relative_to(ROOT)),sha256=digest(p),bytes=p.stat().st_size,zipEntriesScanned=entries,releasePrivateReaderPresent=release_reader))
windows=ROOT/'companion/artifacts/windows-win-x64'
windows_files=0
for p in windows.rglob('*'):
    if p.is_file():
        windows_files+=1; value=p.read_bytes()
        for label,secret in known:
            if secret in value: hits.append(dict(file=str(p.relative_to(ROOT)),kind=label))
exe=windows/'ApexCompanion.Capture.Windows.exe'
trx=ROOT/'companion/TestResults/capture-tests-final.trx'
ns={'t':'http://microsoft.com/schemas/VisualStudio/TeamTest/2010'}
counters=ET.parse(trx).find('.//t:Counters',ns).attrib
logs=ROOT/'docs/validation/v0.4-logs';logs.mkdir(exist_ok=True)
for name in ['full-final','companion-test-selfcontained-final','windows-publish-final','replay-final','focused6','photo-selected6','companion-test-final','ui-connected4','ui-connected5','photo-selected','photo-selected2','photo-selected3','photo-selected4','photo-selected5','history-focused3','profile-disconnect-focused','profile-disconnect-focused2','profile-disconnect-focused3','post-server-restart-android']:
    source=Path('/tmp')/f'apex-v04-{name}.log'
    if source.exists(): (logs/source.name).write_bytes(source.read_bytes())
evidence=dict(
    schemaVersion=1,generatedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
    baseline=cmd(['git','rev-parse','HEAD']).decode().strip(),branch=cmd(['git','branch','--show-current']).decode().strip(),
    milestoneComplete=False,automaticApexTrackingAchieved=False,productionAutoAcceptanceEnabled=False,
    androidSharedGate=json.loads(Path('/tmp/apex-v04-full-final.meta.json').read_text()),
    shared=suites((ROOT/'shared/build/test-results/testAndroidHostTest').glob('TEST-*.xml')),
    android=suites((ROOT/'androidApp/build/outputs/androidTest-results/connected/debug').glob('*.xml')),
    companion=dict(counters=counters,trxSha256=digest(trx),runtime='Linux self-contained net10.0, no Windows execution'),
    syntheticOcr=json.loads((ROOT/'companion/fixtures/synthetic-evidence.json').read_text()),
    windows=dict(status='Implemented — Not Verified',crossPublished=True,exeSha256=digest(exe),archiveSha256=digest(ROOT/'companion/artifacts/apex-companion-capture-win-x64-dev.zip'),archiveBytes=(ROOT/'companion/artifacts/apex-companion-capture-win-x64-dev.zip').stat().st_size,packageFiles=windows_files,gameExecution=False),
    mockLifecycle=json.loads(Path('/tmp/apex-v04-mock-lifecycle.json').read_text()),
    postServerRestartAndroid={'passed':'OK (1 test)' in Path('/tmp/apex-v04-post-server-restart-android.log').read_text(),'scope':'same native pinned HTTPS/auth/replay test after server restart, no re-pairing or token rotation'},
    ios=dict(status='Implemented — Not Verified',appleToolsExecuted=False),
    artifacts=artifacts,privacyAudit=dict(repositoryFilesScanned=scanned,windowsPackageFilesScanned=windows_files,
        generatedPairingTokenAvailable=mock.exists(),developerProviderCredentialAvailable=provider_available,hits=hits,
        limitation='Known-value and private-key-marker scan, source/manifest review; not a proof that arbitrary unknown secrets cannot exist.'),
    coldRestart=json.loads((ROOT/'docs/validation/v0.4-restart-evidence.json').read_text()),
    remaining=['No real Windows WGC/Apex execution','No representative or held-out real Apex OCR fixtures; production automatic acceptance disabled',
               'Native mobile OCR engine not implemented','iOS native compilation/runtime/privacy/lifecycle verification deferred by ADR0007',
               'Desktop installer/signing and journal archive/pruning UI not implemented','No physical-device/full accessibility or Windows gaming overhead measurement'],
    failedAttempts=[
        dict(scope='First integrated full Android/shared gate',reason='212 shared tests passed, 3 legacy History UI tests failed due ambiguous navigation and clipped coordinate clicks; explicit accessible navigation/action selectors corrected, assertions retained; failed XML/log archived.'),
        dict(scope='Initial Android UI instrumentation',reason='Clipped controls, stale composition selectors and insufficient awaited dialog actions; corrected real accessibility actions and full visibility; earlier runs not counted.'),
        dict(scope='Second full gate and focused legacy checks',reason='Navigation desc wrappers needed their actionable parent; then a sibling description below +150 was clipped. Corrected full visibility and actual action selectors; original behavioral assertions retained.'),
        dict(scope='Shared incremental artifacts',reason='Empty compile/runtime bundle caused unresolved imports/NoClassDefFoundError; cause unproven, isolated clean/rebuild recovered; Gradle execution serialized.'),
        dict(scope='Android17 Linux TLS initial attempt',reason='Local network permission blocked connection; added explicit ACCESS_LOCAL_NETWORK consent and fail-closed checks.'),
        dict(scope='Native picker selection attempts1–5',reason='Android17 Collections/device albums and final Add confirmation required updated test navigation; selected6 passed.'),
        dict(scope='First cold-process checker',reason='Fresh install lacked no_backup; exec-out merged remote cat errors. Revised shell-v2 presence/status checks, mkdir and protected temporary recovery copies; actual restart passed without an existing match file being lost.'),
        dict(scope='Ordinary framework-dependent dotnet test',reason='Host lacks Microsoft.AspNetCore.App runtime; aborted, not a pass. Self-contained linux-x64 final run passed.')])
evidence['priorIntegratedAttempts']=[dict(attempt=index,**json.loads(path.read_text())) for index in range(1,5) if (path:=Path(f'/tmp/apex-v04-full-attempt{index}.meta.json')).exists()]
evidence['screenshots']={p.name:dict(sha256=digest(p),bytes=p.stat().st_size,synthetic=True,visuallyInspected=True) for p in sorted((ROOT/'docs/validation/v0.4-captures').glob('*.png'))}
evidence['lint']={}
for variant in ['debug','release']:
    issues=ET.parse(ROOT/f'androidApp/build/reports/lint-results-{variant}.xml').getroot().findall('issue')
    evidence['lint'][variant]={'errors':sum(i.get('severity') in ['Error','Fatal'] for i in issues),'warnings':sum(i.get('severity')=='Warning' for i in issues)}
evidence['sourceManifest']={name:digest(ROOT/name) for name in files if (ROOT/name).is_file() and (name.startswith(('shared/src/', 'androidApp/src/', 'iosApp/', 'companion/src/')) and (ROOT/name).suffix in {'.kt','.swift','.cs','.kts','.csproj','.props','.xml','.plist'})}
output=ROOT/'docs/validation/v0.4-evidence.json';output.write_text(json.dumps(evidence,indent=2,ensure_ascii=False)+'\n')
print(json.dumps(dict(shared=evidence['shared']['passed'],android=evidence['android']['passed'],companion=counters,privacyHits=len(hits)),ensure_ascii=False))
assert not hits
assert evidence['postServerRestartAndroid']['passed']
assert not any(a['releasePrivateReaderPresent'] for a in artifacts)
assert evidence['androidSharedGate']['exitCode']==0 and evidence['shared']['failed']==0 and evidence['android']['failed']==0
