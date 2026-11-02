---
name: api-contract-audit
description: "Validate Apex Legends Status endpoints, payloads, terms, public redistribution approval, rate budget and secret handling for Apex Companion Android+iOS online features."
---

# API Contract Audit

## Read first

`docs/API_INTEGRATION.md`, `docs/SOURCES.md`, `docs/OPEN_QUESTIONS.md`, `docs/LEGAL_ASSETS.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Recheck current provider docs and dates; separate documented claims, local responses and distribution authorization.
2. Use any supplied developer key only if authorized and local; send header auth, redact logs and private identifiers.
3. Create synthetic/sanitized contract fixtures for valid, partial, unknown modes, 403, 429, timeout and schema drift.
4. Validate UTC schedule boundaries, nullable RP and platform IDs; normalize to KMP envelopes independent of HTTP JSON.
5. Document whether permissions cover **both** public Android APK and iOS app; never embed a shared key or deploy unbounded proxy.

## Completion checks

- No key or unlicensed material in fixtures.
- Public online gate explicitly passed with evidence or remains disabled.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
