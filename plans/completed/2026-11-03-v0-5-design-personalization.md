# v0.5 — Design, Personalization & Reliability

Status: COMPLETE — authorized Android/shared intermediate milestone; iOS Implemented — Not Verified. Owner request: attached v0.5 four-design implementation brief, 2026-11-03.

## Goal and value
Deliver four usable original design systems (Carbon, Liquid, Editorial, Tactical), durable appearance/presets, customizable Today, native widget appearance and honest notification capability. Preserve all v0.1–v0.4 data and provenance.

## Source of truth and starting state
AGENTS.md; accepted ADR0001–0010; architecture/requirements; owner brief supersedes the formerly narrow v0.5 spec. Branch `feat/v0.5-polish`, clean at `06daf6e`. Existing Wizard androidApp/shared/iosApp preserved. Separate v0.4 worktree is untouched. v0.4 automatic capture remains unaccepted with production registry disabled. No commits, tags, merges, pushes, publication or deployment authorized.

## Scope and ownership
Shared presentation owns versioned preferences, explicit style tokens, Studio, Today and accessible variants; semantic repositories remain unchanged. Android AtomicFile and iOS protected Foundation adapters persist a separate cosmetic record. Glance/WidgetKit retain separate renderers and independent instance configuration. Existing App Group contains only sanitized appearance/widget data. No bundled fonts/art or new provider endpoints.

## Approach and ordered checklist
- [x] Inspect branch, modules, dependencies, contracts and v0.4 boundaries.
- [x] Shared appearance models, strict bounded codec, repository and migration/failure tests.
- [x] Four complete theme definitions and application-wide components/navigation.
- [x] Appearance Studio, preview/apply/reset and local preset management (focused Android interaction/recreation passed; visual recapture ongoing).
- [x] Today with per-module visibility/order/size and distinct style defaults (pure projections and native control test verified).
- [x] Native widget style/accent/density configuration and app-following projection.
- [x] OS accessibility/power adapters; notification capability and safe schedule policy.
- [x] Android/shared build, lint, tests, actual screenshots and responsive/accessibility inspection.
- [x] Documentation, acceptance report and machine evidence.

## Delegation
Independent widget implementation owns widget files and widget appearance contract; presentation implementer owns Studio/Today files against integrator-owned appearance contract. QA owns new tests/evidence tooling and emulator setup. Shared token/contract/App edits are serialized by integrator. Each delegate reports exact checks and boundaries; no Apple tooling.

## Risks and decisions
Liquid uses bounded translucent/gradient drawing, not unsupported native Liquid Glass or costly full-screen blur. OS power/reduced effects resolve to solid/static tokens. Unknown/corrupt future appearance is retained read-only with visible recovery. Cosmetic writes never touch history/matches/credentials. Public provider rights block actual favorite-map delivery unless a validated authorized schedule exists; provide a clear gated flow rather than speculative alerts.

## Validation
Discover Gradle tasks, run assembleDebug/assembleRelease, testAndroidHostTest, Android unit tasks, lintDebug/lintRelease and connectedDebugAndroidTest. Record NO-SOURCE/skips honestly. Capture actual Android four styles across Today, Maps, Ranked, Matches/detail/statistics and Studio plus small/wide/large text/widget states. No Apple compilation/tooling under ADR0007: iOS Implemented — Not Verified. Test restart/corrupt settings and instance isolation without private user data.

## Progress log
- 2026-11-03: contract/module audit complete. No adb device initially connected; existing SDK emulator availability to be inspected. No visual reference artifacts inspected or claimed.

- 2026-11-04:247shared tests:245passed,2opt-in skips,0failures/errors. Debug/test APKs compile and install with `-r`; versionCode5/0.5.0-dev and source-only iOS version5/0.5.
- Focused6Android tests passed after correcting viewport selectors and a stale reset expectation;4AtomicFile/preset tests plus Studio apply/presets and actual activity/dialog recreation.
- Visual iteration found/fixed dark ambient text, horizontally clipped primary navigation, small native widget placeholders, composited Liquid contrast and stock selected-chip roles. Final screenshots will replace initial failed evidence.
- Task discovery initially failed generated Compose Preview resource requirement; enabled unit-test Android resources without changing dependency pins. Compile defects corrected; logs retained under docs/validation/v0.5-logs.
- Initial stage: Android/shared final build/release/lint gate was running; connected/visual evidence was pending then. Superseded by the final verification below.

## Exit verification
All owner-required Android/shared implementation and scoped evidence are complete. Deferred Apple validation alone does not block intermediate completion; absent features or failed Android evidence do.

- 2026-11-04 continuation: final shared host XML248tests (246passed,2opt-in skips), debug/release/test APK and lint pass (0errors;39debug/41release warnings); app unit suite NO-SOURCE. Final43-case connected regression running after two reproduced harness defects were corrected without dropping assertions.
- All81app screenshots reviewed and SHA-bound. Final120native renders across150×150/250×180/300×220, four designs and unavailable/DEMO/manual/synthetic-LIVE + spacious/details cases pass measured glyph/ellipsis checks and visual review. Height-aware compact layout and bounded header/footer repair resolved clipping. Native instance/editor/concurrent publication tests retained.
- Exact allowlisted Maps/Ranked widget routes implemented for Android and SwiftUI/WidgetKit source; Android launch, repeated-intent and recreation test passed. No URLs carry user data.
- Actual bound TalkBack focus smoke across4styles, hardware TAB/ENTER/BACK navigation and2cold process launches passed, with source-specific limitations in report. No Apple tooling. Source/APK known-value scan found0hits/new permissions/catalog change; production autoacceptance policy unchanged.

Final verification:248shared cases (246passed,2live skips),43connected cases (42passed,1live skip),0failures/errors, debug/release/test builds, lint0errors (39debug/41release warnings),201inspected captures. Exact commands and limitations are in docs/validation/v0.5-report.md. Documentation/diff gates accompany the final machine evidence. No Apple tooling or commit/tag/merge/push/publication/deployment.
