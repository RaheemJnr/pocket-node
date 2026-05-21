Milestone 4 Completion Report
Project: Pocket Node: Mobile CKB Light Client Wallet for Android
Repository: github.com/RaheemJnr/pocket-node
Milestone: M4: Address Book, Polish & Launch
Releases: v1.6.0, v1.6.1

Deliverables Summary
All accepted deliverables for M4 have been completed or are in flight against the v1.7.0 / v2.0.0 launch cuts:

#	Deliverable	Status
1	Security audit: JNI memory safety (internal Phase 1)	Done
2	Security audit: Keystore and key-material storage (internal Phase 1)	Done
3	Security audit: third-party dependency review and version pin (internal Phase 1)	Done
4	Codex security scan triage: 6 findings merged	Done
5	V2 auth-bound AES-256-GCM Keystore key chain with eager migration	Done
6	Argon2id PIN KDF with cumulative 24-hour-decay lockout	Done
7	Rust JNI hardening: catch_unwind on all 24 exports, RwLock poison recovery, OnceLock defer	Done
8	Address Book: ContactEntity, DAO, Repository, screens, Send autocomplete, save-on-success	Done
9	Sync UX: stall detector with one-tap RECENT recovery, truthful percentage baseline	Done
10	In-app updater: Telegram-style banner, Ktor downloader, permission-resume on ON_RESUME	Done
11	Manual sync-stall smoke procedure in CONTRIBUTING.md	Done
12	Public user guide: install, backup, sync, send/receive, DAO, troubleshooting, FAQ, security model	Done
13	Release: v1.6.0	Done
14	Release: v1.6.1	Done
15	Release: v1.7.0 (security + Address Book + polish, week 14)	In flight
16	Release: v2.0.0 (public launch, week 16)	In flight

Feature 1: Internal Security Audits (Phase 1)
Three formal internal audits posted with full reports and follow-up issues filed for every Severity ≥ High finding. All such findings are now resolved or formally risk-accepted.

What was built:

JNI memory safety audit covering OnceLock lifecycle, panic safety, string ownership, network-switch constraint. Finding Critical 1 (in-process re-init) documented as a known constraint with a user-facing process-restart mitigation; Finding High 1 (catch_unwind + RwLock poison recovery) closed in #221; Finding High 2 (OnceLock defer until init succeeds, recoverable partial-init) closed in #222
Keystore and key-material audit against OWASP MASVS L2: 11 findings (0 Critical, 2 High, 4 Medium, 3 Low, 2 Informational). The two High findings drove the V2 Keystore key chain (Feature 2) and the Argon2id PIN KDF (Feature 3)
Third-party dependency audit producing a per-library SBOM and a Rust cargo-audit pass. Finding High 1 (BouncyCastle 1.70 CVE chain, pinned by upstream ckb-sdk-java) documented as accepted risk; Finding High 2 (tokio + ring RUSTSEC advisories) closed by cargo update in the embedded light client
Audit reports archived as comments on the originating tracking issues (#186 JNI, #187 Keystore, #188 Deps); each issue closed with a PR-map summary tying findings to the merged fixes
Issues: #186, #187, #188 (audits); #215/#221, #217/#222, #218 (JNI fixes); #216/#220, #219/#223 (deps fixes)

Feature 2: V2 Auth-Bound Keystore Key Chain
Wallet private keys and mnemonics are now encrypted with an AES-256-GCM key bound to user authentication. On Android 11+ the binding includes both biometrics and the device credential as fallback; on Android 9 and 10 the key is hardware-backed via the AndroidKeystore TEE.

What was built:

V2 key generation with setUserAuthenticationRequired(true), setInvalidatedByBiometricEnrollment(true), and setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL) on API 30+
WalletKeyReader.readPrivateKey(activity, walletId, ...) and readKeyMaterial(...) activity-aware reads that present BiometricPrompt at the call site; per-operation auth with no grace window
WalletKeyBundle JSON wraps privateKey + mnemonic into a single V2 ciphertext, so the user gets one prompt per signing or display action (not one per material type)
KeystoreV2MigrationHelper performs the V1 → V2 migration idempotently and resumably; schema bumps to v10
Migration trigger from AuthScreen → AuthViewModel.runMigrationIfNeeded(activity) → KeystoreV2MigrationRunner.runMigration after the user's first post-upgrade unlock
KeyManager.deriveWalletInfoFromEntity used everywhere boot and sync need a WalletInfo, so V2 wallets do not prompt at startup
Edge cases handled: KeyPermanentlyInvalidated (enrollment changed → user-facing re-import-from-phrase flow), no-credential refusal at wallet setup, biometric-invalidation warning surfaced in Settings + User Guide
Issues: #213 (umbrella); #225, #226, #227, #228, #229, #230 (sub-PRs in order)

