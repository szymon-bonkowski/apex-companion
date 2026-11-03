# ExecPlan: provider API feasibility and distribution permission

**Status:** IN PROGRESS — external access/rights blocked. **Opened:** 2026-11-02. **Owner:** API research agent / maintainer. **Scope:** research and controlled tests only, **not** public rollout. **References:** [API integration](../../docs/API_INTEGRATION.md), [sources](../../docs/SOURCES.md), ADR 0002.

## Goal

Convert the documented Apex Legends Status endpoints into a validated, dated adapter contract and determine a lawful/sustainable **public** data access path, without exposing keys or committing to an unaffordable service.

## Starting evidence

The public API portal documents `maprotation?version=2`, `bridge`, `predator`, `servers`; standard key limit 5 requests/sec; one key per project/person and fair-use conditions. Both match history options currently unavailable to new developers. No developer API key, permission email or live schema fixture has been provided.

## Work plan

- [x] P1: Re-read current API docs and terms, capture date and endpoint-specific attribution.
- [ ] P2: Find out whether a public Android+iOS OSS companion may redistribute map/profile data; record exact written authorization or absence.
- [x] P3: With a deliberately supplied private developer key **only if available**, execute low-rate opt-in requests and save sanitized synthetic/reproducible fixtures; no key in URL logs/source.
- [x] P4: Validate v2 mode mapping, timestamps, unknown fields and a schema-contract test plan.
- [ ] P5: Design/price a bounded shared cache/gateway **only if provider explicitly permits it**; include abuse and privacy review.
- [ ] P6: Write a go/no-go decision for live online public features; record unresolved items, update `docs/OPEN_QUESTIONS.md` and ADR only when justified.

## Validation

No credential use without authorization. `403`/`429` and malformed-payload contract test cases must be reproducible from fixtures. Public live access cannot be declared approved based on successful local API call alone. If no key or permission exists, research may conclude **NO-GO** and close this research plan after documenting evidence, while online feature stays blocked.

## Progress log

- 2026-11-02: task staged. Public documentation researched; no live requests performed.

## Decision log

- Initial conservative assumption: offline mode can be released; remote-enabled public Android and iOS releases remain gated.

## Exit verification

Pending provider terms review and actual evidence collection. Never mark the remote feature implemented by completing this research plan.

2026-11-02 implementation review: Re-read provider portal. Endpoint host/auth/version/fair-use/initial 5 req/s verified from public docs; no complete response schema or public redistribution grant established. Owner explicitly confirmed no approved access and will seek it. No private credential request or API call made. Synthetic parser/transport tests implemented independently. Public runtime remains NO-GO, default unavailable provider and disabled network work. P2/P3 require external evidence; no gateway deployed or priced. See [actual implementation report](../../docs/validation/v0.1-report.md).

2026-11-02 update: owner supplied developer credential outside repo and completed mandatory provider Discord verification. Private documented curl request and production Ktor adapter returned HTTP200; four playlists and current/next/activity/event schema verified. Android debug privately provisioned via stdin successfully displayed real schedules, retained cache STALE offline and recovered LIVE online. No key in debug/release/test APKs; release has no developer reader. Public Android+iOS redistribution grant/topology remains unresolved: NO-GO for public live activation, private developer testing permitted. No proxy deployed. P2/P5/public gate remain external work; map milestone can close under its explicitly authorized-developer alternative.

2026-11-03 ranked update: authorized `/bridge?version=5` name→UID/direct UID and `/predator` production-client contracts passed; opt-in Android UI/real offline/cache/disconnect passed. [Observed ranked contract](../../docs/validation/v0.2-provider-contract.md). No public grant, proxy or release activation is inferred; P2/P5/public gate remain unresolved. This research plan stays active independently of completed local developer milestones.
