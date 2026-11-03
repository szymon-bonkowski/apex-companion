# Home-screen widget contract — Android and iOS

Apex Companion treats Android Glance and iOS WidgetKit as **two native presentations of one KMP-owned domain snapshot**. They have different scheduling, family, configuration and interactivity capabilities. A shared Compose function is **not** a widget implementation on either OS. Reference [iOS details](IOS_WIDGETS.md) and [ADR 0004](adr/0004-widget-lifecycle.md).

## Widget inventory

| Widget | v0.0 offline | Later (authorized sources) | Fallback |
|---|---|---|---|
| Map now/next | DEMO schedule + provenance on Android and iOS | Valid live rotation + next + schedule | DEMO / STALE / UNAVAILABLE visibly labeled |
| Ranked goal | User-entered current/target RP, computed remaining/daily on both | Linked profile and automatic RP | Remains MANUAL/last user value |
| Session delta | Not in v0.0 | User-started RP observation interval | Unknown when not comparable |
| Predator threshold | Not in v0.0 | Mode/platform with source/time | Missing is UNAVAILABLE, not 0 |
| Server health | Not in v0.0 | Source-attributed provider status | STALE/UNAVAILABLE |
| Small next-map | Map compact variant | Live only under permission | No extrapolated value |

**No packs/heirloom widget or associated action is in scope.**

## State flow

1. Shared domain validates source records and computes `WidgetSnapshot` with `schemaVersion`, source, quality and validity.
2. App commits snapshot to durable local cache **before** requesting a widget refresh.
3. Android Glance reads the Android widget-visible snapshot. iOS WidgetKit extension reads only the serialized App Group snapshot.
4. Native widget checks validity against device time and renders `DEMO`, `STALE`, `UNAVAILABLE` or valid values; it must not trust an already expired `LIVE` flag.
5. OS decides when or whether a periodic refresh occurs. No per-widget network fetch and no promises of per-second updates.

## Families and appearance

| Capability | Android | iOS |
|---|---|---|
| Framework | Glance; RemoteViews only after measured need | SwiftUI in WidgetKit Extension |
| Sizes | Actual launcher-specific `DpSize`; compact/square/wide when available | Supported `WidgetFamily` small/medium/large as target OS permits |
| Configuration | Android widget instance + settings | WidgetKit/App Intents configuration or deep-link to app |
| Update | Local state action + `update` + conservative WorkManager | `TimelineProvider`, timeline dates, `WidgetCenter.reloadTimelines` best effort |
| Shared storage | Local projection datastore accessible to widget process | Explicit App Group entitlement, validated atomic snapshot file/suite |
| Interaction | Taps and supported Glance actions | Taps/deep links; App Intents interactions only for supported iOS versions |

Target visual equivalence of information, not pixel-for-pixel layouts or unsupported native interactivity. Try to keep tap targets ~44-48dp where family permits. Content may hide secondary details at small sizes, never the source label.

## Time semantics

A timeline with source-defined start/end can expose *potential* future transitions, but an OS may defer rendering updates. For unknown next, show an end timestamp and unknown state instead of inventing a map. In app, foreground countdown is derived from UTC end time. Widget may show `Ends 22:45` rather than a frozen ticking-seconds readout. Timezone/DST changes affect **display only**, never persisted UTC schedule.

## Interactions

Tapping a map widget opens Maps screen; tapping a goal widget opens the appropriate manual/linked goal screen. OS-specific deep-link parsing validates inputs. Refresh action, when supported, enforces a cooldown and updates a **single shared repository source**. Setup without provider access shows offline demo or a truthful unavailable screen, never a spinner without end.

## Verification

- First install, app unlaunched, process killed, reboot, widget removed/re-added.
- Two native widget instances with distinct mode/goal appearance; no settings bleed.
- Offline/expired/past time boundary, rate-limit 429, 403, schema drift, last success stale.
- Large text, VoiceOver/TalkBack labels, light/dark, contrast and launcher/WidgetKit family sizes.
- iOS: app/extension App Group signatures match, snapshots readable after termination, `WidgetCenter` reload is best-effort, timeline dates sane.
- Android: Samsung One UI launcher and AOSP emulator if available; at least one real phone prior to release.
- Confirm no extra provider calls proportional to number of widgets.

## v0.2 implemented Ranked widgets

Android registers Rank Progress, Ranked Goal and Predator Threshold Glance providers, responsive at 150×150, 250×180 and 300×220dp. They show normalized cached values, MANUAL/LIVE/STALE/UNAVAILABLE, source/time and an Open app action. Progress/goal instances have independent detail settings; cutoff instances select their own platform. A player-to-cutoff gap is emitted only for that linked player's platform and selected provider RP; unrelated platform/manual comparisons abstain. Cutoffs are dynamic and imply no guaranteed position.

Every instance reads `AndroidRankedRuntime.widgetSnapshot()` from the committed repository. It does not synchronize the provider. The existing unique map sync work now also refreshes ranked data and publishes all widgets; local boundary work includes ranked freshness and local midnight. Android OS scheduling remains best effort. WidgetKit source implements corresponding widgets and AppIntent configuration from the shared sanitized timeline; Apple verification is deferred. See the v0.2 implementation report for executed tests and platform limits.
