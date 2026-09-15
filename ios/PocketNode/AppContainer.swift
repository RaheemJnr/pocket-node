import SwiftUI

/// Manual dependency injection (design D0): one object graph created at launch
/// and handed down through the SwiftUI environment. No DI framework on iOS.
@MainActor
@Observable
final class AppContainer {
    let lightClient: LightClientService

    /// The wallet's key material at rest, protected by the Secure Enclave.
    let walletKeyStore: WalletKeyStore

    /// Kept in sync with the system color scheme by `RootView`.
    var theme: Theme = .light

    init() {
        self.lightClient = LightClientService()

        let keychain = KeychainStore()
        let wrapper = SecureEnclaveKeyWrapper()
        // Keychain items survive app deletion; a fresh install must not inherit
        // the previous one's wallet (see `InstallMarker`).
        InstallMarker().wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)
        self.walletKeyStore = WalletKeyStore(keychain: keychain, wrapper: wrapper)
    }
}
