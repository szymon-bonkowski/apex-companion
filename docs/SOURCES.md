# External evidence and dependency register

**Review baseline:** 2026-11-02. These are source-reported capabilities and design references; they do not prove an actual API key test, cross-platform compile, WidgetKit entitlement, or granted data redistribution rights.

## Apex Legends Status API

- https://apexlegendsapi.com/ — unofficial API portal; key required, fair use, one key per project/person; published baseline 5 requests/s limit.
- Documented: `/maprotation?version=2`, `/bridge` by UID/platform or user lookup, `/predator`, `/servers`.
- Match-history endpoints and leaderboards may have restricted new-developer access; **do not** promise automatic per-match data.
- Map v2 schema/modes were privately observed on 2026-11-02 through authorized developer header auth after account verification: battle_royale, ranked, ltm, wildcard; current/next ranges and Mixtape activity/event metadata. No static seasonal map pool is asserted. Public key redistribution/provider grant and RP thresholds remain unverified.

## Kotlin Multiplatform and platforms

- KMP Wizard https://kmp.new/ — choose Android/iOS **shared UI** template.
- JetBrains quickstart https://kotlinlang.org/docs/multiplatform/quickstart.html — `androidApp`, `iosApp`, `shared` in current default mobile project.
- New default structure https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/ — old `composeApp` layout is not the default.
- Android Glance https://developer.android.com/develop/ui/compose/glance/glance-app-widget and Android widget interaction https://developer.android.com/develop/ui/compose/glance/user-interaction .
- Android WorkManager periodic https://developer.android.com/reference/kotlin/androidx/work/PeriodicWorkRequest — OS-controlled scheduling; not precise polling.
- Apple WidgetKit timelines https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date — system-controlled timeline reload.
- Kotlin iOS integration https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html — Xcode app consumes shared framework.
- Android Photo Picker https://developer.android.com/training/data-storage/shared/photo-picker . iOS PhotosPicker and Vision to be evaluated before OCR implementation.

## EA legal

EA content policy https://help.ea.com/en/articles/security-and-rules/ea-content-policy/ — fan content independence and asset ownership rules. Never assume permission for redistributing protected assets.

## User input

- `universal_project_layout.md` — document quality/architecture-first methodology, not literal content template.
- User-provided Wingman Tracker screenshots — inspiration for functionality and widget quality **only**, not reusable layout/artwork.
- 2026-11-02 project decision: rename **Apex Companion**, remove pack/heirloom tracking for now, commit Android+iOS KMP.

## Source and implementation rule

Before live endpoints, record current dated source, sanitize fixtures and confirm provider rights. Before claiming iOS support, build/run on macOS/Xcode. Product specs express intent; none imply compiled software exists in the starter.

## Actual v0.1 lookup / library evidence (2026-11-02)

Provider portal prose and a subsequent private authenticated v2 request were reviewed; endpoint/auth/rate and observed map schema are confirmed. Written public redistribution authorization remains absent. See API integration for explicit schema assumptions. Android Glance 1.2.0 and WorkManager 2.12.0 were checked against official release pages: https://developer.android.com/jetpack/androidx/releases/glance and https://developer.android.com/jetpack/androidx/releases/work . Ktor 3.4.3 is a verified published stability release (https://ktor.io/docs/releases.html); common client supports Android and Darwin engines (https://ktor.io/docs/client-engines.html), streaming/timeouts/mock tests use official documentation. Kotlinx JSON 1.9.0, datetime 0.7.1 and coroutines 1.10.2 are conservative published versions, not claims of latest releases. Existing Wizard Kotlin/AGP/Compose pins were preserved. Dependencies added are Apache-2.0 (Ktor/Kotlinx/AndroidX), with no EA art or new third-party asset. Android compilation and tests passed for these selections; Apple compilation is unverified. A transitive dependency security audit was not performed.
