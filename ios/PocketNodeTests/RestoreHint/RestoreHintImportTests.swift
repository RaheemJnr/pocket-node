import Foundation
import PocketNodeCore
import XCTest

@testable import PocketNode

/// Using a restore hint file in the phrase and private-key import (#559): the
/// file is picked before the import, checked against the secret just entered,
/// and its outcome waits for Home's first sync choice, where "Start restore"
/// applies CUSTOM N, or FULL_HISTORY at block 0.
@MainActor
final class RestoreHintImportTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.restoreHintImport"
    private let tag = "com.rjnr.pocketnode.tests.restoreHintImport.wrapper"
    private let suiteName = "com.rjnr.pocketnode.tests.restoreHintImport.prefs"

    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var walletStore: WalletStore!
    private var directory: URL!
    private var handoff: RestoreHintHandoff!
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!

    override func setUp() async throws {
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.restoreHintImport-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        handoff = RestoreHintHandoff()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        preferences = UserDefaultsPreferences(defaults: defaults)
    }

    override func tearDown() async throws {
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        try? FileManager.default.removeItem(at: directory)
        defaults.removePersistentDomain(forName: suiteName)
        keychain = nil
        wrapper = nil
        walletStore = nil
        directory = nil
        handoff = nil
        defaults = nil
        preferences = nil
    }

    private func makeModel(
        network: NetworkType = .testnet,
        lockGeneration: @escaping @MainActor () -> Int = { 0 }
    ) -> OnboardingViewModel {
        let handoff = handoff!
        return OnboardingViewModel(
            creator: WalletCreator(
                keyStore: WalletKeyStore(keychain: keychain, wrapper: wrapper),
                walletStore: walletStore
            ),
            restoreHint: OnboardingViewModel.RestoreHintSupport(
                network: { network },
                lockGeneration: lockGeneration,
                deliver: { handoff.deliver($0) }
            )
        )
    }

    // MARK: - Import

    func testTheRowIsOfferedOnAFirstRunImport() {
        XCTAssertTrue(makeModel().offersRestoreHint)
        let without = OnboardingViewModel(
            creator: WalletCreator(keyStore: WalletKeyStore(keychain: keychain, wrapper: wrapper), walletStore: walletStore)
        )
        XCTAssertFalse(without.offersRestoreHint)
    }

    func testAPhraseImportVerifiesThePickedFileAndHandsTheStartToHome() async throws {
        let model = makeModel()
        model.beginImport()
        model.setRestoreHint(text: try RestoreHintFixtures.text(RestoreHintFixtures.androidExport))

        await model.importMnemonic(words: RestoreHintFixtures.abandonWords, name: "Restored")

        XCTAssertEqual(model.step, .pinSetup)
        XCTAssertNil(model.errorMessage)
        XCTAssertNil(model.restoreHintText, "only the outcome travels on; the file text is dropped")
        let walletId = try XCTUnwrap(walletStore.load()?.id)
        XCTAssertEqual(
            handoff.pending,
            PendingRestoreHint(
                walletId: walletId,
                verification: .ready(RestoreHintStart(startBlock: 22_579_000, sourceCoverageStart: 22_580_000))
            )
        )
    }

    func testAFileForAnotherPhraseIsRejected() async throws {
        let model = makeModel()
        model.setRestoreHint(text: try RestoreHintFixtures.text(RestoreHintFixtures.androidExport))

        await model.importMnemonic(words: RestoreHintFixtures.legalWinnerWords, name: "Other")

        XCTAssertEqual(model.step, .pinSetup, "a rejected hint never blocks the import")
        XCTAssertEqual(handoff.pending?.verification, .rejected(.notThisWallet))
    }

    func testAFileForTheOtherNetworkIsRejected() async throws {
        let model = makeModel(network: .mainnet)
        model.setRestoreHint(text: try RestoreHintFixtures.text(RestoreHintFixtures.androidExport))

        await model.importMnemonic(words: RestoreHintFixtures.abandonWords, name: "Restored")

        XCTAssertEqual(handoff.pending?.verification, .rejected(.otherNetwork))
    }

    func testAMalformedFileIsRejectedAsUnreadable() async {
        let model = makeModel()
        model.setRestoreHint(text: "{\"format\":\"something-else\"}")

        await model.importMnemonic(words: RestoreHintFixtures.abandonWords, name: "Restored")

        XCTAssertEqual(handoff.pending?.verification, .rejected(.unreadable))
    }

    func testAPrivateKeyImportChecksTheFileAgainstThatKey() async throws {
        // The Android file is a mnemonic wallet's; a raw key cannot verify it.
        let model = makeModel()
        model.setRestoreHint(text: try RestoreHintFixtures.text(RestoreHintFixtures.androidExport))

        await model.importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key")

        XCTAssertEqual(model.step, .pinSetup)
        XCTAssertEqual(handoff.pending?.verification, .rejected(.notThisWallet))
    }

    func testALockDuringVerificationDiscardsTheResult() async throws {
        let lock = LockGenerationScript()
        let model = makeModel(lockGeneration: { lock.next() })
        model.setRestoreHint(text: try RestoreHintFixtures.text(RestoreHintFixtures.androidExport))

        await model.importMnemonic(words: RestoreHintFixtures.abandonWords, name: "Restored")

        XCTAssertEqual(model.step, .pinSetup)
        XCTAssertNil(handoff.pending, "a check a lock interrupted is thrown away")
        XCTAssertNil(model.restoreHintText)
    }

    func testNoFileMeansNoHint() async {
        let model = makeModel()

        await model.importMnemonic(words: RestoreHintFixtures.abandonWords, name: "Restored")

        XCTAssertNil(handoff.pending)
    }

    func testRemovingThePickedFileDropsIt() throws {
        let model = makeModel()
        model.setRestoreHint(text: try RestoreHintFixtures.text(RestoreHintFixtures.androidExport))

        model.removeRestoreHint()

        XCTAssertNil(model.restoreHintText)
    }

    func testAFailedImportKeepsTheFileForTheRetry() async throws {
        let model = makeModel()
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)
        model.setRestoreHint(text: text)

        await model.importMnemonic(words: Array(repeating: "abandon", count: 12), name: "Bad")

        XCTAssertNotNil(model.errorMessage)
        XCTAssertEqual(model.restoreHintText, text)
        XCTAssertNil(handoff.pending)
    }

    func testAnOversizedPickIsRefusedUnread() throws {
        let url = directory.appendingPathComponent("huge.json")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(repeating: 0x61, count: RestoreHintFile.maxChars * 4 + 1).write(to: url)
        let model = makeModel()

        model.pickRestoreHint(.success(url))

        XCTAssertNil(model.restoreHintText)
        XCTAssertEqual(model.restoreHintPickError, "This file could not be read.")
    }

    func testThePickCapCountsUtf16UnitsLikeTheSharedCodec() throws {
        // Each emoji is one Swift character but two UTF-16 units, so this is
        // under the cap by `count` and over it by Kotlin's `length`.
        let url = directory.appendingPathComponent("emoji.json")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let text = String(repeating: "\u{1F600}", count: RestoreHintFile.maxChars / 2 + 1)
        XCTAssertLessThanOrEqual(text.count, RestoreHintFile.maxChars)
        try Data(text.utf8).write(to: url)
        let model = makeModel()

        model.pickRestoreHint(.success(url))

        XCTAssertNil(model.restoreHintText)
        XCTAssertEqual(model.restoreHintPickError, "This file could not be read.")
    }

    func testAPickedFileIsReadAsText() throws {
        let url = directory.appendingPathComponent("hint.json")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)
        try Data(text.utf8).write(to: url)
        let model = makeModel()

        model.pickRestoreHint(.success(url))

        XCTAssertEqual(model.restoreHintText, text)
        XCTAssertNil(model.restoreHintPickError)
    }

    // MARK: - Home applies it

    private func saveWallet() throws {
        try walletStore.save(
            WalletRecord(
                id: "w1",
                name: "Restored",
                type: WalletCreator.typeMnemonic,
                derivationPath: "m/44'/309'/0'/0/0",
                mainnetAddress: WalletCreatorTests.testMainnetAddress,
                testnetAddress: WalletCreatorTests.testTestnetAddress,
                mnemonicBackedUp: true,
                createdAt: 0
            )
        )
    }

    private func makeHome(_ sync: FakeSyncStatusProvider) -> HomeViewModel {
        HomeViewModel(walletStore: walletStore, preferences: preferences, sync: sync, restoreHint: handoff)
    }

    func testStartRestoreAppliesCustomAtTheHintsStart() async throws {
        try saveWallet()
        handoff.deliver(PendingRestoreHint(
            walletId: "w1",
            verification: .ready(RestoreHintStart(startBlock: 22_579_000, sourceCoverageStart: 22_580_000))
        ))
        let sync = FakeSyncStatusProvider(mode: nil)
        let home = makeHome(sync)
        XCTAssertEqual(home.restoreHintStart?.startBlock, 22_579_000)

        let applied = await home.applyRestoreHint()

        XCTAssertTrue(applied)
        XCTAssertEqual(sync.chosen.count, 1)
        XCTAssertEqual(sync.chosen.first?.0, .custom)
        XCTAssertEqual(sync.chosen.first?.1, 22_579_000)
        XCTAssertNil(handoff.pending)
        XCTAssertNil(home.restoreHintStart)
    }

    func testStartRestoreAtBlockZeroAppliesFullHistory() async throws {
        try saveWallet()
        handoff.deliver(PendingRestoreHint(
            walletId: "w1",
            verification: .ready(RestoreHintStart(startBlock: 0, sourceCoverageStart: 0))
        ))
        let sync = FakeSyncStatusProvider(mode: nil)

        let applied = await makeHome(sync).applyRestoreHint()

        XCTAssertTrue(applied)
        XCTAssertEqual(sync.chosen.first?.0, .fullHistory)
        XCTAssertNil(sync.chosen.first?.1)
    }

    func testARefusedStartKeepsTheHint() async throws {
        try saveWallet()
        handoff.deliver(PendingRestoreHint(
            walletId: "w1",
            verification: .ready(RestoreHintStart(startBlock: 22_579_000, sourceCoverageStart: 22_580_000))
        ))
        let sync = FakeSyncStatusProvider(mode: nil)
        sync.acceptsChoice = false

        let applied = await makeHome(sync).applyRestoreHint()

        XCTAssertFalse(applied)
        XCTAssertNotNil(handoff.pending)
    }

    func testARejectionIsShownAndTheNormalChoiceClearsIt() async throws {
        try saveWallet()
        handoff.deliver(PendingRestoreHint(walletId: "w1", verification: .rejected(.otherNetwork)))
        let sync = FakeSyncStatusProvider(mode: nil)
        let home = makeHome(sync)

        XCTAssertNil(home.restoreHintStart)
        XCTAssertEqual(home.restoreHintRejection, "This file is for the other network.")
        let applied = await home.applyRestoreHint()
        XCTAssertFalse(applied, "a rejected file applies nothing")
        XCTAssertTrue(sync.chosen.isEmpty)

        _ = await home.chooseSyncMode(.recent, customBlockHeight: nil)

        XCTAssertNil(handoff.pending)
        XCTAssertEqual(sync.chosen.first?.0, .recent)
    }

    func testAHintForAnotherWalletIsIgnored() throws {
        try saveWallet()
        handoff.deliver(PendingRestoreHint(
            walletId: "someone-else",
            verification: .ready(RestoreHintStart(startBlock: 1, sourceCoverageStart: 1))
        ))

        let home = makeHome(FakeSyncStatusProvider(mode: nil))

        XCTAssertNil(home.restoreHintStart)
        XCTAssertNil(home.restoreHintRejection)
    }

    func testAHintIsIgnoredOnceTheWalletHasASyncMode() throws {
        try saveWallet()
        handoff.deliver(PendingRestoreHint(
            walletId: "w1",
            verification: .ready(RestoreHintStart(startBlock: 1, sourceCoverageStart: 1))
        ))

        let home = makeHome(FakeSyncStatusProvider(mode: .recent))

        XCTAssertNil(home.restoreHintStart)
    }

    /// The whole path: an Android file picked before a phrase import ends in
    /// Home applying CUSTOM at the planned start.
    func testFromPickedFileToAppliedStart() async throws {
        let model = makeModel()
        model.setRestoreHint(text: try RestoreHintFixtures.text(RestoreHintFixtures.androidSecondExport))
        await model.importMnemonic(words: RestoreHintFixtures.abandonWords, name: "Restored")
        let sync = FakeSyncStatusProvider(mode: nil)
        let home = makeHome(sync)

        XCTAssertEqual(home.restoreHintStart?.message, RestoreHintCopy.ready(startBlock: 22_569_000, sourceCoverageStart: 22_570_000))
        let applied = await home.applyRestoreHint()

        XCTAssertTrue(applied)
        XCTAssertEqual(sync.chosen.first?.0, .custom)
        XCTAssertEqual(sync.chosen.first?.1, 22_569_000)
    }
}

/// A lock generation that is unchanged when the check starts and has moved by
/// the time it ends. A class rather than a captured `var`, which Swift 6.0
/// (Xcode 16) is stricter about.
@MainActor
final class LockGenerationScript {
    private(set) var reads = 0

    func next() -> Int {
        reads += 1
        return reads > 1 ? 1 : 0
    }
}
