# Privacy and security model — Android + iOS

## Threats and mitigations

| Threat | Mitigation | Verification |
|---|---|---|
| Shared upstream API key extracted from Android APK or iOS IPA/extension | Never distribute keys; gated permitted access topology | Artifact/extension scan; public rights review |
| Open proxy enables API abuse | Provider-approved bounded gateway/cache, identity abuse prevention; otherwise no backend | Load/abuse/cost test |
| UID, goals and sessions in logs | Redact identifiers; telemetry off | Log inspection on both platforms |
| Screenshot/privacy leak | Android Photo Picker/iOS PhotosPicker; local OCR, explicit consent, discard draft | Permissions, iOS Info.plist, network monitoring |
| Hostile JSON import | Strict max size/schema/data validation, staging, atomic commit | Fault injection and migrations on both OSes |
| Export accidentally includes secrets | Explicit allowlist of user records; no tokens, screenshots, App Group paths | Cross-platform fixture tests |
| Widget extension reads app-private data or leaks tokens | Versioned minimal App Group snapshot only; shared entitlement | Xcode capabilities/signature/data tests |
| Incorrect cached map displayed live | Validity time checks and truthful stale state in app and widgets | Offline-expired and UTC tests |
| Malicious dependency/build scripts | Pin actual verified toolchain, review packages, signed releases | Dependency inventory and CI audit |

## Local data

Local by default: manual RP goals, optional linked identity, cached map observations, opted-in session history, confirmed match records, widget appearance and screenshot drafts awaiting user confirmation. Widget snapshots are minimized, **never** copied wholesale from DB to App Group. Remote lookups use only explicitly linked public profile identity/mode when authorized. Public game UID can still be personal data; don't upload or publish query history.

No ads, mandatory accounts, analytics identifiers, crash-upload SDKs or undisclosed background telemetry. Player can unlink profile, export and delete local records. Deleting all data also removes iOS App Group widget snapshots and clears Android widget-visible cache; externally exported backup remains user-owned. Android uninstall and iOS app deletion normally remove their app-scoped data subject to OS backup/restore policies.

## Permissions and prompts

Use OS system document pickers for backup, Android Photo Picker and iOS PhotosPicker for optional screenshot OCR, and request notification authorization only for opted-in alerts. No blanket photo-storage or gameplay surveillance permission. Document entitlements and background behavior in app privacy statement.

## Credential modes

- **Developer experiments:** locally supplied out-of-repo key, only with provider authorization; do not log query/header or publish debug builds containing a key.
- **Public builds:** no shared upstream credential anywhere in app, Android resources, Apple entitlement or WidgetKit extension; use only expressly permitted access model.
- **Not allowed:** obfuscation/JNI as secret protection, unbounded BYOK proxy, bypassing provider quotas, hidden server collection or EA login password requests.

Respond to secret exposure by revoking it, reviewing distributed artifacts and assessing impact; deleting a git line alone is insufficient. See [SECURITY.md](../SECURITY.md).
