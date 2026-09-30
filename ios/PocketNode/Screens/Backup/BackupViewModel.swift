import Foundation

/// Reads the wallet's decrypted key material. `WalletKeyStore` (an actor)
/// conforms via the extension below — its actor isolation already makes every
/// cross-boundary call implicitly asynchronous, so the non-`async` actor
/// method satisfies this `async` requirement without a wrapper. Tests
/// substitute a stub that never touches the Keychain or Secure Enclave.
protocol WalletKeyReading: Sendable {
    func load(reason: String) async throws -> WalletKeyBundle
}

extension WalletKeyStore: WalletKeyReading {}

/// The re-authentication gate `BackupViewModel` asks before decrypting the
/// phrase. `AuthService` conforms directly (its `requireAuth(reason:)` has
/// this exact shape); tests substitute a stub so the gate can be exercised
/// without a PIN pad or a biometric prompt.
@MainActor
protocol AuthGating: AnyObject {
    func requireAuth(reason: String) async -> Bool
    /// Bumped on every session lock. A reveal records it before prompting and
    /// drops its result if it moved, so a phrase never lands behind the lock.
    var lockGeneration: Int { get }
}

extension AuthService: AuthGating {}

/// Drives the recovery-phrase backup and verification flow.
///
/// Mirrors Android's `MnemonicBackupViewModel` step machine (`gate -> display
/// -> verify -> success`, `docs` in `ui/screens/onboarding/MnemonicBackupScreen.kt`),
/// simplified to the one key-material shape iOS has today: there is no
/// KDF-version split, just the app's single re-auth gate (`AuthService`).
///
/// The revealed words live in memory only for the `.display` and `.verify`
/// steps: `reveal()` populates them, and `onBackgrounded()` (the scene going
/// to the background, wired by the view to `scenePhase`) wipes them and
/// returns to `.gate` — the same ON_STOP re-arm Android does. Nothing here
/// ever logs or persists the phrase; only `WalletStore.mnemonicBackedUp` is
/// written, and that is a boolean.
@MainActor
@Observable
final class BackupViewModel {
    enum Step: Equatable {
        case gate
        /// A raw-key wallet has no mnemonic to back up; there is nothing to
        /// verify, so this step is terminal like `.success`.
        case noPhrase
        case display
        case verify
        case success
    }

    private(set) var step: Step = .gate
    private(set) var words: [String] = []
    private(set) var quiz: [BackupQuiz.Prompt] = []
    private(set) var selections: [Int: String] = [:]
    private(set) var isRevealing = false
    private(set) var errorMessage: String?

    private let walletKeyStore: any WalletKeyReading
    private let walletStore: any WalletRecordStoring
    private let auth: any AuthGating
    private let isOnboarding: Bool
    private let hasPin: () -> Bool
    private var rng: any RandomNumberGenerator

    /// Bumped on every ``onBackgrounded()``. With ``AuthGating/lockGeneration``
    /// it makes up the generation a reveal records before it prompts or reads
    /// (see ``reveal()``), the same guard as Android's `stopGeneration` in
    /// `MnemonicBackupScreen`.
    private var stopGeneration = 0

    /// - Parameters:
    ///   - isOnboarding: true only on the single first-run hop straight out of
    ///     wallet creation, before `InitialPinSetup` runs.
    ///   - hasPin: re-checked on every `reveal()`, not cached, so the
    ///     exemption cannot outlive the PIN it was granted for — the same
    ///     safeguard as Android's `isOnboardingExempt`.
    ///   - rng: seed a deterministic generator in tests; defaults to the
    ///     system RNG in production.
    init(
        walletKeyStore: any WalletKeyReading,
        walletStore: any WalletRecordStoring,
        auth: any AuthGating,
        isOnboarding: Bool,
        hasPin: @escaping () -> Bool,
        rng: any RandomNumberGenerator = SystemRandomNumberGenerator()
    ) {
        self.walletKeyStore = walletKeyStore
        self.walletStore = walletStore
        self.auth = auth
        self.isOnboarding = isOnboarding
        self.hasPin = hasPin
        self.rng = rng
    }

    /// True only when this is a verified onboarding run: the route claims it
    /// AND there is genuinely no PIN yet. A stale `isOnboarding` flag can
    /// never skip the gate on its own once a PIN exists.
    private var isOnboardingExempt: Bool { isOnboarding && !hasPin() }

