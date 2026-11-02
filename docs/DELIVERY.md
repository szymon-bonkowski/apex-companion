# Build, CI and distribution — Android + iOS

## Current state

This is a documentation and agent workflow kit. The **maintainer generates the actual KMP Wizard project** with Android + iOS shared Compose UI. It is false to claim any APK/IPA, Xcode project, Gradle wrapper, tests or live provider integration shipped with this starter archive.

## Bootstrap

- Preserve existing Wizard `androidApp/`, `iosApp/`, `shared/`, root Gradle wrapper, version catalog and Xcode integration. Inspect real versions and toolchain compatibility; do not pin speculative dependency numbers.
- Suggested Android `minSdk=26` pending verification; choose supported iOS deployment target after checking WidgetKit family and App Intent needs.
- Android: SDK/JDK/Gradle; iOS: **macOS + Xcode** for app/extension compile, simulator/device testing and signing.
- Native Android Widgets: Glance and WorkManager where used; native iOS: WidgetKit SwiftUI extension, App Group entitlement and OS-owned timeline. Shared code uses KMP-compatible libraries only.
- Offline debug apps and deterministic fixtures require no API credential. Public live features have separate provider gate.

## Local checks

```bash
python3 tools/check_docs.py
# After KMP Wizard creates its modules, check actual available Gradle tasks:
./gradlew :androidApp:assembleDebug
./gradlew tasks --all
# Then run the exact shared Android test task generated for your chosen KMP toolchain.
```

On a **Mac** open `iosApp` in Xcode. Build/install the iOS application and WidgetKit extension via real Xcode scheme; configure and verify matching App Group entitlements, test iOS simulator and shared iOS target tests. The exact `xcodebuild` scheme/simulator/Gradle iOS test command is determined by the generated project, **not guessed here**. Linux build success cannot stand in for Xcode evidence.

## CI

- Documentation-only workflow must run immediately, without secrets.
- Add Android/shared Gradle CI after Wizard project is committed, using actual generated tasks and compatible JDK/SDK.
- Add macOS Xcode iOS compile/test lane after signing/test environment exists; avoid using private release certs in untrusted PR workflows.
- No automatic GitHub Release, Apple store upload or provider secret use without maintainer approval.
- Scan Android release artifacts and iOS app+extension for secrets, privacy permissions, unlicensed assets and dependencies.

## Distribution

**Android:** signed APK + checksum in GitHub Releases, possibly F-Droid after dependency/reproducibility review. **iOS:** source code on GitHub, then an explicitly selected compliant TestFlight/App Store or signed self-build route. An unsigned or generic IPA uploaded to GitHub is not installable by everyone; Apple account, certificates, provisioning and review can incur costs and restrictions. Report actual availability rather than implying identical store distribution.

## Rollback and release gate

If online data rights/schema fail, disable that provider feature and preserve offline goal/map DEMO experience. Neither platform embeds a shared upstream key. No bypass of upstream quotas, undocumented private endpoints or false map-current status during outage.

See [full release spec](specs/v1-0-release.md), [security](SECURITY_PRIVACY.md), [KMP setup](KMP_SETUP.md) and [iOS widgets](IOS_WIDGETS.md).
