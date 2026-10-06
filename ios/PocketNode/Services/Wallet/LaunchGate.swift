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
    ///
    /// No metadata and a key envelope the Keychain could not look up (a
    /// launch before the first device unlock) is not a walletless device when
    /// a PIN is there or may be: the envelope may well exist. Welcome would
    /// let the user start a wallet that `prepareNewWallet` then refuses for
    /// as long as that PIN stands, so the launch goes to the lock screen
    /// instead, as for any other wallet behind a PIN.
    var launchDestination: OnboardingViewModel.LaunchDestination {
        get async {
            Self.setAsideUndecodableRecordIfWalletless(walletStore: walletStore, keyKeychain: keyKeychain)
            await auth.refresh()
            let hasWallet = await self.hasWallet
            if hasWallet && skipsOnboarding { return .wallet }
            let pinPresence: PinPresence = auth.state == .noPin ? .absent : pinService.pinPresence
            if !hasWallet, pinPresence != .absent, await walletKeyStore.envelopePresence == .unknown {
                return .wallet
            }
            return OnboardingViewModel.launchDestination(
                hasWallet: hasWallet,
                pinPresence: pinPresence,
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
    /// up, and when the app comes back to the front. Re-reads the PIN first,
    /// so a stale session cannot hold the shell on a blank screen.
    ///
    /// The provisional wallet is checked first: a launch that found no
    /// metadata and a key envelope it could not look up went behind the lock
    /// on the chance a wallet is there (``launchDestination``). Once the
    /// lookup answers that there is none, the device starts over the way a
    /// cold launch would, instead of leaving an unlocked user on an empty
    /// wallet shell until the next restart.
    func reroute() async -> OnboardingViewModel.LaunchDestination? {
        if let startOver = await startOverIfNoWalletAfterAll() { return startOver }
        await auth.refresh()
        guard needsSecuritySetup else { return nil }
        let destination = await launchDestination
        return destination == .wallet ? nil : destination
    }

    /// What to do with a wallet phase that has no metadata behind it.
    enum ProvisionalWalletCheck: Equatable {
        /// Keep the wallet phase: there is metadata (readable or not), the
        /// key envelope is there (keys without metadata, as before), or the
        /// lookup still cannot answer and is asked again next time.
        case stay
        /// No metadata and no envelope, both confirmed: there is no wallet.
        case startOver
    }

    /// The decision on its own, from what the stores answered.
    ///
    /// - Parameters:
    ///   - metadataExists: whether `wallet.json` is on disk. A file that is
    ///     there but cannot be read counts as existing: a failed read is not
    ///     an absence.
    ///   - envelope: the key envelope lookup.
    static func provisionalWalletCheck(metadataExists: Bool, envelope: KeyMaterialPresence) -> ProvisionalWalletCheck {
        guard !metadataExists, envelope == .absent else { return .stay }
        return .startOver
    }

    /// Carries out ``provisionalWalletCheck(metadataExists:envelope:)``. On
    /// ``ProvisionalWalletCheck/startOver`` it does what a cold launch does
    /// for no wallet: the orphaned PIN cleanup (which itself deletes only on
    /// a confirmed absent envelope and absent metadata), then the launch
    /// decision, which lands on Welcome. If the PIN cannot be cleared,
    /// Welcome says so when a wallet is started, as at launch.
    private func startOverIfNoWalletAfterAll() async -> OnboardingViewModel.LaunchDestination? {
        guard !skipsOnboarding else { return nil }
        let check = Self.provisionalWalletCheck(
            metadataExists: walletStore.hasWallet,
            envelope: await walletKeyStore.envelopePresence
        )
        guard check == .startOver else { return nil }
        OrphanedPin.removeIfOrphaned(
            walletMetadataExists: walletStore.hasWallet,
            keyKeychain: keyKeychain,
            pinKeychain: pinKeychain,
            preferences: preferences
        )
        let destination = await launchDestination
        return destination == .wallet ? nil : destination
    }

    // MARK: - Restoring missing keys

    /// What the root should do about a wallet whose keys may not have come
    /// across with a device backup.
    ///
    /// That is what restoring an iCloud or Finder backup onto a new phone
    /// leaves: `wallet.json` comes back, the `ThisDeviceOnly` Keychain items
    /// do not. Such a wallet shows an address it can never spend from, and
    /// ``WalletCreator`` would refuse to import it again because a wallet is
    /// "already there", so `RootView` sends it to the restore flow instead of
    /// the wallet shell. A Keychain that cannot be read yet (a launch before
    /// the first device unlock) is not an absence and does not count. Now
    /// that `wallet.json` is excluded from backups, only a backup made before
    /// that exclusion (or by an older build) can produce metadata without keys.
    enum RestoreRoute: Equatable {
        /// Open the restore flow for this wallet now.
        case restore(WalletRecord)
        /// Keep this wallet's restore pending: behind the lock screen until
        /// the session is unlocked, or until the Keychain can say whether its
        /// keys are there.
        case hold(WalletRecord)
        /// Nothing to restore; carry on with the PIN routing.
        case none
    }

    /// Decides the restore routing from what is stored, for `RootView` to
    /// carry out. Asked at launch and on every reroute of the wallet phase.
    ///
    /// - Parameter pending: the restore `RootView` is already holding, if
    ///   any. A Keychain lookup that fails right after an unlock says nothing
    ///   about the keys, so it keeps that restore pending rather than dropping
    ///   it; only an envelope confirmed present (or the metadata gone) ends it.
    func restoreRoute(pending: WalletRecord? = nil) async -> RestoreRoute {
        // The metadata-only wallet `POCKETNODE_SKIP_ONBOARDING` seeds for
        // the wallet shell UI tests has no keys on purpose.
        if skipsOnboarding { return .none }
        guard let record = walletStore.load() else { return .none }
        switch await walletKeyStore.envelopePresence {
        case .present:
            return .none
        case .unknown:
            return pending.map { .hold($0) } ?? .none
        case .absent:
            let mayStart = OnboardingViewModel.mayStartRestore(
                pinPresence: pinService.pinPresence,
                sessionUnlocked: auth.state == .unlocked
            )
            return mayStart ? .restore(record) : .hold(record)
        }
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
