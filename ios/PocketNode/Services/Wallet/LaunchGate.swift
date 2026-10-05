import Foundation

/// The launch-time decisions about the stored wallet and its PIN, in one place
/// so they can be tested over throwaway stores rather than only through
/// `AppContainer`, which always opens the real ones.
///
/// It owns the session (`PinService`, `AuthService`) because two of its jobs
/// have to run before the session reads the PIN store: clearing a PIN left
/// with no wallet, and setting aside a `wallet.json` that cannot be decoded.
@MainActor
final class LaunchGate {
    let pinService: PinService
    let auth: AuthService

    private let walletStore: WalletStore
    private let walletKeyStore: WalletKeyStore
    private let keyKeychain: any KeyValueStoring
    private let pinKeychain: any KeyValueStoring
    private let preferences: UserDefaultsPreferences
    private let skipsOnboarding: Bool

    /// - Parameter skipsOnboarding: the debug `POCKETNODE_SKIP_ONBOARDING`
    ///   hook, for the UI tests that drive the wallet shell over a seeded,
    ///   key-less, PIN-less wallet. Always false in release builds.
    init(
        walletStore: WalletStore,
        walletKeyStore: WalletKeyStore,
        keyKeychain: any KeyValueStoring,
        pinKeychain: any KeyValueStoring,
        preferences: UserDefaultsPreferences,
        biometrics: any BiometricAuthenticating,
        pinCost: Argon2Cost = .production,
        skipsOnboarding: Bool = false
    ) {
        self.walletStore = walletStore
        self.walletKeyStore = walletKeyStore
        self.keyKeychain = keyKeychain
        self.pinKeychain = pinKeychain
        self.preferences = preferences
        self.skipsOnboarding = skipsOnboarding

        // Both before `PinService` seeds its state from the store, so the
        // first frame already reflects them. The record goes first: once a
        // corrupt record is out of the way, a PIN in front of it is orphaned.
        Self.setAsideUndecodableRecordIfWalletless(walletStore: walletStore, keyKeychain: keyKeychain)
        // `AppContainer`'s fresh-install PIN wipe is not retried if it fails,
        // and a PIN left with no wallet would refuse every PIN the next
        // onboarding tries to set.
        OrphanedPin.removeIfOrphaned(
            walletMetadataExists: walletStore.hasWallet,
            keyKeychain: keyKeychain,
            pinKeychain: pinKeychain,
            preferences: preferences
        )

        let pinService = PinService(keychain: pinKeychain, cost: pinCost)
        self.pinService = pinService
        self.auth = AuthService(pin: pinService, biometrics: biometrics, preferences: preferences)
    }

    // MARK: - Launch

    /// Whether onboarding has already been completed: metadata or a key
    /// envelope. A device with only one of them is mid-failure rather than
    /// fresh (see `AppContainer.hasWallet`).
    var hasWallet: Bool {
        get async {
            if walletStore.hasWallet { return true }
            return await walletKeyStore.hasWallet
        }
    }

    /// Where this launch lands: see
    /// ``OnboardingViewModel/launchDestination(hasWallet:pinPresence:record:)``.
    ///
    /// The PIN is read through the session (``AuthService/refresh()``), the
    /// same source ``needsSecuritySetup`` reads, so the two cannot disagree
    /// and leave `RootView` on a screen neither one routes away from.
    var launchDestination: OnboardingViewModel.LaunchDestination {
        get async {
            Self.setAsideUndecodableRecordIfWalletless(walletStore: walletStore, keyKeychain: keyKeychain)
            await auth.refresh()
            let hasWallet = await self.hasWallet
            if hasWallet && skipsOnboarding { return .wallet }
            return OnboardingViewModel.launchDestination(
                hasWallet: hasWallet,
                pinPresence: auth.state == .noPin ? .absent : pinService.pinPresence,
                record: walletStore.load()
            )
        }
    }

    /// True while the session reports no PIN. The wallet shell must not be
    /// shown in that state; `RootView` sends it to the unfinished onboarding
    /// step instead (``reroute()``).
    var needsSecuritySetup: Bool {
        !skipsOnboarding && auth.state == .noPin
    }

    /// Where the wallet phase should go instead, or nil to stay. Called when
    /// the session changes while the wallet shell (or its blank stand-in) is
    /// up. Re-reads the PIN first, so a stale session cannot hold the shell
    /// on a blank screen.
    func reroute() async -> OnboardingViewModel.LaunchDestination? {
        await auth.refresh()
        guard needsSecuritySetup else { return nil }
        let destination = await launchDestination
        return destination == .wallet ? nil : destination
    }

    // MARK: - Starting a new wallet

    /// Clears what an earlier install may have left behind and reports
    /// whether a new wallet may be created now.
    ///
    /// False while a PIN is stored (or cannot be read) and the session has
    /// not been unlocked with it: the wallet would be created, the PIN step
    /// would then refuse to replace that PIN, and the next launch would ask
    /// for a PIN the user never chose. Onboarding refuses to start the wallet
    /// instead, with a message that says what to do.
    func prepareNewWallet() async -> Bool {
        if OrphanedPin.removeIfOrphaned(
            walletMetadataExists: walletStore.hasWallet,
            keyKeychain: keyKeychain,
            pinKeychain: pinKeychain,
            preferences: preferences
        ) {
            await auth.refresh()
        } else {
            await pinService.refresh()
        }
        return pinService.pinPresence == .absent || auth.state == .unlocked
    }

    // MARK: - Corrupt metadata

    /// A `wallet.json` that cannot be decoded, with a key envelope the
    /// Keychain confirms is absent, is not a wallet: there is no record to
    /// show and no key to back up or restore, and leaving it in place would
    /// resume onboarding at a backup step that can never read a phrase. It is
    /// moved aside so the device reads as walletless. Never done while the
    /// envelope is present or cannot be read.
    private static func setAsideUndecodableRecordIfWalletless(
        walletStore: WalletStore,
        keyKeychain: any KeyValueStoring
    ) {
        guard walletStore.hasUndecodableRecord else { return }
        guard let hasEnvelope = try? keyKeychain.contains(account: WalletKeyAccount.envelope),
              !hasEnvelope
        else { return }
        try? walletStore.setAsideUndecodableRecord()
    }
}
