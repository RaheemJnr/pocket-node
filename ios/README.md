# Pocket Node for iOS

M1 skeleton: a SwiftUI shell around the embedded CKB light client (testnet) and
the shared Kotlin Multiplatform core.

## Prerequisites

- Xcode 26.x on Apple Silicon, iOS 17.0+ simulator or device
- Rust with the `aarch64-apple-ios` and `aarch64-apple-ios-sim` targets
- `brew install xcodegen`
- `android/local.properties` with `sdk.dir` set (Gradle builds the KMP framework)

## Build and run

```bash
external/ckb-light-client/build-ios.sh   # once; produces CkbLightClientFFI.xcframework
cd ios && xcodegen generate              # regenerates PocketNode.xcodeproj (not committed)
open PocketNode.xcodeproj                # then run the PocketNode scheme
```

`PocketNode.xcodeproj` is generated from `project.yml`; edit the spec, not the
project. A pre-build script phase runs
`./gradlew :shared:embedAndSignAppleFrameworkForXcode` to produce
`PocketNodeCore.framework`.

## Tests

- `xcodebuild -scheme PocketNode ... test` runs the offline unit tests.
- `xcodebuild -scheme PocketNodeNetwork ... test` runs the Node Status
  acceptance test, which starts the node and waits for a real testnet tip.
- `WalletKeyStoreDeviceTests` is skipped on the simulator and needs a physical
  iPhone with a passcode and enrolled biometrics:

  ```bash
  xcodebuild -project PocketNode.xcodeproj -scheme PocketNode \
    -destination 'platform=iOS,id=<device-udid>' \
    -only-testing:PocketNodeTests/WalletKeyStoreDeviceTests test
  ```

  It prompts for Face ID or Touch ID and prints the store's diagnostics, which
  report `hardwareBacked: true` only on real hardware.

## Wallet keys

`PocketNode/Services/Keys/` holds the key material at rest, mirroring the
Android Keystore V2 threat model: the wallet bundle is AES-256-GCM ciphertext
under a random data key, and that data key is wrapped by a P-256 key generated
inside the Secure Enclave and guarded by the current biometric set or the device
passcode. Both blobs live in the Keychain as
`kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`, non-synchronizable items. The
simulator has no Enclave, so it uses a software P-256 key behind the same
protocol; `isHardwareBacked` reports which one is in play. Keychain items
survive app deletion, so `InstallMarker` wipes them on the first launch of a
fresh install.

## Preferences and wallet metadata

`PocketNode/Services/Preferences/UserDefaultsPreferences.swift` implements
the shared `core.prefs` interfaces (`SyncPreferences`, `UiPreferences`,
`NetworkPreferences`, `AppStatePreferences` from `PocketNodeCore`) on
`UserDefaults`, key-for-key with Android's
`data/wallet/WalletPreferences.kt`: same key names, same defaults (network
selection defaults to mainnet; sync mode defaults to `NEW_WALLET`), same
per-network / per-wallet key suffixing. No Keychain access here; nothing
secret goes into UserDefaults.

`PocketNode/Services/Wallet/WalletStore.swift` persists the single active
wallet's metadata (name, type, addresses, derivation path,
`mnemonicBackedUp`, `createdAt`) as a JSON file in
`Application Support/PocketNode/wallet.json`, complete-file-protected and
written atomically. This is the M2 single-wallet store; M3 decides whether
multi-wallet moves to Room via KMP or SQLDelight, and `WalletRecord`'s field
names mirror Android's `WalletEntity` so that migration can read this file
directly.

`AppContainer` reads the selected network from `NetworkPreferences` once at
launch and passes it to `LightClientService`. The `PocketNodeNetwork`
acceptance test needs testnet specifically, so it sets
`POCKETNODE_NETWORK=testnet` in `app.launchEnvironment` before launching
rather than the app defaulting to testnet for everyone (`NodeStatusUITests`,
`AppContainer.applyNetworkOverrideForTestingIfPresent`).

## CI

`.github/workflows/ios-ci.yml` runs on macOS runners for every PR and push to
`main` that touches `android/shared/**`, `ios/**`, or
`external/ckb-light-client/**`: it builds and tests the shared KMP module
(`:shared:iosSimulatorArm64Test`, `:shared:testAndroidHostTest`), builds the
`CkbLightClientFFI.xcframework` via `build-ios.sh`, regenerates the Xcode
project with `xcodegen`, then builds and tests the offline `PocketNode`
scheme on a simulator resolved at run time. The networked `PocketNodeNetwork`
scheme talks to real testnet peers and is intentionally not run in CI.