Feature 3: Argon2id PIN KDF and Cumulative Lockout
PIN derivation moved from a single Blake2b hash to Argon2id with memory-hard parameters; lockout policy moved from a per-session counter to a cumulative 24-hour-decay window with a permanent lockout at 10+ failures.

What was built:

Argon2id at 64 MB memory cost, t=3 iterations, p=1 lane via BouncyCastle 1.70's Argon2BytesGenerator (already on classpath, no new dependency)
Silent Blake2b → Argon2id migration on the first successful unlock after upgrade: the legacy hash verifies, the new hash overwrites, the legacy is wiped
Cumulative attempt counter with a 24-hour exponential decay so a tired user's typos do not lock them out, but an attacker's brute-force is throttled
Permanent lockout at 10+ failures, recoverable only via the 12-word recovery phrase
Settings UI explains the lockout model so the user is not surprised
Issues: #214 (umbrella); #224 (single PR)

Feature 4: Codex Security Scan Triage
A Codex code scan run pre-M4 surfaced 6 hardening opportunities, all addressed.

What was built:

#178 / #212: release signing fails closed at task-graph time when env vars are missing; the release-signing GitHub environment created with required reviewer approval
#179: resolve active wallet type from durable Room state before the mnemonic-backup startup gate fires (defense-in-depth for #180)
#180: require authentication before showing the mnemonic backup screen (High severity: mnemonic was rendering pre-auth on cold start)
#181: remove the Copy Private Key button from the mnemonic flow (clipboard exposure)
#182: harden the release workflow's tag provenance so a tag push alone cannot trigger an unreviewed release
#183: remove the dead-code home private-key backup path (was unreachable in current UI; future-proof hardening)
Operational change: release cuts now use git tag vX.Y.Z && git push --tags, then a manual Actions → Release → Run workflow, then approve the release-signing environment gate
Issues: #184 (umbrella); #178–#183 (individual findings)

Feature 5: Address Book
Full contact management with smart suggestions and Send-screen integration. Nine sub-issues, all merged.

What was built:

ContactEntity + Room MIGRATION_9_10: contacts table with name, address (validated against the current network), optional note, createdAt, lastUsedAt
ContactDao: CRUD plus search-by-name and most-recently-used queries
ContactRepository: lifecycle, validation, smart-suggestions ordering
Hilt providers wired into AppModule so the contact stack is injectable everywhere
ContactsScreen: list, search, empty state with a Settings → Contacts entry point
AddContactScreen, EditContactScreen, ContactDetailScreen: full per-contact flows
NavGraph routes for all three contact screens
SendScreen integration: recipient autocomplete fires on every keystroke, plus a full contact picker sheet
Save-to-contacts prompt after a successful send: "Save Alice to contacts?" if the address is not already saved; markUsed updates lastUsedAt on every send so the picker always shows the most-recent recipients at the top
Issues: #189, #190, #191, #192, #193, #194, #195, #196, #197

Feature 6: Sync UX: Stall Detector, Truthful Baseline, One-Tap Recovery
Real-world reports drove a three-part fix to the long-standing "stayed at 0% for 30 minutes" pathology on 2021-era wallets.

What was built:

SyncCoordinator now logs every (walletId, startBlock) on registration and every (synced, tip, delta, progress) on each 5-second poll, INFO-level so it survives release builds. Next "stayed at 0" report is diagnosable from logcat without instrumentation
SyncProgressTracker.seedStartHeight anchors the UI percentage to the registered start block, not the first poll's syncedToBlock (which is often 0 during peer warm-up). Percentage is truthful from poll cycle #1
SyncStallDetector pure-logic watcher: if syncedToBlock has not advanced for 5 minutes while away from tip, HomeViewModel raises showSyncStallBanner. Detector + dismissal flag reset on sync-mode change, wallet switch, network switch
SyncStallBanner composable with two CTAs: "Use Recent" (one-tap switch to RECENT sync mode for the fastest recovery on 2021-wallet pathology) and "Dismiss" (hides for the session)
Manual smoke procedure in CONTRIBUTING.md with a symptom-to-code-path failure table so a future regression has a one-line diagnostic path
Regression test SyncCoordinatorCustomBlockTest pins the CUSTOM block height to the JNI setScripts payload so a future refactor cannot silently drop the user's chosen start block
Issues: #150 (umbrella); #256, #257, #258, #259 (sub-PRs)

Feature 7: In-App Auto-Update Stabilization (v1.6.1)
The in-app updater shipped in M3 was inconsistent for some users; v1.6.1 rebuilt the download + install pipeline.

What was built:

