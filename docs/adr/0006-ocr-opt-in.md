# ADR 0006 — Proposed on-device screenshot import (Android + iOS)

**Status:** Proposed for v0.4 research, not yet accepted to implement.

**Context:** Match-history APIs may be unavailable. User-owned results screenshots can contain legitimate data, but OCR can misread fields or include incidental usernames.

**Proposed decision:** Use Android Photo Picker or iOS PhotosPicker, local on-device OCR adapter, explicit edit-and-confirm flow and a transient draft that is erased on cancellation. Never auto-commit guessed stats or upload images by default. Evaluate OCR library licensing and per-platform availability before choosing engines.

**Alternatives:** Fully manual entry is an essential lower-complexity fallback. Cloud OCR requires a materially different privacy/cost model and is not in the committed scope.

**Consequences:** Two platform picker/OCR integrations, binary size/privacy and accuracy testing; shared parsing and match-schema logic still reusable.

**Validation before acceptance:** representative rights-cleared corpus, field-specific error rates, opt-in privacy, cross-platform cancellation/duplicate tests and OSS/F-Droid licensing assessment.
