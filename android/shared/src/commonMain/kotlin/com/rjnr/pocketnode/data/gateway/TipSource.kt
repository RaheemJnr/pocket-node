package com.rjnr.pocketnode.data.gateway

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow seam over the repository so
 * [com.rjnr.pocketnode.data.sync.BroadcastWatchdog] can be unit-tested without
 * instantiating a full Repository (whose constructor surface is wide).
 * `GatewayRepository` implements this; tests use a small fake.
 *
 * Moved to `commonMain` with the watchdog (M3 #5), keeping its package, so the
 * repository's `: TipSource` needs no import change.
 */
interface TipSource {
    /** Monotonic light-client tip stream. Initial value 0L until first publish. */
    val tipFlow: StateFlow<Long>

    /** Pull a fresh tip via the bridge and publish to [tipFlow] if higher. Returns the tip read (or 0L). */
    suspend fun fetchAndPublishTip(): Long

    /** (walletId, networkName) of the active wallet, or null if no active wallet. */
    fun activeWalletAndNetworkOrNull(): Pair<String, String>?
}
