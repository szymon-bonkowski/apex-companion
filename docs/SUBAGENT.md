# Subagent policy — Apex Companion

The owner delegates code, validation and research to AI agents. Parallelism is for **independent** work only; accepted requirements/ADRs cannot be overwritten by a subordinate agent. No agent may claim an unrun device test or unattended publication.

## Roles

| Role | Scope | Evidence/result | Must not do alone |
|---|---|---|---|
| KMP/shared implementer | `shared` domain, UI, common tests | Compilable shared code and test report | Make Android-only dependencies common |
| Android integrator | `androidApp`, Glance, WorkManager, Android picker | APK, launcher tests | Invent iOS readiness |
| iOS integrator | `iosApp`, Xcode WidgetKit, App Group and Swift bridges | Xcode build/simulator and entitlement evidence | Claim generic unsigned IPA install |
| Storage implementer | Cross-platform adapters and migration tests | Round-trip/atomicity on both OSes | Rewrite shared schema silently |
| API researcher | Sources, permissions, fixtures | Dated redacted findings | Publish or bypass provider rights |
| QA/accessibility | TalkBack, VoiceOver, native widget sizes | Device/simulator and test report | Mark tests passed without access |
| Reviewer/architect | ADR impacts, privacy, release readiness | Independent findings | Push/publish without approval |
| Integrator | Merge, tests, docs/plan consistency | Verified scoped diff | Ignore failure or quietly delete iOS scope |

## v0.6 cost-aware roles and design ownership

The **coordinator** is the architecture/integration decision-maker and uses
the strong model selected in the primary Codex session (e.g. GPT-6.1 Sol
High). The maintainer alone approves visual/product decisions.

Use `.codex/agents/implementer.toml` (`gpt-6-luna`, medium) for bounded code
tasks, including Dashboard and native Widgets once a design is approved.
An implementation agent may test its scope but must not invent missing
design direction or make major schema/security decisions without escalation.
Use `.codex/agents/quality-assurance.toml` (`gpt-6.1-sol`, high) for
independent, periodic code/security/accessibility/behavior review. QA must
not self-approve visual taste; it checks objective differences against the
owner's approved references and records actual test evidence.

`[agents]` in `.codex/config.toml` limits concurrent child threads to 2
and defaults unnamed subagents to Luna Medium. Custom agent role files and
explicit spawn overrides may take precedence. Confirm actual model/effort
in Codex before starting expensive tasks. Unavailable model IDs must be
replaced with models actually offered by the signed-in account.

Cost policy:
- Prefer no subagent for a short/sequential task.
- Assign explicit allowed files and finish criteria; avoid full-context
  duplication and overlapping edit ownership.
- Run cheap automated checks during coding; request strong QA after a
  meaningful integration stage, not every pixel adjustment.
- Run the full test matrix at milestone gates and after high-risk changes.
- Report any high-model escalation and the reason.

During intermediate milestones, iOS source is Implemented - Not Verified;
do not launch Apple tooling. Never push/tag/merge without owner permission.

## Delegation request template

Specify `Goal`, `Input documents`, `Allowed files/modules`, `Out of scope`, `Dependencies`, `Tests to run`, `Expected output`, and `Escalation if blocked`. For example:

```text
Goal: Implement KMP manual Ranked goal math and projection tests.
Inputs: docs/specs/v0-0-offline-vertical-slice.md, docs/DATA_MODEL.md.
Allowed: shared/commonMain feature/ranked + commonTest only.
Out of scope: native widgets, API, platform Gradle changes, pack/heirloom features.
Acceptance: current 13,460 target 16,000 in 22 days => 116/day; 0-day/reached cases.
Report: changed files, exact test task/results, blockers and any ADR conflict.
```

Subagents may not receive production secrets or private player datasets. Serialize single-version schema changes and code edits to one Xcode project. If no subagent mechanism exists, perform roles sequentially and report honestly.
