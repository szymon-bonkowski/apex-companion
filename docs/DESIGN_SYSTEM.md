# Apex Companion — four design systems

One shared application resolves design defaults, cosmetic preferences and OS accessibility signals into presentation tokens. Semantic Maps/Ranked/History/Matches data remains independent. Implementation: `shared/src/commonMain/kotlin/io/github/szymonbonkowski/apexcompanion/appearance/`. [ADR0011](adr/0011-cosmetic-state-and-design-resolution.md) explains persistence and native boundaries.

| Design | Typography and hierarchy | Surfaces and geometry | Dashboard and motion |
|---|---|---|---|
| Carbon | Sans headings, aligned monospace numeric emphasis, precise labels | Graphite/teal, measured16dp default corners, fine outlined panels | Map-led summary, comfortable spacing,150ms restrained progress |
| Liquid | Softer sans hierarchy, luminous numeric emphasis | Violet depth,30dp corners, bounded diagonal highlight gradients, raised layers | Floating map/next/journal flow, spacious spacing,300ms progress |
| Editorial | System serif display/headings, large narrative values, fine rules | Warm cream by default selection,3dp corners, flat publication panels; dark alternative | Numbered one-column daily brief, generous spacing,220ms calm progress |
| Tactical | System monospace headings/numbers, technical labels | Steel/orange, cut corners, leading accent rule, structured outlined clusters | Ranked/capture-led compact telemetry, wide two-column layout,100ms crisp progress |

The four styles cover Today, Maps, Ranked/goals/Predator, Profile, History/trends, Sessions, Matches/manual/detail/statistics/backup, Capture sync/pairing, Settings/import/export, Studio, notification preferences and About. Existing Material buttons, pickers, chips, inputs and dialogs inherit resolved color/typography/geometry. Explicit card variants carry each design's surface identity. Source/status/time remain text, including empty, stale and unavailable states.

## Tokens and customization

`DesignStyle` definitions resolve into `ThemeTokens`: color roles, spacing/padding, corner geometry, border/elevation, density, gradient/opacity, animation duration and numeric emphasis. Preferences choose mode, six-digit RGB accent/presets, Android dynamic accent when available, OLED black, surface temperature, density, corners, flat/outline/elevation, border strength, opacity, gradient intensity, safe85–130% additional type scale, numeric emphasis, motion and accessibility overrides. OS font scaling is retained and nested previews apply custom scaling once.

Custom accents are adjusted to at least4.5:1 against both app/card surfaces. Body/secondary contrast is tested in all four modes. OS high contrast, reduced motion/transparency and low-power signals override effects without overwriting choices. Full/Reduced/Off controls affect app-owned progress transitions and the Material motion scheme. No infinite decorative animation or provider request is created by visual changes. Device-level Material control/system motion behavior remains platform-owned and requires native accessibility testing.

Liquid uses bounded gradients/highlights over readable solid base surfaces. Liquid surface opacity is bounded to70–100% over an opaque local theme backdrop; foreground roles are checked against actual composited card/page gradient stops. It ships no full-screen real-time blur, shader, native Liquid Glass claim or per-second widget animation. Reduced transparency/contrast/power remove those layers. System typefaces require no redistributed font assets. No game artwork/reference mockups were added or claimed inspected.

## Layout and access

Today selects one/two columns based on width, style and font scale; Editorial maintains narrative reading order. Forms use available width in a bounded content area and controls wrap with FlowRow where appropriate. Persistent top secondary destinations and bottom primary destinations expose named semantics. Studio preserves preview/dialog state across recreation. Dashboard order uses accessible Move up/Move down actions; no drag-only control.

Validation must use actual device/emulator captures and screen-reader focus, not concept images. See [v0.5 report](validation/v0.5-report.md), [customization](APPEARANCE_CUSTOMIZATION.md) and [native widgets](WIDGET_THEMING.md). Android results do not prove VoiceOver or WidgetKit. iOS source remains **Implemented — Not Verified** under ADR0007.

## v0.6 superseding visual authority

This document describes **v0.5 as implemented**, not as owner-accepted
visual design. Its token architecture and data isolation are reusable, but
its layout, hierarchy and four style identities may be changed by explicit
owner-approved v0.6 screen specifications. Do not infer owner acceptance
from v0.5 screenshot matrices or contrast/unit test results.

Source: [owner design brief](design/DESIGN_BRIEF.md) and
[v0.6 specification](specs/v0-6-design-overhaul.md).

## Native iOS material evaluation

A bounded `UIVisualEffectView`/`UIGlassEffect` adapter was considered for a small navigation region. Apple's [material guidance](https://developer.apple.com/design/human-interface-guidelines/materials) places Liquid Glass primarily in controls/navigation rather than large content-card layers; [UIGlassEffect](https://developer.apple.com/documentation/uikit/uiglasseffect) is a native effect API, not a Compose surface. The existing host shares its full screen through Compose and targets iOS18.2. A native adapter would need SDK/OS availability guards, separate focus/hit-testing/lifecycle integration and actual power/contrast measurement. Those cannot be established by an Android build, and Apple tooling is deliberately deferred under ADR0007. This milestone therefore ships the actual bounded Compose alpha/gradient design on both app targets, with solid accessibility fallbacks. It does not insert an unverified native backdrop behind the entire shared canvas or claim native Liquid Glass. A future narrowly scoped navigation adapter requires final Mac/device evidence before adoption.
