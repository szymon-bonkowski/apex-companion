# Feature feasibility and provenance matrix

This records product scope and explicit capability gates. See the [v0.5 report](validation/v0.5-report.md) for current design/personalization evidence. v0.1 Maps, v0.2 Ranked/Profile/goals/Predator/widgets and v0.3 private observations/sessions/trends/backup are implemented for private developer use; public activation is gated. For exact verification states see [v0.3 evidence](validation/v0.3-report.md). Product target is Android + iOS; KMP shares logic but not native widgets.

| Capability | Input | First milestone | Gate |
|---|---|---|---|
| Manual Ranked goal and RP/day | User-entered RP/deadline | v0.0 | Fully offline; shared calculation |
| DEMO map and schedule | Synthetic clearly labeled fixtures | v0.0 | Both platforms and widgets indicate DEMO |
| Map/goal native widgets | Shared `WidgetSnapshot` | v0.0 | Glance + WidgetKit; iOS Xcode signing/App Group |
| Live map and next change | `/maprotation?version=2` | v0.1 | Source access, permission, verified schema, cache |
| Live profile/rank/RP | `/bridge` authorized source | v0.2 | UID/platform correct; missing fields gracefully handled |
| Predator threshold | `/predator` | v0.2 | Provider policy and source validation |
| Server health | `/servers` | Optional future scope | Not implemented in v0.2; source, timestamp, approved access required |
| RP delta and sessions | Opted-in compatible observations and explicit user session boundaries | v0.3 | Implemented; user-confirmed split needed when provider lacks one; unknown intervals abstain |
| Portable backup/restore | Allowlisted user settings/history via native document pickers | v0.3 | Implemented; strict bounded validation/preview/confirmation and one atomic commit; iOS source unverified |
| Actual match journal | Manual/screenshot-assisted fields and local Windows evidence | v0.4 | Shared source/quality/duplicate protection; real automatic capture not verified |
| Windows automatic capture | Consent WGC + local Tesseract | v0.4 | Runtime unverified; synthetic layout only; auto-accept disabled pending real corpus |
| Local match sync | Pinned HTTPS authenticated foreground pull | v0.4 | Explicit LAN pairing, native protected secrets, durable cursor/retry |
| Mobile screenshot OCR | Native Photo Picker / PhotosUI draft | v0.4 | Picker/manual draft implemented; native OCR engine and real corpus blocked |
| Map/legend performance | Confirmed default / explicit AUTO_ACCEPTED cohort | v0.4 | Field denominators and provenance; no inference from RP polls |
| Actual split end / rank thresholds | Verified source or manual entry | v0.2+ | Versioned evidence; otherwise unknown |
| Four designs, Studio, Today and widget appearance | Shared resolved tokens + independent cosmetic store + native per-instance variants | v0.5 | Android/shared evidence in v0.5 report; iOS Implemented — Not Verified |
| iOS distribution | Xcode signing/TestFlight/App Store route | v1.0 | Apple account/permissions and store policies |
| Android APK distribution | Signed APK with checksum | v1.0 | Release security and source terms |
| Event/ALGS/store/squad extras | Additional sources and user consent | Future | Separate evidence and scope decision |

**Not planned:** packs/heirloom tracker (withdrawn); no code/schema/widget should be created for it.

Favorite-map notification preferences exist with delivery explicitly disabled while authorized trustworthy future scheduling is unresolved. No speculative/DEMO alerts or extra polling. [v0.5 evidence](validation/v0.5-report.md).
