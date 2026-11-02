# Open decisions and validation work

Open items have safe defaults; agents cannot silently decide paid/third-party access or public release. The Android+iOS KMP decision and Apex Companion name are **already adopted**, not open questions.

| ID | Open issue | Safe default / next validation | Blocks |
|---|---|---|---|
| OQ-001 | Public API redistribution permitted for Android/iOS? | Require written provider permission; offline-only otherwise | Public live data |
| OQ-002 | Bounded gateway cost/abuse/privacy? | No hosted gateway until explicitly authorized and sustainable | Public live data |
| OQ-003 | Apex Companion naming/trademark conflicts? | Original non-official visuals; search before public branding | Store listings/public branding |
| OQ-004 | Final Android `applicationId`, iOS bundle ID and signing team? | Owner chooses unique values in Wizard/Xcode; record actual IDs | Release identity |
| OQ-005 | Current map v2 payload and playlist mapping? | Approved local spike, sanitized fixtures | Live rotation |
| OQ-006 | Verified split deadlines and seasonal RP thresholds? | Manual goal and versioned source; no guesses | Auto season rules |
| OQ-007 | Apple Developer Program, signing, TestFlight/App Store route? | iOS source/simulator implementation first; no free IPA promise | Broad iOS distribution |
| OQ-008 | Compatible KMP local DB and backup adapters? | Verify against actual Wizard versions on both OSes | v0.0 data persistence |
| OQ-009 | iOS WidgetKit App Group entitlement/configuration? | Xcode native extension prototype, no private DB access | v0.0 iOS widgets |
| OQ-010 | Android/iOS widget exact countdown capability? | End timestamp and best-effort updates | Animated countdown |
| OQ-011 | ML Kit vs OSS OCR engine on iOS/Android? | Research per-platform SDK and licensing | Optional v0.4 OCR |
| OQ-012 | Authoritative exact per-match data? | Manual confirmation or explicitly authorized feed | Automatic per-match analytics |
| OQ-013 | Owner of future gateway / incident response? | None; publish offline-first | Live scale |

No pack/heirloom tracking solution should be invented as part of this open-question list. That feature is intentionally paused. Resolve questions with a dated evidence record and ADR when scope changes.
