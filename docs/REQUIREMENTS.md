# Requirements and acceptance contract

Priority: **P0** first offline vertical slice, **P1** authorized online/ranked beta, **P2** optional later. All requirements apply on Android **and** iOS unless they explicitly name a native platform implementation. A test is passed only when actually executed, not inferred from docs.

| ID | Pri | Functional requirement | Acceptance |
|---|---|---|---|
| FR-001 | P0 | Manual ranked RP goal (current RP, target, deadline) | Same deterministic rounded result on Android and iOS for edge cases |
| FR-002 | P0 | Offline map schedule with permanently visible DEMO label | No synthetic schedule anywhere represented as live |
| FR-003 | P0 | Native map now/next widget on Android and iOS | Glance on Android, WidgetKit extension on iOS; both read persisted versioned snapshots |
| FR-004 | P0 | Native ranked goal widget on Android and iOS | Shows calculated target with `MANUAL` provenance, independent instance configuration |
| FR-005 | P0 | Reusable app UI: Today, Maps, Ranked, Settings and About | Screen and logic work offline on both platforms |
| FR-006 | P0 | Settings plus export/import via system document pickers | Validated versioned JSON round-trip; no partial mutation on malformed import |
| FR-007 | P0 | Original themes, widget sizes and accessible state labels | Device/simulator contrast, Dynamic Type and screen reader evidence |
| FR-008 | P1 | Authorized map rotation from a documented provider | Normalized modes/times, stale boundary, source, 403/429 and schema handling |
| FR-009 | P1 | Linked public profile with rank/RP | UID/platform, incomplete/unknown/error states and unlink |
| FR-010 | P1 | Cached Ranked goal and progress widgets | Freshness/source and correct manual fallback |
| FR-011 | P1 | Manual session and timestamped RP observation intervals | Intervals never turn into fabricated matches; split resets handled |
| FR-012 | P1 | Optional favorite-map notifications | Permission/opt-in and platform-specific best-effort timing |
| FR-013 | P1 | Predator cutoff and server status, if permitted | Mode/platform/source/time explicit, rate-budgeted |
| FR-014 | P0 | Match journal, Windows result capture and secure local sync; screenshot OCR fallback | ADR0009 machine provenance; auto-accept only measured validated layouts, uncertain stays review; picker privacy checks |
| FR-015 | P1 | Map/legend aggregate insights based on saved eligible matches | Confirmed default; explicit automatic policy, provenance and field denominators visible |
| FR-016 | P2 | Season recap share cards | Original assets, user opt-in sharing |
| FR-017 | P2 | Squad overview | Limited explicitly added identities, compliant query budget |
| FR-018 | P2 | Wear OS companion | Separate Android-only optional extension, not required for iOS parity |

The maintainer v0.4 scope extension (ADR0009) promotes FR-014 to P0 and FR-015 to P1 for that milestone: automatic Windows collection and secure phone sync are the primary objective. This does not activate public providers or convert RP intervals into matches.

## Cross-platform nonfunctional requirements

| ID | Requirement | Validation |
|---|---|---|
| NFR-001 | No provider secrets in APK, IPA or WidgetKit extension | Scan distributables and repo; authorization check |
| NFR-002 | Every remote card/widget has source and freshness | Offline/expired/error UI snapshots on both OSes |
| NFR-003 | RP delta is an interval, never a match | KMP `commonTest` suite with split/zero/gap tests |
| NFR-004 | No required sign-in, ads, analytics or hidden uploads | Android manifest, iOS Info.plist and network audit |
| NFR-005 | App/widget data snapshots are persisted before reload | Device process-death, reboot, iOS widget extension lifecycle tests |
| NFR-006 | TalkBack and VoiceOver, scalable readable typography | Device/simulator manual test matrix |
| NFR-007 | Graceful 403/429/schema failure/backoff | Pure adapter fixture tests |
| NFR-008 | Original visual identity and accurate independence notice | Rights review and release checklist |
| NFR-009 | KMP shared logic and platform widget boundaries stay enforced | Module dependency and build review |
| NFR-010 | iOS builds/signing/extension entitlements verified on Mac before claiming parity | Xcode test/install evidence; blocked if Mac unavailable |
| NFR-011 | Import/export is versioned and atomic; data remains on device | Cross-platform fixture round-trip and corrupt import tests |
| NFR-012 | No packs/heirloom UI, data schema, widget or background tasks | Static scope audit + feature review |

## State model

`LIVE` = validated source freshness and validity; `STALE` = last known snapshot with age/failed refresh; `UNAVAILABLE` = no trustworthy value; `DEMO` = explicitly synthetic fixtures; `MANUAL` = user-provided inputs. Unknown values must not become zero. Season-specific thresholds and rotation pools must be source-backed and dated, not hardcoded.
