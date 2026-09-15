import XCTest

@testable import PocketNode

/// The seam between onboarding and the backup flow.
///
/// The hand-off is deliberately not a phrase passed between view models: the
/// wallet is already stored when the backup step is reached, so the words go
/// from `WalletCreator` into the key store and come back out through
/// `BackupViewModel.reveal()`. This drives that whole path over a throwaway
/// Keychain and Secure Enclave key, then checks the quiz is what records the
/// wallet as backed up and that onboarding resumes at the PIN step.
@MainActor
final class OnboardingBackupWiringTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.wiring.onboarding"
    private let tag = "com.rjnr.pocketnode.tests.wiring.onboarding.wrapper"

    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var keyStore: WalletKeyStore!
    private var walletStore: WalletStore!
    private var directory: URL!
    private var model: OnboardingViewModel!

    override func setUp() async throws {
        try await super.setUp()
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        keyStore = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(service)-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        model = OnboardingViewModel(
            creator: WalletCreator(keyStore: keyStore, walletStore: walletStore)
        )
    }

    override func tearDown() async throws {
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        try? FileManager.default.removeItem(at: directory)
        model = nil
        walletStore = nil
        keyStore = nil
        wrapper = nil
        keychain = nil
        directory = nil
        try await super.tearDown()
    }

    /// The backup step's view model as `AppContainer.makeBackupViewModel`
    /// builds it for onboarding, over the same real key store the wallet was
    /// just written to.
    private func makeOnboardingBackup(
        gate: StubAuthGate,
        hasPin: @escaping () -> Bool = { false }
    ) -> BackupViewModel {
        BackupViewModel(
            walletKeyStore: keyStore,
            walletStore: walletStore,
            auth: gate,
            isOnboarding: true,
            hasPin: hasPin
        )
    }

    func testTheBackupStepReadsThePhraseOfTheWalletOnboardingJustCreated() async throws {
        await model.createWallet(wordCount: 12, name: "Main")

        XCTAssertEqual(model.step, .backup)
        XCTAssertEqual(walletStore.load()?.mnemonicBackedUp, false)

        let gate = StubAuthGate()
        let backup = makeOnboardingBackup(gate: gate)

        await backup.reveal()

        XCTAssertEqual(backup.step, .display)
        XCTAssertEqual(backup.words.count, 12, "the phrase comes back out of the key store")
        XCTAssertEqual(
            gate.requestCount,
            0,
            "no PIN exists yet during onboarding, so there is nothing to re-authenticate against"
        )

        backup.advanceToVerify()
        for prompt in backup.quiz {
            backup.select(position: prompt.position, word: prompt.correctWord)
        }
        backup.submitVerify()

        XCTAssertEqual(backup.step, .success)
        XCTAssertTrue(backup.words.isEmpty, "the phrase must not outlive the screen that shows it")
        XCTAssertEqual(
            walletStore.load()?.mnemonicBackedUp,
            true,
            "passing the quiz is what records the wallet as backed up"
        )

        // What `BackupView`'s "Done" calls in the onboarding flow.
        model.finishBackup()

        XCTAssertEqual(model.step, .pinSetup)
    }

    func testBackgroundingTheBackupStepWipesThePhraseAndReArmsTheGate() async {
        await model.createWallet(wordCount: 12, name: "Main")
        let backup = makeOnboardingBackup(gate: StubAuthGate())
        await backup.reveal()
        XCTAssertEqual(backup.step, .display)

        // `BackupView` calls this from `scenePhase == .background`, which is
        // why the onboarding view model does not need a wipe of its own: it
        // never holds the phrase.
        backup.onBackgrounded()

        XCTAssertEqual(backup.step, .gate)
        XCTAssertTrue(backup.words.isEmpty)
        XCTAssertTrue(backup.quiz.isEmpty)
    }

    func testAStaleOnboardingFlagStillHitsTheGateOnceAPinExists() async {
        await model.createWallet(wordCount: 12, name: "Main")
        let gate = StubAuthGate()
        gate.granted = false
        // The same route, re-entered after the PIN was set: the exemption is
        // re-checked on every reveal rather than cached at construction.
        let backup = makeOnboardingBackup(gate: gate, hasPin: { true })

        await backup.reveal()

        XCTAssertEqual(gate.requestCount, 1)
        XCTAssertEqual(backup.step, .gate)
        XCTAssertTrue(backup.words.isEmpty)
    }
}
