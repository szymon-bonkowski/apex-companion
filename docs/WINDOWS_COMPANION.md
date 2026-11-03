# Windows Auto Capture — setup, pairing and verification

Status: Windows runtime **Implemented — Not Verified**. Local unsigned package: `companion/artifacts/apex-companion-capture-win-x64-dev.zip` (not a published release). Linux crosscompilation and portable synthetic tests are available. Actual Apex layouts and exclusive-fullscreen/borderless/windowed capture are unverified. Production automatic acceptance is disabled: no validated real layout is registered. The primary zero-per-match-interaction goal is unfinished; do not describe this prototype as working automatic Apex tracking.

## Build and install locally

Use .NET10 SDK. On Linux crosscompilation needs Windows targeting reference packages; it cannot run WGC/WinForms.

```sh
dotnet build companion/src/ApexCompanion.Capture.Windows/ApexCompanion.Capture.Windows.csproj -c Release
dotnet publish companion/src/ApexCompanion.Capture.Windows/ApexCompanion.Capture.Windows.csproj -c Release -r win-x64 --self-contained true -o companion/artifacts/windows-win-x64
dotnet test companion/tests/ApexCompanion.Capture.Tests/ApexCompanion.Capture.Tests.csproj -c Release -r linux-x64 -p:SelfContained=true --logger 'trx;LogFileName=capture-tests.trx' --results-directory companion/TestResults
```

Copy the win-x64 output to a Windows10 1903+ or Windows11 PC. Install a trusted local Tesseract5 build with English trained data; choose its executable in the utility. Tesseract/runtime/language data are separate dependencies, not bundled or downloaded automatically. Review their distribution licenses before repackaging. The output is an unsigned local utility folder, not an installer/store release.

Start ApexCompanion.Capture.Windows.exe with no arguments for the independent journal. To explicitly scope records to a linked mobile player:

```text
ApexCompanion.Capture.Windows.exe --uid 123456789 --platform PC
```

Other platform enum values PLAYSTATION/XBOX/SWITCH describe user-configured history scope; this does not implement capture from those consoles. Scope is not inferred from screen names. Each identity has a separate local journal/credential directory. Close capture/sync before relaunching with another identity. A per-identity Windows mutex rejects a second writer; Linux mock uses an exclusive process-lock file. Windows mutex/runtime behavior still requires Windows validation. Mobile pairing must select the exact same identity, or independent scope when no identity is configured.

## Capture controls and privacy

Capture and listener start disabled. Choose installed Tesseract and sampling interval (1–10seconds, default2seconds). Press Choose Apex window/Start, explicitly select the intended window in Windows' picker. The adapter uniquely resolves the selected visible Apex-title window and creates WGC for that HWND using documented interop; the returned picker display item is never directly captured. Ambiguous/non-Apex titles are rejected. This title safeguard is not authentication of the game process. No process handle, injection, hooking or gameplay automation is used. System border stays enabled. STOP capture or closing the utility ends capture; an error stops work. Capture restrictions are never bypassed.

Frames are cropped/resized in memory and passed to local Tesseract over stdin. No video/frame/screenshot is saved by default. Only normalized fields, confidence, versioned parser and immutable events persist. Prototype result detection requires observed gameplay for20seconds then3result frames spanning at least2seconds. Loading/spectator/session-summary/unknown screens create no match. Repeated results update one event; contradictions clear fields, out-of-order frames are ignored and startup results cannot arm tracking. Missing mode/map/legend/duration remain unknown. Review candidate confirmation queues a USER_CONFIRMED/WINDOWS_CAPTURE record; it never becomes an official server result. The implemented automatic policy/queue path is exercised only with an explicitly test-only validated synthetic policy; Production registry is empty with no runtime enable switch.

## Pair and synchronize

1. Put PC and phone on a trusted reachable LAN; select the PC's private IPv4 address. No public/0.0.0.0 bind or automatic router/firewall opening. If Windows Firewall asks, allow only the intended private network/interface.
2. For the first device explicitly press Pair new device/rotate pairing code. HTTPS43821 starts only on that interface. Copy endpoint, companion ID, certificate SHA256 pin and private token directly from the utility to Capture sync on the phone. Verify the pin on the PC; do not use a configuration received from another device/person.
3. Select matching mobile identity, consent to pairing and grant Android17 local-network permission when asked. iOS local-network consent/source requires later native verification. Token and pin must each be64lowercase hex characters.
4. Pair and synchronize validates exact certificate identity and hostname then authenticates before storing native protected credentials. Phone pulls accepted records on app foreground, every30seconds while foreground and on Sync now. No constant background or instant delivery guarantee. Offline results stay in the bounded PC journal; later retries deduplicate stable IDs and preserve mobile corrections. Data and cursor commit before ACK; PC retains accepted records after ACK.
5. Unpair and stop local sync on PC revokes the token. After restarting the utility, Start/resume paired local sync reuses the protected token and certificate; the phone keeps its one-time pairing. Creating another pairing explicitly revokes the previous token (one paired device supported). Mobile unpair attempts remote revocation and clears local encrypted credentials; if offline, separately revoke on PC. Saved match records remain. Changing PC interface or expired certificate requires explicit credential regeneration/re-pairing while stopped; no silent trust reset.

