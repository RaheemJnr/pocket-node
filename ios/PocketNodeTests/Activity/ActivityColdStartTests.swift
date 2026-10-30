import PocketNodeCore
import XCTest

@testable import PocketNode

/// The cold-start retry: the screen can be opened before the light client has
/// started, and a first walk that fails then is not something the user can act
/// on.
@MainActor
final class ActivityColdStartTests: XCTestCase {

    func testAFailedFirstWalkOnAnEmptyListSchedulesARetry() async {
        let source = FakeActivityPaging()
        source.refreshError = ActivityTestError(message: "the node is not up")
        let wait = ControlledRetryWait()
        defer { wait.close() }
        let model = ActivityViewModel(source: source, retryWait: wait.sleep)

        model.onAppear()
        await waitFor { wait.isWaiting }
        XCTAssertEqual(source.refreshCount, 1)
        XCTAssertEqual(model.error, "the node is not up")
        XCTAssertEqual(wait.requestedDelay, Duration.seconds(3))

        // Install the successful response while precisely the first retry is held.
        source.refreshError = nil
        source.rows[ActivityFilter.all] = [ActivityFixtures.item()]
        wait.release()
        await waitFor { source.refreshCount == 2 && !model.isRefreshing && !model.isLoading && model.rows.count == 1 }

        XCTAssertEqual(source.refreshCount, 2, "one initial walk and exactly one automatic retry")
        XCTAssertEqual(wait.waitCount, 1)
        XCTAssertNil(model.error, "the retry cleared it without the user touching anything")
        XCTAssertEqual(model.rows.count, 1)
    }

    func testACancelledHeldRetryCannotWalkAfterManualRecovery() async {
        let source = FakeActivityPaging()
        source.refreshError = ActivityTestError(message: "the node is not up")
        let wait = ControlledRetryWait()
        defer { wait.close() }
        let model = ActivityViewModel(source: source, retryWait: wait.sleep)
        model.onAppear()
        await waitFor { wait.isWaiting }
        XCTAssertEqual(source.refreshCount, 1)

        source.refreshError = nil
        source.rows[ActivityFilter.all] = [ActivityFixtures.item()]
        model.refresh()
        await waitFor { source.refreshCount == 2 && !model.isRefreshing && !model.isLoading && model.rows.count == 1 }
        wait.release()
        await waitFor { wait.resumeCount == 1 }
        XCTAssertEqual(source.refreshCount, 2, "the cancelled timer cannot start a third walk")
        XCTAssertNil(model.error)
    }

    func testARetryIsNotScheduledWhenThereAreRowsToShow() async {
        let source = FakeActivityPaging()
        source.rows[ActivityFilter.all] = [ActivityFixtures.item()]
        // A near-zero delay, so "no retry fired" is a real claim rather than
        // an assertion made before the production three seconds elapsed.
        let model = ActivityViewModel(source: source, retryDelay: .milliseconds(5))

        model.onAppear()
        await settle()
        let after = source.refreshCount

        // A later failure with rows on screen leaves them alone and does not
        // start hammering the node.
        source.refreshError = ActivityTestError(message: "peers went away")
        model.refresh()
        await settle()
        XCTAssertEqual(model.error, "peers went away")
        XCTAssertEqual(model.rows.count, 1)

        // Many multiples of the retry delay. A retry that was going to fire
        // has had every chance to.
        try? await Task.sleep(for: .milliseconds(300))
        await settle()
        XCTAssertEqual(source.refreshCount, after + 1, "no automatic retry while rows are shown")
    }

    func testTheRetryStopsAtTheCeilingWhenTheNodeNeverComesUp() async {
        let source = FakeActivityPaging()
        source.refreshError = ActivityTestError(message: "no node")
        let model = ActivityViewModel(source: source, retryDelay: .milliseconds(2))

        model.onAppear()
        // One walk plus at most `maxAutoRetries` more, however long we wait.
        try? await Task.sleep(for: .milliseconds(400))
        await settle()

        XCTAssertEqual(
            source.refreshCount,
            ActivityViewModel.maxAutoRetries + 1,
            "it gives up so the Retry button means something"
        )
        XCTAssertEqual(model.error, "no node")
    }

    func testTheRetryGivesUpSoTheButtonMeansSomething() async {
        let source = FakeActivityPaging()
        source.refreshError = ActivityTestError(message: "no node")
        let model = ActivityViewModel(source: source)

        model.onAppear()
        await settle()

        // Bounded: the ceiling is the same half-minute the sync layer gives
        // the node, so a node that never starts stops being asked.
        XCTAssertLessThanOrEqual(ActivityViewModel.maxAutoRetries, 10)
        XCTAssertEqual(
            ActivityViewModel.maxAutoRetries * ActivityViewModel.retryDelaySeconds,
            SyncService.nodeWaitSeconds
        )
        XCTAssertEqual(model.error, "no node")
    }

    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
    }

    /// Polls [condition] for up to five seconds. The retry sleeps for seconds,
    /// so a fixed yield count cannot see it land.
    private func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<500 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("the condition never became true")
    }
}

/// A deliberately noncooperative wait lets the model's cancellation check be tested.
/// close() also handles a late waiter, so assertion failure cannot strand a task.
@MainActor
private final class ControlledRetryWait {
    private var continuation: CheckedContinuation<Void, Never>?
    private var closed = false
    private(set) var waitCount = 0
    private(set) var resumeCount = 0
    private(set) var requestedDelay: Duration?
    var isWaiting: Bool { continuation != nil }

    func sleep(_ delay: Duration) async throws {
        waitCount += 1
        requestedDelay = delay
        if !closed {
            await withCheckedContinuation { continuation = $0 }
        }
        resumeCount += 1
    }

    func release() {
        let held = continuation
        continuation = nil
        held?.resume()
    }

    func close() {
        closed = true
        release()
    }
}
