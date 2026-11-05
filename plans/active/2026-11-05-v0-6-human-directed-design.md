# ExecPlan - v0.6 Human-Directed Design Overhaul

**State:** NOT STARTED. This file records planned work, not implemented UI.
**Baseline:** v0.5.0-dev. **Separate incomplete work:** v0.4 Auto Capture.

## Goal and user value

Deliver the maintainer's approved, genuinely enjoyable mobile UI with
familiar native interaction, excellent visual hierarchy, accurate data,
purposeful motion/haptics and four distinct customizable looks.

## Source of truth

- `AGENTS.md`, accepted ADRs, `docs/ARCHITECTURE.md`.
- `docs/design/DESIGN_BRIEF.md` and owner-approved per-screen records.
- `docs/specs/v0-6-design-overhaul.md`.
- This plan, with evidence for each completed stage.

## Starting state

- v0.5 provides four theme tokens, Appearance Studio, configurable Today,
  native widget appearance and regression tests. Its aesthetics are not
  accepted by the owner; functional tests are not visual acceptance.
- v0.4 Windows real-game Auto Capture remains unfinished and separately
  testable. Do not accidentally enable auto-acceptance.
- Initial UI review noted duplicate navigation, oversized headers,
  excessive developer terminology and dense/repetitive cards.
- iOS remains Implemented - Not Verified under ADR0007.

## Scope and non-scope

Scope: owner-directed navigation, reusable UI components, individually
approved screens/widgets, interaction/haptics/accessibility, measured
Android performance, focused v0.5 debt repairs, regression evidence.

Non-scope: autonomous design generation, fabricated statistics, public
game API access, unapproved Windows capture work, iOS Apple tooling,
releases, app stores, packs/heirloom and new major game features.

## Platform ownership

- Shared Compose UI/domain: `shared/src/commonMain` and `commonTest`.
- Android native touch/haptics, entry, Glance and tests: `androidApp/`,
  `shared/src/androidMain` and androidTest.
- iOS source-only adapters, WidgetKit and host: `iosApp/`,
  `shared/src/iosMain`. No Xcode/Swift/Kotlin-Native execution until the
  final stabilization gate.
- Separate Windows companion v0.4 worktree is not owned by this plan.

## Approach and ordered checklist

- [ ] NOT STARTED - Owner approves navigation/information architecture.
- [ ] NOT STARTED - Inventory screens, user tasks and actual entry paths.
- [ ] NOT STARTED - Owner supplies/approves first screen design and states.
- [ ] NOT STARTED - Implement first screen only, with targeted tests.
- [ ] NOT STARTED - Review real screenshots, native behavior and QA issues.
- [ ] NOT STARTED - Record explicit owner acceptance or revise.
- [ ] NOT STARTED - Repeat approved screen-by-screen for all needed screens.
- [ ] NOT STARTED - Verify four themes and durable appearance settings.
- [ ] NOT STARTED - Approve native widget variants and test real rendering.
- [ ] NOT STARTED - Verify purposeful haptics, performance and accessibility.
- [ ] NOT STARTED - Address scoped v0.5 technical debt with regression tests.
- [ ] NOT STARTED - Run final Android/shared suites and capture evidence.
- [ ] NOT STARTED - Write validation report and collect final owner sign-off.

## Risks

- Weak taste/overdesign from AI: no unilateral design decisions.
- Screen proliferation: enforce approval gates and prioritize primary tasks.
- Excessive Codex use: two concurrent subagents maximum, inexpensive
  implementation agents, milestone QA only, targeted tests during edits.
- Performance: avoid unmeasured blur, animations and heavy recomposition.
- Platform drift: iOS source adapted but never marked Apple-tested.
- Concurrent v0.4 work: separate worktree; integrate only after reviewed.

## Validation plan

For each screen: record human-approved design link, source diff, exact
Android test commands, real emulator/device screenshot path, accessibility
and interaction findings, QA report and explicit human accept/reject.
Before finalizing v0.6: relevant shared tests, Android instrumented tests,
debug/release, lint, visual and haptic checks on physical hardware where
available. No fabricated times or measurements. Document skipped checks.

## Progress log

- 2026-11-05: Plan authored for owner review. No v0.6 UI implementation
  or tests claimed. Branch and files must be applied in the user's repo.

## Decisions and exit verification

- Owner retains sole final visual/interaction approval.
- Coordinator owns architecture; implementer writes scoped code; QA is
  independent and evidence-driven.
- Never mark COMPLETE or move this plan to `plans/completed/` until all
  mandatory screens are explicitly accepted and test evidence is recorded.
