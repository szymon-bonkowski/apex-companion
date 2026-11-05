# ADR0011 — Independent cosmetic state and resolved design tokens

Status: Accepted implementation decision within the explicit owner v0.5 four-design brief, 2026-11-03. This records the requested scope expansion; it does not claim acceptance tests passed.

## Context
Carbon, Liquid, Editorial and Tactical must be genuinely usable designs across shared screens and native widgets without duplicating game repositories. Existing v0.3 history/settings and v0.4 match transactions must retain their schemas and crash semantics. Cosmetic changes cannot reset player data or introduce provider polling.

## Decision
`shared/appearance` owns `AppearancePreferences`, bounded local presets, a semantic dashboard configuration, explicit style resolution and shared Compose Studio/Today screens. Domain repositories never import appearance types. Resolve design defaults, user overrides and runtime OS accessibility/power signals separately. All screens use one Material role/typography/shape layer plus explicit `ThemedCard`/`DesignCard` variants. Editorial uses system serif headings, Tactical system monospace instrumentation, Carbon measured outlined graphite and Liquid layered gradients/rounded surfaces. No proprietary fonts or game art.

Store one new independent `appearance-v1.json` with schemaVersion1, at most128KiB/24presets, strict fields/enums/bounds and bounded nesting. Android reuses AtomicFile; iOS source uses protected Foundation atomic writes. Missing fields in schema1 default safely, absent file means defaults; future/corrupt data stays read-only until explicit appearance-only reset. Commit before StateFlow publication. Failed writes retain prior committed settings. Presets include appearance and optional dashboard only; no game IDs/history/credentials. No appearance export or changes to existing portable backup schemas in this milestone.

A separate sanitized widget wire contains design/accent/dark/density/OLED only. Glance preferences and WidgetKit AppIntent values belong to each native instance, with follow-app as a selectable default. Widget adapters never poll providers on appearance changes. App Group receives only this minimal cosmetic projection alongside existing sanitized data projections.

Liquid uses static bounded gradients and highlight layers, without native Liquid Glass or full-screen blur claims. Reduced transparency/contrast/power disable decorative layers; OS motion preferences override user motion. Favorite-map notification delivery remains capability-gated while provider redistribution/authorized future scheduling is unresolved. Shared UTC planning is deterministic; no scheduler, permission or new polling is activated through an appearance setting.

## Alternatives and consequences
Four application forks would duplicate state/business rules and drift. Extending the private history schema with cosmetics would needlessly put game data in every cosmetic transaction. A database/new settings library is unnecessary for the bounded record. Native glass/blur would increase platform risk and rendering cost without improving data utility. Existing history/match backups deliberately remain independent; document appearance presets as device-local.

## Validation
Common style/mode/contrast/accessibility/codec/failure/restart/preset/dashboard tests; Android real AtomicFile, Studio/dialog recreation, cold process, Glance instance isolation and actual captures. Record exact evidence in [v0.5 report](../validation/v0.5-report.md). iOS source Implemented — Not Verified; no Apple tooling under ADR0007. Final stabilization must validate Kotlin/Native, Foundation, App Group/AppIntent migration, Dynamic Type, VoiceOver and native widget families on Mac/device.

Native signal references (source design, not Apple execution): [UIKit reduced motion](https://developer.apple.com/documentation/uikit/uiaccessibility/isreducemotionenabled), [Foundation low power](https://developer.apple.com/documentation/foundation/processinfo/islowpowermodeenabled). Native compatibility still requires the deferred Mac gate.
