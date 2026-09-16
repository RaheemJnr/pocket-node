import PocketNodeCore

/// Kotlin types that are safe to hand across an isolation boundary.
///
/// Swift 6 treats every imported Kotlin class as non-`Sendable`, so calling one
/// of SKIE's `async` wrappers (which are `nonisolated`) from the main actor is
/// rejected: the receiver and its arguments would be "sent" out of the actor.
/// The declarations below say, for four specific types, that this is fine.
///
/// Each one is justified rather than blanket:
///
/// - `SingleWalletSyncService` holds `@Volatile` references and `StateFlow`s,
///   both thread-safe by construction, and hands the rest of its work to a
///   coroutine scope of its own. Kotlin/Native's memory model has allowed
///   objects to be shared between threads since 1.7.20, which is what the whole
///   shared core already relies on.
/// - `SyncMode` is an enum constant: four process-wide singletons with no state.
/// - `KotlinLong` and `KotlinBoolean` are immutable boxes around a primitive.
///
/// The same reasoning `UniffiLightClientApi` is declared `@unchecked Sendable`
/// under. Do not widen this list without the same kind of argument: a Kotlin
/// class with plain `var` state would be a real race.
extension SingleWalletSyncService: @retroactive @unchecked Sendable {}
/// - `ActivityFeed` carries two `MutableStateFlow`s (thread-safe by
///   construction) and two pieces of plain mutable state that are not:
///   `rescanAttempted`, an ordinary `MutableSet`, and `broadcastJob`, an
///   ordinary `var`. Neither is a race here, and the reason is the call sites
///   rather than the types. `broadcastJob` is written only by
///   `observeBroadcasts`, which `SyncService` calls from `activate` on the
///   main actor. `rescanAttempted` is read and written only inside
///   `readBalance`, and `SyncService.refreshBalance` single-flights that: it
///   refuses to start a second read while `balanceRead` is non-nil, and that
///   property too is main-actor-only. So both are effectively confined to one
///   actor with no concurrent second caller. If a second balance reader is
///   ever added, or `observeBroadcasts` is called off the main actor, guard
///   `rescanAttempted` with a `Mutex` before widening this.
/// - `NetworkType`, `ActivityFilter`, `TxDisplayState`, `TxFailureReason` and
///   `ElapsedUnit` are enum constants: process-wide singletons with no state.
/// - `Script`, `BalanceResponse`, `TransactionRecord`, `PendingBroadcastRecord`,
///   `ActivityItem` and `ElapsedBucket` are Kotlin `data class`es with `val`
///   properties only, so they are immutable values in every sense but the
///   compiler's.
extension ActivityFeed: @retroactive @unchecked Sendable {}
extension NetworkType: @retroactive @unchecked Sendable {}
extension ActivityFilter: @retroactive @unchecked Sendable {}
extension TxDisplayState: @retroactive @unchecked Sendable {}
extension TxFailureReason: @retroactive @unchecked Sendable {}
extension ElapsedUnit: @retroactive @unchecked Sendable {}
extension ElapsedBucket: @retroactive @unchecked Sendable {}
extension Script: @retroactive @unchecked Sendable {}
extension BalanceResponse: @retroactive @unchecked Sendable {}
extension TransactionRecord: @retroactive @unchecked Sendable {}
extension PendingBroadcastRecord: @retroactive @unchecked Sendable {}
extension ActivityItem: @retroactive @unchecked Sendable {}
extension SyncMode: @retroactive @unchecked Sendable {}
extension KotlinLong: @retroactive @unchecked Sendable {}
extension KotlinBoolean: @retroactive @unchecked Sendable {}
