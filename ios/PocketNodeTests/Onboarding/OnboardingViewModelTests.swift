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

    // MARK: - Resuming an interrupted onboarding

    /// The wallet is stored before the backup and PIN steps run, so a process
    /// killed in between leaves a wallet on disk and no PIN. The next launch
    /// must land back on the unfinished step, never on the wallet.
    func testAWalletCreatedButNotBackedUpResumesAtTheBackupStepOnRelaunch() async {
        await model.createWallet(wordCount: 12, name: "Main")
        XCTAssertEqual(model.step, .backup)

        // The app is killed here. A relaunch sees only what was stored.
        XCTAssertEqual(
            OnboardingViewModel.launchDestination(
                hasWallet: walletStore.hasWallet,
                pinPresence: .absent,
                record: walletStore.load()
            ),
            .onboarding(.backup)
        )
    }

    func testAWalletBackedUpButWithoutAPinResumesAtThePinStepOnRelaunch() async throws {
        await model.createWallet(wordCount: 12, name: "Main")
        let record = try XCTUnwrap(walletStore.load())
        try walletStore.save(
            WalletRecord(
                id: record.id,
                name: record.name,
                type: record.type,
                derivationPath: record.derivationPath,
                mainnetAddress: record.mainnetAddress,
                testnetAddress: record.testnetAddress,
                mnemonicBackedUp: true,
                createdAt: record.createdAt
            )
        )

        XCTAssertEqual(
            OnboardingViewModel.launchDestination(
                hasWallet: walletStore.hasWallet,
                pinPresence: .absent,
                record: walletStore.load()
            ),
            .onboarding(.pinSetup)
        )
    }

    func testAnImportedWalletWithoutAPinResumesAtThePinStepOnRelaunch() async {
        await model.importMnemonic(words: WalletCreatorTests.testPhrase, name: "Restored")
        XCTAssertEqual(model.step, .pinSetup)

        XCTAssertEqual(
            OnboardingViewModel.launchDestination(
                hasWallet: walletStore.hasWallet,
                pinPresence: .absent,
                record: walletStore.load()
            ),
            .onboarding(.pinSetup)
        )
    }

    /// End to end over the real PIN store: until a PIN is stored the launch
    /// resumes onboarding, and once one is the wallet opens (behind the lock).
    func testOnlyAStoredPinLetsALaunchReachTheWallet() async throws {
        let pinKeychain = KeychainStore(service: "\(service).pin")
        try? pinKeychain.deleteAll()
        defer { try? pinKeychain.deleteAll() }

        await model.importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key")

        let beforePin = PinService(keychain: pinKeychain, cost: .testing)
        XCTAssertEqual(
            OnboardingViewModel.launchDestination(
                hasWallet: walletStore.hasWallet,
                pinPresence: beforePin.pinPresence,
                record: walletStore.load()
            ),
            .onboarding(.pinSetup),
            "a wallet with no PIN must not open"
        )

        try await beforePin.setPin("123456")

        // A cold start: a new service over the same Keychain.
        let afterPin = PinService(keychain: pinKeychain, cost: .testing)
        XCTAssertEqual(
            OnboardingViewModel.launchDestination(
                hasWallet: walletStore.hasWallet,
                pinPresence: afterPin.pinPresence,
                record: walletStore.load()
            ),
            .wallet
        )
    }

    func testLaunchDestinationRules() {
        let created = Self.record(type: WalletCreator.typeMnemonic, backedUp: false)

        XCTAssertEqual(
            OnboardingViewModel.launchDestination(hasWallet: false, pinPresence: .absent, record: nil),
            .onboarding(.welcome)
        )
        XCTAssertEqual(
            OnboardingViewModel.launchDestination(hasWallet: true, pinPresence: .present, record: created),
            .wallet,
            "a PIN exists, so the lock screen guards the wallet"
        )
        XCTAssertEqual(
            OnboardingViewModel.launchDestination(hasWallet: true, pinPresence: .unknown, record: created),
            .wallet,
            "an unreadable PIN store locks rather than offering to replace the PIN"
        )
        XCTAssertEqual(
            OnboardingViewModel.launchDestination(hasWallet: true, pinPresence: .absent, record: created),
            .onboarding(.backup)
        )
    }

    func testResumeStepFollowsTheRecord() {
        XCTAssertEqual(
            OnboardingViewModel.resumeStep(for: Self.record(type: WalletCreator.typeMnemonic, backedUp: false)),
            .backup
        )
        XCTAssertEqual(
            OnboardingViewModel.resumeStep(for: Self.record(type: WalletCreator.typeMnemonic, backedUp: true)),
            .pinSetup
        )
        XCTAssertEqual(
            OnboardingViewModel.resumeStep(for: Self.record(type: WalletCreator.typeRawKey, backedUp: false)),
            .pinSetup,
            "a raw key has no phrase to back up"
        )
        XCTAssertEqual(
            OnboardingViewModel.resumeStep(for: nil),
            .backup,
            "an unreadable record must not skip the backup of a phrase that may never have been written down"
        )
    }

    func testAResumedFlowCarriesOnFromTheStepItWasGiven() {
        let resumed = OnboardingViewModel(
            creator: WalletCreator(keyStore: keyStore, walletStore: walletStore),
            resumingAt: .backup
        )
        XCTAssertEqual(resumed.step, .backup)

        resumed.finishBackup()
        XCTAssertEqual(resumed.step, .pinSetup)

        resumed.finishPinSetup()
        XCTAssertEqual(resumed.step, .done)
    }

    private static func record(type: String, backedUp: Bool) -> WalletRecord {
        WalletRecord(
            id: "resume-test",
            name: "Main",
            type: type,
            mainnetAddress: "ckb1",
            testnetAddress: "ckt1",
            mnemonicBackedUp: backedUp,
            createdAt: 0
        )
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
