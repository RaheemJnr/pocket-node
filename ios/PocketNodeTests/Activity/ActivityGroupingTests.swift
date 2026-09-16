import PocketNodeCore
import XCTest

@testable import PocketNode

/// Date headers, decided once per assembly rather than by looking backwards
/// from inside a SwiftUI row closure.
///
/// The closure version crashed: `ForEach` re-evaluates a row body with the
/// index it was built with, and the cold-start retry, a pull-to-refresh and a
/// page append all replace `items` underneath it, so `items[index - 1]` could
/// address an element that was no longer there
/// (`Array._checkSubscript`, index out of range). These tests are on the pure
/// function that replaced it.
final class ActivityGroupingTests: XCTestCase {

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    private let now = Date(timeIntervalSince1970: 1_726_000_000)

    private func hex(_ date: Date) -> String {
        "0x" + String(Int64(date.timeIntervalSince1970 * 1000), radix: 16)
    }

    private func item(_ txHash: String, at date: Date) -> ActivityItem {
        ActivityFixtures.item(
            record: ActivityFixtures.record(txHash: txHash, blockTimestampHex: hex(date))
        )
    }

    // MARK: - Headers

    func testOnlyTheFirstRowOfEachGroupCarriesAHeader() {
        let today = now
        let older = now.addingTimeInterval(-10 * 86_400)
        let items = [
            item("0xa", at: today),
            item("0xb", at: today),
            item("0xc", at: older),
        ]

        let rows = ActivityGrouping.assemble(items, now: now, calendar: calendar)

        XCTAssertEqual(rows.map(\.showsGroupHeader), [true, false, true])
        XCTAssertEqual(rows.map(\.groupLabel), ["TODAY", "TODAY", "EARLIER"])
        XCTAssertEqual(rows.map(\.id), ["0xa", "0xb", "0xc"])
    }

    func testEveryDistinctGroupOpensWithItsOwnHeader() {
        let rows = ActivityGrouping.assemble(
            [
                item("0xa", at: now),
                item("0xb", at: now.addingTimeInterval(-86_400)),
                item("0xc", at: now.addingTimeInterval(-10 * 86_400)),
            ],
            now: now,
            calendar: calendar
        )

        XCTAssertEqual(rows.map(\.groupLabel), ["TODAY", "YESTERDAY", "EARLIER"])
        XCTAssertEqual(rows.map(\.showsGroupHeader), [true, true, true])
    }

    func testARowWithNoBlockTimestampGroupsAsPending() {
        let rows = ActivityGrouping.assemble(
            [
                ActivityFixtures.item(
                    record: ActivityFixtures.record(txHash: "0xp", blockTimestampHex: nil)
                ),
                item("0xa", at: now),
            ],
            now: now,
            calendar: calendar
        )

        XCTAssertEqual(rows.map(\.groupLabel), ["PENDING", "TODAY"])
        XCTAssertEqual(rows.map(\.showsGroupHeader), [true, true])
    }

    func testAFailedRowGetsItsOwnHeaderRatherThanSittingUnderPending() {
        let failed = ActivityFixtures.item(
            record: ActivityFixtures.record(
                txHash: "0xdead",
                confirmations: 0,
                blockTimestampHex: nil,
                status: "FAILED"
            ),
            broadcast: ActivityFixtures.broadcast(txHash: "0xdead", state: "FAILED", nullCount: 4)
        )
        let stillPending = ActivityFixtures.item(
            record: ActivityFixtures.record(
                txHash: "0xwait",
                confirmations: 0,
                blockTimestampHex: nil,
                status: "PENDING"
            ),
            broadcast: ActivityFixtures.broadcast(txHash: "0xwait", state: "BROADCAST")
        )

        let rows = ActivityGrouping.assemble([failed, stillPending], now: now, calendar: calendar)

        // Neither is in a block, so both would read as PENDING on the block
        // timestamp alone. They mean opposite things.
        XCTAssertEqual(rows.map(\ActivityListRow.groupLabel), ["FAILED", "PENDING"])
        XCTAssertEqual(rows.map(\ActivityListRow.showsGroupHeader), [true, true])
    }

    func testTwoFailedRowsShareOneHeader() {
        let failed = { (hash: String) in
            ActivityFixtures.item(
                record: ActivityFixtures.record(
                    txHash: hash,
                    confirmations: 0,
                    blockTimestampHex: nil,
                    status: "FAILED"
                ),
                broadcast: ActivityFixtures.broadcast(txHash: hash, state: "FAILED")
            )
        }

        let rows = ActivityGrouping.assemble(
            [failed("0xa"), failed("0xb")],
            now: now,
            calendar: calendar
        )

        XCTAssertEqual(rows.map(\ActivityListRow.groupLabel), ["FAILED", "FAILED"])
        XCTAssertEqual(rows.map(\ActivityListRow.showsGroupHeader), [true, false])
    }

    func testAnEmptyPageAssemblesToNothing() {
        XCTAssertTrue(ActivityGrouping.assemble([], now: now, calendar: calendar).isEmpty)
    }

