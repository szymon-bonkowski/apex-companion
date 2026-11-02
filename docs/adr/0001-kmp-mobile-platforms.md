# ADR 0001 — Android + iOS with Kotlin Multiplatform

**Status:** Accepted 2026-11-02; replaces the earlier Android-only architecture baseline.

**Context:** A free Apex Legends companion should work on both Android and iOS, with shared map/Ranked logic and polished home-screen widgets. The maintainer will create the project with KMP Wizard. Widgets remain platform-specific even when UI screens are shared.

**Decision:** Use Kotlin Multiplatform **Android + iOS** with **shared Compose Multiplatform UI**; preserve the generated modules (`androidApp`, `iosApp`, `shared` in the current template). Put pure calculations, models, source policy, repository contracts and Compose screens in shared. Android Glance and OS integrations belong to Android module; iOS WidgetKit/SwiftUI extension, App Group and Xcode signing belong to iOS project. Persist user data via KMP-compatible libraries or platform adapters verified for both targets. `minSdk=26` is provisional; iOS minimum selected after toolchain review.

**Alternatives:** Android-only is simpler initially but fails committed iOS reach. Fully native duplicate apps double maintenance and risk diverging RP semantics. Flutter/React Native share UI but would still need native widget implementation; do not switch away from the owner's KMP choice.

**Consequences:** Extra Apple toolchain, provisioning and WidgetKit work. Development proceeds on Android/shared on Linux. ADR 0007 defers iOS verification to final stabilization; intermediate completion permits implemented unverified iOS source. Verified Apple support still requires macOS/Xcode evidence. No single-platform compiled widget implementation can be reused unchanged.

**Validation:** Wizard app builds on Android and iOS, identical goal/demo results from shared tests, real Glance and WidgetKit variants read the same semantic snapshot, App Group entitlement verified and no Android-only imports appear in `commonMain`.