No screenshots, API keys, RP observations, unrelated files or capture settings are transferred. Desktop current-user DPAPI protects pairing; Android Keystore and iOS ThisDeviceOnly Keychain protect phone secrets. Desktop data lives under LocalAppData/ApexCompanion/Capture with identity subdirectories. Keep private journals/backups under your own account. Queue capacity16MiB/10000accepted records and1000candidates; writes fail visibly without truncation. No desktop archive/pruning UI is implemented yet; preserve journals externally before any manual recovery. Corrupt/future records stay untouched and halt tracking.

## Linux mock and offline replay

```sh
dotnet publish companion/src/ApexCompanion.Capture.Mock/ApexCompanion.Capture.Mock.csproj -c Release -r linux-x64 --self-contained true -o /tmp/apex-capture-mock
/tmp/apex-capture-mock/ApexCompanion.Capture.Mock 127.0.0.1 43821 /tmp/apex-capture-mock-private --add-synthetic
```

This creates an explicitly synthetic accepted event and a mode0600 pairing.json in the chosen mode0700 directory. Never paste its token into a report or commit it. The emulator reaches host loopback at10.0.2.2; change the endpoint in its private test pairing copy to https://10.0.2.2:43821 (certificate SAN includes that address). For a physical Android device bind a selected private LAN interface instead of loopback. The mock is not real game capture. Stop it and remove its generated credentials after testing. Android MatchPlatformInstrumentedTest reads a synthetic private fixture only when explicitly provisioned at /data/local/tmp/apex-v04-match-sync-fixture.json; otherwise that integration check is opt-in skipped.

Reproduce measured local extraction after installing a local Tesseract5/English engine:

```sh
dotnet run --project companion/src/ApexCompanion.Capture.Replay/ApexCompanion.Capture.Replay.csproj -c Release -- /path/to/tesseract companion/fixtures /tmp/synthetic-capture-evidence.json
```

The replay harness processes only the documented original fixture manifest and writes normalized per-field/classifier/resource evidence; it never treats fixture output as real match history.

Original synthetic images are in companion/fixtures with manifest rights and exact expected fields; generator uses ImageMagick/fonts and no game assets. synthetic-evidence.json records measured Tesseract extraction. No representative real Apex or held-out game corpus exists. Synthetic accuracy is not game accuracy or Windows resource overhead.

## Mandatory Windows verification before acceptance

Use disposable Windows/phone test histories for the synthetic helper and mock. Keep synthetic results out of a real player journal; preserve any existing private directory before test setup.

1. On Windows verify dependency installation, OS/API support, system picker, correct selected-window border, immediate STOP/error/window-close release, and no frame/video files on disk. Start the original ordinary-window helper with --synthetic-window; verify capture/detection/OCR/review/queue and restart with results already visible. This only establishes ordinary-window WGC behavior.
2. When permitted by applicable game rules, capture the actual selected Apex window in windowed/borderless/exclusive modes, alt-tab/resize/display-scaling variants; record protected/black results as unsupported without bypass. Confirm spectator/loading/menu/session screens never create matches. Microsoft's API documentation is not EA/anti-cheat approval.
3. With owner consent, curate rights-cleared representative frames with only necessary regions, null/ambiguous fields and negatives. Keep separate tuning and held-out subsets. Measure classifier FP/FN and per-field missing/wrong/exact values for each resolution/language/layout. Add a new versioned layout only after it meets a documented acceptance rule; do not edit historical parser identities.
4. Validate successive real matches, missing result screens, identical-stat matches, game restart and corrections while phone is offline; reconnect and prove exactly-once mobile insertion over authenticated pinned TLS. Wrong pin/token/identity, interrupted batches/ACK, capacity and revocation must fail safely.
5. Profile actual Windows gaming CPU/GPU/memory and capture behavior. Linux parent-process/replay timings do not establish gaming overhead. Only after real extraction and PC-to-phone evidence may automatic tracking be called achieved.

See [feasibility](validation/v0.4-capture-feasibility.md), [ADR0009](adr/0009-windows-match-capture.md), [protocol](contracts/match-sync-v1.md) and [v0.4 evidence](validation/v0.4-report.md).
