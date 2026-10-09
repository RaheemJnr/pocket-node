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
    /// A broadcast row changing while `loadMore()` waits on its page used to
    /// cancel that load, whose spinner cleanup only runs when it is still
    /// current, and the reload that replaced it never cleared the spinner
    /// either. The trailing spinner stuck and every later `loadMore()`
    /// returned at its guard.
    func testABroadcastChangeDuringLoadMoreDoesNotStrandTheSpinner() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = items(ActivityViewModel.pageSize * 2 + 4)
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle(model)
        XCTAssertEqual(model.rows.count, ActivityViewModel.pageSize)

        source.holdPageIndex = 1
        model.loadMore()
        await settle(model)
        XCTAssertTrue(source.isHoldingPage)
        XCTAssertTrue(model.isLoadingMore)

        source.emitBroadcastChange()
        await settle(model)

        XCTAssertFalse(model.isLoadingMore, "the reload that replaced the load cleared its spinner")
        XCTAssertEqual(
            model.rows.count,
            ActivityViewModel.pageSize * 2,
            "the page the cancelled load was after was read by the reload, not lost"
        )
        XCTAssertTrue(model.hasMore)

        // The superseded read finally answering changes nothing.
        source.releaseHeldPage()
        await settle(model)
        XCTAssertFalse(model.isLoadingMore)
        XCTAssertEqual(model.rows.count, ActivityViewModel.pageSize * 2)

        // And paging still works: the guard is not stuck shut.
        model.loadMore()
        await settle(model)
        XCTAssertEqual(model.rows.count, ActivityViewModel.pageSize * 2 + 4)
        XCTAssertFalse(model.hasMore)
        XCTAssertFalse(model.isLoadingMore)
    }

    // MARK: - Retry

    private func failedItem() -> ActivityItem {
        ActivityFixtures.item(
            record: ActivityFixtures.record(txHash: "0xfail", status: "FAILED")
        )
    }

    func testARetryThatDidNotGoOutRaisesItsReason() async {
        let source = FakeActivityPaging()
        // A main-actor class rather than a captured `var`: the handler is a
        // `@MainActor` function type, which is Sendable, and older compilers
        // reject a Sendable closure that mutates a captured local.
        let asked = RetryRecorder()
        let model = ActivityViewModel(source: source, onRetry: { hash in
            asked.hashes.append(hash)
            return "The network rejected the transaction"
        })

        XCTAssertTrue(model.canRetry)
        model.retry(txHash: failedItem().record.txHash)
        await settle(model)

        XCTAssertEqual(asked.hashes, ["0xfail"])
        XCTAssertEqual(model.retryError, "The network rejected the transaction")

        model.dismissRetryError()
        XCTAssertNil(model.retryError)
    }

    func testARetryWithNoReasonStillSaysItFailed() async {
        let model = ActivityViewModel(source: FakeActivityPaging(), onRetry: { _ in "" })

        model.retry(txHash: "0xfail")
        await settle(model)

        XCTAssertEqual(model.retryError, ActivityCopy.retryFailed)
    }

    func testARetryThatWentOutRaisesNothing() async {
        let model = ActivityViewModel(source: FakeActivityPaging(), onRetry: { _ in nil })

        model.retry(txHash: "0xfail")
        await settle(model)

        XCTAssertNil(model.retryError)
    }

    func testNoRetryHandlerMeansNoRetry() async {
        let model = ActivityViewModel(source: FakeActivityPaging())

        XCTAssertFalse(model.canRetry)
        model.retry(txHash: "0xfail")
        await settle(model)
        XCTAssertNil(model.retryError)
    }

    private func settle(_ model: ActivityViewModel) async {
        for _ in 0..<20 {
            await Task.yield()
        }
        _ = model
    }
}

@MainActor
private final class RetryRecorder {
    var hashes: [String] = []
}
