package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.LightClientApi
import com.rjnr.pocketnode.data.gateway.SyncPollSource
import com.rjnr.pocketnode.data.gateway.SyncPoller
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.toFromBlock
import com.rjnr.pocketnode.data.sync.contract.SyncProgressTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/** One read of the node's chain-sync state: the tip and every registered filter script. */
data class ChainSyncState(
    val tipNumber: Long,
    val scripts: List<JniScriptStatus>,
)

/** What [SyncEngine.computeStatus] derives from a [ChainSyncState] for one wallet. */
data class SyncSnapshot(
    /** The active script's own synced-to block; 0 when it is not registered yet. */
    val scriptBlockNumber: Long,
    /** 0.0 to 1.0, relative to the wallet's sync start rather than to genesis. */
    val progress: Double,
    /** Whether that script is within 10 blocks of the tip, either side. */
    val isSynced: Boolean,
)

/**
 * The shared face of chain sync: one object both platforms drive.
 *
 * It owns the [SyncPoller] (and through it the [SyncProgressTracker] behind
 * the percentage and ETA) and adds the two pieces of derivation that used to
 * sit inline in `GatewayRepository.getAccountStatus()`:
 *
 *  - [readChainSyncState] does the node reads and the hex decoding;
 *  - [computeStatus] turns that state plus the active wallet's script args
 *    into the numbers the UI shows.
 *
 * Splitting the read from the derivation is what lets Android keep everything
 * it does between them (the per-wallet `setWalletSyncBlock` loop, the BALANCED
 * re-registration and the sub-account reconcile) exactly where it was, while
 * iOS, which has none of that, drives both halves back to back through
 * [SingleWalletSyncPollSource].
 *
 * Everything else here is a one-line delegation to the poller, so each
 * platform has a single entry point for the whole sync surface.
 *
 * [queryContext] is where the blocking bridge reads in [readChainSyncState]
 * run. It defaults to [Dispatchers.Default] because `Dispatchers.IO` is not
 * visible from `commonMain` (it is JVM-only in kotlinx-coroutines), the same
 * constraint [createPocketNodeCoreDatabase][com.rjnr.pocketnode.data.storage.createPocketNodeCoreDatabase]
 * works around. JVM callers pass `Dispatchers.IO` explicitly, which
 * `SharedModule` does; iOS keeps the default.
 */
