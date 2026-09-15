import XCTest

@testable import PocketNode

@MainActor
final class BackupViewModelTests: XCTestCase {
    private var directory: URL!
    private var walletStore: WalletStore!

    // `async` on purpose: the non-async `setUp()` override of a `nonisolated`
    // superclass method runs task-isolated, so it cannot touch this
    // `@MainActor` test case's stored properties. The async form inherits the
    // class's isolation (see `PinServiceTests` for the same note).
    override func setUp() async throws {
        try await super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.backupVM-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
        directory = nil
        walletStore = nil
        try await super.tearDown()
    }

    private func makeRecord(type: String = "mnemonic", backedUp: Bool = false) -> WalletRecord {
        WalletRecord(
            id: "w1",
            name: "Main Wallet",
            type: type,
            derivationPath: type == "mnemonic" ? "m/44'/309'/0'/0/0" : nil,
            mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
            testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
            mnemonicBackedUp: backedUp,
            createdAt: 1_700_000_000_000
        )
    }

    private func makeViewModel(
        bundle: WalletKeyBundle = WalletKeyBundle(
            privateKeyHex: "00",
            mnemonic: "abandon ability able about above absent absorb abstract absurd abuse access accident"
        ),
        auth: StubAuthGate = StubAuthGate(),
        isOnboarding: Bool = false,
        hasPin: @escaping () -> Bool = { true }
    ) -> (BackupViewModel, StubWalletKeyReader, StubAuthGate) {
        let reader = StubWalletKeyReader(result: .success(bundle))
        let vm = BackupViewModel(
            walletKeyStore: reader,
            walletStore: walletStore,
            auth: auth,
            isOnboarding: isOnboarding,
            hasPin: hasPin,
            rng: SeededRandomNumberGenerator(seed: 123)
        )
        return (vm, reader, auth)
    }

    // MARK: - Gate

    func testOnboardingExemptionSkipsTheGate() async {
        let auth = StubAuthGate()
        let (vm, reader, _) = makeViewModel(auth: auth, isOnboarding: true, hasPin: { false })

        await vm.reveal()

        XCTAssertEqual(auth.requestCount, 0, "onboarding exemption must not prompt for auth")
        XCTAssertEqual(reader.loadCount, 1)
        XCTAssertEqual(vm.step, .display)
    }

    func testOnboardingFlagAloneDoesNotSkipTheGateOnceAPinExists() async {
        let auth = StubAuthGate()
        auth.granted = true
        let (vm, _, _) = makeViewModel(auth: auth, isOnboarding: true, hasPin: { true })

        await vm.reveal()

        XCTAssertEqual(auth.requestCount, 1, "a PIN existing must re-require auth even on the onboarding route")
        XCTAssertEqual(vm.step, .display)
    }

    func testNonOnboardingPathRequiresAuth() async {
        let auth = StubAuthGate()
        let (vm, reader, _) = makeViewModel(auth: auth, isOnboarding: false)

        await vm.reveal()

        XCTAssertEqual(auth.requestCount, 1)
        XCTAssertEqual(auth.lastReason, BackupViewModel.revealReason)
        XCTAssertEqual(reader.loadCount, 1)
        XCTAssertEqual(vm.step, .display)
    }

    func testCancellingAuthKeepsTheGate() async {
        let auth = StubAuthGate()
        auth.granted = false
        let (vm, reader, _) = makeViewModel(auth: auth, isOnboarding: false)

        await vm.reveal()

        XCTAssertEqual(vm.step, .gate)
        XCTAssertEqual(reader.loadCount, 0, "the phrase must never be read if auth was refused")
        XCTAssertTrue(vm.words.isEmpty)
    }

    func testRevealCanBeRetriedAfterACancel() async {
        let auth = StubAuthGate()
        auth.granted = false
        let (vm, _, _) = makeViewModel(auth: auth, isOnboarding: false)

        await vm.reveal()
        XCTAssertEqual(vm.step, .gate)

        auth.granted = true
        await vm.reveal()
        XCTAssertEqual(vm.step, .display)
    }

    func testUnreadableKeyMaterialShowsAnErrorAndStaysOnTheGate() async {
        let reader = StubWalletKeyReader(result: .failure(StubWalletKeyReaderError.unreadable))
        let vm = BackupViewModel(
            walletKeyStore: reader,
            walletStore: walletStore,
            auth: StubAuthGate(),
            isOnboarding: false,
            hasPin: { true }
        )

        await vm.reveal()

        XCTAssertEqual(vm.step, .gate)
        XCTAssertEqual(vm.errorMessage, BackupViewModel.unreadableKeyMaterialMessage)
    }

