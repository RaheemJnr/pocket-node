import Foundation
import Observation

/// Drives the first-run flow: welcome, then create or import, then the backup
/// step, then the PIN.
///
/// Screen order mirrors Android (`OnboardingScreen` to `MnemonicBackupScreen`
/// to `InitialPinSetupScreen` to Home), with one deliberate difference: a
/// wallet imported from a phrase or a raw key skips the backup step, because
/// the user already holds the phrase or has no phrase at all. That is the same
/// distinction Android draws with `mnemonicBackedUp`.
///
/// This view model never holds the generated phrase. `WalletCreator` has
/// already stored the wallet by the time ``Step/backup`` is reached, so the
/// backup screen reads the words back out of the key store through
/// `BackupViewModel.reveal()`, the same path it uses outside onboarding. That
/// leaves exactly one copy of the phrase in memory, owned by the screen that
/// shows it and wiped when the app is backgrounded.
@MainActor
@Observable
final class OnboardingViewModel {
    enum Step: Equatable {
        /// Create or import.
        case welcome
        /// Choosing word count and name for a new wallet.
        case create
        /// Entering a phrase or a private key.
        case importWallet
        /// Showing and verifying the freshly generated phrase, through
        /// ``BackupView`` in onboarding mode.
        case backup
        /// Choosing the app PIN and opting into biometrics.
        case pinSetup
        /// Onboarding is over; the root view moves on to the wallet.
        case done
    }

    private(set) var step: Step = .welcome

    /// True while a create or import is in flight. Every button that starts one
    /// is disabled on it, so a double tap cannot produce two wallets.
    private(set) var isBusy = false

    /// The last failure, already turned into something a user can read.
    private(set) var errorMessage: String?

    private let creator: WalletCreator

    /// Runs before a new wallet is created or imported. `LaunchGate` clears a
    /// PIN left behind with no wallet there and answers whether the wallet
    /// may be started; false means a PIN is still stored that the PIN step
    /// would refuse to replace, so nothing is created.
    private let prepareNewWallet: @MainActor () async -> Bool

    /// The wallet whose keys are missing, when this flow is a restore rather
    /// than a first run (see ``init(creator:restoring:hasPin:)``).
    let restoringRecord: WalletRecord?

    /// Re-read after a restore: a PIN that survived is left alone, rather than
    /// letting the restore replace it without knowing it.
    private let hasPin: () -> Bool

    /// - Parameter step: where the flow starts. ``Step/welcome`` for a device
    ///   with no wallet; ``resumeStep(for:)`` for one whose onboarding was cut
    ///   short after the wallet was stored (see ``launchDestination(hasWallet:pinPresence:record:)``).
    init(
        creator: WalletCreator,
        resumingAt step: Step = .welcome,
        prepareNewWallet: @escaping @MainActor () async -> Bool = { true }
    ) {
        self.creator = creator
        self.prepareNewWallet = prepareNewWallet
        self.restoringRecord = nil
        self.hasPin = { false }
        self.step = step
    }

    /// A restore for a wallet whose metadata is on this device but whose keys
    /// are not, the state a backup restored onto a new phone leaves (the
    /// Keychain envelope is `ThisDeviceOnly` and does not travel). The flow
    /// opens on the import step, and a phrase or key is accepted only if it
    /// is the one for `record`; it then replaces the key-less entry under the
    /// same id and address. There is no way back to the welcome step: the
    /// device already has this wallet, so creating another would be refused.
    init(creator: WalletCreator, restoring record: WalletRecord, hasPin: @escaping () -> Bool) {
        self.creator = creator
        // A restore puts keys back under an existing wallet; it never starts a
        // new one, so there is no orphaned PIN to clear first.
        self.prepareNewWallet = { true }
        self.restoringRecord = record
        self.hasPin = hasPin
        self.step = .importWallet
    }

    /// True for the restore flow.
    var isRestoring: Bool { restoringRecord != nil }

