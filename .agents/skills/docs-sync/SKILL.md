---
name: docs-sync
description: "Keep Apex Companion accepted KMP architecture, requirements, version specs, Android+iOS evidence, ADRs, local agent skills and active ExecPlans consistent after changes."
---

# Docs and Plan Sync

## Read first

`docs/INDEX.md`, `docs/ROADMAP.md`, `docs/PLANS.md`, `docs/ADR.md`, `docs/KMP_SETUP.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. List actual code changes and per-platform tests, compare with requirements and accepted ADRs.
2. Update delivery/feature statuses and open questions without silently reopening packs/heirlooms.
3. Record Android and iOS results/blocked states in active ExecPlan; only move complete when all exits verified.
4. Run `python3 tools/check_docs.py`, check relative links, skill metadata, old names and stale Android-only wording.
5. Prepare concise next-agent handoff with exact known blockers.

## Completion checks

- No README promise of an unbuilt app.
- All references resolve and skills metadata valid.
- KMP targets remain explicit.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
