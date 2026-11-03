# v0.3 import validation follow-up

Status: COMPLETE — 2026-11-03.

## Goal and value
Reject contradictory provider-season/context relationships, and make repeated backup restore semantics truthful without changing the version1 wire format or expanding v0.3.

## Source of truth and starting state
Owner's narrowly scoped follow-up; AGENTS.md; ADR0002/0003/0007/0008; v0.3 specification. Clean feat/v0.3-ranked-observations at51c3708. Reviewed implement-spec, ranked-observation-review and multiplatform-storage procedures. No Apple tools, Git publication or v0.4 work.

## Scope and ownership
Single integrator; shared history model/codec/repository, import warning copy, deterministic common tests, relevant policy docs and validation report only. No native adapter, dependency, permissions, provider or UI layout changes.

## Approach and decisions
Provider-season evidence must match the declared confirmed season; observations and sessions referencing a user-confirmed context must match its identity/source/mode and historical bounds. Batch IDs remain version1 advisory markers, not content fingerprints or a shortcut around validation/merge. Rename misleading preview flag to batchPreviouslyImported; explicitly warn that confirmed repeated restores can add valid records and restore settings. Stable-ID conflicts still reject atomically; legitimate explicit restore of backed-up goals/manual values remains allowed.

## Risks
Avoid rejecting legitimate upstream-only context IDs, completed-session replay or independent manual history. Existing stored contradictory records fail closed without overwriting bytes. No migration or hashes needed for advisory batch IDs.

## Ordered checklist
- [x] Inspect actual branch, working tree, code and accepted policies.
- [x] Implement minimal validation and explicit repeat-restore semantics.
- [x] Add deterministic contradiction/replay/collision/atomicity/settings regressions.
- [x] Run complete existing shared/Android build/test/lint/device suite; fix failures.
- [x] Update report/policy with exact diff/results and complete exit audit.

## Validation
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:lintRelease :androidApp:connectedDebugAndroidTest
python3 tools/check_docs.py; git diff --check. Report opt-in skips and NO-SOURCE separately. iOS remains Implemented — Not Verified; no Apple tool invocation.

## Progress log
2026-11-03: confirmed missing model invariant and misleading alreadyImported flag based solely on batch marker. Selected advisory-marker semantics with normal revalidation/merge and explicit confirmation; no wire-format changes.

## Exit verification
All criteria verified in the v0.3 report follow-up section and evidence JSON followUp object. Ready for review; Apple validation stays deferred under ADR0007.

2026-11-03 verification: 9 new common tests passed (179total177pass2skip). Two full device runs exposed the same preexisting RankedScreenTest cached-node StaleObjectException after setContent replacement. Idle/cache synchronization alone was insufficient; bounded fresh clickable-chip queries (only stale-node retries, assertions unchanged) passed the isolated test in8s. Final full suite is running. No further production changes.

2026-11-03 final: nine new common tests pass; full seven-task gate BUILD SUCCESSFUL in328s/176tasks, host177pass2skip, Android19pass1skip, app unit NO-SOURCE, lint0errors29/31warnings. Two preexisting stale-node test failures fixed with bounded fresh navigation-node queries, assertions unchanged; focused rerun passed8s before the final full gate. No production changes after the successful gate. Docs/diff checks and actual credential/APK gate passed. No Apple/commit/tag/push.
