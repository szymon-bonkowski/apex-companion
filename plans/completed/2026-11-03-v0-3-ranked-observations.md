# v0.3 Ranked observations, sessions and trends

Status: COMPLETE — 2026-11-03; ready for maintainer review. iOS Implemented — Not Verified under ADR0007.

## Goal and value
Implement the requested v0.3 end to end: honest context-bound RP evidence, durable explicit sessions, shared trend/history UI and user-controlled portable backup/restore. No inferred matches.

## Source of truth and starting state
Owner implementation brief; AGENTS.md; accepted ADR0002/0003/0007; v0.3 spec and data/privacy contracts. Clean branch at c736c9efad76416db6bc7b82ff19c88820c1f612 (v0.2), new local feat/v0.3-ranked-observations. Existing Wizard modules/dependencies retained. Read the three requested local skills. No commit/push/publication authorized.

## Scope and ownership
Integrator owns shared schema, repository/provider integration, cross-platform contracts and final checks. Independent UI/platform work may be delegated only after contracts are fixed, with nonoverlapping files. Android native storage/pickers/instrumentation and feasible iOS Foundation/picker bridges required. iOS Implemented — Not Verified, no Apple tooling. No new widget/backend/API activation/OCR.

## Approach and decisions
Separate versioned private history record. Atomic whole-record commits own observation/session/context/import-batch and portable user settings, so restore has one commit point. Migrate existing v0.2 goals/manual settings non-destructively once; current profile/global caches remain separate. Explicit user context is source/identity scoped, never provider-verified. Unknown observations retained; context changes start baselines. Suspicious discontinuities quarantine and invalidate context; ordinary negative values remain valid. Histories never auto-delete at capacity. Corrupt/future records fail closed until explicit user deletion. One active session globally is the conservative overlap policy. Import never links a profile and rejects a foreign linked identity; preview/confirmation precedes commit.

## Risks
Cross-file legacy settings ownership, provider refresh vs crash boundary, context unknown upstream, malformed imports, Android document provider cancellation, iOS bridge API export unverified. Preserve Maps/Predator/goal/widget behavior. No network calls from history rendering/start/stop; existing refresh budget unchanged.

## Ordered checklist
- [x] Inspect clean baseline, read required documents/skills and existing source.
- [x] Shared normalized models, deterministic compatibility/session logic, bounded strict codecs.
- [x] Durable history repository, capture hook, legacy settings compatibility, atomic import/export.
- [x] Shared history/trend/sessions/privacy/backup UI.
- [x] Android native storage/pickers and source-only iOS bridges.
- [x] Deterministic shared tests, instrumented storage/import/session/UI tests.
- [x] Actual Android cold restart, picker and failure recovery evidence.
- [x] Full Android/shared regression, lint, binary/privacy audit and docs validator.
- [x] Documentation/report/evidence and exit audit; move plan only with evidence.

## Validation
Required Gradle gate: :androidApp:assembleDebug :androidApp:assembleRelease :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:lintRelease :androidApp:connectedDebugAndroidTest. Discover focused checks. NO-SOURCE is not a test pass; live tests remain opt-in. Real emulator UI, force-stop/restart and interrupted AtomicFile/import checks. Synthetic identities only. python3 tools/check_docs.py. No Apple tools.

## Progress log
2026-11-03: baseline and rules inspected; implementation begun. Provider parser deliberately supplies no split. Existing v0.2 records are independent from global caches. No private inputs read.

## Exit verification
All mandatory v0.3 criteria have evidence in [the report](../../docs/validation/v0.3-report.md) and [evidence JSON](../../docs/validation/v0.3-evidence.json). Android/shared intermediate exit achieved; only explicitly deferred Apple checks and unchanged external public provider/release gates remain. The separate historical v0.0 plan stays active for its unrelated gaps.

2026-11-03 intermediate evidence: initial :androidApp:assembleDebug :shared:testAndroidHostTest passed (4s). Expanded host audit passed160 tests+2 opt-in skips (5s); at that checkpoint final additions awaited the integrated gate. Four actual Android native persistence tests passed; actual system DocumentsUI export/import/cancellation passed (18s focused rerun). Initial picker selector StaleObjectException fixed as test-only timing. At that checkpoint shared UI tests were in progress; selector/composition races were being corrected and incomplete attempts were not counted. Source review fixed aggregate-session false quarantine, session/context ownership, no-sample confirmation expiry, deterministic late samples, provider source persistence after restart, background refresh preserving manual sessions, older active-backup replay and backup chronology. Decisions documented in ADR0008. No Apple tools, credentials/public activation or remote Git actions.

2026-11-03 integrated verification: full required seven-task Gradle command passed in6m22s (176 tasks). All three new actual UI cases passed (opt-in/manual/context/+150 trend; active recreate and MANUAL/PARTIAL; preview/cancel/confirm/export/delete/reimport twice) together with native picker/persistence and existing Maps/Profile/goals/widgets. Captures saved and visually inspected. A final repeated full gate was then queued after a focused missing-RP season-evidence guard; its targeted host suite already passed170 total (168 pass,2 opt-in skips). Production source was frozen for that gate, whose result is recorded below. No Apple tools or remote Git operations.

2026-11-03 final exit: required seven-task gate BUILD SUCCESSFUL in5m47s,176tasks; host168passed+2 opt-in skips, Android19passed+1 opt-in skip, app unit NO-SOURCE. Lint0errors/29debug31releasewarnings. All8 new native/UI and76 new host history checks passed. Final debug APK installed and actual cold-process/recovery/partial-stop check passed again; protected original records restored byte-for-byte. Actual credential absent from all APK entries/repository files; release DEX excludes debug reader. Synthetic captures inspected. Docs validator and diff whitespace checks passed. No Apple tool, public activation, commit/push/tag/merge/release/deployment. Plan completed only after actual evidence. Next step: maintainer review; Apple validation remains at final stabilization.
