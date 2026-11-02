# v1.0 specification — Android and iOS public release readiness

**Status:** Planned; publishing is human-approved only. Offline-only builds are valid if external rights remain unresolved.

## Shared gates

- Source, tests, platform feature matrix and known limitations match actual binaries and screenshots.
- No upstream shared API key in repo, Android artifacts, iOS app/extension, logs or distributed configuration.
- EA non-affiliation notice, original asset inventory and provider approval per enabled endpoint.
- Privacy documentation accurately lists Android permissions, iOS Info.plist entitlements/App Group storage, network lookups and local data deletion.
- Export/import verified, no fake full match history or fabricated schedule, no unsupported instant widget timer claims.
- Dependencies/licenses and signing supply chain reviewed; security reporting and release notes ready.

## Android release gate

- Signed APK (optional AAB later), checksum, GitHub Release and tag after explicit approval.
- Installation on at least a real Android device; Glance widget actions/open intent tested; build/lint/unit checks in CI.
- F-Droid eligibility and reproducibility remain separate assessments, not presumed.

## iOS release gate

- Build and install on simulator and at least one appropriate iOS device where possible; verify WidgetKit extension, matching App Group entitlement and release signing.
- Select a compliant distribution strategy (TestFlight/App Store or individually signed source build). Public GitHub-hosted IPA is **not** automatically installable on arbitrary iPhones.
- Record Apple Developer Program/review/certificate requirements and any ongoing cost. Do not claim App Store acceptance or free public iOS installation before confirmed.

## Failure conditions

Fail release if provider rights are unresolved but public live is on, key embedded, any stale map presented as LIVE, unlicensed art bundled, a backup can destroy user data, an iOS widget cannot read its signed shared container, or platform-specific tests were not actually executed. Report Android and iOS readiness independently; do not publish without maintainer approval.