    // MARK: - Raw-key wallet

    func testRawKeyWalletWithNoMnemonicGoesToNoPhraseStep() async {
        let (vm, _, _) = makeViewModel(bundle: WalletKeyBundle(privateKeyHex: "00", mnemonic: nil))

        await vm.reveal()

        XCTAssertEqual(vm.step, .noPhrase)
        XCTAssertTrue(vm.words.isEmpty)
    }

    // MARK: - Verify

    func testWrongAnswersResetTheQuizAndClearSelections() async {
        let (vm, _, _) = makeViewModel()
        await vm.reveal()
        vm.advanceToVerify()
        XCTAssertEqual(vm.step, .verify)

        let firstQuiz = vm.quiz
        for prompt in firstQuiz {
            // Deliberately pick a wrong choice for every prompt.
            let wrong = prompt.choices.first { $0 != prompt.correctWord }!
            vm.select(position: prompt.position, word: wrong)
        }

        vm.submitVerify()

        XCTAssertEqual(vm.step, .verify, "a wrong answer must not advance past verify")
        XCTAssertNotNil(vm.errorMessage)
        XCTAssertTrue(vm.selections.isEmpty, "selections must be cleared on a miss")
        XCTAssertFalse(vm.canSubmitVerify)

        try? walletStore.save(makeRecord())
        XCTAssertEqual(walletStore.load()?.mnemonicBackedUp, false, "a failed verify must not mark the wallet backed up")
    }

    func testCorrectAnswersMarkBackedUpAndAdvanceToSuccess() async throws {
        try walletStore.save(makeRecord(backedUp: false))
        let (vm, _, _) = makeViewModel()
        await vm.reveal()
        vm.advanceToVerify()

        for prompt in vm.quiz {
            vm.select(position: prompt.position, word: prompt.correctWord)
        }
        vm.submitVerify()

        XCTAssertEqual(vm.step, .success)
        XCTAssertTrue(vm.words.isEmpty, "the phrase must be wiped once verified")
        let record = try XCTUnwrap(walletStore.load())
        XCTAssertTrue(record.mnemonicBackedUp)
    }

    // MARK: - Backgrounding

    func testOnBackgroundedWipesWordsAndReturnsToGateFromDisplay() async {
        let (vm, _, _) = makeViewModel()
        await vm.reveal()
        XCTAssertEqual(vm.step, .display)
        XCTAssertFalse(vm.words.isEmpty)

        vm.onBackgrounded()

        XCTAssertEqual(vm.step, .gate)
        XCTAssertTrue(vm.words.isEmpty)
        XCTAssertTrue(vm.quiz.isEmpty)
    }

    func testOnBackgroundedWipesWordsAndReturnsToGateFromVerify() async {
        let (vm, _, _) = makeViewModel()
        await vm.reveal()
        vm.advanceToVerify()
        vm.select(position: vm.quiz[0].position, word: vm.quiz[0].correctWord)

        vm.onBackgrounded()

        XCTAssertEqual(vm.step, .gate)
        XCTAssertTrue(vm.words.isEmpty)
        XCTAssertTrue(vm.selections.isEmpty)
    }

    func testOnBackgroundedIsANoOpOnTheGateStep() {
        let (vm, _, _) = makeViewModel()
        XCTAssertEqual(vm.step, .gate)

        vm.onBackgrounded()

        XCTAssertEqual(vm.step, .gate)
    }

    func testOnBackgroundedIsANoOpOnceSucceeded() async throws {
        try walletStore.save(makeRecord())
        let (vm, _, _) = makeViewModel()
        await vm.reveal()
        vm.advanceToVerify()
        for prompt in vm.quiz {
            vm.select(position: prompt.position, word: prompt.correctWord)
        }
        vm.submitVerify()
        XCTAssertEqual(vm.step, .success)

        vm.onBackgrounded()

        XCTAssertEqual(vm.step, .success, "success is terminal; backgrounding must not re-arm the gate")
    }

    // MARK: - Screenshot mitigation (M1)

