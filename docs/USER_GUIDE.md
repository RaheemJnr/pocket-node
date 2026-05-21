# Pocket Node User Guide

Pocket Node is a native Android wallet for Nervos CKB. It runs a CKB light client on your phone, so you do not trust any remote server with your balance, your history, or your transactions. Everything happens locally on your device.

This guide walks through everything you need to use the app, from first install to advanced features.

> Screenshots in this guide are captured against the latest release. Minor visual differences are possible if you are on an older or pre-release build.

## Table of contents

1. [Install](#install)
2. [First run](#first-run)
3. [Back up your recovery phrase](#back-up-your-recovery-phrase)
4. [Sync modes](#sync-modes)
5. [Send CKB](#send-ckb)
6. [Receive CKB](#receive-ckb)
7. [Address book](#address-book)
8. [Multiple wallets](#multiple-wallets)
9. [Nervos DAO](#nervos-dao)
10. [Updating the app](#updating-the-app)
11. [Troubleshooting](#troubleshooting)
12. [FAQ](#faq)
13. [Security model](#security-model)

## Install

Pocket Node is available two ways.

### GitHub Release (current)

For now, the only way to install Pocket Node is by downloading the APK directly from GitHub. Visit the [releases page](https://github.com/RaheemJnr/pocket-node/releases) and download the `pocket-node-vX.Y.Z.apk` from the latest release.

### Google Play Store (coming soon)

A Play Store listing is in preparation and will be the recommended install path once it goes live. Until then, GitHub Releases is the official channel.

Tap the APK on your phone. Android will ask whether to allow installs from this source; tap **Settings**, enable **Allow from this source**, then return and tap **Install**.

After the first sideload, future updates can be installed inside the app itself. See [Updating the app](#updating-the-app).

### System requirements

- Android 8.0 (Oreo) or newer
- Roughly 100 MB of free storage for the app, plus another 50 MB to 200 MB for light client data depending on sync mode and network
- A working internet connection on first sync; the app reconnects automatically when you return online

## First run

Open the app. You will land on the welcome screen with two choices.

### Create a new wallet

Tap **Create new wallet**. The app generates a fresh 12-word BIP39 recovery phrase locally on your device. The phrase never leaves your phone.

You will be asked to:

1. Read a short security warning about handling the recovery phrase.
2. View the 12 words. Write them down on paper in order. Do not screenshot them. Do not type them into another app, a notes file, or a cloud document.
3. Confirm the phrase by tapping the words back in order.

The app then prompts you to set up a PIN and (optionally) biometric unlock.

### Import an existing wallet

Tap **Import wallet**. You have two options:

- **Recovery phrase**: paste a BIP39 12-word or 24-word phrase. This is the most common case if you are moving from another CKB wallet.
- **Private key**: paste a 64-character hex private key. Use this only if you have a raw private key from a non-standard source. The recovery phrase route is preferred.

After import, the app asks you to choose a sync mode. See [Sync modes](#sync-modes).

### Set a PIN

PIN protection is required. You cannot skip it. Pick a 6-digit PIN that you can remember without writing down. The app uses Argon2id key derivation with 64 MB of memory cost, so PIN guessing is computationally expensive even with the phone in an attacker's hands.

After 10 failed PIN attempts, the wallet locks permanently and can only be recovered from your recovery phrase. Failed attempts decay over a 24-hour window, so a typo or two while you are tired will not lock you out.

### Enable biometric unlock (optional)

If your phone has fingerprint or face unlock, you can enable biometric authentication after PIN setup. Biometric unlock is a convenience layer on top of the PIN, not a replacement; the PIN is still required after a reboot, after the wallet has been backgrounded for a long time, or if your biometric enrollment changes (for example, you add a new fingerprint).

If your biometric enrollment changes, the app will ask you to confirm with your PIN once before re-enabling biometric unlock. This is by design and protects against a stolen, jailbroken phone with attacker-added biometrics.

## Back up your recovery phrase

Your 12-word recovery phrase is the only thing that can restore your wallet if your phone is lost, stolen, factory-reset, or destroyed. Pocket Node cannot recover it for you. No company can.

### Where to store it

Good:

- A piece of paper kept in a safe or a locked drawer at home.
- A metal seed-phrase plate stamped with the words (resistant to fire and water).
- Two paper copies in two different physical locations (home and a trusted relative, for example).

Bad:

- A screenshot. Screenshots end up in cloud photo backups.
- A note in your phone's notes app, even if synced to a cloud account.
- A text message or email to yourself.
- A password manager whose vault password you have forgotten.

### How to back up later

If you skipped the initial backup confirmation or want to re-verify, go to **Settings → Wallet → Back up recovery phrase**. The app will require PIN or biometric authentication, then show you the words again.

The backup screen has `FLAG_SECURE` set, meaning Android screen recordings and screenshots from other apps cannot capture the phrase.

### Sub-accounts and backups

If you have created HD sub-accounts under a parent wallet, you do not need to back them up separately. The parent wallet's recovery phrase derives every sub-account deterministically. Back up the parent phrase once, restore it, and every sub-account comes back automatically.

## Sync modes

Sync mode tells the embedded CKB light client how far back in time to scan the blockchain when looking for your transactions and balance. The first sync is the slowest operation in the app's life cycle; later updates are fast and incremental.

There are four modes.

### New wallet

Starts scanning from the current chain tip. Use this if the wallet has never received CKB before, for example because you just generated it.

Sync time: instant (no history to scan).

### Recent activity

Scans the last roughly 200,000 blocks, which is approximately the last 30 days of activity. Use this if you used a CKB wallet in the last month, or if you are not sure and want a reasonable default.

Sync time: about 2 minutes on a typical phone with a typical home internet connection.

### From a specific date (CUSTOM)

You enter a block height, and the light client scans from that block forward. Use this if you remember roughly when your wallet first had activity but it is older than a month.

The app shows a link to the public CKB block explorer where you can look up the block height for a given date. Pick a block from a couple of weeks before your wallet's first known activity to be safe.

Sync time: proportional to how far back you go. Six months back is roughly 10 to 20 minutes on a typical phone. A year back is 30 to 60 minutes. Two or more years back can take hours.

### All history (FULL_HISTORY)

Scans from genesis (block 0) forward. This downloads everything since the CKB network launched in 2019. Use this only if you know your wallet has activity dating back to the early years and you cannot estimate a block height.

Sync time: several hours minimum on mainnet. Plan for an overnight sync.

### Changing sync mode later

Go to **Settings → Sync mode** to switch modes after the first sync. Switching will re-sync from the new start point and may take time. A confirmation prompt explains the impact before any change takes effect.

### When sync stalls

If sync stops advancing for 5 minutes or more, the app shows a warning banner on the home screen with a one-tap **Use Recent** option. This is the typical recovery for older wallets where a long history scan is the cause. Tapping it switches the wallet to Recent activity mode and rebases the scan.

## Send CKB

Tap **Send** on the home screen.

### Recipient

Three ways to enter a recipient:

- **Type or paste an address**. Mainnet addresses start with `ckb1`. Testnet addresses start with `ckt1`. The app validates the address format and refuses to send to a malformed address.
- **Pick from your address book**. Tap the contact icon next to the recipient field to open the picker.
- **Scan a QR code**. Tap the QR icon next to the recipient field, point at the recipient's address QR, and the app fills the field automatically.

### Amount

Type the amount in CKB. The smallest unit on the CKB network is 1 shannon, which is 0.00000001 CKB. The app shows your available balance below the amount field; tap **Max** to send everything minus the network fee.

A CKB cell has a 61 CKB minimum capacity, which is a network rule, not a Pocket Node policy. If you try to send an amount that would leave a change cell smaller than 61 CKB, the app will warn you. Increase the amount slightly, or send the full balance with **Max** to avoid creating an undersized change cell.

### Fee

The default fee is 0.001 CKB (100,000 shannons). This is enough for confirmation under normal network conditions. There is no advanced fee picker because CKB does not have congestion-driven fee markets in the same way other networks do.

### Sync gate

You cannot send if the wallet is still syncing. A banner above the send button explains why. Wait for sync to finish, then try again. This protects you from accidentally sending stale balance.

### Confirm and broadcast

Review the recipient, amount, and fee on the confirmation sheet. Authenticate with your PIN or biometric, and the app builds, signs, and broadcasts the transaction. You will see it in the activity list immediately as pending.

### Pending vs confirmed

A sent transaction goes through three states:

- **Pending**: broadcast to the network, not yet in a block. Typical wait is 10 to 30 seconds.
- **Confirmed**: included in a block. Most wallets and exchanges consider this final, but for high-value transactions you may wait for additional confirmations.
- **Failed**: rejected by the network or the broadcast never confirmed within 6 minutes. Tap the **Failed** chip on the activity list to retry with the recipient and amount pre-filled.

## Receive CKB

Tap **Receive** on the home screen.

### Your address

The screen shows your wallet's CKB address as text and as a scannable QR code. Tap **Copy** to copy the address to the clipboard. Tap **Share** to send it through any sharing target on your phone (messaging app, email, AirDrop equivalent).

The same address is reusable. Unlike Bitcoin, where reusing addresses leaks privacy, CKB's UTXO model and the addresses used by Pocket Node are designed for reuse. You do not need to generate a new address for every incoming transaction.

### Network awareness

The address shown is for the network the app is currently set to. Mainnet addresses start with `ckb1`; testnet addresses start with `ckt1`. If you switch networks (Settings → Network), the receive screen shows the address for the new network.

### Watching for incoming funds

After someone sends you CKB, the transaction will appear on your home screen activity list as Pending, then transition to Confirmed once it lands in a block. Your balance updates automatically.

If you do not see an incoming transaction, check:

- That the sender used the correct network (a testnet send will never appear on a mainnet wallet, and vice versa).
- That sync is up to date. If sync is behind by hours, the most recent transactions may not have been scanned yet.
- That the sender actually broadcasted. Ask them for the transaction hash and look it up on the public CKB explorer.

## Address book

Add the people you send to often to your address book. Each contact has a name, an address, and an optional note.

### Add a contact

**Settings → Contacts → Add contact**. Enter a name and paste the address. Save.

You can also add a contact straight from the Send screen: after a successful send, the app prompts **Save Alice to contacts?** if the address is not already saved. Tap **Save** and the contact appears in your book.

### Edit or delete a contact

Tap a contact in the list. **Edit** lets you change the name, address, or note. **Delete** removes the contact (the wallet activity for that address is unaffected; only the contact entry is removed).

### Use a contact when sending

On the Send screen, tap the contact icon next to the recipient field. Pick from the list, and the address fills in automatically. The contact picker also has a search box for finding contacts by name.

The recipient field has live autocomplete. Start typing a contact name, and matching contacts appear under the field. Tap one to fill the address.

### Smart suggestions

The app surfaces your most recently used contacts at the top of the picker so the people you send to often are always one tap away.

## Multiple wallets

Pocket Node supports unlimited wallets on the same device. Each wallet has its own private key, its own address, its own balance, and its own activity history. Switching between them is one tap.

### Add a wallet

**Settings → Wallets → Add wallet**. You can:

- **Create a new wallet**: generates a fresh recovery phrase. Same flow as first-run wallet creation.
- **Import a wallet**: paste a recovery phrase or private key.
- **Add an HD sub-account**: under a parent wallet, derives a new account at the next BIP44 path. Sub-accounts share the parent's recovery phrase, so a single backup covers all of them.

### Switch wallet

Tap the wallet name in the top bar of the home screen. A sheet opens listing all your wallets. Tap one to switch.

The switch is instant for cached balance, and the activity list refreshes in the background. Each wallet has its own sync state, so switching to a wallet that has not been used recently may show "Catching up" briefly.

### Rename or delete a wallet

**Settings → Wallets**, tap a wallet, and **Edit** lets you rename it. **Delete** removes the wallet from this device. Make sure you have the recovery phrase backed up before deleting; the wallet cannot be recovered from this app without it.

Deleting a parent wallet also deletes all sub-accounts derived from it, since the parent phrase is the only way to derive them.

### Sub-accounts explained

CKB uses Hierarchical Deterministic (HD) key derivation. A single recovery phrase can derive an effectively unlimited number of distinct accounts, each with its own address and private key. Sub-accounts in Pocket Node use the BIP44 path `m/44'/309'/N'/0/0` where `N` is the account index.

This is useful if you want separate accounts for different purposes (savings, spending, a side project) without managing multiple recovery phrases.

## Nervos DAO

Nervos DAO is a built-in feature of the CKB network that lets you lock CKB and earn compensation (similar to staking, but with different mechanics). Pocket Node has native DAO support.

### Deposit

**Home → Stake → Deposit**. Enter the amount of CKB to deposit. The deposit creates a special DAO cell on-chain that locks your CKB. There is no minimum lock time and no fixed reward rate; compensation accrues continuously based on network parameters.

### Withdraw

DAO withdrawal is a two-step process baked into the CKB protocol:

1. **Initiate withdrawal**: tap a deposit cell in your DAO list, then **Withdraw**. This signals to the network that you want to unlock; compensation stops accruing from this point.
2. **Wait for the lock period**: the network requires about 30 days from withdrawal initiation. During this period, your CKB is still on-chain but not yet spendable.
3. **Complete withdrawal**: after the lock period ends, the app shows the cell as ready. Tap **Complete withdrawal** to move the CKB plus compensation back to your spendable balance.

### Compensation tracking

Each deposit cell in the DAO list shows the current accrued compensation. The number is computed from on-chain headers, not estimated, so it is always exact.

### Important caveats

- Once you initiate withdrawal, you cannot reverse it. You must wait the full lock period and complete the withdrawal to get your CKB back.
- The lock period is enforced by the network, not by Pocket Node. No wallet can shorten it.
- DAO operations require sync to be at or near tip. The app gates DAO actions behind sync state for safety.

## Updating the app

Pocket Node ships a new release every two weeks on average. Updates include bug fixes, performance improvements, and new features.

### In-app updater (current)

Until the Play Store listing is live, all updates flow through the in-app updater. The app checks for new GitHub releases on startup and shows a banner at the top of the home screen when a new version is available. Tap **Update** to start the download. The APK downloads in the background; you can keep using the app while it downloads.

When the download finishes, tap **Install** on the banner. Android may ask permission to install from unknown sources the first time. After granting, return to the app and the install resumes automatically.

The in-app updater has been stable since v1.6.1. If you are on an older version and the in-app updater is not working reliably, update to the latest version manually from the GitHub releases page once. From then on, future updates work in-app.

### Play Store (coming soon)

Once the Play Store listing is live, users installed via Play Store will receive updates through the normal Play Store channel. The in-app updater will continue to work for GitHub-installed builds.

### Checking your version

**Settings → About → App version** shows the installed version.

## Troubleshooting

### Sync is stuck at 0%

After about 5 minutes of no progress, the app shows a warning banner offering **Use Recent**, which switches the wallet to Recent activity mode and re-syncs from a much closer start point. This is the fastest fix for the most common stall cause (a wallet with old history and a slow scan).

If the banner does not appear and sync is genuinely stuck:

1. Check your internet connection.
2. Open **Settings → Node status**. Peer count should be at least 3. If it is 0 or 1, the light client is having trouble finding peers; force-close the app and reopen to trigger a fresh peer discovery.
3. If the peer count is healthy but sync is still flat, try **Settings → Sync mode → Use Recent**.

### Balance is wrong or missing

Pocket Node only sees what the light client has scanned. If sync is not at tip, recent transactions are not yet reflected.

1. Pull down on the home screen to refresh.
2. Check sync state. If it is below 100 percent, wait for sync to finish.
3. If sync is at 100 percent and the balance is still wrong, the most likely cause is that the wallet was originally synced from a start point that missed the early incoming transactions. Switch to a wider sync mode (a CUSTOM block height further back, or All history) and let it re-sync.

### Peer count is low

The light client needs at least one peer to sync. It tries to maintain 3 to 10 peers for redundancy.

If peer count is 0 for more than a few minutes:

1. Check your internet connection. Light clients connect over TCP to fixed CKB bootnodes; if your network blocks outbound TCP on non-standard ports, peer discovery will fail.
2. Try toggling airplane mode off and on to reset the device's network stack.
3. Force-close and reopen the app to retry peer discovery.

### The app crashed

Reopen the app. Your wallet state, sync state, and balance are persisted, so nothing is lost.

If the app crashes immediately on every open:

1. Check that you are on the latest version (uninstall the APK and reinstall from the latest GitHub release).
2. As a last resort, **before uninstalling**, make sure you have your recovery phrase. Uninstalling deletes all local state including private keys. After reinstall, import your wallet from the recovery phrase.

### Network switch did not complete

Switching networks (Settings → Network → Mainnet/Testnet) closes and reopens the app. This is intentional and is required because the light client cannot re-initialize in the same process. If the app does not reopen automatically, tap the app icon to reopen manually. Your wallets and balances are preserved across the switch.

## FAQ

**Is Pocket Node custodial?**

No. Pocket Node is fully self-custodial. Your private keys are generated on your device and never leave it. There is no company or server that holds your funds.

**Where is my CKB stored?**

On the Nervos CKB blockchain. The app holds the keys that authorize spending, but the CKB itself lives on-chain. Closing the app, factory-resetting your phone, or even destroying the phone does not destroy your CKB; restoring from the recovery phrase on a new device gives you full access again.

**Why does the app need to download blockchain data?**

Because there is no middleman server you have to trust. The light client downloads enough of the blockchain to verify your balance and history independently. Most light wallets (including most "non-custodial" ones) actually trust a remote indexer; Pocket Node does not.

**Why is sync slow the first time?**

The first sync downloads block headers and scans them for transactions involving your address. The deeper your wallet's history, the more there is to scan. Subsequent app launches only sync the gap since you last opened the app, which is fast.

**Can I use Pocket Node offline?**

You can view your cached balance and history offline, but you cannot send or receive without an internet connection. The app reconnects automatically when you return online.

**Does Pocket Node support other tokens or chains?**

Pocket Node is a CKB-native wallet. It does not support Bitcoin, Ethereum, or other chains. It does not support custom CKB tokens (sUDT, xUDT) at this time; only native CKB is supported in v1.x and v2.0. Token support is on the roadmap.

**What if I lose my phone?**

Buy a new phone. Install Pocket Node. Import your wallet using the 12-word recovery phrase you wrote down at first run. Your full balance and history come back.

**What if someone steals my phone?**

The thief sees the wallet locked at the PIN screen. Pocket Node uses Argon2id key derivation backed by the Android Keystore, with hardware-bound encryption keys on Android 9 and newer. After 10 wrong PIN attempts the wallet permanently locks. As long as your recovery phrase is safe somewhere off-device, you can wipe the lost phone remotely (Find My Device), buy a new one, and restore.

**Can I export my private key?**

Yes, from **Settings → Wallet → Export private key**. This requires PIN or biometric authentication. Exporting a private key is risky; only do it if you understand why you need it and where it will go.

**What happens to my old transactions if I switch sync modes?**

Switching to a sync mode that starts later than your wallet's first activity means the app will not display the older transactions on this device. Your balance is still correct (the light client reconstructs balance from current unspent cells, not from the full history), and the older transactions are still on-chain. Switching back to a wider sync mode will recover the history view.

**Does Pocket Node work without Google services?**

Yes. The app does not depend on Google Play Services. The APK installed from GitHub Releases is the only delivery channel today, and a future Play Store listing will run the same code.

**How can I report a bug or request a feature?**

Open an issue on [GitHub](https://github.com/RaheemJnr/pocket-node/issues). For security issues, see the security disclosure policy in `SECURITY.md`.

## Security model

This section is for users who want to understand what Pocket Node trusts, what it does not trust, and what threats it does and does not defend against. Skip this section if you are not interested in security internals.

### What runs locally

- **Key generation**: BIP39 mnemonic generated using the OS-provided cryptographic random number generator. Never sent anywhere.
- **Key storage**: private keys encrypted at rest with AES-256-GCM. The encryption key lives in the Android Keystore, bound to user authentication (PIN or biometric). On Android 9 and newer, the Keystore key is hardware-backed (Trusted Execution Environment or secure element). The Keystore key cannot be extracted from the device.
- **Transaction signing**: secp256k1 signing happens locally. The private key is decrypted from Room into memory only for the duration of the signing call.
- **Light client**: an embedded Rust CKB light client (`libckb_light_client_lib.so`) runs in-process. It maintains its own peer connections, downloads block headers, scans for transactions matching your wallet, and reports balance and history back to the Kotlin layer over JNI.

### What is trusted

- **Your phone's operating system**: Pocket Node trusts that Android's Keystore is implemented correctly and that the kernel does not leak memory between processes. This is the same trust assumption every Android app makes.
- **The Rust light client's correctness**: a bug in the light client could cause Pocket Node to compute a wrong balance or accept a wrong header. The light client is open source and audited.
- **The CKB network's consensus**: Pocket Node assumes the CKB network is producing valid blocks. If the network is taken over by an attacker controlling more than half of the hash power, all bets are off (this is true for every cryptocurrency wallet).

### What is not trusted

- **No remote server**: Pocket Node does not query a central API for balance, history, or peer discovery. There is no remote indexer.
- **No analytics**: no telemetry, no crash reporting to a third party, no user tracking. The app does not phone home.
- **No cloud backup**: the app sets `android:allowBackup="false"`. Your wallet keys never leave the device through Android's auto-backup system. You are responsible for backing up your recovery phrase yourself.

### Threats defended against

- **Lost or stolen device with locked screen**: encrypted keys + PIN with Argon2id KDF + 10-attempt permanent lockout means casual attackers cannot extract your funds.
- **Malicious apps on the same device**: every Android app runs in its own sandbox. Other apps cannot read Pocket Node's data files or memory under standard Android security.
- **Screen capture by other apps**: sensitive screens (mnemonic display, private key export) use `FLAG_SECURE`, blocking screenshots and screen recording from outside the app.
- **Clipboard exposure**: the app does not write the recovery phrase or private key to the clipboard. Only addresses are clipboard-copyable.

### Threats not defended against

- **Physical access by a sophisticated attacker**: a determined attacker with full physical access to your unlocked phone can install rootkits, dump RAM, or use forensic tools. Pocket Node raises the bar substantially with the Keystore-bound encryption, but no software-only wallet can defend against this perfectly. For very high-value holdings, consider a hardware wallet.
- **Compromised recovery phrase**: if your recovery phrase leaks (a photo synced to cloud, a piece of paper found, a phishing attack), an attacker can restore the wallet on their device and drain it. No wallet can protect you from a leaked recovery phrase. Back it up off-device, off-cloud, and never share it.
- **Phishing**: if you paste an attacker's address into the recipient field and confirm a send, your CKB goes to the attacker. The app cannot tell that the recipient is hostile. Always double-check addresses, especially when copying from email, chat, or websites.

### Source code

Pocket Node is open source under the [GitHub repository](https://github.com/RaheemJnr/pocket-node). The Kotlin app code, the Rust JNI bridge, the build configuration, and the release scripts are all public. Reproducible builds let you verify that a published APK matches the source.

### Reporting a vulnerability

See `SECURITY.md` in the repository for the security disclosure process. We respond to reports within 72 hours.

---

Last updated: 2026-05-21. Updated alongside each release where user-visible behavior changes.
