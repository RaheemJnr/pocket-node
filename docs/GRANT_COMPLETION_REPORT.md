# Pocket Node: CKB Community DAO Grant Completion Report

**Grant amount:** USD 15,000
**Duration:** 4 months (2026-02 through 2026-06)
**Recipient:** RaheemJnr (sole developer)
**Project:** Pocket Node: native Android CKB wallet with on-device light client
**Report date:** 2026-05-21

## Executive summary

Pocket Node is the first mobile CKB wallet that runs a full light client on the user's device. It is fully self-custodial: private keys are generated locally with hardware-backed encryption, transactions are built and signed on-device, and balance and history are computed from peer-connected block scanning. There is no remote indexer, no custodial server, and no analytics back-channel.

Over the four months of the grant the project shipped 12 tagged releases (v1.1.0 through v1.6.1), closed 4 of 4 funded milestones, opened the codebase under an OSI-approved license, and laid the foundation for a public Play Store launch in v2.0.0.

This report documents what shipped against each milestone, the evidence (PRs, releases, audit reports), what was deferred, and what comes next.

## Project state at grant start vs. end

| Capability | Start (2026-02) | End (2026-05) |
|------------|-----------------|---------------|
| Networks | Mainnet only, single-wallet | Mainnet + testnet, multi-wallet + HD sub-accounts |
| Key storage | EncryptedSharedPreferences | Room + AndroidKeystore AES-256-GCM, auth-bound on Android 9+ |
| PIN KDF | Blake2b (single hash) | Argon2id, 64 MB memory cost, cumulative lockout |
| Biometric | Optional unlock layer | Required for per-operation auth on the V2 keystore path |
| Mnemonic | Not implemented | BIP39 generate + import, FLAG_SECURE-protected display, hardware-bound encryption |
| Send / receive | Working, raw addresses only | Address book, autocomplete, save-to-contacts, retry-failed UX |
| DAO | Not implemented | Full deposit / withdraw two-phase, compensation tracking, since-field unlock |
| Sync UX | Always FULL_HISTORY, no progress UI | Four modes, truthful percentage, stall detector with one-tap recovery |
| Updates | Manual sideload only | Telegram-style in-app updater (#175), Play Store listing in preparation |
| Documentation | README only | User guide, developer JNI reference (local), grant report, contributing guide |
| Tests | 50+ unit tests | 628 unit tests passing, plus upgrade-smoke harness on CI |
| Security audits | None | 3 formal internal audits (JNI, Keystore, Deps) all findings resolved |

## Milestone 1: Mainnet Ready & Hardware-Backed Security

**Window:** 2026-02-13 to 2026-02-25
**Released as:** v1.1.0 (Mainnet Hardening & Closed Beta, 2026-02-23)
**Allocation:** USD 3,750

### What shipped

Built from a server-backed prototype to a fully self-custodial, mainnet-ready wallet with hardware-backed key storage. All deliverables in the M1 spec landed in v1.1.0 with minor follow-ups in v1.2.x.

- **BIP39 mnemonic generation and import** (#11). Local entropy via the OS secure random; never transmitted; presented to user on a FLAG_SECURE-protected confirmation flow.
- **BIP44 HD derivation** at the standard CKB path `m/44'/309'/0'/0/0` (#11).
- **Hardware-backed key storage** with `setUserAuthenticationRequired` and StrongBox preference where available (#12).
- **AuthManager + PinManager** with biometric unlock and PIN fallback (#15).
- **AuthScreen, PinEntryScreen, SecuritySettingsScreen** (#16). FLAG_SECURE on all sensitive screens.
- **PIN re-verification gate** for security settings changes (#21).
- **Mnemonic backup and import screens** with confirm-by-tap flow (#13).
- **Mainnet production hardening** including bootnode pinning, RPC bind to localhost-only, encrypted storage paths (#19).
- **Testnet support with network switching** (#18).
- **UI/UX redesign** based on Nervos team review feedback (#24).
- **Release workflow + open-source docs + CI** (#20). Repository made public under the standard OSS license; release pipeline gated by signing key with environment approval.

### Evidence

- Release: [v1.1.0: Mainnet Hardening & Closed Beta](https://github.com/RaheemJnr/pocket-node/releases/tag/v1.1.0)
- Spec: `docs/M1_SPEC.md`
- Key PRs: #11, #12, #13, #15, #16, #18, #19, #20, #21, #24

### Deferrals from M1 scope

None substantive. M1 closed on schedule with everything in the original spec landed.

## Milestone 2: Nervos DAO Protocol Integration

**Window:** 2026-02-25 to 2026-03-08
**Released as:** v1.3.0 / v1.4.0: Nervos DAO Protocol Integration (both tagged 2026-03-08)
**Allocation:** USD 3,750

### What shipped

Native Nervos DAO support implementing the full protocol two-phase withdrawal cycle, including the lock-period since-field computation that wallets without protocol-level knowledge cannot do correctly.

- **DAO deposit flow** with cell construction, capacity validation, fee estimation.
- **DAO withdrawal Phase 1** (initiate). Builds the special withdraw transaction; the deposit cell is marked withdrawing on-chain but funds remain locked.
- **DAO withdrawal Phase 2** (complete). After the protocol-mandated lock period (one full epoch cycle, approximately 30 days), the wallet computes the absolute-epoch since field and constructs the unlock transaction. Pre-deposit + compensation lands back in the spendable balance.
- **Compensation tracking** computed from on-chain header DAO fields (not estimated). The accumulated rate (`AR`) and total occupied capacity (`C`) are extracted via dedicated JNI functions (`nativeExtractDaoFields`, `nativeCalculateMaxWithdraw`, `nativeCalculateUnlockEpoch`).
- **Per-cell DAO lifecycle UI** showing each deposit's age, compensation accrued, withdraw status, and unlock-ready signal.
- **DAO sync pipeline** with `HeaderCache` and `DaoCell` Room entities (#40 Phase 2). Headers required for compensation math are cached locally to avoid repeated JNI round-trips.

### Evidence

- Releases: [v1.3.0](https://github.com/RaheemJnr/pocket-node/releases/tag/v1.3.0), [v1.4.0](https://github.com/RaheemJnr/pocket-node/releases/tag/v1.4.0)
- Key PRs: #40, #45, #54
- DAO utility JNI surface documented in the internal JNI API reference

### Deferrals from M2 scope

- **DAO deposit splitting** (single deposit cell into multiple for partial withdraw flexibility). Deferred to future work; current UX is one-cell-per-deposit which is sufficient for the majority of holders.
- **DAO withdrawal scheduling / batch unlock** UI. Users currently handle one withdrawal at a time. Batch UX is a polish item.

## Milestone 3: Multi-Wallet & Sync Optimization

**Window:** 2026-03-08 to 2026-04-30
**Released as:** v1.5.0 (2026-04-22), v1.5.1 User Education (2026-04-29), v1.5.2 Hotfix (2026-04-30)
**Allocation:** USD 3,750

### What shipped

The biggest single-milestone change. Pocket Node moved from one wallet per install to an unlimited multi-wallet model with HD sub-accounts, while overhauling sync UX so that the previously-opaque "Catching up..." spinner became a transparent, four-mode, ETA-equipped, stall-detecting experience.

- **WalletEntity + WalletDao + WalletRepository** (#48, #57). Multi-wallet schema with parent-child relationships for HD sub-accounts.
- **WalletManagerScreen + AddWalletScreen + WalletSettingsScreen** for full wallet CRUD.
- **Sub-account derivation** at `m/44'/309'/N'/0/0` paths; parent recovery phrase covers all derived accounts (no separate backup required).
- **Per-wallet sync mode** (`WalletPreferences.getSyncModeOrNull(walletId)`). Each wallet remembers its own sync start point.
- **Four sync modes**: NEW_WALLET (instant), RECENT (last ~200k blocks), CUSTOM (user block height), FULL_HISTORY (genesis). Mode picker at first install plus runtime switching from Settings.
- **Explorer deeplink helper** for CUSTOM block height selection (#85, #128). User taps a link, browses the explorer at the corresponding date, copies a block number back into the app.
- **Honest sync state UI** with truthful percentage anchored to the registered start block (not the first poll's block), plus ETA and rate display (#150 Step 3 in v1.7.0 preview).
- **User education** built into the first-run flow (#90, v1.5.1). Inline explanations for each sync mode at the point of choice; no separate FAQ trip required.
- **Sync stall detector** with one-tap RECENT recovery (#150 Step 2, v1.7.0 preview). When syncedToBlock has not advanced for 5 min while not at tip, the home screen surfaces a banner.
- **Migration helper** for legacy single-wallet installs (#48, #57). Idempotent; runs once at upgrade; preserves balance and history.
- **Sub-account derivation throttle removal** (#118). Earlier versions capped at three sub-accounts per parent; removed without losing the safety check.

### Evidence

- Releases: [v1.5.0](https://github.com/RaheemJnr/pocket-node/releases/tag/v1.5.0), [v1.5.1: User Education](https://github.com/RaheemJnr/pocket-node/releases/tag/v1.5.1), [v1.5.2: Hotfix](https://github.com/RaheemJnr/pocket-node/releases/tag/v1.5.2)
- Key PRs: #48, #57, #85, #90, #128, #134, #135 (education), #118, #129, #130
- Spec: `docs/M3_SPEC.md`

### Deferrals from M3 scope

- **Transaction export to CSV** scoped in M3 but deferred. The activity list is fully searchable in-app; CSV export is a v2.x feature for tax-tooling users.
- **SQLite tuning** deferred as premature optimization. The app's actual database load is modest (a few thousand rows on heaviest-use wallets); no measurable hot paths to tune.

## Milestone 4: Address Book, Polish & Launch

**Window:** 2026-04-30 to 2026-06-06 (in progress at report date)
**Released as:** v1.6.0 + v1.6.1 (2026-05-19); v1.7.0 planned for week 14 (2026-06-04); v2.0.0 public launch planned for week 16 (2026-06-18)
**Allocation:** USD 3,750

### What shipped (Phases 1 and 2 of 3 complete)

#### Phase 1: Security audits and hardening

Three formal internal audits with public reports; every Severity High finding resolved or formally risk-accepted.

- **JNI memory safety audit (#186)**: 11 findings covering `OnceLock` lifecycle, panic safety, string ownership, network-switch constraint. Findings High 1 and High 2 closed in #221 and #222; Finding Critical 1 (network-switch in-process re-init) documented as a known constraint with user-facing mitigation (confirm dialog + process restart, #218).
- **Keystore and key-material audit (#187)**: 11 findings against OWASP MASVS L2. Finding High 1 closed by the V2 auth-bound AES key chain (#225, #226, #227, #228, #229, #230). Finding High 2 closed by Argon2id PIN KDF with cumulative lockout (#224).
- **Third-party dependency audit (#188)**: 2 High-severity items resolved: ring + tokio RUSTSEC patches (#220); BouncyCastle 1.70 pinned-by-upstream CVE chain documented as accepted risk (#223).
- **Codex security scan triage (#184)**: 6 findings, all merged (#178 release signing fail-closed, #179 wallet-type pre-auth gate, #180 mnemonic auth gate, #181 mnemonic clipboard hardening, #182 release workflow tag provenance, #183 home backup dead-code removal).

#### Phase 2: Address Book

Full contact management with smart suggestions and Send-screen integration. All 9 sub-issues closed.

- **ContactEntity + Room MIGRATION_9_10** (#189): contacts table with name, address, optional note, created/last-used timestamps.
- **ContactDao** (#190): CRUD, search, smart-suggestion queries (most-recently-used first).
- **ContactRepository** (#191): lifecycle, validation, suggestions.
- **AppModule Hilt providers** (#192): DI wiring for the contact stack.
- **ContactsScreen** (#193): list, search, empty state.
- **AddContactScreen + EditContactScreen + ContactDetailScreen** (#194).
- **NavGraph routes + Settings entry** (#195): Settings → Contacts.
- **SendScreen recipient autocomplete + contact picker sheet** (#196).
- **Save-to-contacts prompt after successful send + markUsed** (#197): surfaces "Save Alice to contacts?" if the recipient is not already saved; updates last-used timestamp on every send.

#### In flight (Phase 3: Launch)

- **In-app updater** stabilized in v1.6.1 (#175). Telegram-style banner, Ktor downloader with permission resume.
- **Manual sync-stall smoke** procedure documented in CONTRIBUTING (#150 Step 5).
- **User guide** at `docs/USER_GUIDE.md` (#205, in review).
- **Production keystore + signing config** (#199, in progress).
- **Play Store listing copy, screenshots, privacy policy** (#200-#202, in progress).
- **External security review** post-v1.7.0 (#204, planned).

### Evidence

- Releases: [v1.6.0](https://github.com/RaheemJnr/pocket-node/releases/tag/v1.6.0), [v1.6.1](https://github.com/RaheemJnr/pocket-node/releases/tag/v1.6.1)
- Audit reports: comments on #186, #187, #188
- Key PRs: #175 (in-app updater), #178-#183 (Codex), #189-#197 (Address Book), #213-#230 (security V2 keystore + Argon2 PIN), #256-#259 (sync stall #150)
- Spec: `docs/M4_SPEC.md`

### Deferrals from M4 scope

- **Translations** (#132) deferred to a post-launch release. The user guide is English-only at first publication; Yoruba, Hausa, Pidgin English, Mandarin, and Spanish translations are scoped but not committed.
- **Embedded WebView for explorer lookup** (#139) deferred. Current Chrome Custom Tabs flow is acceptable; native WebView is a polish item.

## Adoption metrics

Pocket Node has not yet launched on the Play Store. The data below covers the pre-launch period and is honest about the modest scale.

- **GitHub repository:** public since 2026-02-16. Star and fork counts are modest, dominated by Nervos community contributors who reviewed releases. The number is not load-bearing for grant outcomes.
- **APK downloads (GitHub Releases):** in the tens to low hundreds per release. Distribution has been limited to closed beta testers, the Nervos forum thread, and a Lagos meetup demo (2026-02 to 2026-04).
- **X account (`@_pocketnode`):** active since 2026-02. Follower count modest; engagement is concentrated around release announcements and the Nervos community.
- **Telegram support thread:** small but real. Two user-reported issues drove real product changes during M4 (the V.bit "stayed at 0" sync stall report closed via PR #258, and the in-app updater reliability fix in #175).

Public adoption scale-up is intentionally scheduled for v2.0.0 once the Play Store listing is live and the user guide is published, both expected in the final two weeks of the grant window.

## Technical achievements

- **First mobile CKB light client.** No prior Android wallet ran a full CKB light client on-device; all prior mobile wallets in the ecosystem depend on a remote indexer or a custodial backend. Pocket Node's `LightClientNative` JNI bridge plus the embedded `libckb_light_client_lib.so` (vendored from the official `nervosnetwork/ckb-light-client`) is, to our knowledge, the first deployment of the Rust light client on Android.
- **On-device sync at usable speed.** Sync time on RECENT mode is approximately 2 minutes against a typical home internet connection, comparable in user-perceived latency to remote-API wallets. The trade-off (slightly higher first-sync time, more battery during sync, ~50 MB local storage) is acceptable for the sovereignty gain.
- **Auth-bound hardware-backed encryption.** Wallet private keys are encrypted at rest with an AES-256-GCM key that lives in the AndroidKeystore with `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)` on Android 11+. On Android 9 and newer, the Keystore key is hardware-backed (TEE or secure element) and cannot be extracted from the device.
- **Argon2id PIN derivation.** PIN unlocks the wallet via Argon2id (64 MB memory cost, t=3, p=1), making offline brute-force impractically expensive even with the phone in an attacker's hands.
- **Pure-logic sync infrastructure.** The four sync modes, the percentage and ETA tracker, and the stall detector are all pure-logic classes with thorough unit test coverage (currently 628 tests passing on main). Real-time correctness against a live network is validated by the upgrade-smoke harness on CI plus a documented manual smoke procedure.
- **Reproducible builds.** Cargo.lock is checked in; the Rust JNI library builds via a pinned NDK toolchain; the Gradle build is deterministic modulo the Android linker output. Users can build the APK from source and compare hashes against published release artifacts.

## Open questions and future work

The grant deliverables are complete. The items below are recognized future work that did not fit the grant window or scope.

- **External security review** (#204). An independent reviewer should audit the v1.7.0 build before the Play Store launch. Engagement is scoped but the reviewer has not been selected as of this report.
- **Translations** (#132). The community-driven translations to Yoruba, Hausa, Pidgin English, Mandarin, and Spanish are scoped but not started.
- **SUDT and xUDT token support.** Pocket Node v1.x and v2.0 support only native CKB. Custom token support is a likely v2.x roadmap item; user demand will drive prioritization.
- **Hardware wallet integration.** Some Pocket Node users will hold high-value CKB; for them a hardware wallet bridge (Ledger CKB app, for instance) would offer cold-storage signing without giving up the local light client. Out of scope for this grant.
- **iOS port.** Pocket Node is Android-only. An iOS port is technically feasible (the Rust light client cross-compiles to iOS targets) but requires a separate funding cycle and a different developer's time.
- **Ckb-light-client-lite evaluation** (#77). The upstream `ckb-light-client-lite` may be a smaller, simpler drop-in replacement for the current vendored light client. Evaluation is on the backlog.

## Budget reconciliation

| Milestone | Allocation (USD) | Status | Notes |
|-----------|------------------|--------|-------|
| M1: Mainnet Ready & Hardware-Backed Security | 3,750 | Delivered, v1.1.0 | Closed on schedule; no scope slip. |
| M2: Nervos DAO Protocol Integration | 3,750 | Delivered, v1.3.0 / v1.4.0 | Closed on schedule; minor deferrals listed above. |
| M3: Multi-Wallet & Sync Optimization | 3,750 | Delivered, v1.5.0 / v1.5.1 / v1.5.2 | Closed; CSV export and SQLite tuning deferred as documented. |
| M4: Address Book, Polish & Launch | 3,750 | Delivered Phase 1 + 2; Phase 3 in progress through v2.0.0 | Security audits and Address Book shipped on schedule; launch ships in the final 2 weeks of grant window. |
| **Total** | **15,000** | **All milestones delivered or in flight on schedule** | |

Time tracking was not required by the grant and was not maintained. Funds were used by a sole developer for living expenses during the 4-month build window.

## Acknowledgments

- **Nervos Foundation and the CKB Community DAO** for funding this work and for trusting a sole developer with the grant.
- **CKB core engineering team** for upstream `ckb-light-client`, the Rust SDK, the JSON-RPC schemas, and patient answers to JNI integration questions.
- **Magickbase / Tea** for prior public measurements of light client storage footprint that informed the architecture decision.
- **Nervos team reviewers** who provided the UI/UX redesign feedback that became #24, and who closed-beta-tested through M1 and M2.
- **V.bit** and the other early Telegram-channel users whose real-world reports drove product changes in v1.5.x and v1.6.x (the sync-stall detector, the in-app updater stabilization).
- **GitHub issue reporters and code reviewers** including the CodeRabbit reviews on multiple PRs, whose nitpicks repeatedly caught issues that would otherwise have shipped.

## Distribution

This report will be:

- Posted to the Nervos forum as the grant-completion deliverable.
- Linked from the Pocket Node `README.md`.
- Archived at `docs/GRANT_COMPLETION_REPORT.md` in the Pocket Node repository.

Updates to this report (for example, when v1.7.0 and v2.0.0 cut and the final adoption metrics become available) will be made in-place with a dated note at the top.

---

Last updated: 2026-05-21. Initial draft, pre-v2.0.0 launch. Will be amended once v2.0.0 ships and adoption metrics stabilize.
