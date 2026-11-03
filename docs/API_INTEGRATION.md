# External data source integration and access policy

**Evidence baseline:** 2026-11-02. Third-party **unofficial** Apex Legends Status API at `https://apexlegendsapi.com/`. Map rotation v2 was subsequently confirmed with the owner’s private developer key as detailed below. Other endpoint contracts remain documentation-only; recheck terms/schema before their implementation.

| Data | Documented endpoint | Important caveat |
|---|---|---|
| Map rotations | `GET /maprotation?version=2` | Modes/validity vary by season; unknown fields allowed |
| Public player identity | `GET /bridge?uid=...&platform=...` | UID/platform and nullable identity fields |
| Name lookup | `GET /bridge?player=...&platform=...` | EA/Origin-linked PC identity may differ from Steam name |
| Predator threshold | `GET /predator` | Source/version/platform semantics must be validated |
| Server status | `GET /servers` | Visible provider attribution required by docs |
| Match history | `/games` or legacy history field | Access currently restricted/not broadly available to new users |

Provider auth uses an Authorization header or `auth` query; favor **header** to limit accidental credential logs. Published baseline standard limit is 5 requests per second per key, subject to current fair-use terms — **not a polling target**. One key per project/person and upstream redistribution limits require verification.

## Modes and release gate

- **Offline/demo**: no API, manual RP calculator, synthetic DEMO map, both Android/iOS native widgets.
- **Authorized developer test**: explicit local-only key outside git; synthetic/redacted fixtures, documented low-rate tests; never ship resulting credential-bearing builds.
- **Public live**: written recorded provider authorization covering both Android and iOS redistribution, attribution and allowed key topology; budgeted, abuse-resistant access. Otherwise leave remote feature off.

No secret in Android `BuildConfig`, JNI, Swift sources, Info.plist, WidgetKit extension, distributed binary or base64 obfuscation. An unlimited public pass-through proxy is forbidden. If approved, a shared gateway caches global map/server/Predator sources with bounded per-identity queries and no public lookup history.

## KMP adapter and cache

Use a provider-independent shared Kotlin interface, `kotlinx.serialization` models and a verified Ktor common HTTP client with Android and iOS engine dependencies chosen after inspecting the actual Wizard build. Normalize external fields to nullable domain envelopes and UTC schedule, never leak raw provider JSON into Compose screens. Widgets read validated app-generated snapshots and **never** call upstream APIs.

Recommended initial **hypotheses** for UX/caching (subject to source terms and battery validation): map refresh on foreground if older than 5 minutes, best-effort background checks at least 15–30 minutes when permitted; profile only opted-in and rate-limited; Predator/server less often. Android WorkManager and iOS background/WidgetKit scheduling are OS-owned and not exact. If source segment expires, the widget must not falsely call it live. Deduplicate requests across all widget instances, use exponential backoff on 429/5xx and disable further refresh for unresolved 403.

## Public online acceptance

Record (1) explicit public redistribution rights on both platforms, (2) credential isolation, (3) rate budget and sustainability, (4) source attribution, (5) profile privacy, (6) incident/rollback strategy, (7) sanitized actual contract test fixtures. Failure of any gate means **offline-only public release**. Source evidence and gaps: [SOURCES.md](SOURCES.md).

## v0.1 implementation and observed contract — 2026-11-02

The provider portal was re-read. Real API host: `https://api.apexlegendsstatus.com`, path `/maprotation?version=2`; Authorization header required. Portal states one key/project/person, fair use and initial 5 requests/second across APIs, a ceiling rather than a polling target. These docs do not grant public Android/iOS redistribution or a credential topology.

The owner supplied a private developer key outside this repository, then completed the provider-required Discord verification. An authenticated documented curl request and the **production Ktor adapter** both returned HTTP200. Observed top-level modes: `battle_royale`, `ranked`, `ltm`, `wildcard`. Each supplied current/next map/code strings and numeric Unix-second start/end. Mixtape additionally supplied `isActive` and `eventName`. Source-defined activity is respected: explicitly inactive segments never become current/upcoming; malformed activity abstains. Unknown map/mode strings have safe textual fallbacks. Actual response bytes and real-data screenshots stay private outside git; no provider image was fetched. Tests use synthetic replacements, not copied map pools.

