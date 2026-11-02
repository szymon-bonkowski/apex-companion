# ADR 0002 — Provider-independent adapters and public credential gate

**Status:** Accepted 2026-11-02.

**Context:** Apex Legends Status offers unofficial map, profile and server APIs but requires keys and imposes terms/limits. Public mobile app binaries, including iOS app/extension and APK, can be inspected. New access to full match history is not guaranteed.

**Decision:** Keep remote data behind source-independent KMP contracts, validation and a fail-safe release flag. Use authorized developer credentials only for local optional tests. Enable publicly distributed live features only after documented provider permission, required attribution, sustainable bounded gateway or other explicitly approved topology, and privacy review. Never ship a shared provider key in any app/widget binary.

**Alternatives:** Obfuscating embedded credentials, unrestricted proxy, undocumented scraping or unreviewed BYOK are unsafe or legally uncertain. An always-on backend on day one conflicts with free OSS sustainability.

**Consequences:** Online features can lag offline MVP; both Android and iOS retain manual/demo fallback.

**Validation:** 403/429 and schema drift tests, key scans of Android/iOS artifacts, written access authorization and rate budget documented before public live release.
