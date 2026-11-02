# Agent/model routing by task difficulty

Select available models by task complexity and current access, not an assumed vendor/model/version. Use independent review for cross-platform schema, security and release changes.

| Task | Typical reasoning | Required evidence |
|---|---|---|
| README/docs copy, focused UI bug | Medium | Diff + docs check |
| KMP shared goal/map spec implementation | Medium–high | Common tests, Android and iOS compile when available |
| Android Glance and WidgetKit bridges/entitlements | High | Native device/simulator behavior; Apple Mac gate |
| Cross-platform storage migrations and backup schema | High | Both-platform migration and import tests |
| Provider permissions/key architecture | High | Current written provider evidence, privacy review |
| RP split boundaries and observation math | High | Same-context/gap/rollover test vectors |
| iOS/Android release and legal audit | High/extra-high independent reviewer | Actual binaries, signatures, rights, human approval |
| OCR research | High research then scoped implementation | Measured privacy/accuracy and platform SDK constraints |

Preferred loop: **architect/spec reviewer → bounded implementer → independent QA → integrator**. No special slash command. Never claim tests, parallel agents or tools that did not actually run.
