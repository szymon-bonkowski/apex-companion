# Appearance Studio and your Today dashboard

v0.5 implements shared Compose customization for Android and iOS. Android runtime evidence belongs to the [v0.5 report](validation/v0.5-report.md); iOS source is **Implemented — Not Verified** under [ADR0007](adr/0007-intermediate-platform-validation.md).

Open **Settings → Appearance Studio**. The preview uses the same theme, cards, typography and progress components as the app, with clearly labeled local **DEMO** values. Previewing never changes a profile, goal, RP sample, match, capture configuration or provider cache. Choose **Apply appearance** to persist the draft and update the app immediately. **Discard preview** returns to the applied settings. **Preview previous** lets you inspect the preceding selection after applying another appearance. Draft settings and an open preset dialog use saved UI state during activity recreation; the applied appearance uses durable storage across process restarts.

The four choices have distinct defaults and component treatments:

| Design | Character | Default Today emphasis |
|---|---|---|
| Carbon | Graphite, turquoise, measured rounded outlines, restrained spacing | Current rotation, goal, profile, recent match |
| Liquid | Violet layers, large rounded geometry, subtle gradients and depth | Rotation, next drop, recent match, goal |
| Editorial | Warm light preview, serif headings, spacious flat surfaces and fine separators | Goal, RP observations, rotation, sessions |
| Tactical | Steel, orange, monospace headings, cut corners and compact panels | Profile, goal, capture status, rotation |

Editorial’s design selection and reset use light appearance. Every design also supports explicit light, dark and system modes. System font families are used; no proprietary font or game art is bundled. Liquid is a lightweight Compose treatment, without a native Liquid Glass or live blur claim.

Liquid card surfaces use bounded 70–100% alpha over a local, opaque theme backdrop. This keeps nested previews and cards readable without depending on an arbitrary image or another theme behind them. Text and accent contrast are resolved against the actual composited surface and gradient stops. Reduced transparency, high contrast, reduced effects and low power select an opaque surface; reduced transparency also removes decorative gradients.

## Preview controls

- **Colors:** theme defaults or turquoise/violet/orange/ocean/rose accents; six-digit custom RGB; Android dynamic accent when the device supplies it; surface temperature; light/dark/system mode; true black in dark appearance; high contrast. Invalid RGB input cannot be applied as an accent. Resolved foreground/control colors prioritize readable contrast over an exact unsafe color choice.
- **Geometry:** theme/compact/comfortable/spacious density; theme/small/medium/large corners; theme/flat/outlined/elevated cards; bounded border strength; Liquid opacity and gradient intensity; reduced decorative effects and reduced transparency.
- **Typography & motion:** UI text size from 85% to 130%, multiplied by system font scaling; numerical emphasis; full/reduced/off motion. Device reduced-motion and low-power signals take precedence. There are no continuously animated decorative backgrounds or appearance-driven provider refreshes.

Switching designs in the gallery starts a preview using that design’s defaults. **Reset design defaults** resets the preview to the currently previewed design; it requires Apply to change the saved appearance. Existing dashboard customization stays independent. Core controls wrap instead of depending on horizontal scrolling; previews and dashboards fall back to one column for large system text.

## Saved presets

The **Saved presets** category saves the **applied** appearance and dashboard arrangement locally. Apply a preview first if it is the look you want to save. Presets support create, restore, rename, duplicate and confirmed deletion; up to 24 are stored. Names are limited to 60 characters and validated. Restoring a preset replaces its cosmetic preferences and dashboard arrangement only. Deleting a preset leaves the currently applied appearance and game data intact. Preset names are user-authored local data; appearance export/import is not implemented in this milestone.

## Configure Today

Open **Today → Configure dashboard**. Changes save immediately. Each module has an independent visibility switch, accessible **Move up / Move down** actions and compact/standard/expanded presentation. Choose the dashboard’s map playlist; Maps keeps its own current screen selection. **Hide unavailable modules** removes modules without an available value while retaining their configuration. **Restore [design] dashboard** removes the custom arrangement and returns to that design’s defaults. Changing appearance with a custom arrangement does not discard it.

Available modules are current rotation, next map, ranked profile, ranked goal, Predator threshold, latest match, RP observations, ranked sessions and capture sync. They use existing repositories and cannot initiate provider requests. Unavailable data remains unavailable; no module invents a map cycle, season threshold, match statistic or RP value. A goal can remain visible with an unknown current RP and no fabricated progress. Manual RP is kept separate from retained provider RP and is never compared to the linked player’s Predator cutoff.

RP charts show up to twelve selected-source observations and connect compatible contexts only. They do not derive individual matches. Match and session summaries use the selected identity/source; future-dated observations, results and sessions are excluded from “latest” summaries. Historical source and quality labels remain visible. Automatic Apex capture remains unverified and production auto-acceptance stays disabled.

## Storage and recovery

The bounded, schema-versioned cosmetic record is separate from maps, ranked history/settings, match journal and credentials. Android uses the native atomic-file adapter; iOS source uses private protected Foundation storage. Missing preferences migrate to safe defaults. Unsupported/corrupt records remain read-only with original bytes retained. Studio provides **Reset unreadable appearance**, with confirmation before replacing the cosmetic file; this removes its presets/dashboard configuration, resets favorite-map preferences and does not reset game data.

Appearance settings do not contain player identities, match history, provider credentials or widget instance IDs. There is no cloud synchronization, telemetry or added provider polling. Native widgets have their own per-instance controls and rendering limitations; see [widget theming](WIDGET_THEMING.md).
