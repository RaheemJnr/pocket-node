import PocketNodeCore
import XCTest

@testable import PocketNode

/// The Kotlin `SyncProgress` to Swift `SyncStatus` mapping, and the one number
/// the view derives from it.
final class SyncStatusTests: XCTestCase {

    func testEveryFieldCrossesFromKotlinUnchanged() {
        let progress = SyncProgress(
            isSyncing: true,
            syncedToBlock: 18_300_512,
            tipBlockNumber: 18_400_000,
            percentage: 42.5,
            etaDisplay: "about 3 minutes left",
            justReachedTip: false,
            firstCatchingUpAtMs: nil
        )

        let status = SyncStatus(progress)

        XCTAssertTrue(status.isSyncing)
        XCTAssertEqual(status.syncedToBlock, 18_300_512)
        XCTAssertEqual(status.tipBlockNumber, 18_400_000)
        XCTAssertEqual(status.percentage, 42.5, accuracy: 0.0001)
        XCTAssertEqual(status.etaDisplay, "about 3 minutes left")
        XCTAssertFalse(status.justReachedTip)
    }

    func testTheJustReachedTipEdgeSurvivesTheCrossing() {
        let progress = SyncProgress(
            isSyncing: false,
            syncedToBlock: 100,
            tipBlockNumber: 100,
            percentage: 100,
            etaDisplay: "",
            justReachedTip: true,
            firstCatchingUpAtMs: nil
        )

        XCTAssertTrue(SyncStatus(progress).justReachedTip)
    }

    func testTheProgressBarFractionIsThePercentageOverAHundred() {
        XCTAssertEqual(SyncStatus(percentage: 0).fraction, 0, accuracy: 0.0001)
        XCTAssertEqual(SyncStatus(percentage: 42.5).fraction, 0.425, accuracy: 0.0001)
        XCTAssertEqual(SyncStatus(percentage: 100).fraction, 1, accuracy: 0.0001)
    }

    func testAnOutOfRangeFractionIsClamped() {
        // `ProgressView(value:)` draws nonsense outside 0...1, and the tracker
        // can briefly report past 100 when the tip moves backwards.
        XCTAssertEqual(SyncStatus(percentage: 140).fraction, 1, accuracy: 0.0001)
        XCTAssertEqual(SyncStatus(percentage: -5).fraction, 0, accuracy: 0.0001)
    }

    func testAFreshStatusIsTheEmptyOne() {
        let status = SyncStatus()

        XCTAssertFalse(status.isSyncing)
        XCTAssertEqual(status.syncedToBlock, 0)
        XCTAssertEqual(status.tipBlockNumber, 0)
        XCTAssertEqual(status.percentage, 0, accuracy: 0.0001)
        XCTAssertEqual(status.etaDisplay, "")
    }
}
