# ADR 0003 — Ranked RP delta is an interval, not a match

**Status:** Accepted 2026-11-02.

**Context:** Periodic profile queries do not reliably expose individual games; multiple games can happen between readings. Complete match APIs may not be available to new clients.

**Decision:** Persist timestamped context-bound RP observations and compute differences only for compatible verified player/mode/season/split. Missing or crossed context is explicitly unknown. Only user-confirmed records or genuinely authorized per-match feeds can become MatchRecords. Shared KMP logic enforces this on Android and iOS.

**Alternatives:** Guessing match counts from RP deltas creates false statistics. Suppressing all observations loses meaningful aggregate progress.

**Consequences:** Conservative but trustworthy progress, with manual match entry/OCR as a separate future feature.

**Validation:** Same RP across samples, negative, 0, split reset, missing context and out-of-order tests in common Kotlin test suite and both platforms' UI states.
