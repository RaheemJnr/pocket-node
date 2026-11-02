# Security Policy

## Reporting vulnerabilities privately

Please report suspected vulnerabilities through [GitHub private security advisories](https://github.com/RaheemJnr/pocket-node/security/advisories/new) or email [mumedian6@gmail.com](mailto:mumedian6@gmail.com). Either channel can be used for any severity. Do not post exploit details in public issues, discussions, logs, or pull requests before coordinated disclosure.

Include the affected version/platform, impact, and a minimal reproduction using synthetic wallet material. Never send a real recovery phrase, private key, PIN, wallet passphrase, signing credential, or unredacted wallet database. We aim to acknowledge reports within 48 hours; investigation and release timelines depend on the finding. Keep reports private while we agree on remediation and disclosure.

Key/mnemonic exposure, authentication bypass, transaction manipulation, unsafe storage/backups/logging, update verification, and embedded native bridge issues are in scope. Report CKB protocol issues privately to the Nervos Security Team at [security@nervos.org](mailto:security@nervos.org); application misuse of the protocol is still in scope here. A compromised OS, root/jailbreak, coercion, and social engineering are outside the protection guarantees below, but reports identifying an application defect remain welcome.

## Versions and release status

This policy was updated against the **unreleased maintenance branch on 2026-10-29**. The security model below describes that branch's implementation unless explicitly stated otherwise. No release was made by this documentation update. The Android build still declares version 1.8.6; that version string does not establish that a published APK contains these changes.

| Platform/version | Security maintenance status |
| --- | --- |
| Android v1.8.6 | Published baseline at this review. Reports accepted; the maintenance changes described below are not claimed to be in this APK. |
| Older Android versions | Reports accepted, but no separate backport commitment. Fixes target the next qualified Android release; use the latest official release. |
| Android maintenance branch | Unreleased work requiring final review and release/device qualification. No fixed release version is assigned here. |
| iOS development tree | Development implementation, not a supported production release under this policy. Android vault/migration claims do not establish iOS parity. |

Use the [official releases](https://github.com/RaheemJnr/pocket-node/releases) and the notes for the exact release you install. Do not infer remediation from a branch commit, closed tracker, passing host tests, or an unchanged version number.

## Android wallet protection

### V3 vault and independent passphrase

The V3 storage path encrypts wallet private-key and available mnemonic material with a random 256-bit data key using AES-256-GCM. An independent wallet passphrase wraps that key with AES-256-GCM after Argon2id derivation (64 MiB, 3 iterations, parallelism 4, 32-byte salt). Authenticated metadata binds the wallet identity, format and generation; wraps also bind the encrypted payload. V3 refers to the key-storage migration level; its envelope serialization currently has its own version 1.

The wallet passphrase is separate from the six-digit app PIN, Android device credential, and recovery phrase. The input policy requires at least 16 Unicode code points, permits spaces, and does not trim or normalize input. Length alone does not guarantee entropy: choose a strong, independent passphrase and keep recovery material safely offline. A copied passphrase envelope can be guessed offline; app lockout cannot limit those guesses.

Optional device wrapping provides another way to unlock the same data key. It requires a fresh Android Keystore `CryptoObject` operation with user authentication, rather than trusting an already-unlocked screen. Strong biometrics are supported where available; a separate device-credential prompt is supported on Android 11/API 30 and later. Earlier supported Android versions require the available biometric path for this option. Device-key loss, invalidation, or unsupported authentication leaves the passphrase/recovery route necessary. Device wrapping is not a portable backup.

The V3 device wrapper requests per-use authentication; it does not request StrongBox or prove a particular hardware security level. Legacy preferences may request StrongBox and fall back, but this is not a guarantee that every supported phone uses StrongBox or a TEE. Managed-runtime/provider copies of plaintext and keys cannot be guaranteed erased from memory.

See the [envelope implementation](android/app/src/main/java/com/rjnr/pocketnode/data/vault/WalletEnvelopeCrypto.kt), [format](android/app/src/main/java/com/rjnr/pocketnode/data/vault/WalletEnvelope.kt), and [device wrapper](android/app/src/main/java/com/rjnr/pocketnode/data/vault/DeviceVaultKeyStore.kt).

### Legacy migration, retention and deletion

Older installations may contain Keystore-wrapped database material, EncryptedSharedPreferences, archived migration sources, and PIN-only compatibility backup files, including interrupted temporary copies. An upgrade alone does not prove that all such sources have been retired.

Authenticated migration verifies the persisted V3 replacement and its wallet identity before retiring captured legacy copies. Cleanup also preserves recovery capability: a matching private key is insufficient if the source contains a mnemonic absent from the replacement. Unreadable, conflicting, changed, or ambiguously owned copies remain for recovery and may leave cleanup pending indefinitely. An explicit acknowledgement bound to the exact inaccessible V2 archive and wallet can authorize its retirement; this exception does not permit discarding unreadable V1 archives. Shared legacy wrapping aliases remain while other wallets or recovery sources may depend on them; an alias remaining by itself does not show that wallet ciphertext survives.

Ordinary wallet removal targets that wallet ID's database records, scoped legacy backup files (committed and temporary), and scoped legacy preferences. Failed scoped cleanup has a durable retry record and is retried at startup; a replacement identity or unresolved ownership can prevent completion. Recovery removal can intentionally preserve backups. Unknown global/default sources and historical orphan copies are not assigned to an arbitrary wallet and are not guaranteed removed by deleting one UUID wallet.

The explicit installation reset is the broader removal path: it fences secret writers, removes and checks app-managed wallet namespaces, and removes PIN state last. Incomplete reset remains pending. This is distinct from ordinary per-wallet cleanup.

All removal claims are **logical deletion within app-managed storage**, not guaranteed physical erasure. Flash wear leveling, freed SQLite pages/WAL, OS snapshots, exported files, external/platform backups, and previously copied ciphertext can outlive an unlink or database deletion. The app cannot recall copies that have left its storage.

### App PIN and attempts

The six-digit app PIN controls the application gate; it is not the V3 wallet passphrase. Current PIN hashes use Argon2id (64 MiB, 3 iterations, parallelism 4, 32-byte salt and output). Legacy salted Blake2b hashes remain readable and are upgraded after successful verification when the storage write succeeds. Android persists the PIN verifier and attempt state in EncryptedSharedPreferences.

Failures accumulate across sessions and timed lockouts. The schedule is 30 seconds at failure 5, 1 minute at 6, 5 minutes at 7, 30 minutes at 8, 1 hour at 9, and recovery required at 10. Correct PIN verification after a timed lockout expires clears the failure budget when persistence succeeds. Normal signed upgrades, version jumps, relaunches, missing version metadata, and downgrades do not clear Android attempt state. The recovery-required path is explicit reset and restore, not an upgrade-triggered counter reset. Timed lockouts depend on the device clock; they are not hardware-enforced offline-guessing protection.

See [PinPolicy](android/shared/src/commonMain/kotlin/com/rjnr/pocketnode/data/auth/PinPolicy.kt), its [Android adapter](android/app/src/main/java/com/rjnr/pocketnode/data/auth/PinManager.kt), and [startup version bookkeeping](android/app/src/main/java/com/rjnr/pocketnode/StartupVersion.kt).

### Backups and transaction boundaries

The maintenance branch no longer writes or refreshes six-digit-PIN-only compatibility backups. Historical PBKDF2 v1 and Argon2id v2 files remain readable for authenticated recovery and checked retirement. Six digits provide only one million combinations: a slower KDF and app attempt counter do not make stolen PIN-only ciphertext a strong backup. Such copies can remain under the retention rules above, including deliberately preserved recovery backups.

`android:allowBackup="false"` disables the application's normal Android backup participation; it is not a physical-erasure guarantee or protection against a compromised platform or previously exported copies. Keep an independent offline recovery phrase/private key as appropriate to the wallet. Raw-private-key imports do not acquire a mnemonic merely by migration.

Signing takes place locally. Signed transactions and public chain queries go to the network; the application does not need to send private keys or mnemonic material for them. Deliberately revealing/exporting recovery material creates additional user-controlled copies. Local authentication does not verify the recipient's identity or make a malicious recipient safe.

## iOS development boundary

The iOS tree uses a separate Keychain/Secure Enclave wallet-key implementation with AES-GCM payload encryption and an enclave-wrapped data key. It shares PIN derivation policy code, but the Android V3 passphrase vault, legacy cleanup, attempt-state adapter, and installation-reset guarantees above must not be assumed to apply to iOS. Simulator tests do not establish Secure Enclave behavior. See [WalletKeyStore](ios/PocketNode/Services/Keys/WalletKeyStore.swift), [SecureEnclaveKeyWrapper](ios/PocketNode/Services/Keys/SecureEnclaveKeyWrapper.swift), and the [iOS test instructions](ios/README.md).

## Network services and privacy

The embedded light client connects directly to CKB peers on the selected mainnet or testnet. Both platforms' bundled maintenance configurations specify an empty P2P listener list (outbound connections and discovery remain enabled; UPnP remains disabled). Generated configurations are refreshed at the next native initialization, including for existing installations. An already-running process needs normal restart/initialization to receive the new resources. This policy applies to bundled mobile configurations, not arbitrary custom native configurations.

The embedded bridge does **not start an HTTP RPC server**. The TOML `127.0.0.1:9000` RPC setting is inert in this bridge; the startup log mentioning RPC is not evidence of a listener. App queries use JNI on Android and UniFFI on iOS. P2P and RPC are separate services. See [native lifecycle](external/ckb-light-client/light-client-lib/src/bridge_core/lifecycle.rs) and [Android configuration](android/app/src/main/assets/mainnet.toml).

Direct P2P is not anonymity: peers can observe connection metadata and protocol requests/transactions. CKB P2P is not an application-provided confidential transport. Android also contacts CoinGecko and Binance for price data, GitHub for release metadata, and allowed GitHub asset hosts for update downloads. These HTTPS services remain third-party dependencies; the app does not provide certificate pinning for these requests. Avoid interpreting an embedded node as an absence of all external services.

## Builds, release verification and limits

The unreleased release workflow builds without private signing credentials, signs/verifies in a separate job that executes no release-tag/downloaded helper code, and confines repository release-write permission to a separate publishing job. A future authorized run is configured to publish the APK, `SHA256SUMS`, `SIGNING-CERTIFICATE-SHA256.txt`, and `release-provenance.json`. This update did not run that workflow, publish these assets, rotate a key, or attest a historical release. Existing v1.8.6 notes already provide an APK checksum and signing-continuity statement.

Verify the APK digest and APK signature, then compare the certificate with an independently trusted fingerprint. Files beside the APK are not independent authenticity proof. Build provenance records source/toolchain/native-library evidence supplied by the build job; a malicious build can fabricate claims. The signer binds the artifact, native bytes and expected certificate, not source-to-binary correctness. The manifest is neither a signed attestation nor proof of reproducibility. See [release artifact semantics and verification commands](tools/release/README.md).

The old BouncyCastle/CKB Java SDK runtime-risk description no longer describes the app dependency model: these libraries remain differential-test dependencies, and the Android runtime-classpath guard rejects them. This is not a claim that all dependencies or native code are vulnerability-free.

Host tests and source review support the described boundaries, but do not establish every Android Keystore/provider or iOS Secure Enclave behavior on physical devices. The maintenance network checks parse the bundled resources and exercise isolated native startup/shutdown without live peers; they do not prove outbound chain sync. Physical signed-upgrade, device-authentication, background/lifecycle, and outbound sync qualification remain necessary before release. No independent third-party audit or complete device qualification is claimed by this policy.
