import XCTest

@testable import PocketNode

/// The onboarding state machine, driven through a real ``WalletCreator`` over
/// throwaway storage. Real because the transitions the tests care about are the
/// ones a failed derivation or a refused write actually produces.
@MainActor
final class OnboardingViewModelTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.onboarding"
    private let tag = "com.rjnr.pocketnode.tests.onboarding.wrapper"

    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var keyStore: WalletKeyStore!
    private var walletStore: WalletStore!
    private var directory: URL!
    private var model: OnboardingViewModel!

    override func setUp() async throws {
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        keyStore = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.onboarding-\(UUID().uuidString)")
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
    }

    // MARK: - Navigation

    func testTheFlowStartsOnTheWelcomeStep() {
        XCTAssertEqual(model.step, .welcome)
        XCTAssertNil(model.errorMessage)
    }

    func testWelcomeLeadsToEitherBranchAndBack() {
        model.beginCreate()
        XCTAssertEqual(model.step, .create)

        model.backToWelcome()
        XCTAssertEqual(model.step, .welcome)

        model.beginImport()
        XCTAssertEqual(model.step, .importWallet)

        model.backToWelcome()
        XCTAssertEqual(model.step, .welcome)
    }

    // MARK: - Create path

    func testCreatingAWalletRunsWelcomeToBackupToPinToDone() async {
        model.beginCreate()

        await model.createWallet(wordCount: 12, name: "Main")
        XCTAssertEqual(model.step, .backup)
        XCTAssertNil(model.errorMessage)
        XCTAssertFalse(model.isBusy)
        XCTAssertNotNil(walletStore.load(), "the wallet is stored before the backup step")

        model.finishBackup()
        XCTAssertEqual(model.step, .pinSetup)

        model.finishPinSetup()
        XCTAssertEqual(model.step, .done)
    }

    func testATwentyFourWordCreateAlsoReachesTheBackupStep() async {
        await model.createWallet(wordCount: 24, name: "Main")

        XCTAssertEqual(model.step, .backup)
        XCTAssertNil(model.errorMessage)
        // The word count itself is `WalletCreatorTests`' subject; this view
        // model never sees the phrase, by design.
    }

    // MARK: - Import path

    func testImportingAPhraseSkipsTheBackupStep() async {
        model.beginImport()

        await model.importMnemonic(words: WalletCreatorTests.testPhrase, name: "Restored")

        XCTAssertEqual(model.step, .pinSetup, "the user already holds the phrase")
        XCTAssertNil(model.errorMessage)
    }

    func testImportingAPrivateKeySkipsTheBackupStep() async {
        model.beginImport()

        await model.importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key")

        XCTAssertEqual(model.step, .pinSetup)
    }

    // MARK: - Failures

    func testABadPhraseLeavesTheUserOnTheImportStepWithAMessage() async {
        model.beginImport()

        await model.importMnemonic(words: Array(repeating: "abandon", count: 12), name: "Restored")

        XCTAssertEqual(model.step, .importWallet)
        XCTAssertEqual(model.errorMessage, "Invalid recovery phrase. Check your words and try again.")
        XCTAssertFalse(model.isBusy)
    }

    func testABadPrivateKeyLeavesTheUserOnTheImportStepWithAMessage() async {
        model.beginImport()

        await model.importPrivateKey(hex: "nope", name: "Key")

        XCTAssertEqual(model.step, .importWallet)
        XCTAssertEqual(
            model.errorMessage,
            "That is not a valid private key. It should be 64 hexadecimal characters."
        )
    }

    func testASecondWalletIsRefusedWithAMessage() async {
        await model.createWallet(wordCount: 12, name: "Main")
        model.finishBackup()

        await model.createWallet(wordCount: 12, name: "Second")

        XCTAssertEqual(model.errorMessage, "This device already has a wallet.")
    }

    func testStartingAnotherStepClearsTheLastError() async {
        await model.importMnemonic(words: Array(repeating: "abandon", count: 12), name: "Restored")
        XCTAssertNotNil(model.errorMessage)

        model.beginCreate()

        XCTAssertNil(model.errorMessage)
    }

    func testDismissingAnErrorClearsIt() async {
        await model.importPrivateKey(hex: "nope", name: "Key")
        XCTAssertNotNil(model.errorMessage)

        model.dismissError()

        XCTAssertNil(model.errorMessage)
    }

    // MARK: - Error copy

    func testEveryFailureMapsToSomethingAUserCanRead() {
        XCTAssertEqual(
            OnboardingViewModel.message(for: WalletCreationError.walletAlreadyExists),
            "This device already has a wallet."
        )
        XCTAssertEqual(
            OnboardingViewModel.message(for: WalletCreationError.invalidWordCount),
            "Choose 12 or 24 words."
        )
        XCTAssertEqual(
            OnboardingViewModel.message(for: WalletCreationError.metadataStorageFailed),
            "Could not save your wallet. Try again."
        )
        XCTAssertEqual(
            OnboardingViewModel.message(for: WalletCreationError.keyStorageFailed(.authenticationFailed)),
            "Could not confirm it was you. Try again."
        )
        XCTAssertEqual(
            OnboardingViewModel.message(for: WalletCreationError.keyStorageFailed(.keyInvalidated)),
            "This device's wallet keys are unusable. Import your wallet again."
        )
        XCTAssertEqual(
            OnboardingViewModel.message(for: WalletCreationError.keyStorageFailed(.corrupt)),
            "Could not save your wallet. Try again."
        )
        XCTAssertEqual(
            OnboardingViewModel.message(for: URLError(.badURL)),
            "Something went wrong. Try again."
        )
    }

    func testACancelledPromptSaysNothing() {
        XCTAssertNil(
            OnboardingViewModel.message(for: WalletCreationError.keyStorageFailed(.authenticationCancelled)),
            "the user dismissed the prompt themselves and knows why nothing happened"
        )
    }
}