    /// True for a restore of a wallet type this version cannot restore,
    /// neither a recovery phrase nor a raw private key (a record written by a
    /// later version, say). No phrase or key typed here could match it, so
    /// the screen says so instead of offering fields that refuse everything.
    var isUnsupportedRestore: Bool {
        guard let type = restoringRecord?.type else { return false }
        return type != WalletCreator.typeMnemonic && type != WalletCreator.typeRawKey
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

    // MARK: - Launch and resume

    /// Where a launch lands: onboarding at a given step, or the wallet shell.
    enum LaunchDestination: Equatable {
        case onboarding(Step)
        case wallet
    }

    /// Decides the launch from what is stored, not from anything remembered
    /// about the last run, so a process killed between storing the wallet and
    /// setting the PIN cannot skip the security steps.
    ///
    /// The wallet is stored before the backup and PIN steps run, so a wallet
    /// on disk says nothing about whether onboarding finished. What does is
    /// the PIN: onboarding is not over until one exists, and nothing else in
    /// the app can reach the wallet shell without one. So a wallet with a
    /// confirmed absent PIN resumes onboarding at its first unfinished step
    /// (``resumeStep(for:)``) instead of opening. A PIN that is present, or
    /// that cannot be read yet, goes to the wallet, which stays behind the
    /// lock screen until it is answered; if the unreadable store later turns
    /// out to hold no PIN, `RootView` re-runs this decision.
    ///
    /// Resuming at the PIN step when a PIN already exists would let whoever
    /// holds the phone replace it without knowing it, which is why only
    /// ``PinPresence/absent`` resumes.
    static func launchDestination(
        hasWallet: Bool,
        pinPresence: PinPresence,
        record: WalletRecord?
    ) -> LaunchDestination {
        guard hasWallet else { return .onboarding(.welcome) }
        guard pinPresence == .absent else { return .wallet }
        return .onboarding(resumeStep(for: record))
    }

    /// The first unfinished security step for a wallet that is already
    /// stored: the backup, for a generated phrase the user has not verified
    /// yet, otherwise the PIN. The same rule the live flow follows (a created
    /// wallet goes to backup, an imported one straight to the PIN), read back
    /// from the record's `type` and `mnemonicBackedUp` since those are what
    /// survive the process.
    ///
    /// No readable record (a `wallet.json` that failed to decode, or keys with
    /// no metadata) resumes at the backup too: the backup step reads the
    /// phrase from the key store, so it works without the record, and
    /// skipping it would risk a generated phrase never being written down.
    static func resumeStep(for record: WalletRecord?) -> Step {
        guard let record else { return .backup }
        guard record.type == WalletCreator.typeMnemonic, !record.mnemonicBackedUp else {
            return .pinSetup
        }
        return .backup
    }

    // MARK: - Navigation

    func beginCreate() {
        // Never from a restore: the device already has this wallet.
        guard !isRestoring else { return }
        errorMessage = nil
        step = .create
    }

    func beginImport() {
        errorMessage = nil
        step = .importWallet
    }

    /// Back out of a create or import screen. Only reachable before a wallet
    /// has been stored, so there is nothing to undo.
    func backToWelcome() {
        // A restore has no welcome step: create and import would both be
        // refused, since the device already has this wallet.
        guard !isRestoring else { return }
        errorMessage = nil
        step = .welcome
    }

    func dismissError() {
        errorMessage = nil
    }

    /// Leaves the backup step, called from ``BackupView``'s "Done".
    ///
    /// `mnemonicBackedUp` is not written here: passing the verify quiz is what
    /// sets it, and `BackupViewModel` has already done so by the time the
    /// backup step calls this.
    func finishBackup() {
        errorMessage = nil
        step = .pinSetup
    }

    /// Called by ``PinSetupView`` once the PIN is stored and the biometric
    /// question has been answered.
    func finishPinSetup() {
        step = .done
    }

    // MARK: - Wallet creation

    func createWallet(wordCount: Int, name: String) async {
        await run {
            try await self.requireCleanStart()
            // The returned phrase is deliberately dropped: the wallet is
            // stored by now, and the backup step reads the words back from
            // the key store rather than from a second copy kept here.
            _ = try await self.creator.createWallet(wordCount: wordCount, name: name)
            self.step = .backup
        }
    }

    func importMnemonic(words: [String], name: String) async {
        if let record = restoringRecord {
            await run {
                try await self.creator.restoreMnemonic(words: words, replacing: record)
                self.step = self.hasPin() ? .done : .pinSetup
            }
            return
        }
        await run {
            try await self.requireCleanStart()
            try await self.creator.importMnemonic(words: words, name: name)
            // Nothing to back up: the user supplied the phrase.
            self.step = .pinSetup
        }
    }

    func importPrivateKey(hex: String, name: String) async {
        if let record = restoringRecord {
            await run {
                try await self.creator.restorePrivateKey(hex: hex, replacing: record)
                self.step = self.hasPin() ? .done : .pinSetup
            }
            return
        }
        await run {
            try await self.requireCleanStart()
            try await self.creator.importPrivateKey(hex: hex, name: name)
            self.step = .pinSetup
        }
    }

    /// Why onboarding refused to start a wallet.
    enum StartError: Error, Equatable {
        /// A PIN from earlier app data could not be cleared.
        case staleAppData
    }

    private func requireCleanStart() async throws {
        guard await prepareNewWallet() else { throw StartError.staleAppData }
    }

    private func run(_ body: () async throws -> Void) async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        do {
            try await body()
        } catch {
            errorMessage = Self.message(for: error)
        }
        isBusy = false
    }

    // MARK: - Error copy

    /// Shown when a PIN from earlier app data could not be cleared. Closing
    /// and reopening the app retries the cleanup at launch.
    static let staleAppDataMessage = "Could not clear old app data. Close the app and open it again, then try again."

    /// Maps a failure to what the user is told.
    ///
    /// `nil` for a cancelled authentication: the user dismissed the Face ID
    /// sheet themselves and knows why nothing happened, so an error banner
    /// would only be noise. Android's `OnboardingViewModel.persistErrorMessage`
    /// stays silent on its equivalent `Result.Cancelled` for the same reason.
    static func message(for error: Error) -> String? {
        if error as? StartError == .staleAppData {
            return staleAppDataMessage
        }
        guard let error = error as? WalletCreationError else {
            return "Something went wrong. Try again."
        }
        switch error {
        case .walletAlreadyExists:
            return "This device already has a wallet."
        case .invalidWordCount:
            return "Choose 12 or 24 words."
        case .invalidMnemonic:
            return "Invalid recovery phrase. Check your words and try again."
        case .invalidPrivateKey:
            return "That is not a valid private key. It should be 64 hexadecimal characters."
        case .keyStorageFailed(let reason):
            switch reason {
            case .authenticationCancelled:
                return nil
            case .authenticationFailed:
                return "Could not confirm it was you. Try again."
            case .keyInvalidated:
                return "This device's wallet keys are unusable. Import your wallet again."
            default:
                return "Could not save your wallet. Try again."
            }
        case .metadataStorageFailed:
            return "Could not save your wallet. Try again."
        case .doesNotMatchWallet:
            return "That does not match this wallet. Enter the recovery phrase or private key for the address shown."
        }
    }
}
