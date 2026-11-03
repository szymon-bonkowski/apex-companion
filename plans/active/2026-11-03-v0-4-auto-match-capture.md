# v0.4 Automatic Match Tracking and Match History

Status: IN PROGRESS. No commit, tag, push, release or deployment authorized.

## Goal and value
Implement a durable mobile match journal and secure offline PC-to-mobile collection pipeline, with a consent-based Windows screen capture companion. Automatic results retain machine provenance; uncertain evidence stays provisional. Manual and screenshot fallbacks remain independent.

## Source of truth and starting state
Maintainer v0.4 brief supersedes manual-only scope through a new ADR. Read AGENTS/SUBAGENT, required product/spec/security/history documents and three local skills: implement-spec, multiplatform-storage, mobile-test-matrix. Branch feat/v0.4-auto-match-capture at 51c3708 (completed v0.3), with pre-existing uncommitted v0.3 import follow-up changes. Preserve those changes. Wizard modules and dependencies retained.

## Scope and ownership
Integrator owns shared match schema/repository/codec/analytics, ADRs and cross-component validation. Independent delegated Windows component and mobile integration work use the agreed wire contract. No provider activation, game internals, cloud, copyrighted assets or unrelated redesign. iOS source only under ADR0007; no Apple tools.

## Approach and decisions
Separate bounded versioned match store preserves ranked-history-v1 and its atomic settings transaction. Portable match backup excludes pairing secrets. One atomic commit per match batch; stable event IDs reject duplicates/conflicts. HTTPS with explicit out-of-band certificate pin and random bearer credential; opt-in listener, mobile pull on foreground. Durable companion records remain until acknowledged; no raw frames persist by default. Auto-acceptance remains disabled until representative held-out real-screen accuracy is demonstrated. Synthetic tests prove mechanics only.

## Risks
No Windows runtime and no rights-cleared real Apex fixtures initially available; live capture and OCR quality must remain unverified. Android emulator availability checked separately. Local TLS provisioning, parser drift, storage exhaustion and identity/conflict handling require fail-closed behavior. No Android result proves iOS.

## Ordered checklist
- [x] Inspect Git, baseline, local changes, required documents and skills.
- [x] Investigate capture/OCR/licensing and record ADR plus feasibility report.
- [x] Shared strict match model, codec, repository, aggregates and tests.
- [x] Windows consent capture, pipeline/state machine, durable queue and secure sync endpoint.
- [x] Android/iOS durable storage, pairing/sync, shared journal/detail/stats/manual UI.
- [x] Native screenshot fallback where feasible; precise blockers otherwise.
- [x] Backup preview/confirmation, deletion, conflict/replay and recovery tests.
- [x] Android/shared full builds/tests/lint/instrumentation and screenshots.
- [x] Privacy/license/artifact audit, docs, evidence and honest exit report.

- [ ] Representative/held-out real Apex layout quality and actual Windows capture-to-phone acceptance.
- [ ] Native mobile OCR predictions and platform quality gate (picker/manual draft is implemented).

## Validation
Discover actual Gradle tasks; run Android debug/release assemblies, host tests, app unit tests, debug/release lint and connected instrumentation if an emulator can be started. Portable companion tests and Linux mock HTTPS integration. Synthetic fixture metrics reported separately from unavailable real Apex accuracy. docs checker and git diff --check. No Apple builds or game compatibility claims.

## Progress log
2026-11-03: no connected adb devices initially. dotnet/python/adb available. Architecture and implementation work beginning; main automatic objective is not yet achieved.

## Exit verification
Remain active until mandatory implementation and evidence exist. Deferred iOS verification alone is permitted; missing automatic capture validation must be explicitly reported and cannot be called complete.

2026-11-03 implementation checkpoint: root added MatchModels/Codec/Repository and MatchJournalTest; initial shared regression passed, then expanded journal/strict-key host runs passed (2s/7s), with existing opt-in provider checks skipped. Separate machine original records preserve corrected data on replay, deletions retain tombstones and cursor, and match-only archives keep v0.3 settings/history untouched. Added ADR0009/0010 and revised spec/roadmap/product/data/privacy/test docs. Native adapters/UI/companion delegated with exclusive ownership after wire contract agreed.

2026-11-03 platform checkpoint: Windows source crosscompiled Release (0warnings/0errors), no Windows execution. Root review required documented HWND-specific WGC interop after picker selection, bounded reads and asynchronous shutdown; fixes crosscompiled. Android emulator API37 available. Native storage/Keystore tests initially passed; real TLS mock revealed Android17 ACCESS_LOCAL_NETWORK permission requirement, implemented explicit pairing permission. Photo Picker opens actual system UI; no native OCR engine or real corpus is provided. Common sync tests initially9pass. A concurrent/incremental Gradle output failure produced an empty shared compile jar; cause unproven, recovered by isolated rerun-tasks compile/bundle. All later Gradle gates serialized. Failed attempts remain in /tmp logs and are not counted as passes.

