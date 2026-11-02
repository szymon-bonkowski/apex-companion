# Feature feasibility and provenance matrix

This records planned product scope. The v0.1 map scope is implemented for private developer use; public activation is gated. For exact verification states see [v0.1 evidence](validation/v0.1-report.md). Product target is Android + iOS; KMP shares logic but not native widgets.

| Capability | Input | First milestone | Gate |
|---|---|---|---|
| Manual Ranked goal and RP/day | User-entered RP/deadline | v0.0 | Fully offline; shared calculation |
| DEMO map and schedule | Synthetic clearly labeled fixtures | v0.0 | Both platforms and widgets indicate DEMO |
| Map/goal native widgets | Shared `WidgetSnapshot` | v0.0 | Glance + WidgetKit; iOS Xcode signing/App Group |
| Live map and next change | `/maprotation?version=2` | v0.1 | Source access, permission, verified schema, cache |
| Live profile/rank/RP | `/bridge` authorized source | v0.2 | UID/platform correct; missing fields gracefully handled |
| Predator threshold | `/predator` | v0.2 | Provider policy and source validation |
| Server health | `/servers` | v0.2 | Source, timestamp, approved access |
| RP delta and sessions | Consecutive compatible observations | v0.3 | Only interval delta, never individual match count |
| Actual match stats | Confirmed manual entry / on-device OCR | v0.4 | User review and duplicate protection |
| Map/legend performance | Confirmed MatchRecords | v0.4+ | No inference from RP polls |
| Actual split end / rank thresholds | Verified source or manual entry | v0.2+ | Versioned evidence; otherwise unknown |
| Original themes, accessibility | Shared UI and native widget family variants | v0.5 | Both mobile platforms tested |
| iOS distribution | Xcode signing/TestFlight/App Store route | v1.0 | Apple account/permissions and store policies |
| Android APK distribution | Signed APK with checksum | v1.0 | Release security and source terms |
| Event/ALGS/store/squad extras | Additional sources and user consent | Future | Separate evidence and scope decision |

**Not planned:** packs/heirloom tracker (withdrawn); no code/schema/widget should be created for it.
