---
name: release-qualification
description: "Verify Apex Companion Android signed APK/GitHub distribution and compliant iOS Xcode/TestFlight/App Store readiness, security, tests, attributions and truthful platform support."
---

# Mobile Release Qualification

## Read first

`docs/DELIVERY.md`, `docs/specs/v1-0-release.md`, `docs/SECURITY_PRIVACY.md`, `docs/LEGAL_ASSETS.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Inventory actual shared, Android and iOS code/test state; require real binaries and independent checks.
2. Verify Android APK signing, checksum, device widget evidence, release notes and version.
3. Verify iOS Xcode signing, app/extension/App Group, simulator/device, App Store/TestFlight plan and Apple account constraints.
4. Run secret scanning, source/asset rights and provider attribution audit for enabled online features.
5. Stop any planned auto-release or store upload until explicit maintainer approval; report platform readiness separately.

## Completion checks

- No unjustified public IPA install promise.
- No unauthorized live access.
- No claim of unrun iOS/Android builds.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
