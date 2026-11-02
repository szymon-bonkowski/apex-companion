# Codex handoff — first implementation

Use the KMP Wizard project root as workspace. The starter ZIP provides **documentation only**, not an Android or iOS binary. The owner will generate the project with **Android + iOS, shared Compose UI** and copy these files into it. If Wizard-generated `androidApp/`, `iosApp/`, `shared/`, Gradle and Xcode files exist, **do not recreate, replace or move them**.

## Ready-to-paste Codex instruction

> Read `AGENTS.md`, `docs/KMP_SETUP.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`, `docs/specs/v0-0-offline-vertical-slice.md` and `plans/active/2026-11-02-v0-0-offline-vertical-slice.md`. Inspect the actual KMP Wizard module graph. Implement the v0.0 offline **Android and iOS** app with shared Compose UI and domain logic; manual Ranked goal, clearly DEMO map timeline, durable local settings/backup and native map + goal widgets (Android Glance, iOS WidgetKit). Do not implement a pack counter or heirloom features. Keep Apple extension/signing work native, preserve the generated project and do not embed any provider key or copyrighted EA art. Run actual available tests, report Android/shared/iOS evidence separately, and mark any iOS checks blocked if macOS/Xcode is unavailable. Follow project-local skills and update the active ExecPlan. Do not push, publish or change external services without permission.

No special `/goal` message required. The owner should not need to implement code by hand; if Xcode, signing or provider permission is unavailable, proceed with independent safe work and report the precise blocker.

## First tasks

1. Verify checker and existing Wizard build tree, record current toolchain.
2. Implement pure shared goal/math + demo schedule and unit tests.
3. Add durable cross-platform data model, backup and versioned widget projection.
4. Implement shared Compose screens with correct data provenance.
5. Implement Android Glance and Xcode WidgetKit widget families.
6. Verify each platform, report missing environment and update plan/requirements evidence.
