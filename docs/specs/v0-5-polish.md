# v0.5 specification — cross-platform polish and reliability

**Status:** Planned. **Dependencies:** earlier core/map/Ranked widgets and offline fallback; OCR optional behind own gate.

## Scope

- Original consistent design in shared Compose screens and native Android/iOS widgets; independent theme/opacity/preset configuration.
- Responsive mobile/tablet layouts, meaningful Touch targets, Material You where supported and native iOS readability conventions.
- TalkBack, VoiceOver, large fonts and reduce-motion support; clear `DEMO/STALE/UNAVAILABLE` in smallest widget families.
- Background and network budget testing with 1, 3 and 10 widgets on both OSes, recognizing different OS scheduling semantics.
- Favorite-map notifications only opt-in, subject to Android notification permissions and iOS authorization/OS delivery timing.
- Better onboarding/error copy; backup schema migration and corruption QA; privacy and legal review.

## Acceptance

1. Android launchers and iOS WidgetKit families display critical value and state at available sizes.
2. Accessibility evidence from TalkBack/VoiceOver and largest practical dynamic text configuration.
3. No permanent background polling solely to animate countdowns; multi-widget refresh does not multiply provider calls.
4. Provider unavailable leaves manual Ranked goal and DEMO map usable offline.
5. Source expiry/DST/airplane mode tested on both platforms; iOS App Group fallback remains robust.
6. Screenshot-based visual QA includes actual OS/device/widget family, defects and fixes.
7. No copied Wingman UI or unlicensed game art in Android or iOS distributable.
