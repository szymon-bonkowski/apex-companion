# Data model and cross-platform persistence contract

Planned semantic schema, **not an existing database migration**. The authoritative user data live in app-owned durable storage on Android and iOS. `shared` publishes repository contracts and pure models. Platform adapters may implement storage with libraries verified against the actual Wizard toolchain; do not import `android.*` or Swift types into `commonMain`.

## Data envelope

```text
DataEnvelope<T> {
  value: T?                      # null is unknown, never numeric 0
  source: SourceId              # APEX_STATUS, USER, DEMO, GENERATED
  mode: DataMode                # LIVE, STALE, UNAVAILABLE, DEMO, MANUAL
  retrievedAtUtc: Instant?
  sourceValidUntilUtc: Instant?
  error: SourceError?
  schemaVersion: Int
}
```

`SourceError` is typed: `Unauthenticated`, `RateLimited(retryAt?)`, `NotFound`, `Unavailable`, `Timeout`, `SchemaMismatch`, `PermissionDenied`. Last successful snapshot and last failed attempt are separate records. Upstream expiry overrides a permissive local cache age.

## Core records

| Record | Key fields | Constraint |
|---|---|---|
| `PlayerIdentity` | `(platform, uid)`, name?, linkedAt | Explicitly selected public identity, no EA password |
| `RemoteRefresh` | endpoint/context, observedAtUtc, status, safeError | Redacted diagnostics; never store auth headers |
| `MapSegment` | source, mode, startUtc, endUtc, mapKey, quality | Start before end; overlapping segments rejected or flagged |
| `MapSnapshot` | mode, current?, next?, segments, fetchedAt, upstreamExpiry?, source | May have unknown next; never assume cyclic map order |
| `RankedSnapshot` | identity, mode, seasonKey?, splitKey?, rp?, rankCode?, time | Nullable RP and split, full provenance |
| `RpObservation` | observationId, identity, mode, season/split, rp, observedAtUtc | Append-only, optional dedupe fingerprint |
| `RpInterval` | priorObservationId, nextObservationId, delta?, classification | Derived only within compatible season/split; match count unknown |
| `RankGoal` | id, playerIdentity?, targetRp, manualCurrentRp?, deadline?, mode | Offline goal works without a linked account |
| `RankedSession` | id, identity?, startedAt, endedAt?, observation anchors | User-controlled start/stop, supports partial state |
| `WidgetPreset` | presetId, kind, mode?, appearance, linkedGoal? | Portable logical preset; OS widget IDs not portable |
| `WidgetSnapshot` | schemaVersion, kind, dataState, generatedAtUtc, validUntil?, label, accessibilityText | Small, sanitized, safe to publish inside app/widget shared container |
| `ImportBatch` | id, schemaVersion, sourceHash, importedAtUtc | Idempotent atomic import; duplicate user records prevented |
| `MatchRecord` | id, confirmed fields, source, optional evidence digest | Created only after explicit user confirmation (v0.4) |
| `ScreenshotDraft` | temporary pointer, parsed fields, confidence, lifecycle | Ephemeral; never automatically becomes a MatchRecord |

There is **no pack, inventory, heirloom, shard, or event-ledger model** in the approved schema. Do not introduce these entities without updated requirements and an ADR.

## Provenance

- `CONFIRMED_UPSTREAM`: provider data validated within an actual documented contract, with source timestamp.
- `OBSERVED_INTERVAL`: difference between compatible RP observations, not match details.
- `USER_RECORDED`: manually supplied or corrected record, no claim of source verification.
- `DERIVED`: deterministic result with input IDs and formula version.
- `DEMO`: synthetic fixture that must be visibly marked.

## Units, IDs and time

Use integer RP, `Long` where aggregation requires overflow protection, and UTC instants for storage. Preserve external platform codes at adapter boundary (do not assume iOS/Android equals PC/PlayStation/Xbox/Switch game platforms). Season/split keys must be source-backed or explicitly user-selected, never silently inferred by date. Stored IDs are stable and imports deduplicate exactly.

## Widget projection wire contract

Platform-native widgets must not load arbitrary Kotlin database tables. App creates a **small versioned projection** that is safe for both platforms:

