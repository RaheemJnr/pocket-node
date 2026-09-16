package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.data.gateway.SyncPollSource
import com.rjnr.pocketnode.data.gateway.models.AccountStatusResponse

/** The wallet the poll loop is reporting on: its address and its lock args. */
data class ActiveWallet(
    val address: String,
    val scriptArgs: String,
)

/**
 * The plain single-wallet driver for [SyncPoller][com.rjnr.pocketnode.data.gateway.SyncPoller]:
 * read the chain state, derive this wallet's numbers, answer.
 *
 * This is the iOS path. Swift hands in a closure that reads whatever it holds
 * the active wallet in, and gets the same `AccountStatusResponse` the Android
 * UI has always polled, without reimplementing the derivation.
 *
 * Android deliberately does not use it: `GatewayRepository` implements
 * [SyncPollSource] itself because its `getAccountStatus()` also persists every
 * registered wallet's progress, re-evaluates the BALANCED filter set and
 * reconciles sub-account discovery candidates between the read and the derive.
 *
 * [activeWallet] is called on every poll rather than captured once, so a
 * wallet switch is picked up on the next tick.
 *
 * One deliberate difference from the Android path: `isRegistered` here is
 * derived, meaning "this wallet's lock args appear in the light client's
 * registered script list". The repository instead reports its own
 * `_isRegistered` flag, which it sets when it asks for registration. The two
 * disagree while a registration is in flight, and this one is the stricter
 * reading: it only says yes once the node itself confirms the script.
 */
class SingleWalletSyncPollSource(
    private val engine: SyncEngine,
    private val activeWallet: () -> ActiveWallet?,
) : SyncPollSource {

    override fun hasWalletInfo(): Boolean = activeWallet() != null

    override suspend fun getAccountStatus(): Result<AccountStatusResponse> = runCatching {
        val wallet = activeWallet() ?: throw Exception("No wallet")
        val state = engine.readChainSyncState()
        val snapshot = engine.computeStatus(state, wallet.scriptArgs)

        AccountStatusResponse(
            address = wallet.address,
            isRegistered = state.scripts.any { it.script.args == wallet.scriptArgs },
            tipNumber = state.tipNumber.toString(),
            syncedToBlock = snapshot.scriptBlockNumber.toString(),
            syncProgress = snapshot.progress.coerceIn(0.0, 1.0),
            isSynced = snapshot.isSynced,
        )
    }
}