`ApexStatusRotationProvider` uses an injected Ktor client and explicit `AuthorizedDeveloper` header supplier or `ApprovedGateway` HTTPS route with permission reference. The adapter streams success bodies within 512KiB, ignores error bodies, disables redirects/logging, identifies itself as `ApexCompanion/0.1`, maps 401/403, 429, 5xx/timeouts/network/schema failure and reads numeric/HTTP-date Retry-After. A strict Accept override caused HTTP406 in the real check and was removed to match the documented request. Initial HTTP429 explained an unverified developer account; the owner resolved that before another real response was accepted.

Repository requests are serialized across modes/consumers. Fresh foreground data are rechecked after five minutes or source boundaries; attempts have a 30-second minimum, exponential failure delays 30s–30m (longer Retry-After up to24h), persisted failures and forbidden latch. Failed/partial responses retain usable old schedules with original per-mode observation times and STALE quality. Source schedule coverage bounds freshness; never extrapolate cycles. Android background refresh is unique best-effort 30-minute work only while an authorized developer source is configured. Widgets do not fetch per instance.

### Private local contract check

`ProviderLiveContractTest` is an Android-host-only, deliberately opt-in test. Ordinary CI/builds skip it. It requires an owner-only key file outside the project, makes one production-client request, validates the parser and stores credential-free response bytes only under private `~/.local/state/apex-companion/provider-validation/`. No credential is printed or passed as a Gradle property:

```bash
APEX_STATUS_LIVE_TEST=1 APEX_STATUS_KEY_FILE="$HOME/.config/apex-companion/als-key.private" ./gradlew :shared:testAndroidHostTest --tests '*ProviderLiveContractTest' --rerun-tasks
```

### Private Android runtime and public gate

Android has separate debug/release factories. Only the **debug** source can read deliberately provisioned owner-only `files/developer-als-key.private` from private app data. This is not a project/build input, asset, resource, cache/widget field or public key-entry setting. Release always binds `UnconfiguredRotationProvider` and contains no developer-key reader. Debug backup is disabled; cloud backup and device transfer exclude the private credential even if build variants replace one another. The helper supplies key bytes only over ADB stdin, never command arguments/logs:

```bash
python3 tools/provision_android_developer_key.py --key-file "$HOME/.config/apex-companion/als-key.private" --serial emulator-5554
# Launch the installed debug app normally.
python3 tools/provision_android_developer_key.py --remove --serial emulator-5554
```

This is controlled local developer validation under ADR0002, not a publicly distributed BYOK feature, hosted proxy or provider redistribution grant. Debug builds without a deliberately provisioned file remain unavailable. Android real data, all four mode selections, countdown/current/next, cache retention on real offline failure and online recovery were checked on the emulator. All APKs were scanned for the actual supplied key and contained none. iOS keeps its provider unconfigured until an approved source is supplied; shared adapter/Darwin dependencies and source-only cache/widget integration are implemented, with Apple verification deferred.

### Required before public activation

Record written Android+iOS redistribution/attribution rights; choose a sustainable authorized bounded credential-isolating topology; wire the approved gateway or other explicitly allowed strategy in release hosts; test privacy/rate/error rollback and actual Android network behavior. No hosted backend/payment/subscription was introduced. A private key and successful developer test do not clear that gate. All iOS runtime/source compilation/signing verification waits until final stabilization.

## v0.2 ranked developer integration — 2026-11-03

`/bridge?version=5` supports EA-name and UID lookup for PC/PS4/X1; SWITCH is UID-only. The locally authorized production-adapter contract check resolved name to UID, repeated by UID and normalized `/predator`. [Observed fields and contract evidence](validation/v0.2-provider-contract.md). Name is display metadata; the persisted UID/platform is used for subsequent refreshes. Missing rank/RP remains unknown. The response cannot reliably distinguish private, unranked and incomplete rank data, so the UI states that uncertainty.

Android debug reuses the existing private runtime credential supplier; release and iOS hosts still bind unconfigured providers. The map-only gateway authorization does not authorize ranked routes. A single app-process budget serializes all three endpoints with conservative one-second spacing; any 429 blocks every endpoint until its delay, and 403 latches access. The repositories retain their own normalized persisted retry metadata, restored before background requests. Profile refresh is five minutes, Predator at least thirty minutes, and forced taps respect thirty-second attempt cooldown/backoff. The existing unique WorkManager handles map and ranked work; widgets never create provider requests.

Player records and goals are separate from global map/Predator records. Only normalized allowlisted fields are stored. Android backup excludes private profile records as well as credentials; iOS source excludes the private ranked file from system backup. Widget projections contain no name, UID or auth. Disconnection erases the linked snapshot while retaining independent manual RP/goals and global cutoff data. Public redistribution permission and credential-isolating release topology remain **Blocked** under ADR0002.
