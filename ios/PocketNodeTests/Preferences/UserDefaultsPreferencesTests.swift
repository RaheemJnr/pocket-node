import PocketNodeCore
import XCTest

@testable import PocketNode

/// Defaults, round trips and namespacing for the `UserDefaults`-backed
/// implementation of the shared `core.prefs` interfaces. Runs against a
/// throwaway suite so nothing here can touch a real installed app's prefs.
final class UserDefaultsPreferencesTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.preferences"

    private var defaults: UserDefaults!
    private var prefs: UserDefaultsPreferences!

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        prefs = UserDefaultsPreferences(defaults: defaults)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        prefs = nil
        super.tearDown()
    }

    // MARK: - Defaults (fresh suite, nothing ever written)

    func testDefaultSelectedNetworkIsMainnet() {
        XCTAssertEqual(prefs.getSelectedNetwork(), .mainnet)
    }

    func testDefaultSyncModeIsNewWallet() {
        XCTAssertNil(prefs.getSyncModeOrNull(network: nil, walletId: nil))
        XCTAssertEqual(prefs.getSyncMode(network: nil, walletId: nil), .theNewWallet)
    }

    func testDefaultCustomBlockHeightIsNil() {
        XCTAssertNil(prefs.getCustomBlockHeight(network: nil, walletId: nil))
    }

    func testDefaultInitialSyncNotCompleted() {
        XCTAssertFalse(prefs.hasCompletedInitialSync(network: nil, walletId: nil))
    }

    func testDefaultZeroCellRescanNotDone() {
        XCTAssertFalse(prefs.isZeroCellRescanDone(walletId: "w1", network: nil))
    }

    func testDefaultBackgroundSyncDisabled() {
        // Matches Android (#116): explicit opt-in only.
        XCTAssertFalse(prefs.isBackgroundSyncEnabled())
    }

    func testDefaultLastSyncedAtIsZero() {
        XCTAssertEqual(prefs.getLastSyncedAt(), 0)
    }

    func testDefaultSyncStrategyIsAllWallets() {
        XCTAssertEqual(prefs.getSyncStrategy(), .allWallets)
    }

    func testDefaultGapLimitSignalNotDetected() {
        XCTAssertFalse(prefs.isGapLimitSignalDetected(network: nil, walletId: nil))
    }

    func testDefaultThemeModeIsSystem() {
        XCTAssertEqual(prefs.getThemeMode(), .system)
    }

    func testDefaultCoachmarkNotSeen() {
        XCTAssertFalse(prefs.hasSeenSyncCoachmark())
    }

    func testDefaultBgSyncPillNotDismissed() {
        XCTAssertFalse(prefs.isBgSyncPillDismissed())
    }

    func testDefaultGapLimitBannerNotDismissed() {
        XCTAssertFalse(prefs.isGapLimitBannerDismissed(network: nil, walletId: nil))
    }

    func testDefaultBulkSendNotUnlocked() {
        XCTAssertFalse(prefs.isBulkSendUnlocked())
    }

    func testDefaultBulkTxHashNotPresent() {
        XCTAssertFalse(prefs.isBulkTxHash(hash: "0xabc"))
    }

    func testDefaultActiveWalletIdIsNil() {
        XCTAssertNil(prefs.getActiveWalletId())
    }

    func testDefaultLastSeenVersionCodeIsZero() {
        XCTAssertEqual(prefs.getLastSeenVersionCode(), 0)
    }

    func testDefaultLastVacuumAtIsZero() {
        XCTAssertEqual(prefs.getLastVacuumAt(), 0)
    }

    // MARK: - Round trips

    func testSelectedNetworkRoundTrips() {
        prefs.setSelectedNetwork(network: .testnet)
        XCTAssertEqual(prefs.getSelectedNetwork(), .testnet)

        prefs.setSelectedNetwork(network: .mainnet)
        XCTAssertEqual(prefs.getSelectedNetwork(), .mainnet)
    }

    func testSyncModeRoundTrips() {
        prefs.setSyncMode(mode: .recent, network: .testnet, walletId: "w1")

        XCTAssertEqual(prefs.getSyncMode(network: .testnet, walletId: "w1"), .recent)
        XCTAssertEqual(prefs.getSyncModeOrNull(network: .testnet, walletId: "w1"), .recent)
    }

    func testCustomBlockHeightRoundTripsAndClears() {
        prefs.setCustomBlockHeight(
            height: KotlinLong(longLong: 18_300_000),
            network: .mainnet,
            walletId: nil
        )
        XCTAssertEqual(
            prefs.getCustomBlockHeight(network: .mainnet, walletId: nil)?.int64Value,
            18_300_000
        )

        prefs.setCustomBlockHeight(height: nil, network: .mainnet, walletId: nil)
        XCTAssertNil(prefs.getCustomBlockHeight(network: .mainnet, walletId: nil))
    }

    func testInitialSyncCompletedRoundTrips() {
        prefs.setInitialSyncCompleted(completed: true, network: .testnet, walletId: "w1")
        XCTAssertTrue(prefs.hasCompletedInitialSync(network: .testnet, walletId: "w1"))
    }

    func testZeroCellRescanDoneRoundTripsAndClears() {
        prefs.setZeroCellRescanDone(walletId: "w1", network: .testnet)
        XCTAssertTrue(prefs.isZeroCellRescanDone(walletId: "w1", network: .testnet))

        prefs.clearZeroCellRescanDone(walletId: "w1", network: .testnet)
        XCTAssertFalse(prefs.isZeroCellRescanDone(walletId: "w1", network: .testnet))
    }

    func testBackgroundSyncRoundTrips() {
        prefs.setBackgroundSyncEnabled(enabled: true)
        XCTAssertTrue(prefs.isBackgroundSyncEnabled())
    }

    func testLastSyncedAtRoundTrips() {
        prefs.setLastSyncedAt(timestampMs: 1_700_000_000_000)
        XCTAssertEqual(prefs.getLastSyncedAt(), 1_700_000_000_000)
    }

    func testSyncStrategyRoundTrips() {
        prefs.setSyncStrategy(strategy: .balanced)
        XCTAssertEqual(prefs.getSyncStrategy(), .balanced)
    }

    func testGapLimitSignalRoundTrips() {
        prefs.setGapLimitSignalDetected(detected: true, network: .testnet, walletId: "w1")
        XCTAssertTrue(prefs.isGapLimitSignalDetected(network: .testnet, walletId: "w1"))
    }

    func testThemeModeRoundTrips() {
        prefs.setThemeMode(mode: .dark)
        XCTAssertEqual(prefs.getThemeMode(), .dark)
    }

    func testSyncCoachmarkRoundTrips() {
        prefs.markSyncCoachmarkSeen()
        XCTAssertTrue(prefs.hasSeenSyncCoachmark())
    }

    func testBgSyncPillDismissalRoundTrips() {
        prefs.setBgSyncPillDismissed()
        XCTAssertTrue(prefs.isBgSyncPillDismissed())
    }

    func testGapLimitBannerDismissalRoundTrips() {
        prefs.setGapLimitBannerDismissed(network: .testnet, walletId: "w1")
        XCTAssertTrue(prefs.isGapLimitBannerDismissed(network: .testnet, walletId: "w1"))
    }

    func testBulkSendUnlockRoundTrips() {
        prefs.setBulkSendUnlocked(unlocked: true)
        XCTAssertTrue(prefs.isBulkSendUnlocked())
    }

    func testBulkTxHashRoundTrips() {
        prefs.addBulkTxHash(hash: "0xabc")

        XCTAssertTrue(prefs.isBulkTxHash(hash: "0xabc"))
        XCTAssertFalse(prefs.isBulkTxHash(hash: "0xdef"))
    }

    func testActiveWalletIdRoundTripsAndClears() {
        prefs.setActiveWalletId(walletId: "w1")
        XCTAssertEqual(prefs.getActiveWalletId(), "w1")

        prefs.clearActiveWalletId()
        XCTAssertNil(prefs.getActiveWalletId())
    }

    func testLastSeenVersionCodeRoundTrips() {
        prefs.setLastSeenVersionCode(code: 26)
        XCTAssertEqual(prefs.getLastSeenVersionCode(), 26)
    }

    func testLastVacuumAtRoundTrips() {
        prefs.setLastVacuumAt(timestampMs: 42)
        XCTAssertEqual(prefs.getLastVacuumAt(), 42)
    }

    // MARK: - Namespacing: per-network and per-wallet keys must not collide

    func testSyncModeDoesNotLeakAcrossNetworks() {
        prefs.setSyncMode(mode: .recent, network: .testnet, walletId: "walletA")

        XCTAssertEqual(prefs.getSyncMode(network: .testnet, walletId: "walletA"), .recent)
        XCTAssertEqual(prefs.getSyncMode(network: .mainnet, walletId: "walletA"), .theNewWallet)
    }

    func testSyncModeDoesNotLeakAcrossWallets() {
        prefs.setSyncMode(mode: .fullHistory, network: .testnet, walletId: "walletA")

        XCTAssertEqual(prefs.getSyncMode(network: .testnet, walletId: "walletA"), .fullHistory)
        XCTAssertEqual(prefs.getSyncMode(network: .testnet, walletId: "walletB"), .theNewWallet)
    }

    func testSyncModeDoesNotLeakBetweenPerWalletAndNetworkWide() {
        prefs.setSyncMode(mode: .custom, network: .testnet, walletId: "walletA")

        // The network-wide (walletId: nil) value is a distinct key from any
        // per-wallet value.
        XCTAssertEqual(prefs.getSyncMode(network: .testnet, walletId: nil), .theNewWallet)
    }

    func testZeroCellRescanDoneDoesNotLeakAcrossWalletsOrNetworks() {
        prefs.setZeroCellRescanDone(walletId: "walletA", network: .testnet)

        XCTAssertFalse(prefs.isZeroCellRescanDone(walletId: "walletB", network: .testnet))
        XCTAssertFalse(prefs.isZeroCellRescanDone(walletId: "walletA", network: .mainnet))
    }

    // MARK: - Persistence across a new instance on the same suite

    func testSelectedNetworkPersistsAcrossNewInstance() {
        prefs.setSelectedNetwork(network: .testnet)

        let reloaded = UserDefaultsPreferences(defaults: defaults)

        XCTAssertEqual(reloaded.getSelectedNetwork(), .testnet)
    }

    func testSyncModePersistsAcrossNewInstance() {
        prefs.setSyncMode(mode: .recent, network: .testnet, walletId: "w1")

        let reloaded = UserDefaultsPreferences(defaults: defaults)

        XCTAssertEqual(reloaded.getSyncMode(network: .testnet, walletId: "w1"), .recent)
    }
}
