---
name: design-qa
description: "Review Apex Companion shared Compose screens and original Android Glance/iOS WidgetKit widget visuals for clarity, accessibility, distinctiveness and source-state accuracy."
---

# Cross-platform Design QA

## Read first

`docs/DESIGN_SYSTEM.md`, `docs/WIDGETS.md`, `docs/LEGAL_ASSETS.md`, `docs/TEST_STRATEGY.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Reject copied Wingman compositions, unlicensed EA assets and misleading official branding.
2. Review shared Compose pages, native Android launcher widgets and iOS WidgetKit family previews at actual sizes.
3. Test 200% or large dynamic type, VoiceOver/TalkBack, contrast, dark/light, reduced motion and status text.
4. Prioritize DEMO/STALE/UNAVAILABLE source info and map/RP value over decorative labels.
5. Log reproducible platform-specific defects, screenshots and validation evidence.

## Completion checks

- Original assets documented.
- Android+iOS usability evidence or explicit blockers.
- No false LIVE label.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
