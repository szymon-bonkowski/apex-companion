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

## v0.3 history and backup controls

History recording is off by default and explicitly enabled in shared Settings. Only validated accepted refreshes or explicit manual saves record evidence; no chart/session/widget initiates polling. Private history retains RP, UTC times, optional UID/platform, context/source and session boundaries. Disconnect hides the disconnected identity's retained history and closes tracking; another profile cannot see it. Confirmed history deletion removes all observation/session/context/import records and turns recording off while preserving independent goals/manual RP and current/global caches. External exported backups are not removed.

Private history/settings use a separate bounded 4 MiB atomic record, excluded from Android cloud backup/device transfer (including .new/.bak) and a backup-excluded private iOS directory. No history enters the WidgetKit App Group. Explicit native document export uses an allowlist; import validates bounds/schema/relationships/identity, previews warnings and commits one full record only after confirmation. UIDs can be present in a backup; users choose its location. Provider secrets/configuration/responses/caches, private paths, screenshots, diagnostic logs and OS widget IDs are excluded. Public access gates and permissions are unchanged. Backups are unsigned user-controlled data; restoring recorded provenance does not independently authenticate an upstream event. iOS source is Implemented — Not Verified.

## v0.4 match collection boundary

Selected-window capture only after explicit desktop activation/system consent; immediate stop, no hooks/process access/injection/driver/packets/game inputs or bypass. No default frame/video/image storage, screen upload, analytics or incidental OCR-name retention. Tesseract is local; actual game capture/EA permission is not established by the OS API. Autoaccept remains disabled without real-screen validation. [ADR0009](adr/0009-windows-match-capture.md).

Local sync is opt-in private IPv4 HTTPS with exact out-of-band DER SHA256 certificate pin and random256bit bearer credential, no arbitrary certificates/redirects/public listeners. Keystore/Keychain/DPAPI protect pairing; backups exclude all credentials/configuration and images. PC revocation invalidates tokens; offline local unpair explains remote revoke. Sync accepts only the paired identity and original event namespace, bounded schema and batches, commits before ACK and never overwrites corrections. Match-only deletion preserves replay tombstones/cursors, while RP history deletion stays separate. No provider key, new public game route, cloud, mandatory account or telemetry added. iOS local-network consent and protected storage require later native validation.

Android17/targetSDK37 requires declared `ACCESS_LOCAL_NETWORK` and an explicit runtime prompt when the user pairs; denied/revoked permission fails without attempting LAN requests. The app requests no gallery permission. Foreground retries are bounded every30seconds only while STARTED and paired, canceled on stop. [Android local-network policy](https://developer.android.com/privacy-and-security/local-network-permission). No network discovery/UPnP or broad Wi-Fi scanning is implemented.