    func testOneRowAlwaysCarriesItsHeader() {
        let rows = ActivityGrouping.assemble([item("0xa", at: now)], now: now, calendar: calendar)
        XCTAssertEqual(rows.count, 1)
        XCTAssertTrue(rows[0].showsGroupHeader)
    }

    // MARK: - The crash

    func testReassemblingAfterTheArrayShrinksReferencesNothingRemoved() {
        let today = now
        let older = now.addingTimeInterval(-10 * 86_400)
        let full = [
            item("0xa", at: today),
            item("0xb", at: today),
            item("0xc", at: older),
        ]
        _ = ActivityGrouping.assemble(full, now: now, calendar: calendar)

        // What the cold-start retry, a filter switch and a refresh all do:
        // replace the array with a shorter one. The old assembly's positions
        // are meaningless now, and the new one must be decided entirely from
        // the array it was handed.
        let shrunk = [full[2]]
        let rows = ActivityGrouping.assemble(shrunk, now: now, calendar: calendar)

        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].id, "0xc")
        XCTAssertTrue(rows[0].showsGroupHeader, "the surviving row opens its own group")
        XCTAssertEqual(rows[0].groupLabel, "EARLIER")
    }

    func testAReorderedArrayIsGroupedByItsOwnOrderNotThePreviousOne() {
        let today = now
        let older = now.addingTimeInterval(-10 * 86_400)

        let first = ActivityGrouping.assemble(
            [item("0xa", at: today), item("0xc", at: older)],
            now: now,
            calendar: calendar
        )
        XCTAssertEqual(first.map(\.groupLabel), ["TODAY", "EARLIER"])

        let reordered = ActivityGrouping.assemble(
            [item("0xc", at: older), item("0xa", at: today)],
            now: now,
            calendar: calendar
        )
        XCTAssertEqual(reordered.map(\.groupLabel), ["EARLIER", "TODAY"])
        XCTAssertEqual(reordered.map(\.showsGroupHeader), [true, true])
    }

    // MARK: - Through the view model

    @MainActor
    func testALoadedPageArrivesAlreadyGrouped() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = [
            item("0xa", at: now),
            item("0xb", at: now),
        ]
        let model = ActivityViewModel(source: source)

        model.onAppear()
        for _ in 0..<20 { await Task.yield() }
        // Re-heads against this test's fixed clock; the load itself assembled
        // against the wall clock, which is years past the fixture's date.
        model.refreshGroupingIfDayChanged(now: now, calendar: calendar)

        XCTAssertEqual(model.rows.count, 2)
        XCTAssertEqual(model.rows.map(\.id), ["0xa", "0xb"])
        XCTAssertEqual(model.rows.map(\.showsGroupHeader), [true, false])
        XCTAssertEqual(model.rows.map(\.groupLabel), ["TODAY", "TODAY"])
    }

    @MainActor
    func testCrossingMidnightReHeadsTheRowsOnScreen() async {
        // 23:50 on the day the row landed.
        let lateYesterday = Date(timeIntervalSince1970: 1_726_012_200)
        let calendar = self.calendar
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = [item("0xa", at: lateYesterday)]
        let model = ActivityViewModel(source: source)

        model.onAppear()
        for _ in 0..<20 { await Task.yield() }
        // Assembled against its own day, so it reads as today.
        model.refreshGroupingIfDayChanged(now: lateYesterday, calendar: calendar)
        XCTAssertEqual(model.rows.first?.groupLabel, "TODAY")

        // The ticker fires twenty minutes later, on the other side of midnight.
        let afterMidnight = lateYesterday.addingTimeInterval(20 * 60)
        model.refreshGroupingIfDayChanged(now: afterMidnight, calendar: calendar)

        XCTAssertEqual(
            model.rows.first?.groupLabel,
            "YESTERDAY",
            "a header that says TODAY after midnight is a lie"
        )
        XCTAssertTrue(model.rows.first?.showsGroupHeader ?? false)
    }

    @MainActor
    func testATickWithinTheSameDayReassemblesNothing() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = [item("0xa", at: now)]
        let model = ActivityViewModel(source: source)

        model.onAppear()
        for _ in 0..<20 { await Task.yield() }
        model.refreshGroupingIfDayChanged(now: now, calendar: calendar)
        let before = model.rows.map(\.groupLabel)

        model.refreshGroupingIfDayChanged(now: now.addingTimeInterval(20), calendar: calendar)

        XCTAssertEqual(model.rows.map(\.groupLabel), before)
        XCTAssertEqual(before, ["TODAY"])
    }

    @MainActor
    func testTheLastRowIsIdentifiedByHashNotByIndex() async {
        let source = FakeActivityPaging()
        let rows = [item("0xa", at: now), item("0xb", at: now)]
        source.rows[ActivityFilter.all] = rows
        let model = ActivityViewModel(source: source)

        model.onAppear()
        for _ in 0..<20 { await Task.yield() }

        XCTAssertFalse(model.isLastLoadedRow(rows[0]))
        XCTAssertTrue(model.isLastLoadedRow(rows[1]))
        // A row that is no longer in the list is simply not the last one,
        // rather than an out-of-range lookup.
        XCTAssertFalse(model.isLastLoadedRow(item("0xgone", at: now)))
    }
}
