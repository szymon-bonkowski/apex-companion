# Kotlin Multiplatform Wizard setup and documentation overlay

The Wizard-generated Android+iOS shared UI project already exists. The overlay instructions below describe its setup history; do not regenerate it. Choose **Android + iOS** and **Shared UI (Compose Multiplatform)**; use Kotlin Gradle DSL and preserve the generated dependency versions. Suggested display name: **Apex Companion**. Suggested repo root folder: `ApexCompanion` or `apex-companion`. A proposed reverse-domain Android application ID is `io.bonkowski.apexcompanion` only if the owner approves and verifies it; similarly choose a unique iOS bundle ID and explicit App Group entitlement when widgets are added. Do not assume they were already registered.

## Expected tree after Wizard + safe overlay

```text
ApexCompanion/
  .agents/skills/                 from this archive
  .github/                       from this archive; merge with existing workflows
  androidApp/                    KEEP Wizard source/build.gradle.kts
  iosApp/                        KEEP Wizard Xcode project, schemes and Swift host
  shared/                        KEEP Wizard KMP shared module + commonMain/commonTest
  gradle/                        KEEP Wizard catalogs/wrapper
  gradlew, gradlew.bat            KEEP Wizard wrappers
  settings.gradle.kts            KEEP Wizard module list
  build.gradle.kts               KEEP Wizard versions/plugins
  AGENTS.md, SUBAGENT.md         copy project instructions to root
  docs/, plans/, tools/          copy entire folders from archive
  README.md                      compare and prefer product README; retain useful Wizard build guidance
  .gitignore                     MERGE ignore rules; do not overwrite blindly
  .editorconfig, .gitattributes  MERGE if already present
  LICENSE, NOTICE.md etc.        copy when absent
```

The Wizard's actual module layout may vary by template/version; inspect files before changes. Do not create `app/` or `composeApp/` merely because older tutorials mention them. An iOS widget extension target is *not* automatically generated and must later be added in Xcode.

## Safe copy on Linux (Archive downloaded to Downloads)

```bash
cd ~/Projects/ApexCompanion
mkdir -p /tmp/apex-companion-overlay
unzip -q ~/Downloads/ApexCompanion_project_starter.zip -d /tmp/apex-companion-overlay
# Copy everything hidden too; leave any existing Wizard file untouched.
rsync -av --ignore-existing /tmp/apex-companion-overlay/ApexCompanion/ ./
python3 tools/check_docs.py
./gradlew :androidApp:assembleDebug
```

`--ignore-existing` intentionally **does not replace** existing Wizard README and ignore/config files. Compare those collisions manually. The archive contains no Gradle build scripts to override. You can also copy in a file manager with hidden files visible.

## Before first implementation

1. Confirm `androidApp/`, `iosApp/` and `shared/` (or the actual selected equivalent); confirm Gradle + Xcode integration from real generated files.
2. Commit the clean generated Wizard baseline and overlay (if using git) before major agent edits.
3. Check Android SDK/JDK/Gradle and run an Android debug build.
4. At final stabilization, verify the generated iOS app on macOS/Xcode; do not attempt Apple tooling during intermediate milestones (ADR 0007). An iOS build **cannot** be validated with Gradle on a Linux-only development machine.
5. Record actual minimum iOS version, Android minSdk, package/bundle IDs, toolchain versions and successful test commands in the v0.0 ExecPlan.
6. Ask the coding agent to implement the approved [v0.0 spec](specs/v0-0-offline-vertical-slice.md), **not** to replace Wizard project scaffolding.

## Native iOS widgets

Create a **WidgetKit Extension** in the existing `iosApp` Xcode project; configure App Groups for both app and extension with the same entitlement and signing Team. Persist only validated versioned snapshot data in the shared container, read it from SwiftUI WidgetKit, and request timeline reloads via `WidgetCenter`. [Details](IOS_WIDGETS.md). A native extension target and source are now registered; Apple provisioning/signing and all runtime verification remain deferred to final stabilization.

## Platform constraints

The app may be coded on Linux for shared and Android work, but iOS simulator/device builds, WidgetKit extension tests, signing and distribution require macOS/Xcode. GitHub can host iOS source; an `.ipa` alone is not a generally installable public iPhone app. Decide on TestFlight/App Store/signing separately from GitHub Android APK distribution.

Reference documentation: [JetBrains KMP quickstart](https://kotlinlang.org/docs/multiplatform/quickstart.html), [default structure](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/), [Apple WidgetKit timelines](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date).
