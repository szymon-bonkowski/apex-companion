---
name: multiplatform-storage
description: "Design, implement and audit Apex Companion cross-platform durable persistence, atomic backup, KMP repository adapters and native widget projection stores for Android and iOS."
---

# KMP Storage and Backup

## Read first

`docs/DATA_MODEL.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY_PRIVACY.md`, `docs/IOS_WIDGETS.md`. Paths are relative to the repository root and `AGENTS.md` remains authoritative.

## Procedure

1. Audit actual Wizard Kotlin/AGP versions and the proposed storage library support for Android/iOS.
2. Keep common domain unaware of platform storage types; implement tested native adapters and migrations.
3. Write versioned allowlisted JSON backup with bounded validation, atomic staging and idempotent import.
4. Atomically publish small safe widget snapshots to Android cache and iOS App Group; clear on delete.
5. Test restart, concurrency, duplicate import, schema mismatch and platform serialization parity, reporting Mac gaps.

## Completion checks

- No secret or device widget ID in exports.
- Crash-safe import and snapshot publication.
- Both platform storage outcomes explicit.

## Report

Report changed files, tests actually executed and their outcomes, Android/iOS evidence separately, blocked checks, architecture conflicts and the next bounded step. Never claim an unrun device test or permission check passed.
