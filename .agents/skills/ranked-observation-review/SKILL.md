---
name: ranked-observation-review
description: "Review Apex Companion RP goal arithmetic, observation compatibility, split changes, manual session semantics and ranking insights without inventing Apex match results."
---

# Ranked Observation Review

## Read first

`docs/RANKED_TRACKING.md`, `docs/DATA_MODEL.md`, `docs/specs/v0-3-observations.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Specify input identity/mode/season/split, time order, sources and unknown conditions.
2. Implement same-context interval delta only; never infer match counts from polling.
3. Verify RP goal integer rounding, past deadline, reached goal and missing target cases in KMP common tests.
4. Handle season/split reset, duplicate observations, gaps, unchanged RP and manual overrides.
5. Review UI/state on both Android and iOS and update evidence.

## Completion checks

- No synthetic matches.
- Unknown is not numeric zero.
- Shared logic tested with reference fixtures.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
