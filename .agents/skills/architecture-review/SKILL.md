---
name: architecture-review
description: "Review Apex Companion Kotlin Multiplatform architecture, ADR changes, cross-platform data contracts, WidgetKit/Glance boundaries, provider permissions and major implementation tradeoffs."
---

# Architecture Review

## Read first

`docs/ARCHITECTURE.md`, `docs/ADR.md`, `docs/REQUIREMENTS.md`, `docs/OPEN_QUESTIONS.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. State exact platform/user value, existing requirement, facts vs assumptions and compatibility risks.
2. Consider one practical alternative and maintenance/testing/cost tradeoffs.
3. Define minimal KMP/interface/schema change and falsifiable proof on Android and iOS.
4. Update ADR, architecture, spec, acceptance and plan coherently after owner decision.
5. Escalate upstream permission, cost, store distribution and dormant packs/heirloom scope decisions.

## Completion checks

- No implicit platform downgrade or undocumented schema migration.
- Accepted ADR never falsely asserts implemented runtime.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
