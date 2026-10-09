import XCTest
import PocketNodeCore
@testable import PocketNode

/// The regression test for a crash that took the whole process down.
///
/// `SendService` is `@MainActor`, and the two callbacks it hands to the shared
/// core satisfy plain Objective-C block parameters rather than `@Sendable`
/// function types. Written as closure literals inside the class, they
/// therefore inherited main-actor isolation. `SendPipeline.sendTransaction`
/// calls `isSyncing` from `Dispatchers.Default` five seconds after a
/// broadcast (#332's guard against re-registering a still-catching-up
/// wallet), and Swift's isolation check then failed on a background queue and
/// trapped: `EXC_BREAKPOINT` in `_dispatch_assert_queue_fail`, via
/// `swift_task_checkIsolatedSwift`. On an iPhone 14 Pro the app simply
/// vanished five seconds after every send, with the transaction already on
/// chain.
///
/// No Kotlin `catch` can intervene, because it is a Swift runtime trap rather
/// than an exception, and it leaves no Swift error behind either. So the test
/// has to be the crash: each case builds the supplier through the
/// `nonisolated` factory and invokes it off the main actor. Before the fix
/// both cases take the test runner down with them; after it they pass.
///
/// What is being asserted is the isolation, not the arithmetic, the bodies
/// only read a lock-guarded `SharedValueBox` and always did.
final class SendServiceIsolationTests: XCTestCase {

    /// Calls `body` on a global queue and waits for it, so the assertion runs
    /// somewhere that is definitively not the main actor.
    private func offMainActor<T: Sendable>(
        _ label: String,
        _ body: @escaping @Sendable () -> T
    ) -> T {
        let captured = SharedValueBox<T?>(nil)
        let done = expectation(description: label)
        DispatchQueue.global(qos: .default).async {
            XCTAssertFalse(Thread.isMainThread, "\(label) did not leave the main thread")
            captured.set(body())
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
        return captured.get()!
    }

    func testTheSyncingSupplierCanBeCalledOffTheMainActor() throws {
        let flag = SyncingFlag(false)
        let supplier = SendService.syncingSupplier(flag)

        XCTAssertFalse(offMainActor("isSyncing false") { supplier().boolValue })

        flag.set(true)
        XCTAssertTrue(
            offMainActor("isSyncing true") { supplier().boolValue },
            "the supplier must read the box live, not a value captured when it was built"
        )
    }

    func testTheBalanceChangedSupplierCanBeCalledOffTheMainActor() throws {
        let latest = BalanceBox(BalanceReading(shannons: 1_000, isCached: false))
        let baseline = BalanceBox(BalanceReading(shannons: 1_000, isCached: false))
        let supplier = SendService.balanceChangedSupplier(latest: latest, baseline: baseline)

        XCTAssertFalse(offMainActor("balance unchanged") { supplier().boolValue })

        latest.set(BalanceReading(shannons: 2_000, isCached: false))
        XCTAssertTrue(offMainActor("balance moved") { supplier().boolValue })
    }

    /// The shared core calls `isSyncing` once per send, from whichever thread
    /// the coroutine resumed on. Hammering it from several at once is not a
    /// realistic call pattern; it is the cheapest way to be sure the box's
    /// lock, and not luck, is what makes the reads safe.
    func testTheSuppliersAreSafeUnderConcurrentReadsAndWrites() throws {
        let flag = SyncingFlag(false)
        let supplier = SendService.syncingSupplier(flag)
        let finished = expectation(description: "concurrent")
        finished.expectedFulfillmentCount = 64

        for index in 0..<64 {
            DispatchQueue.global(qos: .default).async {
                if index.isMultiple(of: 2) {
                    flag.set(index.isMultiple(of: 4))
                } else {
                    _ = supplier().boolValue
                }
                finished.fulfill()
            }
        }

        wait(for: [finished], timeout: 20)
    }
}
