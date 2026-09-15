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
/// The generated phrase lives in ``pendingMnemonic`` and nowhere else, only
/// while ``step`` is ``Step/backup``. Leaving that step clears it.
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
        /// Showing the freshly generated phrase. #516 replaces the placeholder
        /// here with the real backup and verification screen.
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

    /// The freshly generated phrase, non-empty only during ``Step/backup``.
    private(set) var pendingMnemonic: [String] = []

    private let creator: WalletCreator

    init(creator: WalletCreator) {
        self.creator = creator
    }

    // MARK: - Navigation

    func beginCreate() {
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
        errorMessage = nil
        pendingMnemonic = []
        step = .welcome
    }

    func dismissError() {
        errorMessage = nil
    }

    /// Leaves the backup step. The phrase is dropped here: the wallet is
    /// already stored, and the view model is not a second copy of it.
    ///
    /// `mnemonicBackedUp` stays false, which is what the placeholder means. The
    /// real verification in #516 is what will be allowed to set it.
    func finishBackup() {
        pendingMnemonic = []
        errorMessage = nil
        step = .pinSetup
    }

    /// Called by ``PinSetupView`` once the PIN is stored and the biometric
    /// question has been answered.
    func finishPinSetup() {
        pendingMnemonic = []
        step = .done
    }

    // MARK: - Wallet creation

    func createWallet(wordCount: Int, name: String) async {
        await run {
            let created = try await self.creator.createWallet(wordCount: wordCount, name: name)
            self.pendingMnemonic = created.mnemonic
            self.step = .backup
        }
    }

    func importMnemonic(words: [String], name: String) async {
        await run {
            try await self.creator.importMnemonic(words: words, name: name)
            // Nothing to back up: the user supplied the phrase.
            self.step = .pinSetup
        }
    }

    func importPrivateKey(hex: String, name: String) async {
        await run {
            try await self.creator.importPrivateKey(hex: hex, name: name)
            self.step = .pinSetup
        }
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

    /// Maps a failure to what the user is told.
    ///
    /// `nil` for a cancelled authentication: the user dismissed the Face ID
    /// sheet themselves and knows why nothing happened, so an error banner
    /// would only be noise. Android's `OnboardingViewModel.persistErrorMessage`
    /// stays silent on its equivalent `Result.Cancelled` for the same reason.
    static func message(for error: Error) -> String? {
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
        }
    }
}
