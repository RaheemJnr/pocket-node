import PocketNodeCore
import SwiftUI

/// Manual dependency injection (design D0): one object graph created at launch
/// and handed down through the SwiftUI environment. No DI framework on iOS.
@MainActor
@Observable
final class AppContainer {
    let lightClient: LightClientService

    /// The wallet's key material at rest, protected by the Secure Enclave.
    let walletKeyStore: WalletKeyStore

    /// `UserDefaults`-backed implementation of the shared `core.prefs`
    /// interfaces (network selection, sync settings, UI state, app-state
    /// bookkeeping). See `Services/Preferences/UserDefaultsPreferences.swift`.
    let preferences: UserDefaultsPreferences

    /// Single active wallet's metadata (M2; M3 brings multi-wallet).
    let walletStore: WalletStore

    /// Creates and imports that one wallet. Onboarding (#515) is its only
    /// caller today.
    let walletCreator: WalletCreator

    /// Face ID / Touch ID availability and prompting.
    let biometrics: any BiometricAuthenticating

    /// The app PIN, over the shared `PinPolicy`.
    let pinService: PinService

    /// Session state and the lock gate. `RootView` reads it.
    let auth: AuthService

    /// The launch decisions about the wallet and its PIN (see ``LaunchGate``).
    let launchGate: LaunchGate

    /// Kept in sync with the system color scheme by `RootView`.
    var theme: Theme = .light

    init() {
        Self.resetStateForTestingIfRequested()

        self.preferences = UserDefaultsPreferences()
        Self.applyNetworkOverrideForTestingIfPresent(preferences: preferences)
        self.walletStore = WalletStore()
        // Installs that saved `wallet.json` before it was excluded from
        // backups get the flag on their next launch (`WalletStore.save` sets
        // it on every write from now on).
        walletStore.excludeFromBackup()
        self.lightClient = LightClientService(network: preferences.getSelectedNetwork())

        let keychain = KeychainStore()
        let wrapper = SecureEnclaveKeyWrapper()
        // Keychain items survive app deletion; a fresh install must not inherit
        // the previous one's wallet (see `InstallMarker`).
        let marker = InstallMarker()
        let wasMarked = marker.isRecorded
        marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)
        self.walletKeyStore = WalletKeyStore(keychain: keychain, wrapper: wrapper)

        // The PIN lives in its own Keychain service, which `InstallMarker` does
        // not know about, so it needs the same reinstall wipe or a fresh install
        // would inherit the previous owner's PIN and their lockout counter.
        // Gated on the marker having actually been recorded by the call above:
        // if the wallet wipe failed it leaves the marker absent to retry, and
        // clearing the PIN while that wallet is still on the device would drop
        // the lock in front of it.
        let pinKeychain = KeychainStore(service: KeychainPinStore.defaultService)
        if !wasMarked && marker.isRecorded {
            try? pinKeychain.deleteAll()
        }

        // That delete is not retried if it fails (the marker is already
        // recorded). `LaunchGate` clears any PIN left with no wallet, and sets
        // aside an undecodable `wallet.json` with no keys, before the session
        // reads the PIN store.
        let biometrics = BiometricService()
        self.biometrics = biometrics
        let launchGate = LaunchGate(
            walletStore: walletStore,
            walletKeyStore: walletKeyStore,
            keyKeychain: keychain,
            pinKeychain: pinKeychain,
            preferences: preferences,
            biometrics: biometrics,
            skipsOnboarding: Self.skipsOnboardingForTesting
        )
        self.launchGate = launchGate
        self.pinService = launchGate.pinService
        self.auth = launchGate.auth
        self.walletCreator = WalletCreator(keyStore: self.walletKeyStore, walletStore: self.walletStore)

