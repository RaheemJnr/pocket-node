[![Google Play](https://img.shields.io/badge/Google%20Play-Available-brightgreen?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=com.rjnr.pocketnode)
[![Downloads](https://img.shields.io/github/downloads/RaheemJnr/pocket-node/total?label=downloads&color=success)](https://github.com/RaheemJnr/pocket-node/releases)
[![Latest Release](https://img.shields.io/github/v/release/RaheemJnr/pocket-node?label=latest)](https://github.com/RaheemJnr/pocket-node/releases/latest)
[![X](https://img.shields.io/badge/X-%40PocketNodeCKB-black?logo=x&logoColor=white)](https://x.com/PocketNodeCKB)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-yellow?logo=buymeacoffee&logoColor=black)](https://www.buymeacoffee.com/rjnr)

# Pocket Node

A self-custody [Nervos CKB](https://www.nervos.org/) wallet with an embedded light client. It verifies chain data and signs transactions on your device, connecting directly to CKB peers.

Android is available now. An iOS version is in development.

[Google Play](https://play.google.com/store/apps/details?id=com.rjnr.pocketnode) · [APK releases](https://github.com/RaheemJnr/pocket-node/releases/latest) · [User guide](https://pocket-node.com/guide)

## Features

- Send and receive CKB on mainnet and testnet.
- Deposit into Nervos DAO and complete both withdrawal stages.
- Manage multiple wallets and HD sub-accounts.
- Back up and restore with a recovery phrase or private key.
- Protect wallet access with a PIN and biometrics.
- Choose sync coverage when importing a wallet; export restore hints for another device.
- Scan addresses, save contacts, and export transaction history.

Optional fiat price feeds can be disabled in Settings. Initial sync depends on the history being scanned and the device's connection.

Ecosystem app signing through Khie/CCC is [planned](https://github.com/RaheemJnr/pocket-node/issues/430).

## Development

The project uses a Rust light client, shared Kotlin Multiplatform logic, an Android UI in Jetpack Compose, and an iOS UI in SwiftUI.

- `android/app/` — Android application.
- `android/shared/` — shared wallet logic and storage.
- `ios/` — iOS application and platform integrations.
- `external/ckb-light-client/` — embedded light client and native bridges.

For Android, install JDK 17, the Android SDK/NDK, and Rust, then run:

```sh
cd android
./gradlew assembleDebug
```

For tests after the native library has been built:

```sh
./gradlew test :shared:testAndroidHostTest -x cargoBuild
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for Android setup and [ios/README.md](ios/README.md) for iOS build instructions.

## Contributing and security

[Issues and pull requests](https://github.com/RaheemJnr/pocket-node) are welcome. Report vulnerabilities privately using [SECURITY.md](SECURITY.md).

Built on the [Nervos CKB light client](https://github.com/nervosnetwork/ckb-light-client), with support from the [CKB Community DAO](https://talk.nervos.org/t/dis-mobile-ready-ckb-light-client-pocket-node-for-android/9879).

Licensed under [MIT](LICENSE).
