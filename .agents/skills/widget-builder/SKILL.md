---
name: widget-builder
description: "Build or verify Apex Companion native Android Glance and iOS WidgetKit map, Ranked goal or session widgets using shared KMP snapshot contracts. Use for widget sizing, storage, configuration and lifecycle."
---

# Native Widget Builder

## Milestone policy

ADR 0007 applies: during intermediate versions implement feasible iOS source but do not attempt iOS builds/tests/simulators or Apple tools. Mark it Implemented — Not Verified; defer the Apple verification steps below to final stabilization. Android/shared evidence is required now.

## Read first

`docs/WIDGETS.md`, `docs/IOS_WIDGETS.md`, `docs/DATA_MODEL.md`, `docs/DESIGN_SYSTEM.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Select widget type and derive one sanitized versioned KMP WidgetSnapshot, including source freshness and UTC validity.
2. Android: implement Glance with persist-before-update and source cache; test launcher resize and callbacks.
3. iOS: create SwiftUI WidgetKit extension in Xcode, configure App Group entitlements and validated snapshot reader/timeline.
4. Verify multiple independent instances, offline expired map, unknown next and no network call from widget render.
5. Record actual native test family/device, Xcode build/signature outcome or Mac blocker; no pack/heirloom widgets.

## Completion checks

- Native framework per platform, common semantics.
- No expired or DEMO map presented as live.
- No per-widget provider poll storm.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
