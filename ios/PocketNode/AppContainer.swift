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

    /// Kept in sync with the system color scheme by `RootView`.
    var theme: Theme = .light

    init() {
        self.preferences = UserDefaultsPreferences()
        Self.applyNetworkOverrideForTestingIfPresent(preferences: preferences)
        self.walletStore = WalletStore()
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

        self.biometrics = BiometricService()
        let pinService = PinService(keychain: pinKeychain)
        self.pinService = pinService
        self.auth = AuthService(
            pin: pinService,
            biometrics: self.biometrics,
            preferences: self.preferences
        )
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

    /// Whether onboarding has already been completed.
    ///
    /// Both halves are consulted: the Keychain envelope is the wallet, and
    /// `wallet.json` is what the UI reads. A device with only one of them is
    /// mid-failure rather than fresh, and sending it back through onboarding
    /// would refuse at ``WalletCreator/createWallet(wordCount:name:)`` anyway,
    /// so the honest answer is that a wallet is there.
    var hasWallet: Bool {
        get async {
            // Spelled out rather than written with `||`: the short-circuit
            // operator takes its right side as an autoclosure, which cannot
            // carry the `await` the actor hop needs.
            if walletStore.hasWallet { return true }
            return await walletKeyStore.hasWallet
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
        guard ProcessInfo.processInfo.environment["POCKETNODE_SKIP_ONBOARDING"] == "1",
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
}
