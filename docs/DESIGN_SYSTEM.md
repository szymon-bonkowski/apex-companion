# Apex Companion — design system

Original, legible at mobile-widget scale and honest about source status. Wingman screenshots are inspiration for **functionality and polish**, not a layout, artwork or typography license.

## Identity

- Original abstract map/navigation/progress iconography; **never** bundle Apex/EA game logo as app icon.
- Deep graphite + optional teal, amber, violet accents; dark/light, optional true-dark, clear text/contrast.
- On Android support Material You when available; on iOS fit native color, text and safe-area conventions. Shared Compose UI can branch on platform capabilities without duplicating business logic.
- Status conveyed through text **and** icon, not color alone. Use tabular numerals for RP and end times.
- Native widget visuals differ by platform family/material effects but should share values, type hierarchy and provenance semantics.

## Components and pages

`MetricCard` = label, value, units, quality/status and observedAt. `ProgressTrack` has accessible numeric summary. `FreshnessIndicator` labels `LIVE/STALE/UNAVAILABLE/DEMO/MANUAL`. `TimeLabel` formats UTC in user's current zone. `WidgetSurface` is native by platform with semantic style tokens.

Initial shared Compose navigation: **Today**, **Maps**, **Ranked**, **Settings** and **About**. Do not show unimplemented features as dead menu buttons. Disconnected Ranked always offers a useful manual goal screen.

## Interaction and accessibility

- Respect OS reduce-motion, larger text, contrast, screen-reader and locale options; test **TalkBack and VoiceOver**.
- Theme changes persist independently of widget instance/user preset; data always takes precedence over decoration.
- Rebuild/refresh only when useful; no continuous background glows, charts or polling solely to animate a countdown.
- Treat map expiry, missing profile and unsupported modes as visible states, not blank exception views.
- Test small/wide Android launcher widgets and iOS WidgetKit families with 200% text equivalents when feasible.

## Review standard

Capture screenshots at actual Android launcher and iOS simulator/real device widget sizes, both themes and significant text scaling. Document device/OS, family/launcher and visible truncation; prioritize important map/RP/state data over optional detail.