    /// Runs the re-auth gate (unless exempt) and decrypts the wallet. Safe to
    /// call again after a cancelled or failed attempt — the gate step stays
    /// put until this succeeds.
    ///
    /// Both awaits here can outlast the screen: the app can go to the
    /// background, or the session can lock, while the prompt is up or the key
    /// is being read. ``onBackgrounded()`` is a no-op on ``Step/gate``, so
    /// without a check the read would land afterwards and put the phrase back
    /// on screen. The generation is recorded before the first await and
    /// re-checked after each one; a reveal that finds it moved is dropped
    /// without decrypting (after the prompt) or without showing anything
    /// (after the read). The dropped bundle's strings are released here and
    /// never copied into ``words``; Swift offers no supported way to zero a
    /// `String`, which is why nothing retains them.
    func reveal() async {
        guard !isRevealing else { return }
        isRevealing = true
        errorMessage = nil
        defer { isRevealing = false }

        let generation = currentGeneration

        if !isOnboardingExempt {
            let granted = await auth.requireAuth(reason: Self.revealReason)
            guard granted, generation == currentGeneration else { return }
        }

        do {
            let bundle = try await walletKeyStore.load(reason: Self.revealReason)
            guard generation == currentGeneration else { return }
            guard let mnemonic = bundle.mnemonic, !mnemonic.isEmpty else {
                step = .noPhrase
                return
            }
            words = mnemonic.split(separator: " ").map(String.init)
            quiz = BackupQuiz.generate(words: words, rng: &rng)
            selections = [:]
            step = .display
        } catch {
            errorMessage = Self.unreadableKeyMaterialMessage
        }
    }

    /// Moves from the word grid to the verify quiz. No-op off `.display` so a
    /// stray call (e.g. a duplicate button tap) cannot skip state.
    func advanceToVerify() {
        guard step == .display else { return }
        step = .verify
    }

    /// Records the user's choice for one quiz position.
    func select(position: Int, word: String) {
        guard step == .verify else { return }
        selections[position] = word
        errorMessage = nil
    }

    /// Whether every quiz prompt has an answer, so the view can enable the
    /// "Verify" button only once the quiz is fully answered.
    var canSubmitVerify: Bool {
        !quiz.isEmpty && quiz.allSatisfy { selections[$0.position] != nil }
    }

    /// Checks every selection against the quiz. A full match marks the wallet
    /// backed up and advances to `.success`; any miss clears the selections
    /// and regenerates the quiz with fresh positions and choices, so a lucky
    /// repeat guess on the same layout cannot pass.
    func submitVerify() {
        guard step == .verify else { return }
        guard !quiz.isEmpty else { return }
        let allCorrect = quiz.allSatisfy { selections[$0.position] == $0.correctWord }
        guard allCorrect else {
            quiz = BackupQuiz.generate(words: words, rng: &rng)
            selections = [:]
            errorMessage = "Some words are incorrect. Try again."
            return
        }
        markBackedUpAndComplete()
    }

    /// Persists `mnemonicBackedUp = true` against the stored wallet record and
    /// advances to `.success`, wiping the phrase from memory. Only reachable
    /// from a passing `submitVerify()`. A missing wallet record or a failed
    /// save is a hard stop, not a silent success: `.verify` stays put with the
    /// words and selections intact so the user can retry, rather than being
    /// told the backup is done when it was never recorded.
    private func markBackedUpAndComplete() {
        guard let record = walletStore.load() else {
            errorMessage = Self.saveFailedMessage
            return
        }
        do {
            try walletStore.save(record.withMnemonicBackedUp(true))
        } catch {
            errorMessage = Self.saveFailedMessage
            return
        }
        wipeWords()
        step = .success
    }

    /// Called when the scene goes to the background. Wipes the phrase and
    /// returns to the gate on the two steps that show it, matching Android's
    /// `onBackgrounded` (called from `ON_STOP`). On every step it also ends
    /// any reveal still in flight (see ``reveal()``).
    func onBackgrounded() {
        stopGeneration += 1
        guard step == .display || step == .verify else { return }
        wipeWords()
        step = .gate
    }

    /// Called when the OS posts `UIApplication.userDidTakeScreenshotNotification`
    /// while the phrase is on screen. `PrivacyShield` cannot block a
    /// screenshot itself — iOS exposes no such API for ordinary views — so
    /// this is the mitigation: wipe the phrase, drop back to the gate, and
    /// tell the user why so the disappearance is not mistaken for a bug.
    func onScreenshotTaken() {
        guard step == .display || step == .verify else { return }
        wipeWords()
        step = .gate
        errorMessage = Self.screenshotTakenMessage
    }

    private struct Generation: Equatable {
        let stop: Int
        let lock: Int
    }

    private var currentGeneration: Generation {
        Generation(stop: stopGeneration, lock: auth.lockGeneration)
    }

    private func wipeWords() {
        words = []
        quiz = []
        selections = [:]
        errorMessage = nil
    }

    static let revealReason = "Reveal recovery phrase"

    static let unreadableKeyMaterialMessage =
        "Could not read this wallet's keys. Restore from your recovery phrase if this persists."

    static let saveFailedMessage = "Could not save your backup status. Try again."

    static let screenshotTakenMessage =
        "A screenshot was taken. For your safety the phrase was hidden; reveal it again to continue."
}

private extension WalletRecord {
    /// A copy of this record with `mnemonicBackedUp` replaced. `WalletRecord`
    /// has no other mutable fields callers need here, so a full-field copy is
    /// the plainest way to change one.
    func withMnemonicBackedUp(_ backedUp: Bool) -> WalletRecord {
        WalletRecord(
            id: id,
            name: name,
            type: type,
            derivationPath: derivationPath,
            mainnetAddress: mainnetAddress,
            testnetAddress: testnetAddress,
            mnemonicBackedUp: backedUp,
            createdAt: createdAt
        )
    }
}
