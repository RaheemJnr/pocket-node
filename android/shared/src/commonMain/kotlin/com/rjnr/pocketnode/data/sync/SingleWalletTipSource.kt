package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.data.gateway.TipSource
import kotlinx.coroutines.flow.StateFlow

/**
 * [TipSource] over the single-wallet sync service, so iOS can run the shared
 * [BroadcastWatchdog].
 *
 * On Android the repository implements `TipSource` itself, because it is the
 * thing that already holds the tip flow and the active wallet. iOS has no
 * repository: the same two facts live on [SingleWalletSyncService], and this
 * is the three-line adapter that says so. Nothing is cached here on purpose,
 * every member reads through, so a wallet set after the watchdog started is
 * seen on the next tick rather than never.
 */
class SingleWalletTipSource(
    private val service: SingleWalletSyncService,
) : TipSource {

    override val tipFlow: StateFlow<Long> get() = service.tipFlow

    override suspend fun fetchAndPublishTip(): Long = service.fetchAndPublishTip()

    override fun activeWalletAndNetworkOrNull(): Pair<String, String>? =
        service.activeWalletAndNetworkOrNull()
}

/**
 * A [LifecycleProvider] that is always started.
 *
 * Android gates the watchdog per check, from `ProcessLifecycleOwner`, because
 * its watchdog runs for the life of the process. iOS gates it at the other
 * end: the app starts the watchdog when the scene becomes active and stops it
 * when it goes to the background, so by the time a check runs the answer here
 * is always yes. Two gates would mean the stop path had to agree with a
 * predicate as well, which is one more thing to get out of step.
 */
object AlwaysStartedLifecycleProvider : LifecycleProvider {
    override fun isAtLeastStarted(): Boolean = true
}
