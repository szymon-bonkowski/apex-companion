# v0.2 specification — linked Ranked profile and goal widgets

**Status:** Complete for authorized local developer use; [Android/shared evidence](../validation/v0.2-report.md). Minimum missing v0.0 goals/settings are implemented within this scope; backup/full v0.0 remain separate. Authorized private developer access is allowed by the owner objective. Public live activation remains gated under ADR0002. iOS source is Implemented — Not Verified under ADR0007.

## Scope

- User explicitly links public Apex profile by platform+UID, or verified name lookup; do not request EA password.
- Store linked identity locally, support unlink and deletion; retain independent manual goal settings.
- Map provider payload to nullable rank/RP, `observedAt`, season/split version and error provenance. Missing values are unknown, never 0.
- Show goal progress from last validated RP, with transparent `STALE` state and manual fallback when offline.
- Update Android Glance and iOS WidgetKit Ranked Goal widgets from the persisted snapshot, not directly from a network request.
- Optional server health and Predator threshold surfaces only after verifying permissions and current response schema.
- Preserve platform distinction: Android/iOS app platforms are not Apex PC/console platform codes.

## Acceptance

1. Link, reconnect, wrong UID/platform, unknown/private profile and unlink cases tested.
2. Manual goal works offline and after profile is removed.
3. Shared deterministic RP/goal fixtures pass on Android-host Kotlin. The same implementation is used by iOS source; Apple-side compilation and test execution are explicitly deferred under ADR0007, not inferred from Android.
4. Android native widgets show observed/source/time vs manual state with native evidence. Feasible iOS widgets consume the shared projection; Apple verification is deferred under ADR0007.
5. Permission failures are visible but do not corrupt last known good profile snapshot.
6. No unverified per-match history or unauthorized server endpoints claimed.

## Reconciled v0.2 scope

Implement player name/UID linking (Switch UID-only), current provider rank/RP, persistent local goals/manual fallback, dynamic platform Predator cutoff, shared Compose Ranked/Profile/settings and three Android widgets. The original goal calculator dependency was missing and is supplied here. No verified official season rank-threshold table is available: numeric RP targets remain supported, and rank presets are explicitly unavailable. No fixed Predator rank target, match history, sessions, OCR or server/leaderboard dependency is introduced. The provider cannot establish privacy status from missing rank fields; display unavailable with that uncertainty rather than inventing a private/unranked classification.
