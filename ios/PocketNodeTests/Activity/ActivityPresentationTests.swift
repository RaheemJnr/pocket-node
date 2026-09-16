import PocketNodeCore
import XCTest

@testable import PocketNode

/// What the rows and the detail sheet put on screen: the display state as the
/// shared core decides it, the three calendar-relative formats Swift owns, and
/// the explorer link per network.
final class ActivityPresentationTests: XCTestCase {

    // MARK: - Display state through the shared function

    func testDisplayStateComesFromTheSharedRulesNotFromTheRowAlone() {
        // A PENDING ledger row with a BROADCASTING broadcast row is the one
        // case the ledger row alone cannot answer.
        let sending = ActivityFixtures.item(
            record: ActivityFixtures.record(confirmations: 0, status: "PENDING"),
            broadcast: ActivityFixtures.broadcast(state: "BROADCASTING")
        )
        XCTAssertEqual(sending.displayState, TxDisplayState.broadcasting)
        XCTAssertTrue(sending.isInFlight)

        let waiting = ActivityFixtures.item(
            record: ActivityFixtures.record(confirmations: 0, status: "PENDING"),
            broadcast: ActivityFixtures.broadcast(state: "BROADCAST")
        )
        XCTAssertEqual(waiting.displayState, TxDisplayState.pending)

        let confirmed = ActivityFixtures.item()
        XCTAssertEqual(confirmed.displayState, TxDisplayState.confirmed)
        XCTAssertFalse(confirmed.isInFlight)

        let failed = ActivityFixtures.item(
            record: ActivityFixtures.record(confirmations: 0, status: "FAILED"),
            broadcast: ActivityFixtures.broadcast(state: "FAILED", nullCount: 4)
        )
        XCTAssertEqual(failed.displayState, TxDisplayState.failed)
        XCTAssertEqual(failed.failureReason, TxFailureReason.dropped)
    }

    func testEveryStateHasALabelAndAnExplainer() {
        let states: [TxDisplayState] = [
            TxDisplayState.broadcasting,
            TxDisplayState.pending,
            TxDisplayState.confirmed,
            TxDisplayState.failed,
        ]
        for state in states {
            XCTAssertFalse(ActivityCopy.statusLabel(state).isEmpty)
            XCTAssertFalse(ActivityCopy.explainerTitle(state).isEmpty)
            XCTAssertFalse(ActivityCopy.explainerBody(state, reason: nil).isEmpty)
        }
        XCTAssertEqual(ActivityCopy.statusLabel(TxDisplayState.broadcasting), "Broadcasting")
        XCTAssertEqual(ActivityCopy.statusLabel(TxDisplayState.failed), "Failed")
    }

    func testNoUserFacingCopyUsesAnEmDash() {
        let strings = [
            ActivityCopy.failureReason(TxFailureReason.unknown),
            ActivityCopy.failureReason(TxFailureReason.dropped),
            ActivityCopy.failureReason(TxFailureReason.rejected),
            ActivityCopy.loadFailed,
            ActivityCopy.notInABlock,
            ActivityCopy.retryUnavailable,
        ] + ActivityCopy.explainerBody(TxDisplayState.pending, reason: nil)
        for string in strings {
            XCTAssertFalse(string.contains("\u{2014}"), "em dash in: \(string)")
        }
    }

    // MARK: - Elapsed

    func testTheElapsedSuffixIsOnlyOnTheInFlightStates() {
        let bucket = elapsedBucket(elapsedMillis: 5 * 60_000)
        XCTAssertEqual(
            ActivityCopy.statusLabel(TxDisplayState.pending, elapsed: bucket),
            "Pending · 5 min"
        )
        XCTAssertEqual(
            ActivityCopy.statusLabel(TxDisplayState.confirmed, elapsed: bucket),
            "Confirmed",
            "a confirmed row has no in-flight clock to report"
        )
    }

    func testElapsedRendersEveryBucket() {
        XCTAssertEqual(ActivityCopy.elapsedText(elapsedBucket(elapsedMillis: 0)), "<1 min")
        XCTAssertEqual(ActivityCopy.elapsedText(elapsedBucket(elapsedMillis: 60_000)), "1 min")
        XCTAssertEqual(ActivityCopy.elapsedText(elapsedBucket(elapsedMillis: 3 * 3_600_000)), "3 hr")
        XCTAssertEqual(ActivityCopy.elapsedText(elapsedBucket(elapsedMillis: 2 * 86_400_000)), "2 d")
    }

