package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.getCheckpoint
import com.rjnr.pocketnode.data.gateway.models.toFromBlock
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.SyncStrategy
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure BALANCED filter algorithm — no I/O. Lives next to [SyncCoordinator]
 * so unit tests can exercise the production implementation directly without
 * constructing a full GatewayRepository instance.
 *
 * Returns `Pair(kept, dropped)`. The active wallet always lands in `kept`.
 */
internal fun balancedFilterAlgorithm(
    wallets: List<WalletEntity>,
    progressByWalletId: Map<String, Long>,
    activeId: String,
    threshold: Long,
): Pair<List<WalletEntity>, List<WalletEntity>> {
    if (wallets.size <= 1) return wallets to emptyList()
    val maxProgress = progressByWalletId.values.maxOrNull() ?: 0L
    return wallets.partition { wallet ->
        val lag = maxProgress - (progressByWalletId[wallet.walletId] ?: 0L)
        wallet.walletId == activeId || lag <= threshold
    }
}

/**
 * Sync-coordination helper extracted from [GatewayRepository] (#106 phase 1).
 *
 * Owns three pieces of script-registration state:
 *
 *  1. `scriptArgsToWalletId` — reverse map from a registered lock script's
 *     `args` to the walletId it belongs to. The sync poll uses this to fan
 *     progress updates out to every registered wallet, not just the active
 *     one.
 *  2. `lastBalancedEligibleSet` — cache of the most recent BALANCED filter
 *     decision, so a periodic re-evaluation can short-circuit when the set
 *     hasn't changed and avoid a wasteful `nativeSetScripts` round-trip.
 *  3. The three knobs ([MAX_CONCURRENT_WALLET_SCRIPTS], [BALANCED_LAG_THRESHOLD])
 *     that bound how many wallets sync simultaneously and how aggressively
 *     BALANCED drops laggards.
 *
 * ## Why a separate class
 *
 * `GatewayRepository` was ~2100 lines; the sync registration + BALANCED
 * filter block was the largest self-contained cluster (4 methods + 2
 * fields + 2 constants). Pulling it out exposes a small typed seam
 * ([SyncContext]) for the per-call state the repository still owns,
 * while letting unit tests target the registration logic directly. (#106)
 *
 * ## Threading
 *
 * Both mutable fields are `@Volatile`. Worst-case race on
 * `scriptArgsToWalletId` is one dropped progress update for a
 * newly-registered wallet (corrected next poll). Worst-case race on
 * `lastBalancedEligibleSet` is one extra `nativeSetScripts` call, which
 * is idempotent and benign.
 */
/**
 * Clamp PARTIAL setScripts rewinds (#332). For each requested script status,
 * if the same script (by lock args) is already registered at a HIGHER block,
 * replace the requested block with the current one. A rewind makes the Rust
 * light client restart its filter scan from the older block (no monotonic
 * guard in `update_filter_scripts`, and it clears the matched-block download
 * queue) — a multi-hour re-scan on long-history wallets. Returns the clamped
 * list and how many entries were clamped.
 */
internal fun clampPartialRewinds(
    requested: List<JniScriptStatus>,
    currentBlockByArgs: Map<String, Long>,
): Pair<List<JniScriptStatus>, Int> {
    var clampedCount = 0
    val out = requested.map { status ->
        val req = status.blockNumber.removePrefix("0x").toLongOrNull(16)
            ?: return@map status
        val cur = currentBlockByArgs[status.script.args] ?: return@map status
        if (req < cur) {
            clampedCount++
            status.copy(blockNumber = "0x${cur.toString(16)}")
        } else {
            status
        }
    }
    return out to clampedCount
}

/**
 * Thin indirection over the two static JNI methods [SyncCoordinator]
 * touches. Exists so unit tests can fake the JNI surface without
 * forcing `System.loadLibrary` on the JVM — `external` methods can't
 * be intercepted by mockk directly. Production: [LightClientNativeBridge].
 */
interface LightClientBridge {
    suspend fun setScripts(scriptsJson: String, command: Int): Boolean
    suspend fun getTipHeaderRaw(): String?
    suspend fun getScriptsRaw(): String?
}

/** Production bridge — delegates straight to the JNI `external fun`s. */
@Singleton
class LightClientNativeBridge @Inject constructor() : LightClientBridge {
    override suspend fun setScripts(scriptsJson: String, command: Int): Boolean =
        com.nervosnetwork.ckblightclient.LightClientNative.nativeSetScripts(scriptsJson, command)
    override suspend fun getTipHeaderRaw(): String? =
        com.nervosnetwork.ckblightclient.LightClientNative.nativeGetTipHeader()
    override suspend fun getScriptsRaw(): String? =
        com.nervosnetwork.ckblightclient.LightClientNative.nativeGetScripts()
}