```json
{
  "schemaVersion": 1,
  "kind": "MAP_NOW_NEXT",
  "state": "DEMO",
  "generatedAtUtc": "2026-11-02T12:00:00Z",
  "validUntilUtc": "2026-11-02T13:00:00Z",
  "mode": "RANKED",
  "current": { "mapKey": "example-a", "endsAtUtc": "2026-11-02T13:00:00Z" },
  "next": { "mapKey": "example-b", "startsAtUtc": "2026-11-02T13:00:00Z" },
  "accessibilityText": "Demo map schedule. Not live game data."
}
```

This is illustrative **fixture** data, not a claim about current maps. Reject unsupported future schema versions gracefully, missing mandatory fields and date ranges that are internally inconsistent. Allow optional `current`/`next` only when data state permits. Android adapter persists its projection for Glance, iOS app atomically writes the same normalized fields into an **App Group** file or suite preferences; WidgetKit reads and validates them in Swift. Use a single deterministic projection builder in KMP; do not duplicate RP/schedule arithmetic in Swift.

## Backup/import and deletion

UTF-8 JSON with `schemaVersion`, `exportedAtUtc`, user-owned manual goals, optional manually confirmed matches/session notes, preferred modes, widget appearance presets and selected RP observations. **Do not** export raw screenshots, secrets, access headers, device-specific widget IDs or App Group paths. Import previews record counts, stages validation and commits atomically or rolls back. Repeat import is idempotent. Cross-platform restore imports logical data but widgets are re-added through the receiving OS. Deletion unlinks player and erases its snapshots; delete-all clears app databases and widget projections (plus any App Group copy) on the same device. External exports remain user's responsibility.

## v0.1 implemented schedule wire formats

The ISO example above remains illustrative. Actual normalized `RotationCacheCodec` schemaVersion=1 stores `fetchedAtSeconds`, `demo`, playlist IDs/labels and numeric UTC-second segments (`mapId`, `mapName`, `startSeconds`, `endSeconds`); optional per-segment `isActive`/`eventName` and per-mode `observedAtSeconds`/`retained` preserve partial-response provenance. Repository recordVersion=1 wraps the cache and allowlisted error/backoff/403 metadata. Never persist raw provider JSON or authorization.

Actual `RotationWidgetCodec` schemaVersion=1 contains `generatedAtEpochSeconds`, modes, and pre-resolved entries at source/freshness boundaries: `effectiveAtEpochSeconds`, `validUntilEpochSeconds`, state/source/last sync, optional current/next numeric-second records. Swift selects these shared entries and performs defensive validity checks; it does not calculate a separate rotation engine. Android consumes the same normalized domain snapshot through common projection. These first implemented v1 formats have no shipped predecessor migration; unsupported schemas safely fail. Widget instance mode remains device-local and is not portable backup data.

## v0.2 implemented records

`RankedRecordCodec` schemaVersion1 stores a single normalized current profile (UID/platform/name, nullable RP/tier/division, successful fetch time, provider season label and optional level/ladder position), independent goal/baseline/deadline/completion, manual RP/source preference/timestamp and safe endpoint retry metadata. No history or inferred match/reset delta is stored. Unknown split remains null. `ranked-profile-v1.json` uses the existing Android AtomicFile adapter; iOS source uses atomic Foundation `ranked-cache-v1.json`.

Global `predator-cache-v1.json` separately stores platform/cutoff/provider-update time/combined Master+Predator population and retry state. Partial responses retain omitted cached platforms with STALE quality. Existing map schema/path is unchanged. Unsupported future record versions or malformed values produce STORAGE without overwriting the file at initialization; independent global records remain readable. Atomic interrupted-write recovery is tested on Android. Full v0.0/v0.3 backup/import is still absent.

`RankedWidgetCodec` schemaVersion1 is a sanitized precomputed timeline for both native widget systems. It contains RP/goal arithmetic, platform, source/state/update times and cutoff values, never player name/UID. Shared Kotlin calculates local-midnight/DST and freshness boundaries; Android reprojects the committed source at render time, while WidgetKit source reads the atomic App Group timeline and suppresses expired daily calculations. iOS implementation is **Implemented — Not Verified**.
