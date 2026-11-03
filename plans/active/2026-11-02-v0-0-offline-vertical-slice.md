# ExecPlan: v0.0 Android + iOS offline vertical slice

**Status:** NOT STARTED. **Opened:** 2026-11-02. **Owner:** KMP implementing agent/integrator. **Source:** [approved v0.0 spec](../../docs/specs/v0-0-offline-vertical-slice.md). Documentation was revised for the Apex Companion rename, removal of pack/heirloom work and KMP Android+iOS commitment. This is the historical v0.0 plan; the later v0.1 implementation adds Maps without claiming the missing v0.0 goals/settings/backup delivered.

## Goal

Demonstrate a shared Compose Multiplatform app on Android and iOS with an offline manual Ranked RP calculator, visibly DEMO map schedule, durable user preferences, versioned export/import, and native Map + Ranked Goal home-screen widgets per platform. Build on top of **KMP Wizard output**, preserving its `androidApp`, `iosApp` and `shared` modules.

## Starting state and assumptions

This ZIP contains **no KMP Wizard project or built app**. Maintainer will create it and overlay these docs. Toolchain, OS, deployment target, bundle identifiers and Mac access must be inspected and recorded during implementation, not guessed.

## Out of scope

Provider/network credentials, live map, linked profiles, actual matches, OCR, store/squad, and all pack/heirloom tracker code. Avoid third-party game artwork.

## Ordered milestones

- [ ] M1: Inspect generated KMP project and record actual module names, Android/iOS IDs, Gradle and Xcode versions. Run docs checker.
- [ ] M2: Verify baseline shared/KMP tests and Android build; on Mac, verify generated iOS app in simulator before expanding code.
- [ ] M3: Implement shared state models, DEMO schedule, goal math and deterministic common tests.
- [ ] M4: Select and implement compatible cross-platform persistence adapters, widget snapshot projection and atomic backup contracts.
- [ ] M5: Implement original Compose shared screens Today/Maps/Ranked/Settings/About, platform file pickers, error states and theme.
- [ ] M6: Implement native Android Glance map and goal widgets and verify launcher/resize/process-restart behavior.
- [ ] M7: Create/sign iOS WidgetKit extension in Xcode, App Group snapshot bridge, map and goal widget; verify family/timeline and app termination.
- [ ] M8: Verify export->delete->import on both platforms, access, dark/light and time-zone edge cases; run relevant test/build tasks.
- [ ] M9: Log actual evidence in plan, diff review and update documentation; move to completed only after all A0.1-A0.10 are verified.

## Validation commands and platform caveat

`python3 tools/check_docs.py` before/after. After Wizard creation, discover actual Gradle tasks; expected Android build `./gradlew :androidApp:assembleDebug`. Run available shared tests, Android instrumentation and on **macOS with Xcode** iOS shared tests, iOS app/widget extension builds and simulator install. Commands and results must be recorded exactly when run.

**Owner policy update (ADR 0007):** implement feasible iOS source, mark it Implemented — Not Verified and defer all Apple tools/build/tests to final stabilization. Deferred iOS verification alone does not block intermediate completion.

## Risks

- Wizard versions may change; preserve actual module graph and platform compile support.
- WidgetKit extension/App Group entitlements require Xcode/macOS/Apple signing setup; implement as genuine SwiftUI extension, not simulated Compose widget.
- Background refresh is OS-limited; show timestamps and freshness, no seconds-accurate promise.
- Local storage API availability must be verified across Kotlin/Native and Android; test migrations on both.

## Progress log

- 2026-11-02: Documentation adapted to Apex Companion and Android+iOS KMP. No application builds or device tests performed.

## Decision log

- Product owner committed Android+iOS via KMP Wizard and explicitly paused packs/heirloom features.
- Offline manual Ranked + DEMO maps replace the removed pack-counter v0.0 work.

## Exit verification

Pending. All A0.1-A0.10 from v0.0 spec require actual command/device evidence. iOS launch/widget checks are explicitly deferred to final stabilization, never inferred from Android success. Remaining Android/shared v0.0 features are still planned.

2026-11-03 scope update: v0.2 implements the previously missing manual/linked RP goal calculator and minimum source/date/privacy settings, with Android/shared evidence in [v0.2 report](../../docs/validation/v0.2-report.md). This does not complete the v0.0 plan: backup/import, Today/About, additional settings and broad platform polish remain unimplemented. Keep this plan active.