    // MARK: - Timestamps

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    private func hex(_ millis: Int64) -> String { "0x" + String(millis, radix: 16) }

    func testTodayShowsTheTimeAlone() {
        let now = Date(timeIntervalSince1970: 1_726_000_000)
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        let formatted = ActivityFormat.blockTimestamp(
            hex(millis),
            now: now,
            calendar: calendar,
            locale: Locale(identifier: "en_US_POSIX"),
            timeZone: TimeZone(identifier: "UTC")!
        )
        XCTAssertTrue(formatted.contains(":"), "expected a HH:mm time, got \(formatted)")
        XCTAssertFalse(formatted.contains("2024"))
    }

    func testAnOlderDateInTheSameYearDropsTheYear() {
        let now = Date(timeIntervalSince1970: 1_726_000_000)   // 2024-09-10 UTC
        let earlier = now.addingTimeInterval(-40 * 86_400)     // still 2024
        let formatted = ActivityFormat.blockTimestamp(
            hex(Int64(earlier.timeIntervalSince1970 * 1000)),
            now: now,
            calendar: calendar,
            locale: Locale(identifier: "en_US_POSIX"),
            timeZone: TimeZone(identifier: "UTC")!
        )
        XCTAssertFalse(formatted.contains("2024"))
        XCTAssertFalse(formatted.contains(":"))
    }

    func testAnEarlierYearKeepsIt() {
        let now = Date(timeIntervalSince1970: 1_726_000_000)
        let older = now.addingTimeInterval(-500 * 86_400)
        let formatted = ActivityFormat.blockTimestamp(
            hex(Int64(older.timeIntervalSince1970 * 1000)),
            now: now,
            calendar: calendar,
            locale: Locale(identifier: "en_US_POSIX"),
            timeZone: TimeZone(identifier: "UTC")!
        )
        XCTAssertTrue(formatted.contains("2023"), "expected a full date, got \(formatted)")
    }

    func testAMissingBlockTimestampIsNotRenderedAsAWrongDate() {
        XCTAssertEqual(ActivityFormat.blockTimestamp(nil), "Pending")
        XCTAssertEqual(ActivityFormat.blockTimestamp("0x0"), "Pending")
        XCTAssertEqual(ActivityFormat.blockTimestamp(""), "Pending")
        XCTAssertEqual(ActivityFormat.blockTimestamp("not hex"), "Pending")
    }

    func testDateGroupsAreTheThreeUppercaseBuckets() {
        let now = Date(timeIntervalSince1970: 1_726_000_000)
        let yesterday = now.addingTimeInterval(-86_400)
        let older = now.addingTimeInterval(-10 * 86_400)

        XCTAssertEqual(
            ActivityFormat.dateGroup(
                blockTimestampHex: hex(Int64(now.timeIntervalSince1970 * 1000)),
                now: now,
                calendar: calendar
            ),
            "TODAY"
        )
        XCTAssertEqual(
            ActivityFormat.dateGroup(
                blockTimestampHex: hex(Int64(yesterday.timeIntervalSince1970 * 1000)),
                now: now,
                calendar: calendar
            ),
            "YESTERDAY"
        )
        XCTAssertEqual(
            ActivityFormat.dateGroup(
                blockTimestampHex: hex(Int64(older.timeIntervalSince1970 * 1000)),
                now: now,
                calendar: calendar
            ),
            "EARLIER"
        )
        XCTAssertEqual(
            ActivityFormat.dateGroup(blockTimestampHex: nil, now: now, calendar: calendar),
            "PENDING"
        )
    }

    // MARK: - Detail rows

