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
