package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.sync.ChainSyncState
import com.rjnr.pocketnode.data.wallet.WalletInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CellCountStage { WAITING, REGISTERED, CAUGHT_UP }

data class CellCountReadiness(
    val epoch: Long,
    val walletId: String,
    val network: NetworkType,
    val address: String,
    val script: Script,
    val stage: CellCountStage,
    /** Stable across resync epochs; replaced when wallet/network/address activation changes. */
    val activationEpoch: Long = epoch,
)

/** Bounded count-acquisition hints, not transaction authority or a continuously fresh index guarantee. */
internal class CellCountReadinessTracker {
    private val state = MutableStateFlow<CellCountReadiness?>(null)
    val readiness = state.asStateFlow()
    private var epoch = 0L

    @Synchronized
    fun activate(walletId: String, network: NetworkType, info: WalletInfo) {
        val address = if (network == NetworkType.TESTNET) info.testnetAddress else info.mainnetAddress
        val current = state.value
        if (current?.walletId == walletId && current.network == network &&
            current.address == address && current.script == info.script) return
        state.value = CellCountReadiness(++epoch, walletId, network, address, info.script, CellCountStage.WAITING)
    }

    @Synchronized
    fun registered(expected: CellCountReadiness?) {
        val current = state.value ?: return
        if (expected?.epoch != current.epoch || current.stage != CellCountStage.WAITING) return
        state.value = current.copy(stage = CellCountStage.REGISTERED)
    }

    /** One explicit resync set landed for this serialized acquisition token. */
    @Synchronized
    fun resyncLanded(expected: CellCountReadiness?) {
        val current = state.value ?: return
        if (expected?.epoch != current.epoch) return
        state.value = current.copy(epoch = ++epoch, stage = CellCountStage.REGISTERED)
    }

    /** Called only for a fresh chain read held under the repository's registration lock. */
    @Synchronized
    fun observe(expected: CellCountReadiness?, chain: ChainSyncState) {
        val current = state.value ?: return
        if (expected?.epoch != current.epoch || expected.stage == CellCountStage.WAITING ||
            current.stage != CellCountStage.REGISTERED || chain.tipNumber <= 0L) return
        val cursor = chain.scripts.singleOrNull { it.script == current.script && it.scriptType == "lock" }
            ?.blockNumber?.removePrefix("0x")?.toLongOrNull(16) ?: return
        // Exact coverage, not the UI's +/-10-block "synced" window. Native advances
        // this cursor after indexing matched blocks (or proving a no-match range).
        if (cursor >= chain.tipNumber) state.value = current.copy(stage = CellCountStage.CAUGHT_UP)
    }
}
