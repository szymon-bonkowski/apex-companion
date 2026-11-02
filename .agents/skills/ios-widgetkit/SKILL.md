---
name: ios-widgetkit
description: "Configure and validate Apex Companion iOS WidgetKit extension, SwiftUI families, App Group entitlements and KMP snapshot bridge in an existing Wizard iosApp Xcode project."
---

# iOS WidgetKit Integration

## Milestone policy

ADR 0007 applies: during intermediate versions implement feasible iOS source but do not attempt iOS builds/tests/simulators or Apple tools. Mark it Implemented — Not Verified; defer the Apple verification steps below to final stabilization. Android/shared evidence is required now.

## Read first

`docs/IOS_WIDGETS.md`, `docs/WIDGETS.md`, `docs/KMP_SETUP.md`, `docs/TEST_STRATEGY.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Inspect actual iosApp Xcode project/schemes, Apple signing team and deployment target, rather than inventing paths.
2. Create WidgetKit Extension target through Xcode; give app and extension matching App Group capabilities.
3. Implement robust Swift read of sanitized versioned snapshot and safe invalid/empty/expired fallback.
4. Implement map and Ranked goal families, configure timeline and deep links with no per-widget network requests.
5. Build/install app+extension on macOS simulator/device; test app termination, time rollover, VoiceOver and reload throttling.
6. If no Mac/Xcode, supply implementation plan and mark widget validation BLOCKED.

## Completion checks

- App Group data visible in extension with correct signature.
- Source state and validity preserved in widgets.
- Xcode compilation evidence recorded.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
