# Native widget appearance — v0.5

Native Android Glance and iOS WidgetKit retain their own renderers. Their map/ranked snapshots, freshness checks, source labels and update schedules are independent from appearance. Styling never initiates a provider request.

## Four visual identities

| Design | Native presentation |
|---|---|
| Carbon | Graphite, restrained turquoise, measured round corners, sans-serif values and precise labels |
| Liquid | Violet depth, layered highlight header, generous round geometry; static gradient in WidgetKit and solid layered fallback in Glance |
| Editorial | Warm light surfaces, fine rule, serif primary values, larger numerical hierarchy |
| Tactical | Steel, angular geometry, monospace primary values and technical section label |

There is no native Liquid Glass claim, background blur, shader or live animation. Android launchers may impose their own outer geometry; Glance corner clipping requires Android 12+, with rectangular fallback on older supported devices. iOS controls outer widget shape, content margins, rendering mode and timeline delivery. Liquid's WidgetKit gradient becomes solid under Reduce Transparency or increased contrast. All custom accents receive a contrast-safe fallback against both background and surface.

## Independent configuration

Android: tap **Look** on the selected widget. A private native editor provides design, accent, density and surface (theme default/dark/light/OLED), a clearly labeled DEMO preview, Apply and Cancel. Controls are scrollable with 48dp minimum heights. Apply writes only that Glance instance's preference record. Draft selectors survive activity recreation. Existing playlist, details and Predator-platform controls remain independent. No instance ID is included in portable appearance data. Each widget's configuration intent has a unique URI to keep pending intents distinct.

iOS source: Edit Widget exposes the same four choices through each widget's AppIntent parameters. Each timeline entry retains its resolved presentation. Configuration belongs to the native instance, while app-following values are read from the sanitized App Group appearance snapshot. Small families use a condensed hierarchy and retain source/update information. iOS source is **Implemented — Not Verified**; Apple compilation, parameter migration, App Group provisioning, system rendering modes, actual small-family fit and VoiceOver require final Mac/device verification.

**Follow app** is the default. Selecting an independent design supplies its distinctive default palette; accent, density and surface can independently follow or override it. Text and state remain foreground data rather than decorative color cues. The smallest families bound spacious padding so it cannot squeeze out provenance.

## Versioned cosmetic projection

The app publishes `widget-appearance-v1.json` only after durable cosmetic preferences change. Android uses an AtomicFile in app files; iOS source writes a separate sanitized atomic App Group file and requests native reloads. The shared `WidgetAppearanceCodec` allowlists exactly:

```json
{"schemaVersion":1,"style":"CARBON","accentHex":"6EE7D1","dark":true,"density":"COMFORTABLE","trueBlack":false}
```

Maximum size is 2048 bytes. Style/density are enums, booleans are typed, RGB is exactly six hexadecimal digits, unsupported schemas and extra fields are rejected. A missing/corrupt snapshot falls back to readable Carbon; it cannot change map freshness or read private app storage. The projection contains no UID, profile name, observations, matches, pairing information, credentials, paths or native widget identifiers. The separate map/ranked source projections are unchanged.

## Verification

Shared deterministic tests cover all four palettes, contrast, strict round-trip/corruption, per-instance overrides and app-following changes. Android instrumentation renders 120 actual native RemoteViews: four designs × ten unavailable/DEMO/manual/synthetic-LIVE presentations × three registered families (150×150, 250×180, 300×220dp). The matrix includes spacious density with expanded details and checks measured glyph bounds against ancestor clipping, ellipsis, critical values, provenance, timestamps and actions. It also checks that appearance initiates no provider request. Separate AppWidgetHost tests cover per-instance persistence/isolation, native editor draft recreation/application and concurrent atomic publication/read.

Layouts use both width and height. The 250×180 family uses the compact hierarchy; spacious padding is bounded in constrained families. Long current map names can replace the optional next-map row with a second current-map line. Expanded goal status shares a progress line rather than adding an overflowing row. No source, value, state or update timestamp is removed to make room for decoration. Android widget taps open Maps or Ranked through explicit allowlisted intents; repeated intents and recreation are tested. Existing WidgetKit URLs are forwarded by the SwiftUI host through the same allowlist (source only).

These captures verify actual Glance RemoteViews, not physical/OEM launcher geometry or WidgetKit runtime. Native captures use system font scale 1.0; large widget font settings and launcher-specific accessibility remain part of final physical-device qualification. App large-text validation is separate. Exact final commands, outcomes and SHA-bound inspected captures belong to [the v0.5 report](validation/v0.5-report.md) and [widget inspection ledger](validation/v0.5-widget-inspection.json). No Apple tooling is permitted during this intermediate milestone.
