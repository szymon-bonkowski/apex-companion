# v0.0 implementation specification — Android + iOS offline vertical slice

**Status:** Approved specification, **not implemented**. **References:** ADR 0001 (KMP), 0004 (widgets), 0005 (original art); [KMP setup](../KMP_SETUP.md), [architecture](../ARCHITECTURE.md), [data model](../DATA_MODEL.md). Must not regenerate an existing Wizard project.

## User-visible behavior

1. App launch on **both** platforms opens a shared Compose Today page and exposes Maps, Ranked, Settings, About. No placeholder Packs/Heirloom screen.
2. Maps renders a deterministic schedule containing at least two fake map identifiers, clearly labeled **DEMO** on all screens and widget sizes. This is a UX/test fixture, **not** a live Apex season rotation.
3. Ranked Goal accepts manual current RP, target RP and future deadline or positive remaining days. Shows remaining RP and rounded-up RP/day. Goal reached and deadline elapsed get explicit states, never negative or NaN.
4. Persist chosen mode, goal, theme and widget preset settings across app process death; keep user records local.
5. **Android:** usable Glance map-now/next widget and ranked-goal widget (demo/manual data), tapping opens relevant shared Compose screen. Test multiple instances, resizing and launcher process death.
6. **iOS:** usable SwiftUI/WidgetKit map and goal widgets, reading a validated `WidgetSnapshot` written atomically to App Group container; widget taps/deep links open related screen. The app and extension must be correctly configured/signed in Xcode.
7. Settings support dark/light/system theme and conservative widget appearance presets. About clearly states EA non-affiliation.
8. Backup/export includes schema-versioned user goals, mode and theme presets; import validates/stages and atomically applies, with duplicate-safe behavior. Use platform system file interfaces.

## Technical rules

- `shared/src/commonMain` owns pure goal calculations, map fixture schedule, `DataEnvelope` state, normalized `WidgetSnapshot` and Compose screens/navigation. Add no Android-only dependencies to `commonMain`.
- Select a real compatible persistence adapter per platform only after checking versions against the Wizard toolchain. Do not assume Android-only Room objects can run in iOS.
- `androidApp` owns Glance provider, local Android widget projection access and lifecycle; `iosApp` owns Xcode WidgetKit extension, App Group snapshot bridge, timeline and signing.
- App projection commit occurs before widget reload. iOS extension reads the small projection, not the app's Kotlin database.
- No runtime third-party API requests or provider credentials, no game artwork. Keep `INTERNET` permissions/entitlements out of v0.0 unless generated build requirements or a documented integration explicitly need them.
- Pack counter/Heirloom Tracker is excluded, including widget, ledger, screen, models and tests.

## Acceptance checklist

- **A0.1** Shared tests verify RP goal edge cases and DEMO schedule; exact generated KMP task commands recorded.
- **A0.2** `./gradlew :androidApp:assembleDebug` succeeds, debug install launches main screens.
- **A0.3** iOS Xcode project builds and installs to simulator/device, using shared KMP UI (requires Mac).
- **A0.4** Map DEMO and manual goal persist after app restart on both platforms; no false LIVE states.
- **A0.5** Android Glance map + goal widgets render/tap after app process death, preserve separate presets.
- **A0.6** iOS WidgetKit map + goal widgets read app-group projection after main app termination; handle empty/expired snapshot without crashing.
- **A0.7** Goal `13460 -> 16000` over 22 days yields remaining 2540 and ceil 116/day; 0 days and goal reached are safe.
- **A0.8** Export -> delete -> import twice does not corrupt/duplicate user data on either platform, or an explicitly noted platform gate remains blocked.
- **A0.9** Font scaling, TalkBack/VoiceOver labels and dark/light state readable in smallest supported widget family.
- **A0.10** No upstream secrets, copyrighted EA assets, unapproved remote traffic or deferred pack/heirloom code present.

## Suggested work order

B1. Inspect real Wizard modules and record toolchain, Android bundle ID and iOS signing prerequisites.
B2. Implement shared goals, demo timeline and strict source state with unit tests.
B3. Implement app-owned local repositories/adapters and versioned widget projection.
B4. Implement shared Compose screens and stateful navigation; verify Android debug build.
B5. Android Glance two widget types and launcher QA.
B6. iOS WidgetKit Extension target/App Group; two widget types; Mac simulator QA.
B7. Cross-platform export/import and accessible settings; platform verification and documentation.

## Non-scope

Live API, linked player, automatic matches, screen OCR, squad/store features, game art and all pack/heirloom tracking. Under ADR 0007, A0.3/A0.6 and iOS parts of A0.8/A0.9 are deferred to final stabilization, never marked passed without evidence. Implemented unverified iOS source alone does not block intermediate completion; all Android/shared criteria still apply. [Active plan](../../plans/active/2026-11-02-v0-0-offline-vertical-slice.md).
