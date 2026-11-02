# Architecture decision register

Accepted ADRs bind implementation until deliberately superseded. Platform scope, data semantics, legal rights, provider access, security and release topology are decision topics. An ADR records context, selection, alternatives, consequences and a falsifiable validation approach. An accepted design is **not** proof of working code.

| ADR | State | Decision |
|---|---|---|
| [0001](adr/0001-kmp-mobile-platforms.md) | Accepted | Kotlin Multiplatform Android + iOS, shared UI and domain, native widgets |
| [0002](adr/0002-provider-boundary.md) | Accepted | Provider adapters, authorization and public credential gate |
| [0003](adr/0003-ranked-intervals.md) | Accepted | RP observations are intervals, not matches |
| [0004](adr/0004-widget-lifecycle.md) | Accepted | Native Glance + WidgetKit driven by safe persisted snapshots |
| [0005](adr/0005-original-assets.md) | Accepted | Independent product identity and original assets |
| [0006](adr/0006-ocr-opt-in.md) | Proposed | Local OCR with explicit human confirmation on both OSes |
| [0007](adr/0007-intermediate-platform-validation.md) | Accepted (owner instruction) | iOS verification deferred to final stabilization; source stays unverified |

The former pack event-ledger design was **withdrawn**, not migrated. Packs/heirlooms are not in current scope. See [BACKLOG.md](BACKLOG.md) and [REQUIREMENTS.md](REQUIREMENTS.md).