    func testOnScreenshotTakenWipesWordsReturnsToGateAndSetsAnInfoMessage() async {
        let (vm, _, _) = makeViewModel()
        await vm.reveal()
        XCTAssertEqual(vm.step, .display)
        XCTAssertFalse(vm.words.isEmpty)

        vm.onScreenshotTaken()

        XCTAssertEqual(vm.step, .gate)
        XCTAssertTrue(vm.words.isEmpty)
        XCTAssertTrue(vm.quiz.isEmpty)
        XCTAssertEqual(vm.errorMessage, BackupViewModel.screenshotTakenMessage)
    }

    func testOnScreenshotTakenFromVerifyWipesSelections() async {
        let (vm, _, _) = makeViewModel()
        await vm.reveal()
        vm.advanceToVerify()
        vm.select(position: vm.quiz[0].position, word: vm.quiz[0].correctWord)

        vm.onScreenshotTaken()

        XCTAssertEqual(vm.step, .gate)
        XCTAssertTrue(vm.selections.isEmpty)
        XCTAssertEqual(vm.errorMessage, BackupViewModel.screenshotTakenMessage)
    }

    func testOnScreenshotTakenIsANoOpOnTheGateStep() {
        let (vm, _, _) = makeViewModel()
        XCTAssertEqual(vm.step, .gate)

        vm.onScreenshotTaken()

        XCTAssertEqual(vm.step, .gate)
        XCTAssertNil(vm.errorMessage)
    }

    // MARK: - Save-failure handling (H1)

    private func makeViewModel(
        store: any WalletRecordStoring,
        bundle: WalletKeyBundle = WalletKeyBundle(
            privateKeyHex: "00",
            mnemonic: "abandon ability able about above absent absorb abstract absurd abuse access accident"
        )
    ) -> BackupViewModel {
        BackupViewModel(
            walletKeyStore: StubWalletKeyReader(result: .success(bundle)),
            walletStore: store,
            auth: StubAuthGate(),
            isOnboarding: false,
            hasPin: { true },
            rng: SeededRandomNumberGenerator(seed: 123)
        )
    }

    private func passVerify(_ vm: BackupViewModel) async {
        await vm.reveal()
        vm.advanceToVerify()
        for prompt in vm.quiz {
            vm.select(position: prompt.position, word: prompt.correctWord)
        }
    }

    func testMissingWalletRecordSetsAnErrorAndStaysOnVerifyWithWordsIntact() async {
        let store = StubWalletRecordStore()
        let vm = makeViewModel(store: store)
        await passVerify(vm)

        vm.submitVerify()

        XCTAssertEqual(vm.step, .verify, "a missing wallet record must not be reported as a successful backup")
        XCTAssertEqual(vm.errorMessage, BackupViewModel.saveFailedMessage)
        XCTAssertFalse(vm.words.isEmpty, "the phrase must stay intact so the user can retry")
    }

    func testSaveFailureSetsAnErrorAndStaysOnVerifyWithWordsIntact() async {
        let store = StubWalletRecordStore()
        store.recordToLoad = makeRecord()
        store.saveError = StubWalletRecordStoreError.saveFailed
        let vm = makeViewModel(store: store)
        await passVerify(vm)

        vm.submitVerify()

        XCTAssertEqual(vm.step, .verify, "a save failure must not be reported as a successful backup")
        XCTAssertEqual(vm.errorMessage, BackupViewModel.saveFailedMessage)
        XCTAssertFalse(vm.words.isEmpty, "the phrase must stay intact so the user can retry")
    }

    // MARK: - Empty quiz guard (H2)

    func testSubmitVerifyIsANoOpWithAnEmptyQuiz() async {
        let store = StubWalletRecordStore()
        store.recordToLoad = makeRecord()
        // A non-empty, non-nil mnemonic that splits to zero words: the only
        // way `.verify` is reachable with an empty quiz (`BackupQuiz.generate`
        // returns `[]` for empty `words`, and `advanceToVerify()` does not
        // itself check the quiz).
        let vm = makeViewModel(store: store, bundle: WalletKeyBundle(privateKeyHex: "00", mnemonic: "   "))
        await vm.reveal()
        XCTAssertEqual(vm.step, .display)
        vm.advanceToVerify()
        XCTAssertEqual(vm.step, .verify)
        XCTAssertTrue(vm.quiz.isEmpty)

        vm.submitVerify()

        XCTAssertEqual(vm.step, .verify, "submitVerify must no-op rather than act on an empty quiz")
        XCTAssertTrue(store.savedRecords.isEmpty)
    }
}
