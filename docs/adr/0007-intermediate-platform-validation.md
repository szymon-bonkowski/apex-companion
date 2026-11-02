# ADR 0007 — Defer iOS verification until final stabilization

Status: Accepted by explicit owner instruction, 2026-11-02.

## Context and conflict
The v0.1 objective explicitly requires Arch Linux development and forbids all intermediate iOS builds/tests, simulator/device runs and attempts to invoke Apple tools. Earlier ADR 0001, architecture, planning and v0.0 completion text required iOS evidence at each milestone. That timing conflicts with the owner's current instruction; platform support and final release gates remain unchanged.

## Decision
Android and Linux-compatible shared KMP build/tests, Compose UI and Android native widget verification remain intermediate acceptance requirements. Implement feasible iOS adapters/Compose host/WidgetKit/Xcode target configuration as source. Mark source `Implemented — Not Verified`; do not attempt Apple compilation, tests or tooling during intermediate milestones. Missing iOS evidence alone does not block v0.1 or later intermediate completion. Keep `Planned` for absent code and `Blocked` for unavailable implementation dependencies. No Android result establishes iOS compatibility.

All Kotlin/Native, Swift, Xcode, App Group/provisioning, WidgetKit runtime, accessibility and device checks move to final stabilization/release preparation and remain mandatory before claiming verified Apple support or publishing an iOS release. This supersedes only the timing/completion requirements of earlier documents, not equal Android+iOS product scope, shared/native boundaries or release security.

## Validation and consequences
Intermediate reports identify exact Android/shared commands and iOS source uncertainties. Final stabilization must obtain actual Apple evidence. Provider permission/topology and real-response verification remain separate blockers; this decision never authorizes fabricated live data or embedded keys.
