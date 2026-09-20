import PocketNodeCore
import XCTest

@testable import PocketNode

/// The balance on the wallet card: how it is formatted, and the cached-first
/// order the card depends on.
@MainActor
final class HomeBalanceTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.homeBalance.prefs"

    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.homeBalance-\(UUID().uuidString)")
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
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        try? FileManager.default.removeItem(at: directory)
    }

    private func model(_ sync: FakeSyncStatusProvider) -> HomeViewModel {
        HomeViewModel(walletStore: walletStore, preferences: preferences, sync: sync)
    }

    // MARK: - Formatting

    func testTheBalanceIsFormattedByTheSharedCoreFromShannons() {
        let sync = FakeSyncStatusProvider(
            balance: BalanceStatus(shannons: 1_234_560_000_000, isCached: false, hasValue: true)
        )

        XCTAssertEqual(model(sync).balanceText, "12,345.60 CKB")
    }

    func testAZeroBalanceReadsAsZeroRatherThanAsNothing() {
        let sync = FakeSyncStatusProvider(
            balance: BalanceStatus(shannons: 0, isCached: false, hasValue: true)
        )
        let model = model(sync)

        XCTAssertTrue(model.hasBalance)
        XCTAssertEqual(model.balanceText, "0.00 CKB")
    }

    func testABalancePastDoublePrecisionIsExact() {
        // 2.1e18 shannons is well past 2^53, where a Double stops representing
        // a shannon exactly. The card must still reconcile against an explorer.
        let sync = FakeSyncStatusProvider(
            balance: BalanceStatus(shannons: 2_100_000_000_000_000_001, isCached: false, hasValue: true)
        )

        XCTAssertEqual(model(sync).balanceText, "21,000,000,000.00 CKB")
    }

    // MARK: - Cached first

    func testNoReadYetIsNotTheSameAsZero() {
        let model = model(FakeSyncStatusProvider())

        XCTAssertFalse(model.hasBalance)
        XCTAssertFalse(model.isBalanceCached)
    }

    func testACachedValueIsShownAndMarkedUntilTheLiveReadLands() {
        let sync = FakeSyncStatusProvider(
            balance: BalanceStatus(shannons: 6_100_000_000, isCached: true, hasValue: true)
        )
        let model = model(sync)

        XCTAssertEqual(model.balanceText, "61.00 CKB")
        XCTAssertTrue(model.isBalanceCached, "the card says a fresher one is on its way")

        // The live read replaces it and clears the hint.
        sync.balance = BalanceStatus(shannons: 12_200_000_000, isCached: false, hasValue: true)

        XCTAssertEqual(model.balanceText, "122.00 CKB")
        XCTAssertFalse(model.isBalanceCached)
    }

    // MARK: - Refresh

    func testTheViewModelAsksForABalanceOnEveryRefresh() {
        let sync = FakeSyncStatusProvider()
        let model = model(sync)

        // Once from `init`, which calls `refresh()`.
        let afterInit = sync.balanceRefreshCount
        XCTAssertGreaterThanOrEqual(afterInit, 1)

        model.refresh()
        XCTAssertEqual(sync.balanceRefreshCount, afterInit + 1)

        model.refreshBalance()
        XCTAssertEqual(sync.balanceRefreshCount, afterInit + 2)
    }

    func testAHomeWithNoSyncLayerStillDrawsWithoutABalance() {
        let model = HomeViewModel(walletStore: walletStore, preferences: preferences, sync: nil)

        XCTAssertFalse(model.hasBalance)
        XCTAssertEqual(model.balanceText, "0.00 CKB")
    }
}
