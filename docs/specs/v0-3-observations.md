# v0.3 specification — ranked observations, sessions and trend

**Status:** Planned. **Dependencies:** linked profile state v0.2, safe local history and backup schema.

## Scope

1. Append timestamped, provenance-tagged RP observations for linked player/context; dedupe exact repeats without discarding different-time valid samples.
2. Compute delta only for compatible observations inside the same verified mode, season and split. Out-of-order or split transition establishes separate context.
3. User starts and stops sessions; sessions survive process death and can end with partial/no data.
4. Show trend and per-session delta **over sampled intervals**. Match count, kills, placement and damage remain unknown unless separately user-confirmed later.
5. Export/import session and observation records with identity constraints and safe idempotence across Android/iOS.
6. Native session widget (if implemented) displays intervals/freshness, never fake game counts.

## Acceptance

- Same-split +150 RP yields a +150 interval only; equal RP yields net 0 but still no inferred match count.
- Negative delta, split reset and no observation get distinct explainable states.
- Start/kill/restart/stop session retains user-selected boundaries without guessing missing games.
- Duplicate event/import never creates extra observations or crosses identities.
- Shared calculation tests and both platform storage/use-case flows verified; untested platform is marked blocked.