    func testAFeePayingRecordShowsItsFeeAndAReceiveShowsNone() {
        let sent = ActivityFixtures.record(direction: "out", feeShannons: 100_000)
        XCTAssertTrue(sent.paysNetworkFee())
        XCTAssertEqual(sent.formattedFee(), "0.001 CKB")

        let received = ActivityFixtures.record(balanceChange: "0x5f5e100", direction: "in")
        XCTAssertFalse(received.paysNetworkFee(), "the sender paid it, so no fee row")

        // A fee that is not resolvable yet reads as Pending, never a silent
        // zero: no real transaction pays zero.
        let unresolved = ActivityFixtures.record(direction: "out", feeShannons: nil)
        XCTAssertTrue(unresolved.paysNetworkFee())
        XCTAssertNil(unresolved.formattedFee())
    }

    func testTypeAndAmountLabelsMatchTheDirection() {
        XCTAssertEqual(ActivityCopy.typeLabel(ActivityFixtures.record(direction: "in")), "Received")
        XCTAssertEqual(ActivityCopy.typeLabel(ActivityFixtures.record(direction: "out")), "Sent")
        XCTAssertEqual(ActivityCopy.typeLabel(ActivityFixtures.record(direction: "self")), "Self Transfer")
        XCTAssertEqual(
            ActivityCopy.typeLabel(ActivityFixtures.record(direction: "dao_deposit")),
            "Dao Deposit"
        )
        XCTAssertEqual(
            ActivityCopy.typeLabel(ActivityFixtures.record(direction: "dao_unlock")),
            "Dao Unlock"
        )
        // The one place the amount card differs: a self transfer has no
        // direction worth naming above a number.
        XCTAssertEqual(
            ActivityCopy.amountCaption(ActivityFixtures.record(direction: "self")),
            "Amount"
        )
    }

    func testAnAmountIsSignedByDirection() {
        XCTAssertTrue(ActivityFixtures.record(direction: "in").formattedAmount().hasPrefix("+"))
        XCTAssertTrue(ActivityFixtures.record(direction: "dao_unlock").formattedAmount().hasPrefix("+"))
        XCTAssertTrue(ActivityFixtures.record(direction: "out").formattedAmount().hasPrefix("-"))
        XCTAssertTrue(ActivityFixtures.record(direction: "self").formattedAmount().hasPrefix("1"))
        XCTAssertTrue(ActivityFixtures.record(direction: "out").formattedAmount().hasSuffix(" CKB"))
    }

    func testBlockNumberIsGroupedDecimalAndAHashIsTruncated() {
        XCTAssertEqual(ActivityFormat.blockNumber("0x1170ea8"), "18,288,296")
        XCTAssertEqual(ActivityFormat.blockNumber("not hex"), "not hex")

        let hash = "0x1234567890abcdef1234567890abcdef"
        XCTAssertEqual(ActivityFormat.truncateHash(hash), "0x12345678...abcdef")
        XCTAssertEqual(ActivityFormat.truncateHash("0xshort"), "0xshort")
    }

    // MARK: - Explorer

    func testTheExplorerLinkIsPerNetwork() {
        let hash = "0xabc"
        XCTAssertEqual(
            ActivityFormat.explorerURL(txHash: hash, network: NetworkType.mainnet)?.absoluteString,
            "https://explorer.nervos.org/transaction/0xabc"
        )
        XCTAssertEqual(
            ActivityFormat.explorerURL(txHash: hash, network: NetworkType.testnet)?.absoluteString,
            "https://testnet.explorer.nervos.org/transaction/0xabc"
        )
    }

    // MARK: - Empty states

    func testTheEmptyMessageNamesTheTab() {
        XCTAssertEqual(ActivityCopy.emptyMessage(ActivityFilter.all), "No transactions yet")
        XCTAssertEqual(ActivityCopy.emptyMessage(ActivityFilter.received), "No received transactions")
        XCTAssertEqual(ActivityCopy.emptyMessage(ActivityFilter.sent), "No sent transactions")
    }

    func testTheFilterIdentifiersAreTheOnesTheUITestsLookFor() {
        XCTAssertEqual(ActivityCopy.filterIdentifier(ActivityFilter.all), "activity.filter.all")
        XCTAssertEqual(ActivityCopy.filterIdentifier(ActivityFilter.received), "activity.filter.received")
        XCTAssertEqual(ActivityCopy.filterIdentifier(ActivityFilter.sent), "activity.filter.sent")
    }
}
