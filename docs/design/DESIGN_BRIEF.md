# Apex Companion - Owner-directed design brief (v0.6)

**Status:** Design direction approved as a working process; no individual v0.6
screen design is approved until the maintainer reviews its actual mockup.
**Owner:** Project maintainer. **Implementer:** Codex, under owner direction.

## Product aspiration

Create a mobile companion that people genuinely want to open again. Its
interaction quality should be effortless and memorable: clarity first,
satisfying tactile feedback, precise motion, quick responses, zero visual
defects, and a distinctive but familiar native mobile experience.

Duolingo is an example of product coherence, motivation, touch feedback and
ease of use, **not** a visual template to reproduce. Do not copy its art,
characters, sounds, layout or brand assets. Apex Companion remains original.

The maintainer judged the v0.5 visual result unacceptable, despite its
technical implementation and four selectable styles. The actual issues
include duplicated navigation, dense controls, oversized headings,
exposed internal state names, repetitive generic cards, insufficient
information hierarchy, and poor perceived polish. v0.6 is the deliberate
redesign, not another automatic theme-generation exercise.

## Decision authority

- The maintainer chooses information architecture, navigation, hierarchy,
  visual direction, component form, animation, haptics and final acceptance.
- Codex may explain feasibility, accessibility conflicts and platform
  limitations, but **must not implement a new design from its own taste**.
- A generated mockup, skill recommendation, passing UI test, contrast score
  or AI aesthetic rating is not design approval.
- Never self-approve, auto-select a style, or advance to another screen
  without a recorded owner decision.
- When the owner supplies designs, faithfully implement them within
  technical/accessibility constraints. Escalate conflicts for a decision.

## Core principles

1. **Understandable immediately.** Navigation, screen purpose and primary
   action are clear without onboarding, configuration or explanatory walls.
2. **One meaningful hierarchy.** No duplicated global navigation. Avoid
   excessive headings, badges, chips, dense cards or redundant labels.
3. **A quiet default.** Data is not a reason to show everything at once;
   essential information leads and detail is progressively disclosed.
4. **Native-feeling interaction.** Predictable gestures, responsive touch,
   platform-appropriate system bars, back behavior, focus and accessibility.
5. **Purposeful haptics and motion.** Feedback reinforces meaningful actions,
   never annoys, blocks input or violates reduced-motion preferences.
6. **Truthful data.** LIVE/STALE/UNAVAILABLE/DEMO/MANUAL and provenance
   remain understandable without dumping developer enum names into UI.
7. **Offline-first and private.** All prior security/provider boundaries
   remain intact; do not collect unnecessary analytics or user data.
8. **Excellent default before customization.** Every default screen must
   feel complete, with personalization as an option rather than a repair.
9. **One app, four design identities.** Carbon, Liquid, Editorial, Tactical
   remain user-selectable. Shared semantics can use different composition,
   typography and ornamentation where individually approved by the owner.
10. **Measured smoothness.** Emulator screenshots check layout; real-device
    interactions and frametime evidence check native feel. Never invent metrics.

## What is explicitly not an approval

- A feature being compilable or covered by tests.
- A theme having a different palette, font family or corner radius.
- An agent claiming that the UI is attractive or giving it 8/10.
- A polished static screenshot when the real interaction is confusing.
- A reference/mockup that has not been explicitly approved by the owner.

## Screen-by-screen delivery rule

For each screen or widget family:

1. The owner provides or approves a concrete reference and interaction
   specification. Missing decisions remain marked OPEN, not guessed.
2. The coordinator checks feasibility and accessibility, identifies
   interfaces, creates a bounded implementation task, and requests any
   necessary owner decision.
3. The implementer changes only the approved scope and runs targeted tests.
4. Produce **real Android emulator/device screenshots**, not concept renders,
   plus interaction evidence, and compare against the approved reference.
5. QA reviews objective defects and regression risks. The owner makes the
   final design acceptance decision.
6. Only then move on to the next screen. Track approval in the active plan.

Maintain one approval record per screen using
[SCREEN_REVIEW_TEMPLATE.md](SCREEN_REVIEW_TEMPLATE.md). Acceptance requires
explicit owner sign-off; silence is not consent.

## Open design decisions (owner to specify)

- Final primary navigation and grouping of Maps, Ranked, Matches, History,
  Sessions, Capture, Appearance and Settings.
- Default landing screen and progressive disclosure of secondary features.
- Precise geometry, typography, palette and spacing for each style.
- Animations, gestures and haptic patterns by interaction.
- Which screens and widgets are redesigned first.
- Whether the four style variants share an identical component composition
  or have carefully approved structural differences.

These are not permissions for Codex to choose arbitrary defaults.
