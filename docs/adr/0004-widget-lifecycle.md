# ADR 0004 — Native widget runtimes with shared KMP projections

**Status:** Accepted 2026-11-02.

**Context:** Home-screen widgets are central to Apex Companion, but Android Glance and iOS WidgetKit have different lifecycles, storage access and OS-enforced update budgets. Shared Compose UI does not directly implement iOS WidgetKit or Android Glance widgets.

**Decision:** KMP projects a small versioned source-attributed `WidgetSnapshot`. App persists it before notifying native widgets. Android uses Glance with cached persistence and best-effort WorkManager; iOS uses SwiftUI WidgetKit extension and an App Group shared snapshot with timeline/reload requests. Neither widget fetches per-instance upstream data. Prefer truthful map end timestamps over second-accurate promises.

**Alternatives:** Per-widget API polling, always-on service, putting shared Compose inside WidgetKit or relying on app-private DB from extension are fragile, blocked or wasteful.

**Consequences:** OS may defer updates; platform-specific styling and validation required, plus Xcode entitlement/provisioning work. Product semantics remain consistent through shared snapshot versioning.

**Validation:** Android launcher and iOS WidgetKit process death, resizing/families, two independent instances, App Group access, offline expiration, DST and provider outage; no per-widget request storm.
