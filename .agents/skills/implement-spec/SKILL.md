---
name: implement-spec
description: "Implement an approved Apex Companion Android+iOS KMP feature from a version spec. Use for shared Compose, domain, persistence, platform adapters, tests and documentation handoff."
---

# KMP Implement Spec

## Milestone policy

ADR 0007 applies: during intermediate versions implement feasible iOS source but do not attempt iOS builds/tests/simulators or Apple tools. Mark it Implemented — Not Verified; defer the Apple verification steps below to final stabilization. Android/shared evidence is required now.

## Read first

`AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/REQUIREMENTS.md`, the active version spec and ExecPlan. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Read exact scope/acceptance; inspect existing Wizard modules and tests before touching code.
2. Put cross-platform business state and Compose UI in shared, with minimal native Android/iOS bridge interfaces.
3. Implement one working end-to-end vertical slice including truthful provenance, offline fallback and persist-before-render semantics.
4. Do not implement paused packs/heirloom features or promote RP observation interval into a match.
5. Add and run focused common, Android and iOS tests as environment permits. Never invent Xcode success on Linux.
6. Update active plan, code/doc links and report blocked OS checks.

## Completion checks

- Accepted spec behaviors covered with observed evidence.
- Native widget/picker calls stay out of commonMain.
- All unsupported external capabilities are gated.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
