# v0.2 specification — linked Ranked profile and goal widgets

**Status:** Planned. **Dependency:** goal calculator v0.0 and public access gate for live data.

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
3. Same RP/goal test fixtures produce identical computed result across Kotlin Android/iOS targets.
4. Widgets show observed/source/time vs manually entered state correctly on both platforms.
5. Permission failures are visible but do not corrupt last known good profile snapshot.
6. No unverified per-match history or unauthorized server endpoints claimed.
