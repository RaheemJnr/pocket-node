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

Two schemes split the UI tests by whether they need a network, both built from
the one `PocketNodeUITests` target (`project.yml`):

- `xcodebuild -scheme PocketNode ... test` runs the offline unit tests
  (`PocketNodeTests`) plus the offline UI tests (`PocketNodeUITests`):
  `OnboardingUITests` (#517), `WalletShellUITests` and the four M3 acceptance
  suites, `SyncModeUITests`, `ActivityUITests`, `SendUITests` and
  `ScannerUITests`. `NodeStatusUITests` also builds here, since it lives in the
  same target, but skips itself with `XCTSkip` unless
  `POCKETNODE_NETWORK_TESTS=1` is set - this scheme's test action does not set
  it. `.github/workflows/ios-ci.yml` runs this scheme.
- `xcodebuild -scheme PocketNodeNetwork ... test` sets
  `POCKETNODE_NETWORK_TESTS=1` in its test action and runs the same
  `PocketNodeUITests` target, so `NodeStatusUITests` actually starts the node
  and waits for a real testnet tip this time. Not run in CI.
- `WalletKeyStoreDeviceTests` and `PinServiceDeviceTests` are skipped on the
  simulator and need a physical iPhone with a passcode and enrolled
  biometrics:

  ```bash
  xcodebuild -project PocketNode.xcodeproj -scheme PocketNode \
    -destination 'platform=iOS,id=<device-udid>' \
    -only-testing:PocketNodeTests/WalletKeyStoreDeviceTests test
  ```

  It prompts for Face ID or Touch ID and prints the store's diagnostics, which
  report `hardwareBacked: true` only on real hardware.

### Debug-only launch environment flags

`AppContainer`, `RootView` and `SendView` read these from
`ProcessInfo.processInfo.environment` inside `#if DEBUG`; none of them compile
into a release build.

| Flag | Effect |
|------|--------|
| `POCKETNODE_NETWORK` | `testnet` or `mainnet`, seeds `NetworkPreferences.setSelectedNetwork` before anything else reads it. Overrides the default (mainnet, #514). |
| `POCKETNODE_SKIP_ONBOARDING` | `1` writes a throwaway `WalletRecord` (the pinned test vector's addresses, no key material) so `RootView` opens straight to the wallet shell instead of onboarding. Only takes effect when no wallet is already stored. Used by `WalletShellUITests`, `NodeStatusUITests` and the four M3 suites, none of which cares about onboarding. |
| `POCKETNODE_RESET_STATE` | `1` deletes the wallet envelope, the Secure Enclave wrapping key, every PIN Keychain item, `wallet.json` and the install marker, before anything else in `init()` runs. Used by `OnboardingUITests` (#517) so the real onboarding flow gets a clean device on every launch, not only a simulator's first one, and by the M3 suites so each starts from the same seeded wallet. It also relaunches with this flag in its own `tearDown`, so it leaves the device the way `WalletShellUITests`/`NodeStatusUITests` expect to find it (see `POCKETNODE_SKIP_ONBOARDING` above). |
| `POCKETNODE_UITEST_ALLOW_CAPTURE` | `1` makes `PrivacyShield` ignore `UIScreen.isCaptured` for this one signal. XCUITest itself records the screen on a physical iPhone, which the shield otherwise (correctly) treats as a capture and hides the whole app behind it — every UI test sets this. |
| `POCKETNODE_UITEST_EXPOSE_WORDS` | `1` drops the SwiftUI redaction from the recovery-phrase grid so an XCUITest can read the generated words back off it. Set alongside the flag above by every suite that reads text out of the accessibility tree. |
| `POCKETNODE_START_ROUTE` | `send`, `receive`, `activity`, `nodeStatus` or `settings`, pushed onto the wallet shell's first appearance. A screenshot or acceptance run on a simulator cannot tap: driving the UI from outside the app needs assistive access a headless run does not have. |
| `POCKETNODE_SEND_RECIPIENT` | An address to fill the Send form's recipient field with, on that screen's first appearance. The whole `POCKETNODE_SEND_*` family additionally requires the stored wallet's id to be the seeded `ui-test-wallet`, so these can only ever drive a wallet with no key material; on any other wallet they do nothing. |
| `POCKETNODE_SEND_AMOUNT` | A CKB amount for the Send form, run through the same sanitiser a keystroke is. |
| `POCKETNODE_SEND_REVIEW` | `1` waits for the balance to land and then submits, so the run reaches the review sheet. Retries the submit if the node has not reported a tip yet, which is what a user does about "the wallet is still starting up". |
| `POCKETNODE_SEND_CONFIRM` | `1` also ticks the sweep acknowledgement and confirms. On the seeded wallet this always stops at the key step with "Could not read this wallet's keys", which is the state it exists to photograph; it cannot broadcast, because that wallet has nothing to sign with. |

## M3 screens

M3 added sync-mode choice, the activity list and the send path. Everything below
is what a person picking the work up needs to drive those screens, and nothing
in it is specific to one issue.

### Running the offline suite

```bash
cd ios && xcodegen generate
xcodebuild -project PocketNode.xcodeproj -scheme PocketNode \
  -destination 'platform=iOS Simulator,id=<simulator-udid>' \
  -derivedDataPath /tmp/dd-ios test
```

That is the whole offline scheme: the unit tests plus every UI test that does
not need peers. `xcrun simctl list devices available` finds a udid. One
`xcodebuild` at a time per simulator, and the UI tests install and launch the
real app, so a simulator used for a run is left holding the throwaway
`ui-test-wallet`. To hand it back to a person:

```bash
xcrun simctl uninstall <simulator-udid> com.rjnr.pocketnode
xcrun simctl spawn <simulator-udid> defaults delete com.rjnr.pocketnode
```

The four M3 suites (`SyncModeUITests`, `ActivityUITests`, `SendUITests`,
`ScannerUITests`) all launch through `M3UITest.launch` in
`PocketNodeUITests/M3UITestSupport.swift`, which sets
`POCKETNODE_RESET_STATE`, `POCKETNODE_SKIP_ONBOARDING`,
`POCKETNODE_NETWORK=testnet` and the two `POCKETNODE_UITEST_*` flags (see the
table above). They are offline in the sense that matters: the light client
starts, because the wallet shell starts it, finds no peers, and nothing in the
suite ever waits on a sync, a balance or a chain read. A send therefore always
stops at "Insufficient balance" against a balance of zero, which is what
`SendUITests` asserts rather than working around.

The networked counterpart is unchanged: `xcodebuild -scheme PocketNodeNetwork
... test` sets `POCKETNODE_NETWORK_TESTS=1`, which is the only thing that
un-skips `NodeStatusUITests` and has it wait for a real testnet tip. It needs
working bootnodes and is deliberately not run in CI.

### Accessibility identifiers

Set on the screens so the UI tests can address them, and worth keeping stable:
a renamed identifier is an immediately failing test, while a missing one is a
test that quietly matches something else.

| Prefix | Screen | Examples |
|--------|--------|----------|
| `root.*` | the wallet shell's toolbar | `root.settings`, `root.nodeStatus` |
| `home.*` | `HomeView` | `home.root`, `home.balance`, `home.syncCard`, `home.syncChooseButton`, `home.syncChangeButton`, `home.syncError`, `home.syncRetryButton`, `home.send`, `home.receive`, `home.activity`, `home.backupBanner` |
| `syncMode.*` | `SyncModeSheet` | `syncMode.option.newWallet` / `.recent` / `.custom` / `.fullHistory`, `syncMode.recommended`, `syncMode.customHeight`, `syncMode.confirm`, `syncMode.cancel` |
| `activity.*` | `ActivityView` and its sheet | `activity.list`, `activity.filter.all` / `.received` / `.sent`, `activity.row.<txHash>`, `activity.empty`, `activity.error`, `activity.retry`, `activity.detail`, `activity.detail.copyHash`, `activity.detail.explorer`, `activity.detail.retry` |
| `send.*` | `SendView` | `send.root`, `send.available`, `send.recipient`, `send.scan`, `send.addressState`, `send.amount`, `send.max`, `send.dustWarning`, `send.fee`, `send.submit`, `send.reopenStatus` |
| `review.*` | `SendReviewSheet` | the amount, fee and total rows, the sweep acknowledgement and Confirm |
| `sendStatus.*` | `SendStatusSheet` | the phase, the hash and the Hide / Done buttons |
| `sendFailure.*` | the one failure dialog | `sendFailure.title`, `sendFailure.message`, `sendFailure.detail`, `sendFailure.retry`, `sendFailure.ok` |
| `scanner.*` | `QrScannerView` | `scanner.root`, `scanner.preview`, `scanner.torch`, `scanner.pasteField`, `scanner.useButton`, `scanner.error`, `scanner.openSettings` |
| `onboarding.*`, `create.*`, `import.*`, `backup.*`, `pin.*`, `receive.*`, `settings.*` | the M2 screens | see `OnboardingUITests` and `WalletShellUITests` |

Two SwiftUI rules decide how a query has to be written, and both are commented
at their source in `HomeView`:

- an identifier set on a plain container is pushed DOWN onto every element
  inside it, so `app.staticTexts["activity.empty"]` reads the message itself;
- an identifier set on a container marked `.accessibilityElement(children:
  .contain)` (`home.root`, `send.root`, `syncMode.root`) stays on the container
  and the children keep their own.

A `Text` inside a `Button` is folded into that button's label either way, which
is why `SyncModeUITests` asserts Recommended by reading
`app.buttons["syncMode.option.recent"].label` rather than looking the badge up
on its own.

### Kotlin and Swift agreeing about money

`PocketNodeTests/Parity/M3ParityTests.swift` holds a literal copy of the table
in
`android/shared/src/commonTest/kotlin/com/rjnr/pocketnode/parity/M3ParityFixtures.kt`
and asserts it through the framework and through the view models that format
with it: the sync start block per mode and network and the two registration
clamps, `SyncEngine.computeStatus`, the activity display states and elapsed
buckets, the sweep warning, the send error mapping, the row amounts and fees,
and the balance formatter and amount parser. The two files change together or
not at all. A row that has to be edited on one side only means one platform
reformatted a number the other did not, and the right response is to find out
which side moved rather than to update the table.

### Shipping a build

`ios/scripts/` is the TestFlight pipeline, run by hand on a Mac, with no CI step
behind it: `archive.sh` (regenerates the project and produces a signed Release
`.xcarchive`, `--bump-build` to increment the build number), then
`export-testflight.sh` (writes `ExportOptions.plist` and exports the `.ipa`),
then `upload-testflight.sh` (uploads with `altool`, needs an App Store Connect
API key). `ios/scripts/README.md` has the full flow and the one-time account
steps no script can do.

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

## Authentication

`PocketNode/Services/Auth/` holds the lock. `AuthService` owns the session
state (`noPin` / `locked` / `unlocked`), locks on `scenePhase == .background`
only, and exposes `requireAuth(reason:)` for step-up auth on a single action
(the recovery phrase reveal, later the send confirmation). `BiometricService`
wraps `LocalAuthentication` with `.deviceOwnerAuthenticationWithBiometrics`:
the device passcode is deliberately not a fallback, our own 6-digit PIN is.

`PinService` wraps the shared Kotlin `PinPolicy` (`data/auth/PinPolicy.kt`), so
the Argon2id hashing and the lockout schedule are literally the same code
Android runs: 5 failures lock for 30 s, then 1 min, 5 min, 30 min, 1 h, and a
permanent lock at 10. `KeychainPinStore` supplies the six storage fields under
its own Keychain service (`com.rjnr.pocketnode.pin`), where Android supplies
`EncryptedSharedPreferences`. The Keychain has no multi-item transaction, so
`KeychainPinStore.apply` orders the writes such that any interrupted prefix
leaves a safe state; the rules are documented on that method. The counter
persists across reinstalls by design (#370), except on the first launch of a
fresh install, where `AppContainer` clears the PIN service on the same signal
`InstallMarker` uses for the wallet.

Argon2id runs on `PinPolicyActor`, off the main actor: 64 MiB at t=3 is around
150 ms in a release build and over a second in a debug one. Tests lower the
cost via `Argon2Cost.testing` and drive the schedule with an injected clock
(`PocketNodeTests/Auth/`).

`Screens/Auth/` has the UI: `PinEntryView` (the reusable 6-digit pad, no
keyboard), `LockView` (the app-wide gate `RootView` shows while locked) and
`PinSetupView` (create, confirm, then the biometric opt-in). Onboarding (#515)
is what sets the first PIN; until one exists nothing is gated.

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

## Onboarding

`Screens/Onboarding/OnboardingViewModel.swift` (#515) drives the first-run flow
as a step machine — `welcome -> create|importWallet -> backup -> pinSetup ->
done` — mirroring Android's `OnboardingScreen` through
`InitialPinSetupScreen`. A wallet created fresh goes through `backup` because
nobody has written the phrase down yet; a wallet imported from a phrase or a
raw key skips straight to `pinSetup`, because the user already holds it (or
there is no phrase at all). `OnboardingView.swift` renders whichever step the
model is on and owns nothing itself.

`Services/Wallet/WalletCreator.swift` is the one place that creates or imports
the wallet: `createWallet` (`Bip39.shared.generate` then
`Bip32.shared.deriveCkbPrivateKey`), `importMnemonic` (validates, then the same
derivation) and `importPrivateKey` (validates the scalar range directly in
Swift, since a Kotlin `IllegalArgumentException` would terminate the process
rather than reach a `catch`). Key material is stored before metadata; a failed
metadata write rolls the key material back rather than leaving an orphaned
wallet `AppContainer.hasWallet` would send straight to the wallet shell with no
way back into onboarding. `CreateWalletView.swift` and `ImportWalletView.swift`
are the two entry screens; `PinSetupView` (see Authentication above) is what
`Step/pinSetup` shows.

The pinned cross-platform vector — the standard all-"abandon" BIP-39 phrase,
its `m/44'/309'/0'/0/0` private key and both addresses — is asserted three
times: `WalletCreatorTests` (iOS unit), the shared module's
`CrossPlatformAddressParityTest` (`:shared:testAndroidHostTest` and
`:shared:iosSimulatorArm64Test`, `android/shared/src/commonTest/kotlin/com/rjnr/pocketnode/data/wallet/`),
and `OnboardingUITests.testImportingTheTestPhraseShowsThePinnedTestnetAddress`
through the real UI (#517). A derivation drift on either platform fails at
least one of the three.

## Backup and Receive

`Screens/Backup/BackupViewModel.swift` drives the recovery-phrase backup flow:
a `gate -> display -> verify -> success` step machine mirroring Android's
`MnemonicBackupViewModel`, simplified to iOS's one key-material shape. The
gate calls `AuthService.requireAuth(reason:)` unless this is the verified
onboarding hop (`isOnboarding && !hasPin()`, re-checked on every reveal so a
stale flag can never skip the gate once a PIN exists); `WalletKeyStore.load`
then decrypts the bundle. A raw-key wallet (no mnemonic) goes to a `.noPhrase`
step instead of `.display`. `BackupQuiz.swift` is the pure quiz generator: 3
distinct word positions, 4 shuffled choices each (the correct word plus 3
distinct decoys, topped up from `Bip39.shared.WORDLIST` if the phrase itself
cannot supply enough), driven entirely through an injectable
`RandomNumberGenerator` so tests are deterministic. The words live in memory
only for `.display`/`.verify`; `onBackgrounded()` (wired to `scenePhase`) wipes
them and returns to `.gate`, the same ON_STOP re-arm Android does.
`Screens/Backup/PrivacyShield.swift` additionally covers the phrase whenever
the scene is not active or the screen is being captured/mirrored — a
supplement to the wipe, not a replacement for it. `BackupView` takes the view
model and an `onFinished` closure; it does not know how it got there or where
it goes next.

`Screens/Receive/ReceiveViewModel.swift` reads the active wallet's address for
`NetworkPreferences.getSelectedNetwork()` and shows the Android-parity protect
dialog ("Protect your wallet") when the wallet is an unbacked-up mnemonic
wallet; "Back up now" calls an injected `onBackUp` closure. `Services/Qr/QrCodeGenerator.swift`
renders the bare address (no scheme prefix, matching Android's ZXing writer)
through CoreImage's `CIQRCodeGenerator` at correction level "M", scaling the
vector image before rasterising so modules stay crisp with no interpolation.

Both `BackupView` and `ReceiveView` are self-contained: every dependency comes
through their view model's initializer, with no reference to `RootView` or
`AppContainer`, so they can be previewed, tested and wired into navigation
independently of who owns the surrounding flow.

## CI

`.github/workflows/ios-ci.yml` runs on macOS runners for every PR and push to
`main` that touches `android/shared/**`, `ios/**`, or
`external/ckb-light-client/**`: it builds and tests the shared KMP module
(`:shared:iosSimulatorArm64Test`, `:shared:testAndroidHostTest`), builds the
`CkbLightClientFFI.xcframework` via `build-ios.sh`, regenerates the Xcode
project with `xcodegen`, then builds and tests the offline `PocketNode`
scheme on a simulator resolved at run time — unit tests and the offline UI
tests (`OnboardingUITests`, `WalletShellUITests`, `SyncModeUITests`,
`ActivityUITests`, `SendUITests`, `ScannerUITests`) together. The networked
`PocketNodeNetwork` scheme talks to real testnet peers and is intentionally
not run in CI.
