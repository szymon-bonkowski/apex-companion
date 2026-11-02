# iOS widget extension design — WidgetKit

**Status:** Map WidgetKit source and extension target implemented — Not Verified. See [handoff](validation/v0.1-ios.md). Signing/App Group identity is provisional. Do not attempt iOS builds/tests/tools until final stabilization under ADR 0007. WidgetKit is a native iOS facility; it is not implemented by Compose Multiplatform or Android Glance.

## Target structure

```text
iosApp/                        Existing Wizard-generated Xcode project
  ...                         Existing app target/Swift Compose host
  WidgetExtension/            Future SwiftUI WidgetKit target, created in Xcode
    ...                       Timeline provider, views, widget bundle and configuration
```

The actual Swift/Xcode paths and names are defined when the target is created; do not create unregistered Swift files without adding them to the Xcode target.

## Entitlements and shared storage

1. Choose and validate a unique App Group identifier and set the **same group entitlement** for the app and WidgetKit extension in Xcode. Signing team/provisioning must support it.
2. App computes validated KMP `WidgetSnapshot`. A narrow iOS bridge atomically writes sanitized versioned JSON into `FileManager.containerURL(forSecurityApplicationGroupIdentifier:)` or a small scoped app-group preferences suite. Never expose auth tokens, private profile query history or raw screenshots.
3. Widget extension independently decodes *only* this snapshot; rejects schema mismatch/invalid time and displays fallback state. Do not assume the widget can attach to app-private DB, call arbitrary commonMain repositories or inherit the app's in-memory state.
4. App calls `WidgetCenter.shared.reloadTimelines(ofKind:)` after snapshot changes; reload request is advisory. Widget `TimelineProvider` can schedule known valid segment boundaries without guaranteeing exact execution.
5. On logout/delete-all, clear the App Group snapshot and request reload. If no valid data, render safe `UNAVAILABLE`/DEMO card.

## Scope and parity

- v0.0: `Map Now/Next` + `Ranked Goal` widget with user/demo data. Read-only tap/deep-link behavior is enough; do not promise native interactive buttons across all iOS versions.
- Later: source-authorized live maps, linked RP and optional session/Predator/server widgets, governed by feature gates and platform limitations.
- Appearance: implement family-specific SwiftUI views following [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) rather than trying to use Compose view in WidgetKit.

## Test gate

A Mac with supported Xcode and an iOS simulator/device is required. Check signed extension installation, app-group data access, disabled/invalid entitlements, preview sizes, timeline rollover across DST, app termination, stale data, VoiceOver and system widget reload throttling. Keep actual Xcode scheme/build commands in the ExecPlan after inspecting the generated project. Linux Gradle success **does not** satisfy iOS acceptance.

Sources: [Apple WidgetKit: keeping widgets up to date](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date), [KMP iOS integration](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html).