Telegram-style update banner pinned above the bottom navigation in MainScreen, surfacing download progress and the install CTA across tab switches
Ktor downloader replaces the previous Android DownloadManager-backed flow, with end-to-end progress reporting and resumable error recovery
Install-from-unknown-sources permission flow: app sends the user to system settings, captures the pending APK URL + size, and resumes the download automatically on ON_RESUME once permission is granted (the user does not have to re-tap Update)
Self-check test for the updater's GitHub-Releases polling logic, so a release-channel URL change cannot ship undetected
Issues: #175 (single PR); BuildConfig releases-URL change in #170

Feature 8: Documentation
Public-facing documentation for end users plus contributor-facing operational docs.

What was built:

docs/USER_GUIDE.md: 13 sections covering install, first run, recovery phrase backup, sync modes, send / receive, address book, multi-wallet, Nervos DAO, updates, troubleshooting, FAQ, security model. Plain language, addresses the four sync modes at the point of choice (extending the M3 user-education effort)
CONTRIBUTING.md extended with the manual sync-stall smoke procedure and a symptom-to-code-path failure table
SECURITY.md updated with the BouncyCastle 1.70 accepted-risk disclosure (#219 / #223)
CLAUDE.md updated with the network-switch process-restart constraint (#218) so future contributors do not attempt an in-process nativeStop → nativeInit
Issues: #205 (user guide, in #260); #144 (manual smoke); #150 (Step 5); #218 (CLAUDE.md note); #219 (SECURITY.md note)

Testing

New test files added during M4 cover every load-bearing piece of the new functionality:

Test File	Coverage
ContactDaoTest	Room contacts table: insert, query-by-name, most-recently-used ordering, delete, MIGRATION_9_10
ContactRepositoryTest	Contact CRUD, validation, search, smart-suggestion ranking, lastUsedAt update on every send
KeystoreEncryptionManagerTest	AES-256-GCM round-trip, V1 vs V2 key generation, setUserAuthenticationRequired flag, hardware-backed Keystore property assertions
KeystoreV2MigrationHelperTest	Idempotent V1 → V2 migration, resumability, schema bump v9 → v10, post-migration cleanup
PinManagerTest	Argon2id parameters (64 MB / t=3 / p=1), silent Blake2b → Argon2id migration on first unlock, cumulative 24-hour-decay lockout, permanent lockout at 10 failures
AuthManagerSessionPinTest	Session-PIN lifecycle, clearing on backgrounding, post-migration unlock paths
SyncProgressTrackerTest	Rolling-window ETA, percentage baseline via seedStartHeight, reset semantics, no-sample edge cases
SyncStallDetectorTest	First-call no-stall, advance below threshold not flagged, stall after threshold, advance after stall clears flag, synced-within-tolerance suppresses stall, reset clears state
SyncCoordinatorCustomBlockTest	Regression pin: CUSTOM block height reaches the JNI setScripts payload; prior savedBlock overrides syncMode-computed start; FULL_HISTORY produces 0x0; setScripts invoked exactly once per registration

Total: 628 unit tests passing on main (up from 561 at the M3 cut). The upgrade-smoke harness on CI (added late M3 via #144 Phase 2) installs the prior release, installs the new build over it, and verifies the app launches without a migration crash; this caught the v1.5.2 hotfix scenario and continues to gate every release.

Releases

Version	Highlights
v1.6.0	First M4 release: in-app updater Telegram-style banner foundation, debug-build version pill, peers indicator becomes NodeStatus shortcut, post-deposit dialog removal, coachmark fixes (#173)
v1.6.1	In-app updater stabilization (Ktor downloader + permission-resume), versionCode 9 → 10 (#175, #176)

Both releases include signed APKs attached as assets and are deployable via the in-app updater starting from v1.6.0.

Two further releases are scheduled inside the grant window:

v1.7.0 (week 14, ~2026-06-04): Security findings ship in a tagged release (V2 Keystore + Argon2id PIN + JNI hardening), Address Book ships, sync-stall fixes ship, polish items from v1.6.x field testing
v2.0.0 (week 16, ~2026-06-18): Public launch. User guide published, Play Store listing live (pending company-account setup), external security review engagement begun

What's Next: Beyond the Grant

Work after grant completion will focus on:

External third-party security review (#204): post-v1.7.0 audit of the full security surface by an independent reviewer
Translations (#132): user guide and in-app strings localized to Yoruba, Hausa, Pidgin English, Mandarin, Spanish, and others as community contributions land
SUDT and xUDT token support: native CKB only in v1.x and v2.0; token support is the next roadmap item, demand-driven
ckb-light-client-lite evaluation (#77): potential drop-in replacement for the vendored light client with a smaller footprint
DAO batch-unlock UI: M2 shipped per-cell deposit / withdraw; batch-flow polish item
Transaction CSV export (originally M3-scoped, deferred): tax-tooling users have asked; planned for v2.x

Latest Release: v1.6.1 — In-App Updater Stabilization