2026-11-03 verification checkpoint: 7 focused Android UI/native tests passed in188s; 1 additional actual Photo Picker selection test passed in9s after adapting to Android17 Collections/device albums and explicit Add confirmation. Real pinned HTTPS synthetic import, wrong token/pin rejection, replay, restart, AtomicFile and encrypted pairing executed. Shared full host checkpoint214total/212passed/2opt-in skips. Portable companion final39passed using self-contained linux-x64 (ordinary test aborted because host lacks ASP.NET framework runtime). Windows self-contained win-x64 source published; no Windows execution. Original fixture replay remeasured:10synthetic/4positive6negative/0FP0FN, exact17/17expected numeric fields;2.474s serial replay,60,203,008byte parent working set excludes OCR children. No real Apex accuracy claim.

First integrated full gate exposed legacy HistoryScreenTest assumptions about the first scroll container/checked selection after a second scrolling navigation row was introduced. Added distinct accessible navigation groups/destinations and updated that test selector to check selected/checked states without weakening assertions. First gate retained as failed; final rerun required. Initial attempt to replace the local mock found the prior process still listening after SIGTERM; stopped that task-owned synthetic process, launched final-source mock and refreshed protected fixture without printing secrets. No user history affected.

2026-11-03 final stabilization checkpoint: legacy navigation needed the actionable parent of the new description semantics; focused legacy run then passed backup/session checks but failed only a clipped sibling description beneath +150. Full-visible scrolling now retains its following description in view; assertions unchanged. Third integrated gate is running with the first legacy history case passing; the other2legacy cases failed and are being inspected. New native TLS/photo cases passed, remaining match UI/widget regressions are in progress. No failed gate is counted as a pass. Production code frozen. Final Linux mock SIGTERM/single-writer/credential-preservation smoke passed; Windows peridentity mutex source crosscompiled and local unsigned66MiB ZIP generated. These do not prove Windows runtime.

2026-11-03 final executed evidence: complete seven-task Android/shared gate BUILD SUCCESSFUL in573.539s (9m33s),176tasks. Shared214total/212pass/2opt-in skips; Android28total/27pass/1opt-in skip; app unit NO-SOURCE; lint0errors30debug32releasewarnings. Existing Maps/Profile/goals/history/sessions/widgets and all8new Android cases pass. Actual native Photo Picker selection/cancellation, pinned Linux HTTPS wrong-token/wrong-pin/identity/replay, native storage/Keystore and shared manual/correction/statistics/backup/delete/reimport controls passed. Actual app force-stop/relaunch retained synthetic match bytes and displayed record; original current match files restored and four synthetic screenshots visually inspected. No Apple tool or live provider check.

Final desktop review separated Start/resume local sync from explicit Pair new device rotation: existing protected token/pin survive PC/server restart. Portable suite now40pass,0fail/skips; updated Windows package crosscompiled, source bounds/error status/last-saved result and peridentity mutex implemented, runtime unverified. Final-source Linux server resumed the same token, then the native Android TLS/auth/replay case passed again via direct instrumentation (OK1test/6.142s), without re-pairing. Linux SIGTERM/duplicate-writer/credential-preservation smoke passed. Local unsigned Windows ZIP generated; no remote publication.

Final audit:0known generated pairing-token/private-key-block hits in nonignored repository files/APK entries/Windows package. Release DEX excludes private developer-key reader; no developer provider credential provisioned for exact-value scanning. Actual command outputs, source/artifact hashes, synthetic OCR metrics and failed-attempt histories are in docs/validation/v0.4-evidence.json and v0.4-logs. No failed attempt is counted as a pass. Four prior complete gates and focused UI/script failures are retained; selectors now resolve actionable controls, preserve explicit data assertions and account for duplicate section headings/scrolling/async dialogs. Docs and whitespace checks pass.

## Current exit decision
Android/shared journal and accepted-record sync checks are completed with evidence. Main automatic tracking objective remains NOT ACHIEVED: no real Windows/Apex execution, no real/held-out Apex corpus, no approved production layout and autoacceptance disabled. Mobile OCR engine remains unimplemented; iOS source Implemented — Not Verified under ADR0007. Plan stays active and MUST NOT move to completed. Next bounded step: owner-permitted Windows ordinary-window capture, rights-cleared real corpus/quality measurements, then successive real-match PC-to-phone acceptance and Windows gaming resource measurements. No commit/tag/push/release/deploy authorized or performed.
