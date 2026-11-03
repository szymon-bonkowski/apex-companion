# Cross-platform test strategy and evidence

Actual v0.1 evidence is recorded in [the implementation report](validation/v0.1-report.md). Agents must report actual commands and results separately for Android, KMP shared and iOS.

## Layers

1. `commonTest` pure calculations: goal RP, UTC map segment resolution, provenance, stale timing, compatible RP deltas, JSON schema and widget projection.
2. Storage adapter/migration tests for **both** iOS and Android, including restart, duplicate imports and malformed payloads.
3. Network contract fixtures: sanitized success/partial/unknown mode, 403/429/500, timeout, schema drift; no private upstream key in CI.
4. Compose Multiplatform UI semantics: manual/demo/stale/error, font scaling and accessibility.
5. Android instrumentation/launcher: Glance resizing, two instances, foreground update, reboot/Doze, Samsung One UI and AOSP if available.
6. iOS Xcode/simulator/device: SwiftUI WidgetKit families, shared App Group entitlement, process death, timeline rollover, VoiceOver and real installation.
7. Live authorized smoke test only when approved and separately enabled, not required for public CI.

## Concrete acceptance cases

| ID | Scenario | Expected |
|---|---|---|
| T-001 | Manual goal RP 13,460 -> 16,000 with 22 days | 2,540 RP left, **116/day**; common Kotlin tests |
| T-002 | Goal reached or 0 days | No negative target/division by zero; reached/expired state |
| T-003 | Map DEMO fixture and first app launch | DEMO visible on both platforms and widgets |
| T-004 | UTC map segment expires offline | No expired segment falsely rendered as current LIVE |
| T-005 | Two observations 13,000 and 13,150 same split | +150 interval, matches remain UNKNOWN |
| T-006 | Same RP twice or unknown split | No invented match count; valid 0 only for compatible delta |
| T-007 | Split reset/out-of-order RP snapshot | New baseline or invalid interval, no fake losses |
| T-008 | Provider 403/429/schema drift | Typed failure, backoff; stale/unknown values not fabricated |
| T-009 | Two widget instances, different settings | Independent style/selection, shared source cache |
| T-010 | Map timeline timezone/DST change | UTC ordering remains stable; local formatting correct |
| T-011 | iOS app writes projection then terminates | WidgetKit extension can read versioned App Group snapshot |
| T-012 | iOS extension missing entitlement | Clear failure/UNAVAILABLE, no private DB assumption |
| T-013 | Android widget app process killed, restart | Persisted projection survives; widget opens correct screen |
| T-014 | Export -> delete -> import twice | User data restored exactly once; no secrets or widget IDs |
| T-015 | Large/malformed/unsupported-version backup | Bounded safe failure, no partial writes |
| T-016 | Screenshot import canceled or misread | No MatchRecord until confirmation; draft discarded |
| T-017 | Contrast/screen reader / large text | Critical state and control readable in both OSes |
| T-018 | Mobile binary secret scan | No shared API key in APK, IPA or widget extension |

## Platforms and reporting

Android: `:androidApp:assembleDebug` and actual generated KMP Android shared test task. iOS: Xcode app and WidgetKit extension on macOS plus shared iOS tests; **exact Gradle test names must be discovered**, not guessed. A Linux dev machine can run Android and common/JVM-backed checks but cannot conclude iOS target compatibility from syntax alone. During intermediate milestones do not attempt Apple builds/tests/tools. Label implemented iOS source `Implemented — Not Verified`; runtime evidence is deferred to final stabilization under ADR 0007.

## Definition of done

A feature is complete only when every spec acceptance criterion is supported by a real command result or observed UI/device evidence. Intermediate completion requires Android/shared evidence and feasible iOS source with explicit unverified status. Deferred iOS checks alone do not block it; final verified Apple support requires actual Xcode/device evidence (ADR 0007).

## v0.2 executed coverage

See [v0.2 implementation report](validation/v0.2-report.md) for exact commands/counts. Common host tests cover current profile normalization, UID routing, missing/unknown ranks/divisions, manual/live selection, immutable goals/local calendar/DST, stale/backoff/reset/restart/corruption and shared widget timeline/privacy. Android instrumentation covers profile controls, goals/maps separation, AtomicFile interrupted writes, disconnection, native Glance values/sizes and independent instances. The explicitly opt-in host and Android live tests use only external/private runtime developer provisioning and report no player values. They are skipped by default. No Apple tests were run; source-only iOS verification is deferred.
