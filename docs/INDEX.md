# Apex Companion documentation index

## Start implementation

[Agent contract](../AGENTS.md) → [KMP Wizard setup](KMP_SETUP.md) → [Architecture](ARCHITECTURE.md) → [Roadmap](ROADMAP.md) → [active v0.0 ExecPlan](../plans/active/2026-11-02-v0-0-offline-vertical-slice.md) → [v0.0 spec](specs/v0-0-offline-vertical-slice.md) → [skills index](SKILLS_INDEX.md).

## Product and technical contracts

- [Product definition](PRODUCT.md), [requirements](REQUIREMENTS.md), [feature feasibility](FEATURE_MATRIX.md) and [backlog](BACKLOG.md).
- [KMP system boundaries](ARCHITECTURE.md), [data model](DATA_MODEL.md), [Ranked semantics](RANKED_TRACKING.md), [API integration](API_INTEGRATION.md).
- [Android and iOS widgets](WIDGETS.md), [iOS WidgetKit extension](IOS_WIDGETS.md), [design system](DESIGN_SYSTEM.md).
- [On-device OCR design](OCR_IMPORT.md), [test strategy](TEST_STRATEGY.md) and [release/delivery](DELIVERY.md).

## Decisions and agent coordination

- [ADR register](ADR.md), [Open questions](OPEN_QUESTIONS.md), [source evidence](SOURCES.md).
- [Plans protocol](PLANS.md), [subagent policy](SUBAGENT.md), [model routing](MODEL_ROUTING.md), [Codex handoff](CODEX_HANDOFF.md).
- [Security/privacy](SECURITY_PRIVACY.md), [legal/assets](LEGAL_ASSETS.md) and [skills index](SKILLS_INDEX.md).
- [Version specifications](specs/) and [active execution plans](../plans/active/).

## Source-of-truth rules

Accepted ADR/Architecture outrank specs and active plans, after explicit owner/legal instructions. An implementation plan must not introduce a paused feature or silently switch Android+iOS architecture to Android-only. The Wizard-generated project exists. v0.1 Maps and v0.2 Ranked/Profile have Android/shared evidence; iOS source is Implemented — Not Verified under ADR0007. Remaining starter plans are not proof of implementation. Source descriptions are evidence, not automatic public redistribution permission.

## Current implementation evidence

- [v0.2 Ranked/Profile report](validation/v0.2-report.md), [provider contract](validation/v0.2-provider-contract.md), [machine-readable evidence](validation/v0.2-evidence.json).
- [v0.1 Maps report](validation/v0.1-report.md) and [remaining v0.0 scope](../plans/active/2026-11-02-v0-0-offline-vertical-slice.md).
