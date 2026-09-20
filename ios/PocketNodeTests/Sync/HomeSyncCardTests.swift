import PocketNodeCore
import XCTest

@testable import PocketNode

/// Which of the sync card's three faces Home decides to show, and what it
/// forwards when the user picks a mode.
@MainActor
final class HomeSyncCardTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.homeSync.prefs"

    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!

    // `async` on purpose: see `PinServiceTests`: the non-async override runs
    // task-isolated and cannot touch this `@MainActor` test case's properties.
    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.homeSync-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        preferences = UserDefaultsPreferences(defaults: defaults)
        try walletStore.save(
            WalletRecord(
                id: "w1",
                name: "Main Wallet",
                type: WalletCreator.typeMnemonic,
                derivationPath: "m/44'/309'/0'/0/0",
                mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft",
                testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn",
                mnemonicBackedUp: true,
                createdAt: 1_700_000_000_000
            )
        )
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
        defaults.removePersistentDomain(forName: suiteName)
        directory = nil
        walletStore = nil
        defaults = nil
        preferences = nil
    }

    private func makeModel(_ sync: FakeSyncStatusProvider?) -> HomeViewModel {
        HomeViewModel(walletStore: walletStore, preferences: preferences, sync: sync)
    }

    // MARK: - Which face

    func testWithNoStoredModeHomeAsksTheUserToChooseOne() {
        let model = makeModel(FakeSyncStatusProvider(mode: nil))

        XCTAssertTrue(model.showsSync)
        XCTAssertTrue(model.needsSyncMode)
        XCTAssertNil(model.syncMode)
    }

    func testWithAStoredModeHomeShowsProgressInsteadOfThePrompt() {
        let sync = FakeSyncStatusProvider(
            status: SyncStatus(
                isSyncing: true,
                syncedToBlock: 18_300_512,
                tipBlockNumber: 18_400_000,
                percentage: 42.5,
                etaDisplay: "about 3 minutes left"
            ),
            mode: .recent,
            isRegistered: true
        )
        let model = makeModel(sync)

        XCTAssertFalse(model.needsSyncMode)
        XCTAssertEqual(model.syncMode, .recent)
        XCTAssertTrue(model.isRegistered)
        XCTAssertTrue(model.syncStatus.isSyncing)
        XCTAssertEqual(model.syncStatus.syncedToBlock, 18_300_512)
        XCTAssertEqual(model.syncStatus.etaDisplay, "about 3 minutes left")
    }

    func testACaughtUpWalletReportsNotSyncing() {
        let sync = FakeSyncStatusProvider(
            status: SyncStatus(
                isSyncing: false,
                syncedToBlock: 18_400_000,
                tipBlockNumber: 18_400_000,
                percentage: 100
            ),
            mode: .theNewWallet,
            isRegistered: true
        )
        let model = makeModel(sync)

        XCTAssertFalse(model.needsSyncMode)
        XCTAssertFalse(model.syncStatus.isSyncing)
        XCTAssertTrue(model.hasSyncReading)
    }

    func testBeforeTheFirstPollTheCardDoesNotClaimToBeSynced() {
        // A fresh `SyncStatus` is all zeros, `isSyncing` included, so the
        // caught-up test above would also pass for a node that has not started.
        // The tip is what separates them.
        let sync = FakeSyncStatusProvider(status: SyncStatus(), mode: .recent)
        let model = makeModel(sync)

        XCTAssertFalse(model.needsSyncMode, "the mode is stored, so nothing to ask for")
        XCTAssertFalse(model.syncStatus.isSyncing)
        XCTAssertFalse(model.hasSyncReading, "and no reading yet, so no Synced claim")
    }

    func testARegisteredWalletWithNoReadingYetIsWaitingOnPeers() {
        let sync = FakeSyncStatusProvider(status: SyncStatus(), mode: .recent, isRegistered: true)
        let model = makeModel(sync)

        XCTAssertFalse(model.needsSyncMode)
        XCTAssertFalse(model.hasSyncReading)
        XCTAssertTrue(model.isRegistered, "the card says it is starting up")
        XCTAssertNil(model.syncError)
    }

    func testAnUnregisteredWalletWithNoReadingIsAFailedActivation() {
        let sync = FakeSyncStatusProvider(
            status: SyncStatus(),
            mode: .recent,
            isRegistered: false,
            lastError: "The light client did not start."
        )
        let model = makeModel(sync)

        XCTAssertFalse(model.needsSyncMode)
        XCTAssertFalse(model.hasSyncReading)
        XCTAssertFalse(model.isRegistered, "the card says it is not registered yet")
        XCTAssertEqual(model.syncError, "The light client did not start.")
    }

    func testRetryForwardsToTheSyncLayer() {
        let sync = FakeSyncStatusProvider(mode: .recent, lastError: "The light client did not start.")
        let model = makeModel(sync)

        model.retrySync()

        XCTAssertEqual(sync.retryCount, 1)
        XCTAssertNil(model.syncError, "the error clears while the retry runs")
    }

    func testRetryWithNoSyncLayerIsHarmless() {
        makeModel(nil).retrySync()
    }

    func testOneNonZeroTipIsEnoughToCountAsAReading() {
        let sync = FakeSyncStatusProvider(
            status: SyncStatus(isSyncing: true, syncedToBlock: 0, tipBlockNumber: 1),
            mode: .recent
        )

        XCTAssertTrue(makeModel(sync).hasSyncReading)
    }

    func testWithNoSyncLayerTheCardIsNotShownAtAll() {
        let model = makeModel(nil)

        XCTAssertFalse(model.showsSync)
        XCTAssertFalse(model.needsSyncMode, "no layer means nothing to ask the user for")
        XCTAssertFalse(model.isRegistered)
        XCTAssertNil(model.syncMode)
        XCTAssertEqual(model.syncStatus, SyncStatus())
    }

    // MARK: - Choosing

    func testChoosingAModeForwardsItWithNoHeight() async {
        let sync = FakeSyncStatusProvider(mode: nil)
        let model = makeModel(sync)

        let applied = await model.chooseSyncMode(.recent, customBlockHeight: nil)

        XCTAssertTrue(applied)
        XCTAssertEqual(sync.chosen.count, 1)
        XCTAssertEqual(sync.chosen.first?.0, .recent)
        XCTAssertNil(sync.chosen.first?.1)
        XCTAssertFalse(model.needsSyncMode, "the prompt goes away once a mode is stored")
    }

    func testChoosingACustomStartForwardsTheHeight() async {
        let sync = FakeSyncStatusProvider(mode: nil)
        let model = makeModel(sync)

        _ = await model.chooseSyncMode(.custom, customBlockHeight: 12_000_000)

        XCTAssertEqual(sync.chosen.first?.0, .custom)
        XCTAssertEqual(sync.chosen.first?.1, 12_000_000)
    }

    func testARefusedChoiceLeavesTheWalletWithoutAMode() async {
        let sync = FakeSyncStatusProvider(mode: nil)
        sync.acceptsChoice = false
        sync.lastError = "The light client refused to start syncing. Try again in a moment."
        let model = makeModel(sync)

        let applied = await model.chooseSyncMode(.recent, customBlockHeight: nil)

        XCTAssertFalse(applied)
        XCTAssertTrue(model.needsSyncMode)
        XCTAssertEqual(model.syncError, "The light client refused to start syncing. Try again in a moment.")
    }

    func testWithNoSyncLayerAChoiceIsRefusedRatherThanCrashing() async {
        let applied = await makeModel(nil).chooseSyncMode(.recent, customBlockHeight: nil)

        XCTAssertFalse(applied)
    }
}
