# v0.4 specification — user-confirmed match journal and optional OCR

**Status:** Research-gated. **Dependencies:** durable history v0.3 and [OCR design](../OCR_IMPORT.md).

## User flow

1. Player may enter a match manually (mode, optional map/legend, kills, assists, damage, placement, RP delta, timestamp) with explicit user source.
2. Player may choose a results screenshot via **Android system Photo Picker** or **iOS PhotosPicker**; do not request full photo library access by default.
3. Platform-local on-device OCR suggests fields and confidence, keeping missing/ambiguous data blank. No cloud upload.
4. Player reviews/edits all parsed fields and explicitly confirms or cancels. Only confirmation creates `MatchRecord`.
5. Potential duplicate image/signature prompts review, does not silently discard user edits.
6. Aggregates for legend/map are computed only from confirmed records, not sampled RP intervals.

## Acceptance

- Cancel/import error leaves no new record and clears unneeded temporary image/draft.
- False OCR value remains editable and never auto-commits.
- Repeat import warns on duplicate; final import has one confirmed record.
- Both platforms' pickers, permissions and selected OCR license/on-device execution audited.
- Measured OCR field accuracy documented from representative rights-cleared corpus; functionality works without OCR by manual entry.
- No screenshot or incidental username uploaded without explicit separate consent.
