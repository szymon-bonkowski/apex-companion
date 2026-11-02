# Repository-local agent skills

`AGENTS.md` contains global invariants; `docs/specs/` defines **what** to implement; skills define focused, repeatable workflows. Pick 1–3 relevant skills per task. These are in-repository agent procedures, not a ChatGPT Skill installer.

| Skill | Main use |
|---|---|
| [project-bootstrap](../.agents/skills/project-bootstrap/SKILL.md) | Inspect existing KMP Wizard project, toolchain and platform builds |
| [implement-spec](../.agents/skills/implement-spec/SKILL.md) | KMP spec-to-code feature implementation |
| [multiplatform-storage](../.agents/skills/multiplatform-storage/SKILL.md) | Cross-platform durable data, backup and widget projections |
| [widget-builder](../.agents/skills/widget-builder/SKILL.md) | Android Glance and iOS WidgetKit widget contracts |
| [ios-widgetkit](../.agents/skills/ios-widgetkit/SKILL.md) | Xcode extension, App Group and SwiftUI integration |
| [mobile-test-matrix](../.agents/skills/mobile-test-matrix/SKILL.md) | Android/iOS manual + automated regression matrix |
| [ranked-observation-review](../.agents/skills/ranked-observation-review/SKILL.md) | RP interval/goal semantics |
| [api-contract-audit](../.agents/skills/api-contract-audit/SKILL.md) | Provider source terms, fixtures and secret gate |
| [screenshot-import](../.agents/skills/screenshot-import/SKILL.md) | Android/iOS on-device OCR after v0.4 gate |
| [design-qa](../.agents/skills/design-qa/SKILL.md) | Original, accessible Compose/Glance/WidgetKit visuals |
| [security-and-legal-review](../.agents/skills/security-and-legal-review/SKILL.md) | App privacy, EA/IP and provider security |
| [release-qualification](../.agents/skills/release-qualification/SKILL.md) | Android APK and iOS distribution readiness |
| [architecture-review](../.agents/skills/architecture-review/SKILL.md) | Significant KMP/ADR decisions |
| [docs-sync](../.agents/skills/docs-sync/SKILL.md) | Project-wide link and plan coherence |

All 14 skills have `SKILL.md` YAML frontmatter and `agents/openai.yaml` metadata. They do not claim code or tests already exist.
