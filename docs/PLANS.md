# Living ExecPlan protocol

Use for implementation spanning multiple changes/files, migrations, platform widgets, provider security, or changed requirements. Tiny isolated fixes need no plan if they don't affect architecture or accepted specs.

## Lifecycle

- Create `plans/active/YYYY-MM-DD-short-slug.md` before substantial work; include Goal, Value, Source of truth, Starting state, Scope/non-scope, Platform-specific ownership, Approach, Risks, Ordered checklist, Validation, Progress log, Decisions and Exit verification.
- Keep the plan living: log exact tasks run and results/obstacles; do not transform document intent into claimed software progress.
- Mark `NOT STARTED`, `IN PROGRESS`, `BLOCKED`, `VERIFIED` and `COMPLETE` with evidence; move to `plans/completed/` only when every milestone-required criterion has evidence, allowing explicitly deferred iOS checks under ADR 0007.
- During intermediate milestones, do not attempt iOS builds/tests/tools. Mark implemented iOS source `Implemented — Not Verified`; missing iOS evidence alone does not block completion. All iOS validation moves to final stabilization under ADR 0007. Missing Android/shared implementation or provider dependencies remain blockers.
- If two independent implementations need overlapping modifications, serialize the single source-of-truth change (schema/spec/ADR) and delegate only non-conflicting files in parallel.

## Useful example

*Implement shared RP goal arithmetic in commonMain and both native goal widgets. Add deterministic goal tests, serialize one `MANUAL` widget projection, validate Android Glance launcher, verify WidgetKit app-group read on Xcode simulator, and record which actual checks executed.*

An agent never promotes pack/heirloom work from excluded scope or changes KMP platforms merely because a task description is shorter. Keep any research blocked on API rights separate from offline implementation.

## Human-directed design approval gates (v0.6+)

For visual work, the ExecPlan must record the owner-approved design
reference, interaction contract, allowed files and test/evidence for each
screen or widget. Completing a compile/test checklist does not establish
aesthetic acceptance. Keep status blocked/unapproved until the maintainer
explicitly signs off on real UI screenshots and interactions.

Prefer one bounded implementation task per approved screen; independent
agents may only work concurrently on non-overlapping scopes. Run strong
QA at useful review gates, use a lower-cost implementer for straightforward
coding, and keep full Android/shared regression at integration milestones.
