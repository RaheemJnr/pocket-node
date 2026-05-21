# Play Store Data Safety Form — Pocket Node

This document is the authoritative reference for the Data Safety section of the Pocket Node Play Console listing. The maintainer copies these answers into the Play Console form on every submission and updates this file when an answer changes.

Last reviewed against the Play Console form schema: 2026-05-21.

## Top-level questions

> **Does your app collect or share any of the required user data types?**

**No.**

Pocket Node does not collect or share any of the user data types Google enumerates (Location, Personal Info, Financial Info, Health and Fitness, Messages, Photos and Videos, Audio Files, Files and Docs, Calendar, Contacts, App Activity, Web Browsing, App Info and Performance, Device or Other IDs). All data the wallet works with is stored locally on the user's device and never transmitted to any server we operate.

> **Is all of the user data collected by your app encrypted in transit?**

**Yes.**

The only network traffic the app generates is:
- Peer-to-peer libp2p connections from the embedded CKB light client to public Nervos CKB nodes. The libp2p transport in use is Noise + Yamux over TCP, which encrypts all peer-to-peer traffic.
- HTTPS to GitHub's public Releases API for the optional in-app updater.
- HTTPS to public CKB block explorers when the user taps a transaction or block link.

> **Do you provide a way for users to request that their data is deleted?**

**Yes.** Two paths:

1. **Per-wallet deletion** from inside the app: Settings → Wallets → tap a wallet → Delete. This erases the wallet's keys, transaction cache, and preferences from the device.
2. **Full uninstall** from the Android system uninstaller, which removes all of the app's locally stored data.

Because no data is stored on a server, there is no remote deletion request flow. The Data Safety form notes "users can request that data is deleted" because both per-wallet and full-app deletion paths exist.

## Permissions disclosure

The Play Console asks for an explanation of each declared permission that has user-visible impact:

| Permission | Reason | User impact |
|------------|--------|-------------|
| `INTERNET` | The embedded CKB light client connects to public Nervos peers over libp2p to sync block headers, query cells, and broadcast transactions. | None beyond standard network usage. |
| `CAMERA` (optional) | The in-app QR code scanner reads recipient addresses. Camera frames are processed by an on-device barcode scanner; no frames are stored or transmitted. | Camera only activates when the user opens the QR scanner UI. |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` (optional) | Biometric unlock for the wallet. Backed by the Android Keystore; biometric templates never leave the device's secure hardware. | Only used after the user opts in. |
| `REQUEST_INSTALL_PACKAGES` (optional) | Required by the in-app updater to install a new APK downloaded from GitHub Releases. The user must explicitly grant this through system Settings before the install can proceed. | Off by default; the app surfaces a prompt and instructions when needed. |
| `POST_NOTIFICATIONS` (Android 13+) | Used by the optional background sync foreground service to display a system notification while syncing in the background. | Off by default; the user opts in. |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` | Required so the light client can keep syncing while the app is backgrounded, paired with the visible notification above. | No data exfiltration; the service does the same peer-to-peer work the foreground app does. |

The app does not declare permissions for Location, SMS, Contacts (Android system contacts), Calendar, Audio, or Files.

## "Why the app needs network access" (Google's specific Data Safety prompt)

The app needs network access for the embedded light client to function. The light client connects to public Nervos CKB peers over libp2p (Noise + Yamux + TCP) to:

- Download block headers and verify them against the chain's consensus rules.
- Query the chain for cells matching the user's registered lock scripts (this is how the wallet knows the user's balance and history).
- Broadcast signed transactions to the network.

No analytics, telemetry, or server-side endpoints operated by the publisher are contacted.

## Optional disclosures

Google's Data Safety form has a free-text "Privacy practices" link. Point this at the published Privacy Policy:

```
https://pocket-node.com/privacy
```

The Privacy Policy is mirrored at `docs/PRIVACY.md` in the source repository for users who want to review the source.

## Reviewer guidance (for the App Listing reviewer)

Two facts about Pocket Node that make the Data Safety answers unusually short:

1. **No publisher-operated servers.** Every byte the app sends leaves the device by user-initiated action (a transaction broadcast or a manual QR scan). There is no analytics endpoint, no auth server, no push notification gateway, no remote feature flag service, no remote logging.
2. **Open source.** The reviewer can verify every Data Safety claim by reading the source at `https://github.com/RaheemJnr/pocket-node`. The light client's network traffic lives in `external/ckb-light-client/`; the in-app updater lives in `android/app/src/main/java/com/rjnr/pocketnode/data/update/`; the QR scanner lives in `android/app/src/main/java/com/rjnr/pocketnode/ui/screens/scanner/`. There is no other network code.

## Change procedure

When any of these answers would change (for example, if the app adds analytics, a remote indexer, or a custodial backup), update this file in the same PR that introduces the change. The Play Console form must be re-submitted on the next release for the new disclosures to take effect.
