# Apex Companion

**A free, independent, open-source Apex Legends companion app for Android and iOS, built with Kotlin Multiplatform.**

> **Project state (2026-11-03):** v0.3 adds opt-in private RP history, explicit season/split confirmation, persistent sessions, honest trend charts and native document backup/restore of history plus goal/manual settings. Existing Maps, Profile, goals, Predator and native widgets are retained. Android/shared verification is recorded in [the v0.3 implementation report](docs/validation/v0.3-report.md); public provider activation remains blocked and no credential is embedded. iOS source is **Implemented — Not Verified** under ADR0007. Broader v0.0 Today/About, extra settings and platform polish remain incomplete.

The experience centers on map rotation, ranked progress, goals, carefully labeled data freshness and high-quality home-screen widgets. Core rules and UI are shared where practical; Android widgets use Glance and iOS widgets use SwiftUI/WidgetKit. The project has no ads, subscriptions, forced account creation or mandatory telemetry.

## Committed direction

- **Android + iOS** are both product targets, not an unplanned future port. Choose Android, iOS and **shared Compose Multiplatform UI** in KMP Wizard.
- Shared KMP domain logic: schedule, RP goals, observation intervals, provenance, backup models, screen logic and shared Compose screens.
- Platform widgets: **Android Glance** and **iOS WidgetKit**. Neither is implemented by Compose Multiplatform itself.
- v0.0 offline MVP: manual RP goal calculator, an unmistakably labeled DEMO rotation, map and goal widgets on both platforms, settings and a versioned import/export path.
- v0.1 onward: only permitted live map data, ranked profiles, honest RP history, optional screenshot-confirmed match journal and polished UX.
- **Pack counter / heirloom tracking is deferred and NOT in the current app, specs, widgets, data model or roadmap.** Do not implement it without an explicit product change.

## Public API and release constraints

Apex Legends Status is an **unofficial** source. Its key and redistribution restrictions require separate verification and an authorized access topology before enabling public live data. Never embed a shared provider key in Android binaries, an iOS application, or a widget extension. Offline release is valid even if online access is blocked. [API integration](docs/API_INTEGRATION.md).

## Start here

1. [KMP Wizard and install steps](docs/KMP_SETUP.md)
2. [Documentation index](docs/INDEX.md)
3. [Product and requirements](docs/PRODUCT.md)
4. [Architecture](docs/ARCHITECTURE.md)
5. [Roadmap](docs/ROADMAP.md)
6. [AI agent contract](AGENTS.md) and [Codex handoff](docs/CODEX_HANDOFF.md)
7. [Remaining v0.0 plan](plans/active/2026-11-02-v0-0-offline-vertical-slice.md)

## Documentation overlay, not a replacement Wizard project

The ZIP root `ApexCompanion/` contains documentation, agent procedures, GitHub templates and a doc checker. Copy its **contents** into the KMP Wizard project root and merge overlapping files carefully. Preserve all Wizard-generated `androidApp/`, `iosApp/`, `shared/`, Gradle wrapper, Gradle build scripts and Xcode configuration. [Copy instructions](docs/KMP_SETUP.md).

```text
ApexCompanion/
  androidApp/               created by KMP Wizard
  iosApp/                   created by KMP Wizard (Xcode project)
  shared/                   created by KMP Wizard (KMP code)
  gradle/                   created by KMP Wizard
  AGENTS.md                 AI implementation contract
  SUBAGENT.md               delegation entrypoint
  docs/                     source of truth, specs, ADRs, KMP setup
  plans/                    living implementation ExecPlans
  .agents/skills/           repository-local AI procedures
  .github/                  templates and docs CI
  tools/check_docs.py       docs/link/skill consistency checker
```

Build the existing project on Linux:

```bash
./gradlew :androidApp:assembleDebug :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:lintDebug
# With a connected Android emulator/device:
./gradlew :androidApp:connectedDebugAndroidTest
```

Install `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. The Maps screen starts UNAVAILABLE without a deliberately provisioned private developer file; [private setup and public gate](docs/API_INTEGRATION.md) are separate. Public release builds always keep the provider disabled. `Preview demo` explicitly shows synthetic example maps; it never becomes provider data or overwrites the widget source cache. Widgets read the common repository and keep independent playlist selections. No Apple builds/tests/tools are to be attempted during intermediate versions ([ADR 0007](docs/adr/0007-intermediate-platform-validation.md)). The development machine may be Linux/Windows for shared and Android work; **iOS compilation and widget validation require a Mac with Xcode**. Consult the report for actual commands, native widget evidence, platform status and remaining provider blockers.

## Licensing and independence

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md) and [LEGAL_ASSETS.md](docs/LEGAL_ASSETS.md). Original source and documentation are MIT-licensed; this grants no right to redistribute third-party game art or API content.

**This project is not endorsed by or affiliated with EA or its licensors.** Apex Legends and related trademarks belong to their respective owners.

## Private ranked history

Open Settings to opt into local recording. Provider refreshes retain RP evidence only after successful validation and persistence; manual saves are explicitly USER_RECORDED. Because the current provider has no verified split, confirm the known season/split before future intervals can be linked. Unknown samples remain unknown; a confirmation/new split creates a baseline. History and Sessions show observation windows rather than inferred matches. Session start/stop works offline and active sessions survive process restarts.

Export/import uses the system document picker and an explicit confirmation preview. Backups contain user settings and private history (potentially UIDs), never provider keys/configuration/raw responses or widget IDs. Restore validates and atomically merges one logical record. Disconnect retains isolated history; Settings can explicitly delete all history while keeping goals/manual RP. Externally saved backups remain your responsibility. Source-only iOS implementation requires later Apple verification.
