# AGENTS.md — Apex Companion repository contract

This repository is an **Android + iOS Kotlin Multiplatform** project. The maintainer delegates implementation to coding agents but expects evidence, not untested claims. The Wizard-generated project now exists; the earlier documentation-only starter description is historical. Treat its real modules and pinned dependencies as authoritative; never reconstruct or overwrite them just because a plan predates Wizard generation.

## Precedence

1. Human instructions, safety/security, legal rights and provider terms.
2. Accepted ADRs in `docs/adr/` and `docs/ARCHITECTURE.md`.
3. `docs/REQUIREMENTS.md` and version specifications in `docs/specs/`.
4. Current plan in `plans/active/`.
5. Supporting documentation, `.agents/skills/` and toolchain defaults.

If an instruction conflicts with an accepted architectural rule, record the conflict and propose an ADR rather than silently replacing requirements. This package reflects the committed rename **Apex Companion**, removal of pack/heirloom features, and commitment to **both Android and iOS**.

## Required reading

For substantive work: `README.md`, `docs/KMP_SETUP.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`, `docs/PLANS.md`, relevant `docs/specs/*.md` and active ExecPlan. Select at most three relevant local skills using `docs/SKILLS_INDEX.md`. Read other documents only as needed.

## Product invariants

- Android and iOS are equal product targets. Shared domain + Compose Multiplatform screens belong in `shared/`; Android entrypoint and Glance widgets in `androidApp/`; iOS entrypoint and WidgetKit extension in `iosApp/`.
- Native home-screen widgets **cannot** be implemented by sharing a Compose widget; their data snapshot semantics **are** shared.
- Never reintroduce Apex Packs / heirloom counter, related screens, widget actions, ledger or data model. The feature is explicitly deferred until a new human decision.
- Never fabricate current maps, online player data, player stats or matches. Render `LIVE`, `STALE`, `UNAVAILABLE`, `DEMO`, `MANUAL` with provenance and last-update times.
- A difference between RP snapshots is an observation interval, **not** a known match result.
- No proprietary EA/Wingman branding or art, no game modification, privileged game access, anti-cheat interference or secret endpoint scraping.
- No secrets in git, released APK/AAB/IPA, iOS widget extensions, fixtures, logs or screenshots. Release-gate all online public integrations.
- Offline-first, no ads, payments, mandatory user accounts or hidden analytics. Share no local user history without explicit consent.

## KMP boundaries and tooling

- **Do not create a new Gradle/Xcode project** if the Wizard-generated `androidApp/`, `iosApp/`, `shared/` already exist. Do not invent module names such as `app` or `composeApp` without inspecting `settings.gradle.kts`.
- Favor pure Kotlin models, goals, map segments, provenance, snapshot projections and tests in `shared/src/commonMain` and `commonTest`.
- Use `shared/src/androidMain` / `iosMain` or platform app modules for real platform-specific adapters; keep platform UI/access control in its native module. Shared Compose screen implementation is the default.
- Choose KMP-compatible persistence and network libraries only after checking actual generated versions and supported targets. Do not assume Android-only Room APIs work in iOS. Use adapters where required.
- iOS WidgetKit needs an Xcode extension target, entitlement and App Group shared container to read a small sanitized widget snapshot. It cannot safely access arbitrary app-private Kotlin database paths. Shared snapshot format is versioned and platform-neutral.
- iOS builds require macOS/Xcode; on Arch Linux perform real Android/shared checks and record **iOS not validated**, never mark Apple targets passed. Keep at least one CI/macOS or manual Mac validation gate prior to full iOS release.
- Use testable, immutable state, coroutines/Flow where supported, strict failure states and minimal permissions. Widgets do not directly call provider APIs.
- Avoid hardcoding current season map pool or rank thresholds without a verified dated source.

## Implementation loop

1. Inspect actual repository, module names, SDKs, local changes and available build tools.
2. Read the approved spec and record acceptance criteria, tests, data provenance and failure handling.
3. Create/update a living ExecPlan for cross-file work; keep tasks narrow and verifiable.
4. Implement the smallest end-to-end domain/storage/UI/widget slice on **both** targeted platforms, differentiating what was actually tested.
5. Run Android and Linux-compatible shared checks during intermediate milestones. Do not attempt iOS builds/tests/simulators or Apple tooling until final stabilization. Mark iOS source `Implemented — Not Verified`; deferred iOS verification alone does not block intermediate completion. See `docs/adr/0007-intermediate-platform-validation.md`. Android builds never prove iOS validation.
6. Review diff, privacy, runtime permissions, app group entitlements, provider limits and dependency licensing.
7. Update specs/ADRs/plan with exact command output and known blockers. Move a plan to completed only when every exit criterion has evidence.

## v0.6 - Human-directed design and agent cost policy

- Read `docs/design/DESIGN_BRIEF.md` and the owner-approved per-screen record
  before any v0.6 UI implementation. Design decisions belong to the human
  maintainer; agents implement, explain feasibility and validate.
- Do not advance to the next screen or widget until the current one has
  explicit owner acceptance based on real UI, not an AI aesthetic rating.
- Preserve four selectable styles but do not treat palette and radii changes
  as evidence of an approved redesign. Follow the approved screen layouts.
- Main coordinator: the strong model selected by the owner. Use
  `.codex/agents/implementer.toml` (Luna Medium) for bounded coding,
  `.codex/agents/quality-assurance.toml` (Sol High) for independent QA.
  For other subagents use inexpensive defaults when available. Do not spawn
  extra agents for trivial work, and never assume a configured agent ran.
- Prefer targeted tests during implementation and evidence-backed full
  Android/shared validation at review gates. Do not trade correctness for
  superficial token savings. Preserve the existing iOS and provider gates.

## Local working rules

- Never force-push, publish releases, submit stores, rotate production secrets or perform destructive remote actions without explicit human permission.
- Never copy the sample starter's `.gitignore`/README onto Wizard files without a careful merge. Preserve generated Gradle and Xcode configuration.
- Do not write speculative version pins or source stubs that do not compile merely to fill a directory tree.
- No `/goal` slash commands required. Direct Codex prompts are sufficient.

Final report: files changed, actual functionality, exact tests and results per platform, blockers (especially macOS/iOS), privacy/legal implications and next planned step. For delegation see `SUBAGENT.md` and `docs/SUBAGENT.md`.
