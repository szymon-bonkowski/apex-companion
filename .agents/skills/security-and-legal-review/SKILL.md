---
name: security-and-legal-review
description: "Audit Apex Companion Android/iOS provider key exposure, App Group and backup privacy, EA trademarks, original assets, API permissions and cross-platform distribution requirements."
---

# Security and Legal Review

## Read first

`docs/SECURITY_PRIVACY.md`, `docs/API_INTEGRATION.md`, `docs/LEGAL_ASSETS.md`, `docs/DELIVERY.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Inspect source and distributed Android/iOS app/extension artifacts for hidden keys and personal identifiers.
2. Check App Group snapshot contents and entitlements, iOS Info.plist, Android manifest and export privacy.
3. Re-check EA asset policy and provider public redistribution terms, including Android/iOS channels.
4. Review screenshot/OCR consent, logging, fallback and denial/offline behavior.
5. Record go/no-go with severity, evidence dates and human approval requirements.

## Completion checks

- No credential in distributable artifacts.
- Claims and rights are supportable.
- Platform privacy gaps listed.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