class SyncEngine(
    private val lightClient: LightClientApi,
    private val syncPreferences: SyncPreferences,
    private val json: Json,
    private val logger: Logger,
    private val clock: Clock = SystemClock,
    private val queryContext: CoroutineContext = Dispatchers.Default,
) {

    private val poller = SyncPoller(syncPreferences, logger, clock)

    // --- Poller surface (see SyncPoller for the semantics of each) ---

    /** @see SyncPoller.syncProgress */
    val syncProgress: StateFlow<SyncProgress> get() = poller.syncProgress

    /** @see SyncPoller.tipFlow */
    val tipFlow: StateFlow<Long> get() = poller.tipFlow

    /**
     * @see SyncPoller.publishTip
     *
     * Public here where the poller's own is `internal`: the poller and its
     * callers no longer share a module, so the send path and the status
     * listener reach it through the engine.
     */
    fun publishTip(n: Long) = poller.publishTip(n)

    /** @see SyncPoller.start */
    fun start(scope: CoroutineScope, source: SyncPollSource) = poller.start(scope, source)

    /** @see SyncPoller.stop */
    fun stop() = poller.stop()

    /** @see SyncPoller.resetTracker */
    fun resetTracker() = poller.resetTracker()

    /** @see SyncPoller.seedStartHeight */
    fun seedStartHeight(startBlock: Long) = poller.seedStartHeight(startBlock)

    /** @see SyncPoller.resetProgressState */
    fun resetProgressState() = poller.resetProgressState()

    /** @see SyncPoller.calculate */
    fun calculate(tipHeight: Long): SyncProgressTracker.ProgressInfo = poller.calculate(tipHeight)

    // --- Chain-sync state ---

    /**
     * Read the tip header and every registered filter script off the node.
     *
     * Both bridge calls are blocking, so they run on [queryContext] rather
     * than on the caller's thread. That context is injected, following the
     * [createPocketNodeCoreDatabase][com.rjnr.pocketnode.data.storage.createPocketNodeCoreDatabase]
     * precedent, so Android keeps these JNI reads on `Dispatchers.IO` exactly
     * as it did when they sat inline in `GatewayRepository`, whose scope is
     * `SupervisorJob() + Dispatchers.IO`. The poll loop's hop count is
     * unchanged either way.
     *
     * Failures degrade the way the JNI contract does rather than throwing: a
     * null tip reads as block 0, null scripts as none registered.
     */
    suspend fun readChainSyncState(): ChainSyncState = withContext(queryContext) {
        val tipJson = lightClient.getTipHeader()
        val tipNumber = if (tipJson != null) {
            val tip = json.decodeFromString<JniHeaderView>(tipJson)
            tip.number.removePrefix("0x").toLongOrNull(16) ?: 0L
        } else {
            0L
        }

        val scriptsJson = lightClient.getScripts()
        val scripts = if (scriptsJson != null) {
            json.decodeFromString<List<JniScriptStatus>>(scriptsJson)
        } else {
            emptyList()
        }

        ChainSyncState(tipNumber = tipNumber, scripts = scripts)
    }

    /**
     * Derive one wallet's sync numbers from [state].
     *
     * [activeScriptArgs] is the active wallet's lock args; null (no wallet
     * loaded) falls back to the first registered script, which is what the
     * repository did before this moved.
     */
    fun computeStatus(state: ChainSyncState, activeScriptArgs: String?): SyncSnapshot {
        val tipNumber = state.tipNumber

        // Active wallet's block for the sync-progress display below.
        val scriptBlockNumber = if (activeScriptArgs != null) {
            state.scripts.find { it.script.args == activeScriptArgs }
                ?.blockNumber?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
        } else {
            state.scripts.firstOrNull()?.blockNumber?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
        }

        // Log sync progress for debugging
        logger.d(TAG, "📈 SYNC STATUS: tip=$tipNumber, scriptBlock=$scriptBlockNumber, " +
                "behind=${tipNumber - scriptBlockNumber} blocks")

        // Calculate progress relative to sync start (not absolute tip ratio).
        // This gives meaningful feedback for small block ranges (e.g. 50-100 blocks).
        val trackerInfo = calculate(tipNumber)
        val progress = if (tipNumber > 0) {
            (trackerInfo.percentage / 100.0).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        // isSynced tracks the ACTIVE WALLET'S PRIMARY script only — deliberately
        // NOT a MIN across its #382 gap-limit candidate scripts. This looks like
        // Neuron's #2992 ("light client sync miss some tx"), but that was a real
        // miss: Neuron advanced a SHARED fetch cursor past lagging scripts, so
        // their range never got refetched. Our embedded light client scans each
        // registered script INDEPENDENTLY to tip, and candidates re-register from
        // their own historical start (candidateScanStart), so a lagging candidate
        // is never abandoned — it keeps catching up and its cells get indexed.
        // Gating isSynced on candidates would instead show "syncing" for the
        // entire background discovery deep-scan while the user's own funds are
        // already synced and spendable — strictly worse UX. The candidate
        // lifecycle (FOUND/EMPTY/found-funds) is gated separately and correctly
        // by SubAccountReconciler's coverage rule. Do not "fix" this into a MIN.
        val isSynced = tipNumber > 0 &&
                scriptBlockNumber >= tipNumber - 10 &&
                scriptBlockNumber <= tipNumber + 10 // Handle slight mismatches safely

        logger.d(TAG, "📊 SYNC PROGRESS: ${(progress * 100).toInt()}% synced, isSynced=$isSynced")

        return SyncSnapshot(
            scriptBlockNumber = scriptBlockNumber,
            progress = progress,
            isSynced = isSynced,
        )
    }

    /**
     * The block a wallet should register its filter script from, for [mode] on
     * [network].
     *
     * Pure delegation to [toFromBlock]; it exists so iOS has one entry point
     * for the whole sync surface instead of reaching for a top-level extension
     * function, which Swift cannot see.
     */
    fun startBlockFor(
        mode: SyncMode,
        network: NetworkType,
        tipHeight: Long,
        customBlockHeight: Long?,
    ): String = mode.toFromBlock(customBlockHeight, tipHeight, network)

    companion object {
        private const val TAG = "SyncEngine"
    }
}
