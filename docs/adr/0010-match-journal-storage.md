# ADR0010 — Separate bounded atomic match journal and portable match archive

Status: Accepted for v0.4 implementation, 2026-11-03.

## Context
ADR0008 gives ranked-history-v1.json a proven 4MiB logical history/settings transaction. Growing match data and pairing metadata must not compromise that contract. Existing v0.3 backups must remain readable and must preserve goal/manual/settings/observation/session semantics.

## Decision
Use a separate matches-v1.json, native atomic storage, at most16MiB/10000 records, bounded nesting and strict allowlisted schema1. No new database dependency. Whole-record atomic writes are deliberately bounded; capacity rejects additions visibly and never trims silently. Android uses AtomicFile in noBackupFilesDir; iOS uses atomic protected Foundation writes in a backup-excluded directory. Future/corrupt/oversized data enters read-only error state without rewriting original bytes. File recovery/manual backup recovery precedes recording; no silent empty initialization.

Match source, quality, nullable fields, timestamp source, per-field confidence, original event identity/parser, revision and optional exact UID/platform are portable. Original normalized machine evidence is retained when corrected; original screen pixels/text are not. Duplicate event IDs under another match ID reject; exact replay is idempotent; conflicting IDs reject the complete batch. Sync cursor and matches share one commit point. Delete match history preserves cursors and bounded tombstones against reconnect resurrection; explicit previewed backup restore may restore intentionally deleted records. Tombstone exhaustion also fails visibly. No automatic session attachment; observed RP windows remain separate.

Match backup is a **separate portable archive** APEX_COMPANION_MATCH_BACKUP/schema1, with records and normalized originals only. Existing APEX_COMPANION_PRIVATE_BACKUP/schema1 remains the RP/settings backup and is unchanged. These are explicitly named separate actions, not a misleading combined all-data restore. A match archive restores one atomic match-store transaction; it never mutates RP history/goals/sessions. Restoring both archives requires two separately confirmed operations. This minimizes cross-file transaction hazards and preserves existing migration behavior. No claim of atomic combined multi-store restore. No screenshots, cursors, deleted markers, pairing/network/capture config, secrets, native paths or widget IDs in exports.

## Alternatives and limits
A unified schema successor would require recoverable multi-store transactions or moving proven v0.3 ownership; rejected for this slice. SQL pagination would improve large histories but adds platform drivers/migrations and is unnecessary for the explicit bounded capacity. In-memory aggregates/list paging operate over this bounded store. Future capacity expansion requires measured performance and migration rather than silently raising limits. Backups are unsigned user-controlled metadata and never independently authenticate game/provider evidence. Strict UID/platform isolation applies on pairing, sync and import. Linked-user UI must keep null manual identity explicit.

## Validation
Common tests cover bounds, corruption/future schemas, replay/collision, edits, identity, cursor atomicity, backup repeat and failed-write/restart preservation. Android native crash/AtomicFile/restart tests validate adapter behavior. iOS source only under ADR0007; runtime atomicity remains unverified. Baseline v0.3 regression suite must pass unchanged.
