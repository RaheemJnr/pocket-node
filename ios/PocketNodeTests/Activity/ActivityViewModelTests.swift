import PocketNodeCore
import XCTest

@testable import PocketNode

/// The list's paging state machine: what a first load asks for, what a filter
/// switch throws away, and when it stops asking for more.
@MainActor
final class ActivityViewModelTests: XCTestCase {

    private func items(_ count: Int, prefix: String = "0x") -> [ActivityItem] {
        (0..<count).map { ActivityFixtures.item(record: ActivityFixtures.record(txHash: "\(prefix)\($0)")) }
    }

    func testTheFirstLoadReadsPageZero() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(3)
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)

        XCTAssertEqual(model.rows.count, 3)
        XCTAssertEqual(source.requestedPages.first?.1, 0)
        XCTAssertFalse(model.isLoading)
    }

    func testAShortFirstPageMeansThereIsNoMore() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(3)
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)

        XCTAssertFalse(model.hasMore)
        let pagesBefore = source.requestedPages.count
        model.loadMore()
        await settle(model)
        XCTAssertEqual(source.requestedPages.count, pagesBefore, "loadMore did not ask again")
    }

    func testAFullPageIsFollowedByTheNextOne() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(ActivityViewModel.pageSize + 4)
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)
        XCTAssertEqual(model.rows.count, ActivityViewModel.pageSize)
        XCTAssertTrue(model.hasMore)

        model.loadMore()
        await settle(model)

        XCTAssertEqual(model.rows.count, ActivityViewModel.pageSize + 4)
        XCTAssertFalse(model.hasMore)
        XCTAssertEqual(source.requestedPages.map(\.1).suffix(1), [1])
    }

    func testSwitchingFilterResetsPagingToPageZero() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(ActivityViewModel.pageSize + 4)
        source.rows[ActivityFilter.sent] = items(2, prefix: "0xsent")
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)
        model.loadMore()
        await settle(model)
        XCTAssertEqual(model.rows.count, ActivityViewModel.pageSize + 4)

        model.setFilter(ActivityFilter.sent)
        await settle(model)

        XCTAssertEqual(model.filter, ActivityFilter.sent)
        XCTAssertEqual(model.rows.count, 2, "the previous filter's rows were dropped")
        XCTAssertTrue(model.hasMore || model.rows.count < ActivityViewModel.pageSize)
        // The read after the switch starts at page 0, not where the old
        // filter's paging had got to.
        XCTAssertEqual(source.requestedPages.last?.0, ActivityFilter.sent)
        XCTAssertEqual(source.requestedPages.last?.1, 0)
    }

    func testSelectingTheFilterAlreadyOnDoesNothing() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(3)
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)
        let pagesBefore = source.requestedPages.count

        model.setFilter(ActivityFilter.all)
        await settle(model)

        XCTAssertEqual(source.requestedPages.count, pagesBefore)
    }

    func testAFailedHistoryWalkSurfacesItsMessageAndKeepsTheRows() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(2)
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)
        XCTAssertEqual(model.rows.count, 2)

        source.refreshError = ActivityTestError(message: "the node is not up")
        model.refresh()
        await settle(model)

        XCTAssertEqual(model.error, "the node is not up")
        XCTAssertEqual(model.rows.count, 2, "a failed walk leaves the cached rows on screen")
    }

    func testARetryAfterAFailureClearsTheError() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(1)
        source.refreshError = ActivityTestError(message: "no peers yet")
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)
        XCTAssertEqual(model.error, "no peers yet")

        source.refreshError = nil
        model.refresh()
        await settle(model)

        XCTAssertNil(model.error)
        XCTAssertEqual(model.rows.count, 1)
    }

    func testABroadcastChangeRereadsTheLoadedPages() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(2)
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)
        let pagesBefore = source.requestedPages.count

        // The watchdog moved a row from BROADCASTING to BROADCAST; the display
        // state is a join the shared feed owns, so the page is re-read rather
        // than patched here.
        source.rows[ActivityFilter.all] = [
            ActivityFixtures.item(
                record: ActivityFixtures.record(txHash: "0x0", confirmations: 0, status: "PENDING"),
                broadcast: ActivityFixtures.broadcast(txHash: "0x0", state: "BROADCAST")
            ),
            ActivityFixtures.item(record: ActivityFixtures.record(txHash: "0x1")),
        ]
        source.emitBroadcastChange()
        await settle(model)

        XCTAssertGreaterThan(source.requestedPages.count, pagesBefore)
        XCTAssertEqual(model.rows.first?.item.displayState, TxDisplayState.pending)
    }

    func testTheNetworkComesFromTheSource() {
        let source = FakeActivityPaging(network: NetworkType.mainnet)
        XCTAssertEqual(ActivityViewModel(source: source).network, NetworkType.mainnet)
    }

    /// Lets the view model's detached tasks run to completion.
    ///
    /// The loads are fire-and-forget `Task`s rather than `async` methods, which
    /// is what lets a SwiftUI button call them; a few main-actor hops is enough
    /// for a fake that never suspends on real work.
    private func settle(_ model: ActivityViewModel) async {
        for _ in 0..<20 {
            await Task.yield()
        }
        _ = model
    }
}
