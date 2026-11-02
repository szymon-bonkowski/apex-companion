---
name: screenshot-import
description: "Research and implement opt-in Apex Companion Android+iOS on-device OCR and manually confirmed match records only after the v0.4 privacy and quality gate."
---

# Screenshot Import

## Read first

`docs/OCR_IMPORT.md`, `docs/specs/v0-4-match-import.md`, `docs/LEGAL_ASSETS.md`, `docs/TEST_STRATEGY.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Verify v0.4 approval, OCR provider license and local on-device availability per Android/iOS target.
2. Use Android Photo Picker and iOS PhotosPicker with minimum permissions; no automatic screenshot upload.
3. Build shared parser with confidence and unknown fields; preserve manual entry fallback.
4. Require user review/confirmation before persisting MatchRecord; clear draft on cancel.
5. Test duplicate, bad OCR, different locale, screenshot rights, privacy and platform quality metrics.

## Completion checks

- No auto-commit or image upload.
- Platform pickers/permissions audited.
- Measured per-field accuracy documented.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
