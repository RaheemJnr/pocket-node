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
extension SyncMode: @retroactive @unchecked Sendable {}
extension KotlinLong: @retroactive @unchecked Sendable {}
extension KotlinBoolean: @retroactive @unchecked Sendable {}