        Self.seedWalletForTestingIfRequested(walletStore: self.walletStore)
    }

    // MARK: - Screen graphs

    /// The only place `BackupViewModel` is built. Kept to one call site on
    /// purpose: its dependencies are the wallet's most sensitive ones, and a
    /// second construction elsewhere is how a gate quietly stops matching.
    ///
    /// - Parameter isOnboarding: true only on the first-run hop straight out
    ///   of wallet creation. `hasPin` is re-read on every reveal, so that flag
    ///   cannot skip the gate once a PIN exists.
    func makeBackupViewModel(isOnboarding: Bool) -> BackupViewModel {
        BackupViewModel(
            walletKeyStore: walletKeyStore,
            walletStore: walletStore,
            auth: auth,
            isOnboarding: isOnboarding,
            hasPin: { [pinService] in pinService.pinPresence == .present }
        )
    }

    /// The only place `ReceiveViewModel` is built, for the same reason.
    ///
    /// - Parameter onBackUp: navigation to `BackupView`, supplied by whatever
    ///   owns the navigation stack.
    func makeReceiveViewModel(onBackUp: @escaping () -> Void) -> ReceiveViewModel {
        ReceiveViewModel(
            walletStore: walletStore,
            preferences: preferences,
            hasPin: { [pinService] in pinService.pinPresence == .present },
            onBackUp: onBackUp
        )
    }

    /// Backs the wallet shell's first screen.
    func makeHomeViewModel() -> HomeViewModel {
        HomeViewModel(walletStore: walletStore, preferences: preferences)
    }

    /// `POCKETNODE_SKIP_ONBOARDING` (below) seeds a metadata-only wallet with
    /// no PIN and no keys for the UI tests that drive the wallet shell. Those
    /// tests need the shell, not the PIN step onboarding would otherwise
    /// resume at, nor the restore flow a key-less wallet would get. The one
    /// place that hook is read; always false in release builds, which always
    /// enforce the PIN and the restore.
    private static var skipsOnboardingForTesting: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.environment["POCKETNODE_SKIP_ONBOARDING"] == "1"
        #else
        return false
        #endif
    }

    /// The stored wallet, when its metadata is on this device but its key
    /// envelope is confirmed absent; nil otherwise.
    ///
    /// That is what restoring an iCloud or Finder backup onto a new phone
    /// leaves: `wallet.json` comes back, the `ThisDeviceOnly` Keychain items
    /// do not. Such a wallet shows an address it can never spend from, and
    /// ``WalletCreator`` would refuse to import it again because a wallet is
    /// "already there", so `RootView` sends it to the restore flow instead of
    /// the wallet shell. A Keychain that cannot be read yet (a launch before
    /// the first device unlock) is not an absence and does not count.
    var walletNeedingRestore: WalletRecord? {
        get async {
            // The metadata-only wallet `POCKETNODE_SKIP_ONBOARDING` seeds for
            // the wallet shell UI tests has no keys on purpose.
            if Self.skipsOnboardingForTesting { return nil }
            return await walletCreator.walletNeedingRestore()
        }
    }

    /// `PocketNodeNetwork`'s `NodeStatusUITests` exercises the light client,
    /// not onboarding, and #515 put an onboarding gate in front of the wallet
    /// shell it drives. Rather than have that test type a wallet in, it sets
    /// `POCKETNODE_SKIP_ONBOARDING=1` and this writes a throwaway metadata
    /// record so the gate opens. No key material is created: nothing on the
    /// Node Status path reads a key, and minting one here would put a real
    /// Secure Enclave wallet on the simulator for a test that has no use for
    /// it. The addresses are the pinned pair from `WalletCreatorTests`, so
    /// what the shell renders is a real, well-formed address on both networks.
    /// Debug-only, and a no-op on every other launch.
    private static func seedWalletForTestingIfRequested(walletStore: WalletStore) {
        #if DEBUG
        guard Self.skipsOnboardingForTesting,
              !walletStore.hasWallet else { return }
        try? walletStore.save(
            WalletRecord(
                id: "ui-test-wallet",
                name: "UI Test Wallet",
                type: WalletCreator.typeMnemonic,
                derivationPath: WalletCreator.derivationPath,
                mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft",
                testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn",
                mnemonicBackedUp: false,
                createdAt: 0
            )
        )
        #endif
    }

    /// `NetworkPreferences.getSelectedNetwork()` defaults to mainnet (#514,
    /// matching Android's `WalletPreferences`), but the `PocketNodeNetwork`
    /// acceptance test (`NodeStatusUITests`) needs testnet, which is what it
    /// exercised back when the network was hardcoded in `LightClientService`.
    /// Rather than special-case the production default, that UI test passes
    /// `POCKETNODE_NETWORK=testnet` in `app.launchEnvironment`; this reads it
    /// and seeds the preference before anything else touches it. A no-op on
    /// every other launch, since the variable is never set outside that test.
    private static func applyNetworkOverrideForTestingIfPresent(preferences: NetworkPreferences) {
        #if DEBUG
        switch ProcessInfo.processInfo.environment["POCKETNODE_NETWORK"] {
        case "testnet": preferences.setSelectedNetwork(network: .testnet)
        case "mainnet": preferences.setSelectedNetwork(network: .mainnet)
        default: break
        }
        #endif
    }

    /// `OnboardingUITests` drives the real onboarding flow, which
    /// `POCKETNODE_SKIP_ONBOARDING` cannot help with, that flag only opens the
    /// gate in front of an already-seeded wallet. Onboarding itself needs a
    /// device with no wallet, no PIN and no install marker, on every launch, not
    /// only the first one a simulator ever sees. This is the reset: it runs
    /// before anything else in `init()` touches the Keychain or `UserDefaults`,
    /// so `InstallMarker`'s own reinstall wipe (below) finds nothing left to do.
    ///
    /// Debug-only, and a no-op on every other launch since the variable is
    /// never set outside this one UI test target.
    private static func resetStateForTestingIfRequested() {
        #if DEBUG
        guard ProcessInfo.processInfo.environment["POCKETNODE_RESET_STATE"] == "1" else { return }
        try? KeychainStore().deleteAll()
        try? SecureEnclaveKeyWrapper().deleteKey()
        try? KeychainStore(service: KeychainPinStore.defaultService).deleteAll()
        try? WalletStore().delete()
        UserDefaults.standard.removeObject(forKey: InstallMarker.defaultsKey)
        #endif
    }
}
