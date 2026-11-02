# Contributing to Apex Companion

Apex Companion is a free, independent, **Android and iOS Kotlin Multiplatform** application. Contributions should improve offline reliability, honest map/RP provenance, original high-quality widgets, privacy and accessibility on both platforms.

Start with [AGENTS.md](AGENTS.md), [architecture](docs/ARCHITECTURE.md), [roadmap](docs/ROADMAP.md), [KMP setup](docs/KMP_SETUP.md) and the current spec/active ExecPlan. Preserve KMP Wizard `androidApp`, `iosApp` and `shared`; do not invent app modules or degrade iOS support.

For meaningful code changes, include tests and update the plan. Report Android, iOS and shared test results separately; an unavailable Mac must be clearly disclosed. Use `python3 tools/check_docs.py` for docs changes.

Do not commit provider secrets, player IDs, game art, proprietary UI or user exports. Feature proposals need a source/rights/privacy assessment. **Apex Packs/Heirloom Tracker is not in active scope**; do not submit implementation of that feature until the maintainer reopens its design.

Never claim EA endorsement. Respect third-party dependencies and submit public release changes only with explicit maintainer approval.
