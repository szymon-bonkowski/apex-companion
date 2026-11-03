# Ranked goals, RP snapshots and session semantics

## Why per-match reconstruction is unsafe

Two RP snapshots cannot identify how many matches occurred between them. `13,000 -> 13,350` could represent one game or several; `13,000 -> 13,000` does **not** prove no games. Third-party match history is unavailable to new Apex API consumers as of the documented source check. Therefore `RP delta` is modeled separately from `MatchRecord`.

## Three distinct objects

- **Snapshot**: the provider's ranked score at a particular fetch, with identity/platform, game mode, season/split context, timestamp and confidence.
- **Interval**: difference between two compatible snapshots. Fields: `fromId`, `toId`, `netRp`, `duration`, `attributedMatches: unknown`, `quality`.
- **Match record**: created only from an authorized match feed or user-confirmed manual/screenshot information, with a unique ID and explicit source. Never generated automatically from an interval alone.

## Observation algorithm

```text
onSnapshot(s):
  validate identity, mode, value, source time and monotonic order
  persist s if not duplicate
  prev = preceding compatible snapshot
  if prev absent: record baseline, produce no interval
  else if seasonKey/splitKey missing or different: mark boundary, no delta
  else if score reset or provider anomaly suspected: quarantine delta; request confirmation
  else produce interval(netRp = s.rp - prev.rp, matches = UNKNOWN)
```

A split transition can occur while the app is offline. If explicit provider season/split identifiers are absent, **do not assume compatibility** based solely on small timestamp differences. Prefer disabling automatic interval linkage until metadata or user confirmation establishes the same split. A negative delta can be a legitimate Ranked loss; it is not a reset automatically. Heuristics may flag suspicious discontinuities but cannot rewrite history.

## Session state machine

```text
IDLE --start--> ACTIVE --stop--> COMPLETED
                  |              |
                  |--app killed-->| active state persisted
                  |--logout------>| stop with missing end snapshot if needed
```

On start, store UTC start time, linked identity and optional start observation; on stop, persist end time and attempt a safe refresh only if provider cooldown permits. If start/end are not both valid, report `net RP unknown` with no fabricated zero. Sessions have `OBSERVED`, `PARTIAL`, `MANUAL`, `UNCERTAIN` classifications. A user may manually attach confirmed match records. Do not presume that today's number of matches is known from provider polling.

## Goal math and UX

Inputs: player RP or manually entered RP, goal RP, optional end date/day count, platform, ranked mode. Compute:

```text
remaining = max(0, targetRp - currentRp)
daily = ceil(remaining / daysRemaining)  when daysRemaining > 0
```

- At target: show `Goal reached`, daily target 0.
- Deadline passed: show `Deadline passed` and remaining, not divide-by-zero or negative daily.
- No valid split end: ask for a **manual deadline**; do not auto-invent current split dates.
- Rank names and threshold table: versioned external configuration, not immutable constants; show RP numeric progress even if tier mapping unavailable.
- Average RP per game: only from explicitly confirmed match records. If only intervals exist, label a statistic `observed RP per hour/session` not per-game.
- Rank forecast: optional and transparent scenario, with assumptions visible and no probability claim.

## Example test sequence

| Time UTC | RP | Split | Expected |
|---|---:|---|---|
| 09:00 | 13000 | S1 | Baseline, 0 derived intervals |
| 09:30 | 13150 | S1 | Observed +150 across 30 min, unknown game count |
| 10:00 | 13150 | S1 | Observed +0, **not** "0 games" |
| 10:15 | 12600 | S2 | New baseline, no -550 cross-split interval |
| 10:30 | null | S2 | Error/missing, no interval |
| 10:45 | 12680 | S2 | Observed +80 if source sequence validated |

Further tests: duplicate observation ID, out-of-order fetch, timezone/DST shift, changed UID, rank not loaded, provider returns negative/non-integer RP, late replay, reset within same split, overlapping manual session windows.

## Aggregation honesty

A season graph may connect snapshots with a line but must **not** label each segment a match. A `Today +359 RP` summary is valid if it compares same-split first and latest reliable snapshots, identifies the observation window, and clearly distinguishes it from a true game's results. "6 games" requires confirmed match records or a user-entered count.

## v0.3 implementation policy

Shared `history/` implements opt-in immutable observations, pure `rpInterval`/`rpIntervals` and persistent sessions. `/bridge` supplies no trustworthy split: user context confirmation is explicitly USER_CONFIRMED, never upstream-verified. Unknown observations stay unknown; confirmation affects future samples and creates a new baseline. Known provider-season disagreement, a >24-hour provider observation gap, or a suspicious adjacent absolute change >1000 RP suspends the applicable confirmation. This threshold is a conservative anomaly review policy, not a split reset or official RP rule. A normal -100 RP remains a valid interval. Quarantine is derived between adjacent sorted samples; original RP records are retained and late valid evidence is deterministic. Context/source/identity boundaries never connect in the trend chart; long gaps display discrete points and an honest duration.

Only successful validated durably accepted profile refresh events are captured. Cache reads, views, widget rendering, failed requests and stale reuse create no observations. Existing cooldown/budget/scheduler are reused. Saving manual RP explicitly records USER_RECORDED evidence separately; background provider refresh leaves manual context/session untouched. Recording is off until the user opts in and can be stopped/deleted in Settings.

One session is active globally. Start can anchor a real saved valid observation at most five minutes old; start itself creates no sample. Stop is local and works offline, taking an available later sample when present. Missing or identical anchors produce PARTIAL/RP unknown. Incompatible contexts or explicit context/source/profile/recording interruptions produce UNCERTAIN/RP unknown. Compatible provider anchors yield OBSERVED; compatible manual anchors yield MANUAL. Session duration, observation window and RP delta are shown separately, including restored active start times. No session implies matches. History/settings and import commit atomically under [ADR0008](adr/0008-private-history-transaction.md).
