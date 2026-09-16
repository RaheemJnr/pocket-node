import Foundation

/// One value, readable and writable from any thread.
///
/// It exists because the shared core takes two plain, non-suspending callbacks
/// that it invokes from a Kotlin coroutine: `SendContext.isSyncing`, which the
/// send path consults five seconds after a broadcast to decide whether the
/// partial re-register is safe (#332), and
/// `SendStatusPoller.refreshAndCheckBalanceChanged`, which asks whether the
/// balance has moved. Both readings live on main-actor-isolated objects, so a
/// closure reading them directly would be a main-actor access from a Kotlin
/// thread: Swift 6 refuses it, and it would be a real race if it did not.
/// The owning object mirrors the value in here instead.
///
/// A lock rather than `Synchronization.Atomic`, which needs iOS 18 while this
/// app deploys to 17. The critical section is one load or one store.
final class SharedValueBox<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ initial: Value) {
        value = initial
    }

    func set(_ newValue: Value) {
        lock.lock()
        value = newValue
        lock.unlock()
    }

    func get() -> Value {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// Whether the wallet is still catching the chain up.
typealias SyncingFlag = SharedValueBox<Bool>

/// The last spendable balance the sync layer published, in shannons.
typealias BalanceBox = SharedValueBox<Int64>
