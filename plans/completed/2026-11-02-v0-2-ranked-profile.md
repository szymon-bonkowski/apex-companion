# v0.2 Ranked & Player Profile ExecPlan

Status: COMPLETE for authorized local developer use — 2026-11-03

## Goal and value
Implement profile linking, current ranked state, offline manual goals, Predator cutoff and native cached widgets without weakening v0.1 maps or public access gates.

## Source of truth and starting state
Owner goal attachment, AGENTS.md, architecture/ADRs, v0.2 spec, v0.1 validation report. Actual Wizard androidApp/shared/iosApp modules exist. v0.1 uses AtomicFile/Foundation normalized JSON, Ktor and private debug provisioning. Goals/settings/backup absent. Working tree initially clean. Existing project and dependency pins retained.

## Scope and ownership
Integrator: shared goal/domain/repository/storage, Android runtime/scheduler integration, final tests/docs. API agent: documented bridge/predator contracts, synthetic fixtures and transport. UI agent: shared Ranked/Profile/settings navigation and editor. Widget agent: shared sanitized projection and Android/iOS native widget source. No history/sessions/OCR, public activation, deployment or remote writes.

## Decisions and conflicts
The earlier v0.2 spec says public access is a dependency; the explicit owner objective authorizes existing private developer use. Keep public release fail-closed under ADR0002; no architectural conflict. Official season thresholds are unavailable unless verified: numeric targets work and rank presets remain unavailable rather than invented. iOS implementation source only under ADR0007, no Apple tools/tests.

## Approach and risks
Use separate versioned ranked/global records using the existing atomic storage adapter, preserving map cache path/schema. One process-owned network budget across endpoints, endpoint/identity dedup and persisted backoff. Keep goals/manual values independent of linked identity; disconnect erases private snapshots. Persist before exposing successful mutations; safe fixed errors only. No raw provider JSON storage.

## Ordered checklist
- [x] Read objective, required guidance, actual modules and v0.1 evidence.
- [x] Verify provider documentation/schema/platform lookup and permissions.
- [x] Implement shared models, goals, repository/persistence and deterministic tests.
- [x] Implement private developer provider adapters and shared request budget.
- [x] Implement shared Ranked/Profile/settings UI preserving Maps.
- [x] Implement Android native widgets and feasible iOS source bridge/widgets.
- [x] Run shared/Android host, lint/build/instrumentation and opt-in contract checks.
- [x] Inspect privacy/release gate, document evidence/gaps, complete applicable criteria.

## Validation
Run actual existing Linux-compatible tasks: shared:testAndroidHostTest, androidApp:testDebugUnitTest, assembleDebug/Release, lintDebug/Release, connectedDebugAndroidTest. Use emulator if present. Synthetic transport/domain/widget tests, process restart/corruption and map regressions. Private opt-in live contracts through owner-only file; no identifiers/credentials in report. No iOS compilation/testing.

## Progress log
2026-11-02: inspected pristine working tree and v0.1 source/report. Selected implement-spec, api-contract-audit, widget-builder skills. Implementation begun with bounded parallel work.

## Exit verification
All milestone-applicable criteria are evidenced in [the implementation report](../../docs/validation/v0.2-report.md) and [evidence JSON](../../docs/validation/v0.2-evidence.json). Final full Android/shared/build/lint/device gate passed: 92 host checks plus 2 opt-in skips; 11 Android checks plus 1 opt-in skip; app unit task NO-SOURCE. Separate authorized host and Android real-provider checks passed. Source-only iOS remains Implemented — Not Verified under ADR0007. Public activation remains externally gated under ADR0002, with no release/deployment. Broader v0.0 backup/full settings remain separate gaps.

2026-11-03 final verification: production bridge v5 name→UID/direct UID and Predator contracts passed through private external inputs. Integrated shared/current-state repository, local goals/timezone math, typed provider transport and cross-endpoint budget, atomic Android/Foundation source adapters, Compose UI and three native widgets. Fixed both-endpoint credential-reset persistence, partial-cutoff retention/capped retries, corrupt boolean records and completed-goal daily semantics. Existing Maps preserved; actual normalized v0.1 emulator cache was privately held during deterministic test isolation and restored byte-for-byte. Android native fixtures and compact/wide captures inspected. Actual manual editor target/date and cold-process restoration passed. Real Android UI RP/source, actual offline stale retention, goal preservation, widget privacy and disconnection passed (43.619s); Wi-Fi/data restored and private test input/profile cleared. Final full Gradle gate BUILD SUCCESSFUL in36s,176 tasks; lint0errors/29debug and31release warnings. Actual credential absent in all debug/release/test APK entries and346 repository files; release lacks debug reader. No iOS tool/build/test, public activation, commit/push or deployment. Completed report identifies all remaining external gates and v0.0/v0.3 scope.
