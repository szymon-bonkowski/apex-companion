# ADR 0008 — One private record for history and portable settings

Status: Accepted implementation decision within the owner-approved v0.3 scope, 2026-11-03.

## Context
v0.2 has a normalized current-profile file, separate map/global caches and independent goal/manual inputs. Restoring a backup into several files would expose partial imported state after process death. A database dependency is unnecessary for the bounded v0.3 history.

## Decision
A new private `ranked-history-v1.json` owns observations, explicit sessions, context confirmations, import markers and portable goal/manual settings as one atomic transaction. First initialization copies readable v0.2 goal/manual values once. The current-profile v1 record remains readable and continues to own only the current provider snapshot and retry metadata; its old goal/manual fields are a legacy fallback, overridden by the authoritative history settings. All subsequent goal/manual writes and confirmed imports commit to the new record before publication. A bounded noncancellable commit section pairs completed native IO with its corresponding state publication even when a screen is canceled. Session result/window/classification/completeness are stored with formulaVersion1 and validated against their anchors. Maps and global Predator records are unchanged.

Android uses the existing AtomicFile adapter with an explicit 4 MiB bound; iOS source uses a private backup-excluded Foundation directory and atomic protected writes. Full history never enters the App Group or a widget projection. Corrupt/future history stays read-only and is not overwritten by initialization. Explicit confirmed history deletion retains readable portable settings, including legacy fallback when recoverable. A damaged authoritative history may require an external user backup to recover newer settings.

Import is a single full logical history/settings commit. It never links a player, restores provider snapshots/access configuration, enables recording or activates imported context. With a linked profile, every imported provider identity must match it. With no linked profile, foreign identities remain archived and invisible until that exact UID/platform is explicitly linked. Manual history is independent with no player identity. Record IDs are immutable; conflicting collisions fail before commit. Replaying an older active-session backup preserves a locally completed instance rather than reopening it. One globally active session is allowed.

## Compatibility and uncertainty policy
BR Ranked is the only supported mode. User confirmations carry USER_CONFIRMED provenance and apply to future observations. Provider season evidence must agree with confirmation; missing split never comes from a date. Each reconfirmation has a new context ID/baseline. Provider confirmation expires after a 24-hour observation gap (or no samples since confirmation), requiring reconfirmation; genuinely upstream-identified compatible long gaps retain their honest window. An adjacent absolute change above 1000 RP is a conservative review heuristic, not a game rule/reset detector: quarantine the derived interval, retain original RP, suspend applicable current confirmation and ask for a new baseline. Ordinary negative RP is valid. Late samples are sorted by UTC time then stable ID; interval quarantine is derived from adjacent evidence, so a late bridging sample can resolve a gap without rewriting observations.

Sessions use fresh available anchors at start (at most five minutes old); no sample is created by starting/stopping. Local stop makes no network call. Missing/distinct/incompatible anchors yield unknown RP. Actual observation windows and user-selected durations are separate. Explicit source/profile/context/recording changes close an active session as UNCERTAIN. Background provider refresh does not alter an independent manual context/session.

## Alternatives and consequences
A multi-file import journal adds recovery states and a new database adds substantial dependencies. The bounded whole-file approach is simpler and testable; writes rewrite the small record. Limits (4000 observations, 500 sessions, 500 confirmations, 100 import IDs, 4 MiB) reject further writes without silently trimming data. Users can export and explicitly delete. No automatic archival, cloud upload or new polling scheduler exists.

## Validation
Shared deterministic validation/collision/ordering/capacity/source tests; Android AtomicFile interrupted-write recovery, actual SAF export/import/cancellation, UI and process restart evidence. iOS source is Implemented — Not Verified; all Apple tooling deferred under ADR0007. Exact results belong to the v0.3 report, not this design decision.
