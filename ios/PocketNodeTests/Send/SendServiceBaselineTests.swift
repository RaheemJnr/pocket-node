import XCTest
import PocketNodeCore
@testable import PocketNode

/// Found in device-smoke-test review: `SendService.availableShannons`
/// (`sync.balance.shannons`) can be a cached reading, because `SyncService`
/// publishes one before the first live balance of a launch or a wallet
/// switch lands (`publish(_:isCached:)` in `SyncService`). `send()` and
/// `retry()` snapshot it as the poller's baseline regardless, so a send that
/// starts before the first live read would otherwise see the cache-to-live
/// transition itself reported as "the balance moved", even though nothing
/// about this send has happened yet. The poller's fallback, used only after
/// repeated unknown statuses, would then end early on a transaction that
/// never landed.
///
/// `balanceChangedSupplier` closes this by never comparing a cached baseline
/// against a live reading: the first live reading after a cached baseline is
/// adopted as the new baseline instead, and only a move after that counts.
final class SendServiceBaselineTests: XCTestCase {

    /// The cache said X; the first live read also says X. No send has
    /// happened, and the transition must report no change.
    func testCachedBaselineWithAnEqualFirstLiveReadingReportsNoChange() {
        let baseline = BalanceBox(BalanceReading(shannons: 1_000, isCached: true))
        let latest = BalanceBox(BalanceReading(shannons: 1_000, isCached: true))
        let supplier = SendService.balanceChangedSupplier(latest: latest, baseline: baseline)

        // The cache is still the newest thing published; nothing to adopt yet.
        XCTAssertFalse(supplier().boolValue)

        // The first live reading lands, same number as the cache. This must
        // not be reported as a change even though the two readings' isCached
        // flags differ.
        latest.set(BalanceReading(shannons: 1_000, isCached: false))
        XCTAssertFalse(supplier().boolValue, "the cache-to-live transition alone must not count as a move")
    }

    /// The cache said X; the send starts from that baseline; a real live
    /// reading later says Y. The move is real and must be reported, but only
    /// once the live reading actually differs, not on the transition itself.
    func testCachedBaselineWithALaterDifferentLiveReadingReportsChange() {
        let baseline = BalanceBox(BalanceReading(shannons: 1_000, isCached: true))
        let latest = BalanceBox(BalanceReading(shannons: 1_000, isCached: true))
        let supplier = SendService.balanceChangedSupplier(latest: latest, baseline: baseline)

        // First live reading equals the cache: adopted as the new baseline,
        // no change reported yet.
        latest.set(BalanceReading(shannons: 1_000, isCached: false))
        XCTAssertFalse(supplier().boolValue)

        // A later live reading that actually differs (the send landed, or a
        // block moved something else) is a real change.
        latest.set(BalanceReading(shannons: 900, isCached: false))
        XCTAssertTrue(supplier().boolValue)
    }

    /// No cache involved at all: a live baseline compared against a
    /// different live reading is a real change, reported on the first call,
    /// exactly as before this fix.
    func testLiveBaselineWithADifferentLiveReadingReportsChange() {
        let baseline = BalanceBox(BalanceReading(shannons: 1_000, isCached: false))
        let latest = BalanceBox(BalanceReading(shannons: 1_000, isCached: false))
        let supplier = SendService.balanceChangedSupplier(latest: latest, baseline: baseline)

        XCTAssertFalse(supplier().boolValue)

        latest.set(BalanceReading(shannons: 850, isCached: false))
        XCTAssertTrue(supplier().boolValue)
    }
}
