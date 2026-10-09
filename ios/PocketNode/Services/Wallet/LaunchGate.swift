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
    /// fresh: ``launchDestination`` resumes its onboarding rather than
    /// starting over at Welcome.
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
            if !hasWallet, pinPresence != .absent {
                let envelope = await walletKeyStore.envelopePresence
                if envelope == .unknown { return .wallet }
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
        /// key envelope is there (keys without metadata are rebuilt or
        /// replaced through ``LaunchGate/restoreRoute(pending:)``, which runs
        /// first), or the lookup still cannot answer and is asked again next
        /// time.
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

    // MARK: - Starting sync

    /// Whether the sync layer may be pointed at the stored wallet now.
    ///
    /// Only a wallet that is really there: its record can be read, its keys
    /// read as ``KeyHealth/usable``, its PIN is set and no recovery is
    /// waiting for it. Sync reads no key material, so this is not a security
    /// gate. It keeps the node from registering a wallet whose onboarding was
    /// cut short, whose keys did not come across with a device backup, or
    /// whose keys were invalidated or suspended, on the way to the screen
    /// that deals with it.
    ///
    /// - Parameters:
    ///   - record: the stored wallet, or nil when there is none or it cannot
    ///     be read yet (data protection before the first device unlock).
    ///   - health: the stored keys' ``KeyHealth``. Only ``KeyHealth/usable``
    ///     syncs; ``KeyHealth/unknown`` waits for a later call that can
    ///     answer, and ``KeyHealth/suspended`` or ``KeyHealth/invalidated``
    ///     wait for the recovery `RootView` routes to.
    ///   - needsSecuritySetup: ``needsSecuritySetup``.
    ///   - pendingRestore: whether `RootView` is holding a recovery.
    static func maySync(
        record: WalletRecord?,
        health: KeyHealth,
        needsSecuritySetup: Bool,
        pendingRestore: Bool
    ) -> Bool {
        record != nil && health == .usable && !needsSecuritySetup && !pendingRestore
    }

    /// Carries out ``maySync(record:health:needsSecuritySetup:pendingRestore:)``
    /// over the stores. `RootView` asks it whenever it lands in, or stays in,
    /// the wallet phase: at launch, after onboarding, after a recovery, and on
    /// every reroute (session change, return to the front), so a wallet that
    /// could not be read at first starts syncing once it can.
    ///
    /// The `POCKETNODE_SKIP_ONBOARDING` wallet has no keys and no PIN on
    /// purpose, and the wallet shell UI tests need it synced, so with that
    /// hook a readable record is enough.
    func maySync(pendingRestore: Bool) async -> Bool {
        let record = walletStore.load()
        if skipsOnboarding { return record != nil }
        guard record != nil else { return false }
        return Self.maySync(
            record: record,
            health: await walletKeyStore.keyHealth,
            needsSecuritySetup: needsSecuritySetup,
            pendingRestore: pendingRestore
        )
    }

    // MARK: - Recovering keys and metadata

    /// What the root should do about a wallet whose keys or metadata cannot
    /// be used as they are.
    ///
    /// A backup restored onto a new phone brings `wallet.json` back without
    /// the `ThisDeviceOnly` Keychain items; a change to Face ID or the
    /// passcode can leave the envelope behind without its Secure Enclave key;
    /// and a crash or a lost file can leave keys with no metadata. Each of
    /// those would otherwise open a wallet shell that shows an address it can
    /// never spend from, or none at all.
    enum Recovery: Equatable {
        /// Metadata, and key material confirmed absent: restore the keys for
        /// this wallet (its phrase must derive its addresses).
        case keysMissing(WalletRecord)
        /// Metadata, and an envelope that can never be decrypted again:
        /// restore the keys for this wallet the same way, replacing the
        /// unusable envelope.
        case keysInvalidated(WalletRecord)
        /// Usable keys with missing or undecodable metadata: rebuild
        /// `wallet.json` from the keys, with no input from the user.
        case rebuildMetadata
        /// Unusable keys with missing or undecodable metadata: nothing to
        /// check a phrase against, so import any phrase over the dead keys.
        /// Only on structural invalidation, never for suspended keys.
        case replaceKeys
        /// Metadata, and an envelope retired after a refusal that may not
        /// repeat (``KeyHealth/suspended``): the same restore as
        /// ``keysInvalidated(_:)``, plus "Try unlocking again", which
        /// decrypts once more and brings the wallet back if it works.
        case keysSuspended(WalletRecord)

        /// Stable while the same recovery stays pending, for `RootView`'s
        /// retry task.
        var id: String {
            switch self {
            case .keysMissing(let record): return "missing-\(record.id)"
            case .keysInvalidated(let record): return "invalidated-\(record.id)"
            case .rebuildMetadata: return "rebuild"
            case .replaceKeys: return "replace"
            case .keysSuspended(let record): return "suspended-\(record.id)"
            }
        }
    }

    /// The routing `RootView` carries out for a ``Recovery``.
    enum RestoreRoute: Equatable {
        /// Start this recovery now.
        case restore(Recovery)
        /// Keep this recovery pending: behind the lock screen until the
        /// session is unlocked, or until the Keychain can answer again.
        case hold(Recovery)
        /// Nothing to recover; carry on with the PIN routing.
        case none
    }

    /// What `wallet.json` holds, as far as the launch can tell.
    enum MetadataState: Equatable {
        case record(WalletRecord)
        /// No file.
        case missing
        /// A file that reads but does not decode.
        case undecodable
        /// A file that cannot be read (data protection while the device is
        /// locked): nothing is known about it.
        case unreadable
    }

    /// The recovery a given state calls for, before the PIN is considered.
    enum RecoveryDecision: Equatable {
        case recover(Recovery)
        /// Nothing is known well enough to act on: keep what is pending, if
        /// anything, and ask again on the next trigger.
        case wait
        case none
    }

    /// The decision table, from what the stores answered.
    ///
    /// | keys \ metadata | record             | missing / undecodable | unreadable |
    /// |-----------------|--------------------|-----------------------|------------|
    /// | usable          | none               | rebuildMetadata       | wait       |
    /// | absent          | keysMissing        | none (walletless)     | wait       |
    /// | invalidated     | keysInvalidated    | replaceKeys           | wait       |
    /// | suspended       | keysSuspended      | rebuildMetadata       | wait       |
    /// | unknown         | wait               | wait                  | wait       |
    ///
    /// An unknown key health is never read as absent or invalidated, so a
    /// Keychain that refuses a lookup never routes to a restore or an import
    /// and never deletes anything.
    static func recoveryDecision(metadata: MetadataState, health: KeyHealth) -> RecoveryDecision {
        if health == .unknown { return .wait }
        switch metadata {
        case .unreadable:
            return .wait
        case .record(let record):
            switch health {
            case .usable, .unknown: return .none
            case .absent: return .recover(.keysMissing(record))
            case .invalidated: return .recover(.keysInvalidated(record))
            case .suspended: return .recover(.keysSuspended(record))
            }
        case .missing, .undecodable:
            switch health {
            case .usable: return .recover(.rebuildMetadata)
            // The rebuild decrypts once more: a decrypt that works binds the
            // envelope back and writes the record. The import over the keys
            // is never offered for a refusal that may not repeat.
            case .suspended: return .recover(.rebuildMetadata)
            case .invalidated: return .recover(.replaceKeys)
            case .absent, .unknown: return .none
            }
        }
    }

    /// Decides the recovery routing from what is stored, for `RootView` to
    /// carry out. Asked at launch and on every reroute of the wallet phase.
    ///
    /// Every recovery waits behind the lock screen while a PIN stands in
    /// front of the wallet (``mayStartRestore(pinPresence:sessionUnlocked:)``):
    /// the restore screens name the wallet and show its address, and
    /// rebuilding the metadata decrypts the keys.
    ///
    /// - Parameter pending: the recovery `RootView` is already holding, if
    ///   any. A Keychain lookup that fails right after an unlock says nothing
    ///   about the keys, so it keeps that recovery pending rather than
    ///   dropping it; only a state that answers ends it.
    func restoreRoute(pending: Recovery? = nil) async -> RestoreRoute {
        // The metadata-only wallet `POCKETNODE_SKIP_ONBOARDING` seeds for
        // the wallet shell UI tests has no keys on purpose.
        if skipsOnboarding { return .none }
        let metadata = metadataState
        let health = await walletKeyStore.keyHealth
        switch Self.recoveryDecision(metadata: metadata, health: health) {
        case .none:
            return .none
        case .wait:
            return pending.map { .hold($0) } ?? .none
        case .recover(let recovery):
            let mayStart = Self.mayStartRestore(
                pinPresence: pinService.pinPresence,
                sessionUnlocked: auth.state == .unlocked
            )
            return mayStart ? .restore(recovery) : .hold(recovery)
        }
    }

    private var metadataState: MetadataState {
        if let record = walletStore.load() { return .record(record) }
        if !walletStore.hasWallet { return .missing }
        return walletStore.hasUndecodableRecord ? .undecodable : .unreadable
    }

    /// What follows an attempt to rebuild the metadata.
    enum RebuildOutcome: Equatable {
        /// `wallet.json` is back; carry on with the PIN routing.
        case rebuilt
        /// The decrypt failed with a structural proof on the allowlist
        /// (``WalletKeyStoreError/provesKeysUnusable``: the key absent, or a
        /// ciphertext or bundle that fails after a good unwrap), and the key
        /// store now reads the keys as ``KeyHealth/invalidated``: replace
        /// them, as for ``Recovery/replaceKeys``. A refused decrypt only
        /// suspends the keys and is a retry.
        case keysUnusable
        /// The prompt was dismissed or failed, or a write failed. Worth
        /// another try, but only when the user asks: retrying on a timer
        /// would put the system prompt up again and again.
        case retryOnRequest
    }

    /// Classifies the result of ``WalletCreator/rebuildMetadata(reason:)``
    /// (nil for success, otherwise the error it threw) by what the key store
    /// reports right after it.
    ///
    /// Keyed on the health rather than on the error alone, so the route can
    /// never disagree with what the replacement will accept: the store marks
    /// the keys invalidated on exactly the failures that prove them
    /// unusable, and ``WalletCreator/replaceUnusableKeys(words:name:)``
    /// requires that same health. A failure the store does not count (a
    /// dismissed prompt) stays a retry.
    static func rebuildOutcome(error: Error?, healthAfter: KeyHealth) -> RebuildOutcome {
        guard error != nil else { return .rebuilt }
        return healthAfter == .invalidated ? .keysUnusable : .retryOnRequest
    }

    /// ``rebuildOutcome(error:healthAfter:)`` with the health read now.
    func rebuildOutcome(error: Error?) async -> RebuildOutcome {
        let health = await walletKeyStore.keyHealth
        return Self.rebuildOutcome(error: error, healthAfter: health)
    }

    /// Whether a restore may open now or must wait behind the lock screen.
    ///
    /// The restore screen names the wallet and shows its address, so when a
    /// PIN survived (or cannot be read yet) it waits until the session has
    /// been unlocked with it. With a confirmed absent PIN there is nothing to
    /// wait for.
    static func mayStartRestore(pinPresence: PinPresence, sessionUnlocked: Bool) -> Bool {
        pinPresence == .absent || sessionUnlocked
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
