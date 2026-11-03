# Product definition — Apex Companion

## Audience and purpose

Apex Companion is a free, independent companion for Apex Legends players on **Android and iOS**. It consolidates map schedule, Ranked goals, RP progress and trustworthy game information into attractive, accessible app screens and native home-screen widgets. Kotlin Multiplatform shares rules and Compose UI; native widget systems provide platform integration.

## Core journeys

1. **Five-second glance:** from Android launcher or iOS Home Screen, see current/next map (or a clearly identified DEMO/stale state) and time to rotation, with an explicit source timestamp.
2. **Plan Ranked progress:** type current/goal RP and deadline offline; later optionally link a public profile (platform + UID) to populate RP.
3. **Track actual progression:** start/stop a manual session and inspect honest intervals between RP observations; no invented match counts.
4. **Record a match:** at v0.4, optionally collect observed result screens with a Windows companion and sync locally; retain machine provenance, uncertain candidates and independent manual/screenshot-assisted entry. Automatic acceptance requires measured layout quality.
5. **Preserve user control:** export/import local data, remove linked identity and operate without a subscription or login.

## Product invariants

- Both mobile platforms are committed targets; platform parity applies to core flows and error states. Minor native widget differences are documented, not hidden.
- Screens and logic use KMP/Compose Multiplatform; Glance (Android) and WidgetKit (iOS) render **native** widgets using small shared snapshots.
- Every remote value has provenance, observed time and source; unknown is not zero.
- App is useful offline: manual rank calculator, DEMO map timeline, sample widget, preferences and export/import.
- No advertising, compulsory backend, intrusive telemetry, anti-cheat interference or gameplay automation.
- **No packs/heirloom tracker** in the approved plan. The previous manual counter design has been withdrawn and must not be implemented absent a new decision.

## Non-goals

- Reading game memory, packets, anti-cheat state or private undocumented APIs.
- Claiming access to unauthorized complete match histories or inventing per-game stats from RP samples.
- Copying Wingman's UI, shipping unlicensed EA imagery or presenting Apex Companion as official.
- Mandatory cloud accounts, paid features or source-limit circumvention.
- General-purpose desktop/web clients; the optional v0.4 Windows capture utility is the narrow maintainer-approved exception (ADR0009).

## Quality measures

- Data labels remain correct after server failure, schedule expiration and time-zone changes.
- Offline manual goals and widget snapshots remain available across restart/reboot.
- Accessibility with large text/VoiceOver/TalkBack; legible on native widget families.
- Core logic produces the same results on Android and iOS for identical input fixtures.
- User data and image drafts stay local except deliberately authorized public profile lookups.

Public online features require separate provider approval and an ethical, affordable access pattern. Independent offline releases are allowed when API permissions are unresolved.