@Singleton
class SyncCoordinator @Inject constructor(
    private val walletDao: WalletDao,
    private val syncProgressDao: SyncProgressDao,
    private val walletPreferences: WalletPreferences,
    private val keyManager: KeyManager,
    private val json: Json,
    private val lightClient: LightClientBridge,
    private val subAccountCandidateDao: com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao,
) {

    /**
     * Per-call state the [GatewayRepository] owns and threads through.
     * Bundles the small handful of values + callbacks the sync logic
     * needs without bringing back a circular dependency.
     */
    data class SyncContext(
        val network: NetworkType,
        val activeWalletId: String,
        val awaitNodeReady: suspend () -> Boolean,
        val getWalletSyncBlock: suspend (walletId: String) -> Long,
        val onScriptsRegistered: () -> Unit,
    )

    @Volatile
    private var scriptArgsToWalletId: Map<String, String> = emptyMap()

    @Volatile
    private var lastBalancedEligibleSet: Set<String> = emptySet()

    /**
     * Look up the walletId that registered the lock script with `args`,
     * or null if unknown. Drives the sync poll's per-wallet progress
     * fan-out.
     */
    fun getWalletIdForScript(args: String): String? = scriptArgsToWalletId[args]

    /**
     * Set lock scripts on the light client and persist the per-wallet
     * starting block into sync_progress.
     *
     * `walletIds` is parallel to `statuses` — same length, same order.
     * For single-wallet PARTIAL paths, pass `listOf(activeWalletId)`.
     */
    suspend fun setScriptsAndRecord(
        statuses: List<JniScriptStatus>,
        walletIds: List<String>,
        cmd: Int,
        network: NetworkType,
        allowRewind: Boolean = false,
    ): Boolean {
        require(statuses.size == walletIds.size) {
            "setScriptsAndRecord: statuses (${statuses.size}) and walletIds (${walletIds.size}) must be parallel"
        }
        // #332: PARTIAL with an older block rewinds the rust filter scan for
        // hours. Clamp to the currently-registered block per script unless the
        // caller is an intentional rewind (rescue rescan, find-older-deposits).
        val effectiveStatuses = if (
            cmd == LightClientNative.CMD_SET_SCRIPTS_PARTIAL && !allowRewind
        ) {
            val currentByArgs = runCatching {
                lightClient.getScriptsRaw()?.let { raw ->
                    json.decodeFromString<List<JniScriptStatus>>(raw).associate { st ->
                        st.script.args to (st.blockNumber.removePrefix("0x").toLongOrNull(16) ?: 0L)
                    }
                }
            }.getOrNull() ?: emptyMap()
            val (clamped, clampedCount) = clampPartialRewinds(statuses, currentByArgs)
            if (clampedCount > 0) {
                Log.w(TAG, "setScripts PARTIAL: clamped $clampedCount rewind(s) to current block (#332)")
            }
            clamped
        } else {
            statuses
        }
        val jsonStr = json.encodeToString(effectiveStatuses)
        // Diagnostic for the production sync-stall reports (#150). Logs every
        // (walletId, startBlock) pair just before the JNI handoff. If a user
        // reports "stayed at 0", this line tells us deterministically what
        // block they were actually scanning from. NB: release builds strip
        // ALL android.util.Log calls via proguard -assumenosideeffects, so
        // this diagnostic only exists in debug builds — use a debug APK when
        // chasing #150-class sync stalls.
        effectiveStatuses.zip(walletIds).forEach { (status, walletId) ->
            val startBlock = status.blockNumber.removePrefix("0x").toLongOrNull(16) ?: -1L
            Log.i(
                TAG,
                "setScripts cmd=$cmd walletId=$walletId network=${network.name} " +
                    "startBlock=$startBlock (hex=${status.blockNumber})"
            )
        }
        val ok = lightClient.setScripts(jsonStr, cmd)
        if (!ok) {
            Log.w(TAG, "setScripts cmd=$cmd returned false — light client refused registration")
            return false
        }

        val now = System.currentTimeMillis()
        val newMapping = mutableMapOf<String, String>()
        effectiveStatuses.zip(walletIds).forEach { (status, walletId) ->
            if (walletId.isEmpty()) return@forEach
            newMapping[status.script.args] = walletId
            // Malformed block number from the node: skip this script's row
            // rather than abort registration for every wallet (#321).
            val startBlock = status.blockNumber.removePrefix("0x").toLongOrNull(16) ?: return@forEach
            // Atomic UPDATE preserves localSavedBlockNumber under concurrent writes
            // from the sync poll's setWalletSyncBlock. Falls through to upsert only
            // when no row exists yet (no race possible — nothing to overwrite).
            val rowsUpdated = syncProgressDao.updateLightStart(
                walletId, network.name, startBlock, now
            )
            if (rowsUpdated == 0) {
                syncProgressDao.upsert(
                    SyncProgressEntity(
                        walletId = walletId,
                        network = network.name,
                        lightStartBlockNumber = startBlock,
                        localSavedBlockNumber = startBlock,
                        updatedAt = now,
                    )
                )
            }
        }
        // ALL replaces the entire registered set; PARTIAL adds to it.
        scriptArgsToWalletId = if (cmd == LightClientNative.CMD_SET_SCRIPTS_ALL) {
            newMapping
        } else {
            scriptArgsToWalletId + newMapping
        }
        return true
    }

    /**
     * PENDING sub-account discovery candidates for [walletId] as registrable
     * script statuses (#82 phase 2). Registered with walletId="" by callers —
     * [setScriptsAndRecord] skips empty ids for the args→wallet mapping and
     * sync_progress rows, so candidates never pollute per-wallet progress.
     * Same fromBlock as the parent so candidates scan the parent's window.
     * Capped to bound per-script filter cost; remaining PENDING slots
     * register on later cycles as earlier ones resolve FOUND/EMPTY.
     *
     * MUST be included by EVERY path that issues CMD_SET_SCRIPTS_ALL for a
     * wallet: ALL replaces the whole registered set, so a single-wallet
     * re-registration that omits candidates silently unregisters them —
     * the import flow's immediate resync did exactly that and killed
     * discovery before the filter ever scanned (device-test, 2026-07).
     * Non-parents have no candidate rows, so this is a cheap no-op for them.
     */
    suspend fun pendingCandidateStatuses(
        walletId: String,
        lockScript: com.rjnr.pocketnode.data.gateway.models.Script,
        blockNumberHex: String,
    ): List<JniScriptStatus> = runCatching {
        subAccountCandidateDao.getForParent(walletId)
            .filter { it.state == com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity.STATE_PENDING }
            .take(MAX_CANDIDATE_SCRIPTS_PER_PARENT)
            .map { candidate ->
                JniScriptStatus(
                    script = lockScript.copy(args = candidate.scriptArgs),
                    scriptType = "lock",
                    blockNumber = blockNumberHex,
                )
            }
    }.getOrDefault(emptyList())

    /**
     * BALANCED strategy filter: drop wallets whose `localSavedBlockNumber`
     * lags the max-progress wallet by more than [BALANCED_LAG_THRESHOLD]
     * blocks. Active wallet always passes regardless of its own lag.
     *
     * Reference = max localSavedBlockNumber across the candidate set, NOT
     * the active wallet's progress — survives wallet-switch correctly.
     *
     * Pure-ish: I/O is only the bulk read from sync_progress. Decision
     * logic lives in [balancedFilterAlgorithm] for unit-test directness.
     */
    suspend fun applyBalancedFilter(
        wallets: List<WalletEntity>,
        activeWalletId: String,
        network: NetworkType,
    ): List<WalletEntity> {
        if (wallets.size <= 1) return wallets

        val rows = syncProgressDao.getAllForNetwork(network.name)
            .associateBy { it.walletId }
        val progress = wallets.associate { wallet ->
            wallet.walletId to (rows[wallet.walletId]?.localSavedBlockNumber ?: 0L)
        }

        val (kept, dropped) = balancedFilterAlgorithm(
            wallets, progress, activeWalletId, BALANCED_LAG_THRESHOLD
        )

        if (dropped.isNotEmpty()) {
            val maxProgress = progress.values.maxOrNull() ?: 0L
            Log.i(
                TAG,
                "BALANCED: dropped ${dropped.size} laggards: " +
                    dropped.map { "${it.walletId}(lag=${maxProgress - (progress[it.walletId] ?: 0L)})" }
            )
        }
        return kept
    }

    /**
     * Cheap BALANCED re-evaluation: compute the eligible set, compare to
     * [lastBalancedEligibleSet]; only re-issue setScripts when it changed.
     * Caller must already be on a coroutine context.
     */
    suspend fun maybeReregisterBalanced(ctx: SyncContext) {
        val allWallets = walletDao.getAll().sortedByDescending { it.lastActiveAt }
        val filtered = applyBalancedFilter(allWallets, ctx.activeWalletId, ctx.network)
        val newSet = filtered.map { it.walletId }.toSet()

        if (newSet == lastBalancedEligibleSet) return

        Log.i(
            TAG,
            "BALANCED set changed (was=$lastBalancedEligibleSet, now=$newSet): re-registering"
        )
        // Pass through the snapshot we just computed so registerAllWalletScripts
        // doesn't re-fetch + re-filter (avoids double I/O and a snapshot race
        // where wallet add/delete between calls would update the cache against
        // a different set than the comparison was made on).
        registerAllWalletScripts(ctx, preFetchedWallets = allWallets, preFilteredCandidates = filtered)
    }

    /**
     * Register lock scripts for ALL wallets with the light client
     * simultaneously. Used when SyncStrategy is ALL_WALLETS or BALANCED.
     *
     * Capped at the [MAX_CONCURRENT_WALLET_SCRIPTS] most-recently-active
     * wallets to bound resource usage.
     */
    suspend fun registerAllWalletScripts(
        ctx: SyncContext,
        preFetchedWallets: List<WalletEntity>? = null,
        preFilteredCandidates: List<WalletEntity>? = null,
    ) = withContext(Dispatchers.IO) {
        // Force IO dispatcher for the whole body — JNI calls (nativeGetTipHeader,
        // nativeSetScripts via setScriptsAndRecord) block the UI thread otherwise.
        // Symptom #109: adding the 3rd wallet (which triggers a re-registration
        // of all scripts) flashed the screen white because the caller chain ran
        // on viewModelScope.launch (Main) and the JNI round-trip blocked Main
        // long enough for Android to render a blank surface.
        if (!ctx.awaitNodeReady()) {
            throw Exception("Node initialization failed")
        }

        val allWallets = preFetchedWallets
            ?: walletDao.getAll().sortedByDescending { it.lastActiveAt }
        val strategy = walletPreferences.getSyncStrategy()

        // Step 1: BALANCED filter runs BEFORE the cap (Q2=A in design).
        val candidateWallets = preFilteredCandidates ?: when (strategy) {
            SyncStrategy.BALANCED -> applyBalancedFilter(allWallets, ctx.activeWalletId, ctx.network)
            else -> allWallets
        }
        if (strategy == SyncStrategy.BALANCED) {
            lastBalancedEligibleSet = candidateWallets.map { it.walletId }.toSet()
        }

        // Step 2: Cap (unchanged behavior for ALL_WALLETS).
        val wallets = candidateWallets.take(MAX_CONCURRENT_WALLET_SCRIPTS)
        if (candidateWallets.size > wallets.size) {
            val droppedIds = candidateWallets.drop(wallets.size).map { it.walletId }
            Log.i(
                TAG,
                "${strategy.name}: syncing top-${wallets.size} of ${candidateWallets.size} wallets " +
                    "(dropped: $droppedIds)"
            )
        }

        // Bounded tip-wait: awaitNodeReady() only guarantees init success, not
        // that a tip header has arrived from peers. On a fresh wallet boot the
        // light client can be up but tip is still null for several seconds
        // while it handshakes with peers. If we read tipHeight = 0 in that
        // window, toFromBlock(NEW_WALLET, ...) falls back to the hardcoded
        // mainnet checkpoint (~18.3M from the v1.6.0 cut), which by 2026-05
        // is hundreds of thousands of blocks stale — the user perceives a
        // "syncing from a million blocks ago" experience instead of the
        // instant sync NEW_WALLET should deliver.
        //
        // Poll the tip header for up to TIP_WAIT_BUDGET_MS before computing
        // fromBlock; if the budget expires we still fall through to the
        // checkpoint path so the wallet doesn't hang waiting for peers.
        // matt (Telegram, 2026-05-28) reported the symptom.
        val tipDeadline = System.currentTimeMillis() + TIP_WAIT_BUDGET_MS
        var tipHeight = 0L
        var tipPolls = 0
        while (System.currentTimeMillis() < tipDeadline) {
            val tipStr = lightClient.getTipHeaderRaw()
            if (tipStr != null) {
                val parsed = runCatching {
                    json.decodeFromString<JniHeaderView>(tipStr)
                        .number.removePrefix("0x").toLong(16)
                }.getOrNull() ?: 0L
                if (parsed > 0L) {
                    tipHeight = parsed
                    break
                }
            }
            tipPolls++
            delay(TIP_WAIT_POLL_MS)
        }
        if (tipHeight == 0L) {
            Log.w(
                TAG,
                "tip header still null after ${TIP_WAIT_BUDGET_MS}ms ($tipPolls polls); " +
                    "falling back to checkpoint. fromBlock may be stale."
            )
        } else if (tipPolls > 0) {
            Log.i(TAG, "tip resolved after $tipPolls poll(s): $tipHeight")
        }

        // Per-wallet lock-script recovery. Address-only path — V2 wallets
        // boot without a BiometricPrompt (#213 sub-PR 5). The cached
        // WalletEntity already has the address; AddressUtils.decode
        // round-trips to the exact same Script.
        val pairs = coroutineScope {
            wallets.map { wallet ->
                async(Dispatchers.IO) {
                    val lockScript = try {
                        keyManager.deriveLockScriptFromAddress(
                            wallet.testnetAddress.ifBlank { wallet.mainnetAddress }
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot decode address for wallet ${wallet.walletId}, skipping", e)
                        return@async null
                    }

                    // Resume from saved per-wallet progress, or calculate from sync mode if first sync
                    val savedBlock = ctx.getWalletSyncBlock(wallet.walletId)
                    val blockNum: String
                    if (savedBlock > 0) {
                        blockNum = savedBlock.toString()
                    } else {
                        val syncMode = walletPreferences.getSyncMode(walletId = wallet.walletId)
                        val customHeight = walletPreferences.getCustomBlockHeight(walletId = wallet.walletId)
                        val calculated = syncMode.toFromBlock(
                            if (syncMode == SyncMode.CUSTOM) customHeight else null,
                            tipHeight,
                            ctx.network,
                        )
                        val calculatedLong = calculated.toLongOrNull() ?: 0L
                        // Safety: don't start from block 0 — use checkpoint if available
                        val checkpoint = getCheckpoint(ctx.network)
                        blockNum = if (calculatedLong == 0L && syncMode != SyncMode.FULL_HISTORY && checkpoint > 0) {
                            checkpoint.toString()
                        } else {
                            calculated
                        }
                    }
                    val blockNumberHex = "0x${blockNum.toLongOrNull()?.toString(16) ?: "0"}"

                    val own = wallet.walletId to JniScriptStatus(
                        script = lockScript,
                        scriptType = "lock",
                        blockNumber = blockNumberHex,
                    )

                    // #82 phase 2: register PENDING sub-account discovery
                    // candidates alongside their parent so the filter sync
                    // covers them. See [pendingCandidateStatuses].
                    val candidates =
                        pendingCandidateStatuses(wallet.walletId, lockScript, blockNumberHex)
                            .map { "" to it }
                    listOf(own) + candidates
                }
            }.awaitAll().filterNotNull().flatten()
        }

        if (pairs.isEmpty()) {
            Log.w(TAG, "registerAllWalletScripts: no scripts to register")
            return@withContext
        }

        val scriptStatuses = pairs.map { it.second }
        val walletIds = pairs.map { it.first }
        Log.d(TAG, "Registering ${scriptStatuses.size} wallet scripts with light client")
        val result = setScriptsAndRecord(scriptStatuses, walletIds, LightClientNative.CMD_SET_SCRIPTS_ALL, ctx.network)
        if (!result) throw Exception("Failed to set scripts for all wallets")

        ctx.onScriptsRegistered()
    }

    companion object {
        private const val TAG = "SyncCoordinator"

        /**
         * Upper bound for wallets synced simultaneously under ALL_WALLETS.
         * Wallets beyond this are dropped by `lastActiveAt` descending; the
         * dropped ids are logged so support can diagnose "why isn't wallet X
         * syncing".
         */
        private const val MAX_CONCURRENT_WALLET_SCRIPTS = 3

        /**
         * Source: Neuron's THRESHOLD_BLOCK_NUMBER_IN_DIFF_WALLET, validated in
         * production for years. Wallets lagging the max-progress wallet by
         * more than this are dropped from the registered script set (BALANCED
         * strategy) until the leader's tail catches up.
         * https://github.com/nervosnetwork/neuron/blob/develop/packages/neuron-wallet/src/block-sync-renderer/sync/light-synchronizer.ts#L22
         */
        const val BALANCED_LAG_THRESHOLD = 100_000L

        /**
         * PENDING discovery candidates registered per parent per cycle (#82).
         * Each registered script adds filter-sync work in the light client,
         * so scan the low indices first — sub-accounts are created
         * contiguously from 1, so the first few cover real usage.
         */
        private const val MAX_CANDIDATE_SCRIPTS_PER_PARENT = 5

        /**
         * How long to wait for a non-null tip header before falling back to
         * the hardcoded checkpoint when computing NEW_WALLET fromBlock.
         * 5s covers the typical peer-handshake window on a fresh wallet
         * boot without blocking the user noticeably if peers are slow.
         */
        private const val TIP_WAIT_BUDGET_MS = 5_000L
        private const val TIP_WAIT_POLL_MS = 200L
    }
}
