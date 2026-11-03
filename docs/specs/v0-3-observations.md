# v0.3 specification — ranked observations, sessions and trend

**Status:** Complete for maintainer review; Android/shared validation passed. iOS Implemented — Not Verified under ADR0007. **Dependencies:** linked profile state v0.2, safe local history and backup schema.

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
- Shared/Android calculations and native storage/use-case flows have actual evidence; feasible iOS source is Implemented — Not Verified, with all Apple validation deferred under ADR0007.

## Implemented scope and policy

Opt-in `HistoryRepository` stores immutable provider/manual RP observations, source/identity scoped explicit context confirmations, sessions and portable goal/manual settings in a separate versioned atomic private record. The shared history chart uses real UTC times and compatible adjacent points only. Sessions restore active IDs/start times, work offline and expose OBSERVED/PARTIAL/MANUAL/UNCERTAIN results with separate observation windows. User actions invoke native Android SAF and source-only iOS UIDocumentPicker for allowlisted JSON backup/restore, strict preview/confirmation and whole-record atomic import. History deletion is confirmed; disconnection retains hidden isolated history and preserves goals/manual data. Existing Maps/Profile/Predator/widgets and public API gates are preserved. No optional session widget was added.

[ADR0008](../adr/0008-private-history-transaction.md) specifies migration, capacity, corruption, import identity/collision policy, user context expiry and anomaly review. The provider's split stays null; context confirmation never becomes upstream verification. Only BR Ranked is supported. No RP interval/session is a match record. Final command/device evidence is recorded in the v0.3 implementation report.

Evidence: [v0.3 implementation report](../validation/v0.3-report.md) and [machine-readable results](../validation/v0.3-evidence.json). No public release or verified iOS claim.
