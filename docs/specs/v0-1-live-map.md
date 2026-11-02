# v0.1 specification — authorized live map integration

**Status:** v0.1 Maps scope complete for authorized local developer use. Android/shared implemented and verified, including real provider data; iOS source Implemented — Not Verified under ADR0007. Public live activation remains Blocked pending redistribution permission and approved release topology. The supplied Wizard had no v0.0 goals/settings/backup implementation; these remain separate planned work and were not silently claimed delivered.

## Scope

- Validate documented `/maprotation?version=2` with an authorized local credential in a deliberately opt-in test, record endpoint schema and source date.
- Add KMP-compatible networking through `shared` (prefer Ktor common client with Android/iOS engines verified against actual Gradle toolchain). Normalize mode IDs, source segment timestamps, optional current/next and failures.
- Store validated maps, source timestamp and expiry in local cross-platform cache. Render `LIVE/STALE/UNAVAILABLE` in shared Compose UI and persisted widget snapshots.
- Native Glance and WidgetKit read projections only, no direct per-widget requests; grouped/cached sync avoids multiplication from widget count.
- Handle expired segment without an internet connection: known next only if covered by validated source schedule; otherwise unavailable.
- Back off on HTTP 429/5xx; 403 disables refresh until resolved. Unknown fields must not crash or become fake maps.
- Offline DEMO mode remains clearly separate and usable on both platforms.

## Acceptance

1. Deterministic, sanitized fixture tests for valid/partial/unknown mode, bad UTC timestamp, 403/429/timeout/schema drift.
2. Shared UI and Android/iOS widget never render expired schedule as a currently verified live map.
3. iOS WidgetKit timeline uses source-validated segment boundaries; OS-controlled timing is documented.
4. Ten widgets do not trigger ten independent upstream requests; cache/retry is shared and rate-budgeted.
5. No shared upstream key in APK, iOS app or extension, logs or repository.
6. Public live flags enabled only after recorded written rights, privacy/attribution and permitted key topology; otherwise available only in authorized developer testing.

**Non-scope:** private API scraping, embedding keys, publishing unauthorized rotations, exact one-second widget countdown.

## Owner-approved milestone policy

ADR 0007 supersedes earlier intermediate Apple verification requirements: implement feasible iOS source, but do not compile/test/run Apple tooling until final stabilization. iOS non-verification alone is not a v0.1 blocker. Android/shared requirements and the real provider gate still apply. Statuses are `Implemented — Verified`, `Implemented — Not Verified`, `Planned`, `Blocked`. See [actual evidence and limits](../validation/v0.1-report.md).
