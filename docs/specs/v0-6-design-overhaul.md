# v0.6 - Human-Directed Design Overhaul

**Status:** Planned; design implementation must not begin before owner
approval of the specific screen reference and interaction specification.
**Baseline:** v0.5.0-dev (the v0.5 functional milestone); v0.4 Windows
Auto Capture remains separately unverified and unfinished.

## Objective

Replace v0.5's technically functional but visually unsatisfactory
experience with an original, simple, delightful, cohesive, truly native-
feeling Apex Companion. The maintainer directs the design, while Codex
implements and verifies approved decisions. No automatic generative
redesign of the whole app.

## Sources of truth and precedence

1. Existing safety/legal/platform/provider invariants and accepted ADRs.
2. Explicit maintainer design decisions and approved screen references.
3. [Owner design brief](../design/DESIGN_BRIEF.md).
4. This v0.6 specification and the active ExecPlan.
5. Existing v0.5 tokens/components only where not superseded by approvals.

Design approval never permits a security violation or fake game data.
A technical/accessibility conflict must be escalated, not silently solved
by changing the approved visual design.

## Scope

### Phase A - Audit and navigation

- Inventory actual app screens, native widget families, actions, entry
  points, and all navigation paths. Identify duplicate controls, developer-
  facing labels, hidden primary actions and overly dense presentations.
- Ask the owner to approve a single coherent information architecture and
  navigation model, including back behavior and system insets.
- No implementation until the required owner design input is supplied.

### Phase B - Shared interaction foundations

- Build approved Compose Multiplatform components, precise state and
  typography roles, loading/error/empty patterns, predictable touch and
  accessibility behavior, with platform-specific adapters as justified.
- Preserve the existing durable appearance store, profile/history/match
  repositories, navigation destinations and data provenance.
- Add bounded, opt-in, platform-appropriate haptics for explicitly approved
  events, respecting reduced feedback preferences and OS capabilities.
- Use scalable navigation/components, not independent duplicated apps.

### Phase C - Individual screens, owner gated

Redesign only after explicit owner references are ready:

- Today / home and onboarding/empty state.
- Maps and next rotation.
- Ranked / goals / Predator.
- Matches / match details / statistics.
- History / sessions / profile.
- Capture pairing and sync.
- Settings / Appearance Studio / backup / About.
- Native Android Glance widget families and iOS WidgetKit source.

Each screen or widget has its own approval record, actual Android image
evidence, interaction checks, targeted tests and owner sign-off. Work can
proceed in a different order if the owner approves that order.

### Phase D - Four styles and personalization

Preserve Carbon, Liquid, Editorial and Tactical as selectable experiences,
subject to owner-approved visual designs. These are **not** four automatic
reskins: their distinctive hierarchy and component treatments must match
the references. Maintain shared domain behavior and safe appearance
persistence. Ensure customization never compensates for a bad default.

### Phase E - Polish and measured responsiveness

- Test clipping, touch areas, system bars, scroll restoration, navigation,
  large text, landscape/tablet, TalkBack and reduced motion/transparency.
- Test motion, taps and haptic feedback on real Android hardware when
  available; log actual observations and measurement environment.
- Do not claim 120 FPS or a maximum frametime without measured evidence.
- Validate widget placement, sizes and states on supported launchers.

## Technical debt to address without hijacking the design schedule

The v0.5 source review identified these *potential* risks (not all are
independently reproduced bugs). Fix them with isolated regression tests:

1. Match history sorting/filtering and possible-duplicate checks can be
   repeated during the application's one-second clock recomposition.
   Memoize/index or perform scoped projections; preserve match semantics.
2. iOS may publish default Carbon appearance before its local appearance
   repository initializes. Gate UI/projection on durable initialization.
   Implement source only, with iOS runtime validation deferred.
3. Concurrent favorite-map preference writes may be based on stale
   snapshots. Use atomic repository-level updates or serialized controls.
4. Appearance publication may reload native widgets unnecessarily even
   when the snapshot is unchanged. Preserve interrupted-write recovery
   while avoiding redundant work.

Separate such fixes from owner-facing visual approval. Test actual changes;
do not claim a diagnosis from static inspection alone.

## Out of scope

- Letting AI choose the final layout, art direction or product strategy.
- Starting another screen before owner approval of the previous one.
- Replacing Maps/Ranked/Matches/History provider semantics with fake values.
- Resuming unfinished v0.4 automatic capture without a separate plan.
- Public release, public API activation, store submission or iOS signing.
- Unapproved dependencies, game assets, paid services, ads, pack/heirloom.
- Full Apple toolchain validation during this intermediate milestone.

## Acceptance and evidence

- V06-01: Each changed screen has an owner-approved reference/interaction
  spec and explicit owner acceptance after seeing real implementation.
- V06-02: Primary/secondary navigation follows the approved architecture,
  with no duplicate navigation or inaccessible/clipped destinations.
- V06-03: Four selectable styles match their individually approved design
  references; personalization and saved settings still work.
- V06-04: Haptics/motion are purposeful, reversible, responsive and honor
  device accessibility settings. No fabricated measurements.
- V06-05: Existing functional Maps/Ranked/Predator/RP History/Sessions/
  Matches/backup/desktop sync/widget state boundaries are preserved.
- V06-06: Targeted tests per screen, Android shared/unit/instrumented
  regression, debug/release builds, lint and actual screenshot inspection.
- V06-07: Remaining unverified v0.4 capture and iOS Apple runtime checks
  remain explicitly labeled. iOS source is Implemented - Not Verified.
- V06-08: Final report lists approvals, actual comparisons, defects, test
  commands, platform boundaries and unresolved work.

Passing automated tests cannot substitute for human acceptance of visual
and interaction design. This milestone is not complete unless the owner
explicitly accepts the implemented screens/widgets.

## Execution and agent budget

Follow `docs/SUBAGENT.md` and `.codex/config.toml` where loaded. The main
coordinator uses the strong model chosen by the owner; ordinary scoped
implementers should use a lower-cost model (normally gpt-6-luna medium).
Use strong-model QA selectively at important review gates, not in a loop
after every cosmetic edit. No commits/pushes/tags without authorization.
