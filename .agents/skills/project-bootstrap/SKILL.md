---
name: project-bootstrap
description: "Inspect and verify a Kotlin Multiplatform Wizard Android+iOS shared Compose UI project for Apex Companion. Use for initial toolchain, modules and first builds, never to recreate existing Wizard scaffolding."
---

# KMP Project Bootstrap

## Milestone policy

ADR 0007 applies: during intermediate versions implement feasible iOS source but do not attempt iOS builds/tests/simulators or Apple tools. Mark it Implemented — Not Verified; defer the Apple verification steps below to final stabilization. Android/shared evidence is required now.

## Read first

`docs/KMP_SETUP.md`, `docs/ARCHITECTURE.md`, `docs/DELIVERY.md`, `docs/specs/v0-0-offline-vertical-slice.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Inspect actual `settings.gradle.kts`, Gradle catalog, Android SDK and `iosApp` Xcode project; preserve them, never regenerate.
2. Record Android `applicationId`, iOS bundle ID, deployment targets, real module layout and version compatibility; propose no speculative pins.
3. Verify basic Android build with `:androidApp:assembleDebug`; discover exact shared tests from `./gradlew tasks --all`.
4. On a Mac, build and run iOS app using generated Xcode project, record real scheme; if no Mac, label iOS blocked.
5. Add no packs/heirloom code, protected artwork, provider keys or unapproved network access.
6. Record results in the v0.0 ExecPlan, including needed WidgetKit extension and App Group setup.

## Completion checks

- Wizard-generated modules are intact and correctly described.
- Android build result and iOS build result/blocked status are explicit.
- No fabricated APK/iOS release or untested claim.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
