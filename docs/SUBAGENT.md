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
