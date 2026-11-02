---
name: mobile-test-matrix
description: "Execute evidence-based Apex Companion Android+iOS UI, widget, shared logic, persistence and background tests. Use for regression, acceptance or release QA on emulator, device and simulator."
---

# Mobile Test Matrix

## Milestone policy

ADR 0007 applies: during intermediate versions implement feasible iOS source but do not attempt iOS builds/tests/simulators or Apple tools. Mark it Implemented — Not Verified; defer the Apple verification steps below to final stabilization. Android/shared evidence is required now.

## Read first

`docs/TEST_STRATEGY.md`, `docs/WIDGETS.md`, `docs/IOS_WIDGETS.md`, `docs/DELIVERY.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Inventory Android devices/launchers and Apple Xcode/simulators; do not infer access to either.
2. Run shared deterministic tests, Gradle Android build/lint, then actual launcher/Glance tests where available.
3. Run Xcode iOS build/simulator, WidgetKit families/App Group tests and VoiceOver checks on a Mac.
4. Exercise process death, reboots, timezone/DST, offline, stale, schema errors, import and enlarged type.
5. Document precise environment, commands, screenshots/repro steps and separate blocked OS checks.

## Completion checks

- Each platform verified only with actual evidence.
- No false iOS parity from Android success.
- No withheld critical stale-state defects.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
