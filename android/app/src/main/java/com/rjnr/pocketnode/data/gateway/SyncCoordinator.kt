package com.rjnr.pocketnode.data.gateway

import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.prefs.SyncStrategy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * #382: the mode-derived historical sync start for a wallet — what a fresh
 * registration would use — ignoring any saved resume progress. Mirrors the
 * guards both registration paths apply: a future block resets to the RECENT
 * window, and a zero resolution outside FULL_HISTORY falls back to the
 * network checkpoint.
 */
fun historicalStartBlock(
    syncMode: SyncMode,
    customHeight: Long?,
    tipHeight: Long,
    network: NetworkType,
): Long {
    val calculated = syncMode.toFromBlock(
        if (syncMode == SyncMode.CUSTOM) customHeight else null, tipHeight, network
    ).toLongOrNull() ?: 0L
    val checkpoint = getCheckpoint(network)
    return when {
        calculated > tipHeight && tipHeight > 0 -> (tipHeight - 200_000L).coerceAtLeast(0L)
        calculated == 0L && syncMode != SyncMode.FULL_HISTORY && checkpoint > 0 -> checkpoint
        else -> calculated
    }
}

/**
 * #382 (sub-PR 3 review P1): the block gap-limit candidates register from.
 * Candidates must scan HISTORY — inheriting the parent's resume height meant
 * a tip-synced wallet registered them at ~tip, where the Neuron change can
 * never be found. Deepest reasonable start wins:
 *  - the mode-derived historical start ([historicalStartBlock]),
 *  - the earliest cached transaction's block minus a small margin (sibling
 *    change left DURING those transactions),
 *  - and never shallower than the RECENT window when the tip is known.
 */
fun candidateScanStart(
    modeDerivedStart: Long,
    earliestKnownTxBlock: Long?,
    tipHeight: Long,
): Long {
    val recentFloor = if (tipHeight > 0) (tipHeight - 200_000L).coerceAtLeast(0L) else modeDerivedStart
    var start = minOf(modeDerivedStart, recentFloor)
    earliestKnownTxBlock?.let { start = minOf(start, (it - 1_000L).coerceAtLeast(0L)) }
    return start.coerceAtLeast(0L)
}

/**
 * #382 Tier 2 registration policy, pure for unit-test directness.
 * Account-axis candidates (restorable sub-account slots) trickle at most
 * [accountAxisCap] per cycle, lowest indices first — each batch is a fresh
 * filter rewind and they resolve one wallet at a time. Chain-axis gap-limit
 * candidates (accountIndex 0) register as ONE batch: 41 scripts through a
 * capped pipe would rewind-and-rescan the window once per batch.
 */
fun selectCandidatesForRegistration(
    candidates: List<com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity>,
    accountAxisCap: Int,
): List<com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity> {
    val pendingStates = setOf(
        com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity.STATE_PENDING,
    )
    val (chainAxisAll, accountAxis) = candidates.partition { it.accountIndex == 0 }
    // Chain-axis FOUND slots stay registered PERSISTENTLY: dropping a found
    // side script from the filter freezes its view — the sweep spending its
    // cells never indexes against it, so its spent-set never grows and the
    // found-funds amount survives the sweep forever (emulator, 2026-07-08).
    // Account-axis FOUND slots are excluded as before: restore turns them
    // into wallets that register on their own.
    val chainAxis = chainAxisAll.filter {
        it.state in pendingStates ||
            it.state == com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity.STATE_FOUND
    }
    return accountAxis
        .filter { it.state in pendingStates }
        .sortedBy { it.accountIndex }
        .take(accountAxisCap) + chainAxis
}

/**
 * A registration computed for one active wallet was aborted because the
 * user switched wallets before it reached the light client (#431). The
 * switch starts its own registration, so callers that only refresh the
 * set treat this as a quiet no-op.
 */
class ActiveWalletChangedException : Exception("Active wallet changed during registration")

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
    private val syncPreferences: SyncPreferences,
    private val keyManager: KeyManager,
    private val json: Json,
    private val lightClient: LightClientBridge,
    private val subAccountCandidateDao: com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao,
    private val transactionDao: com.rjnr.pocketnode.data.database.dao.TransactionDao,
    private val logger: Logger,
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
        /**
         * The active wallet as of NOW, read when the BALANCED filter and the
         * cap run (after the node wait). [activeWalletId] is a snapshot from
         * when the context was built; a wallet switch in between must still
         * keep the wallet the user is looking at (#431).
         */
        val liveActiveWalletId: () -> String = { activeWalletId },
        /**
         * Runs right before the CMD_SET_SCRIPTS_ALL call, after the tip
         * wait, under the registration mutex together with that call.
         * Throwing aborts the registration without touching the light
         * client, e.g. when a resync went stale (#431).
         */
        val beforeSetScripts: suspend () -> Unit = {},
        /**
         * A (syncMode, customHeight) to use for a wallet instead of its
         * stored prefs, or null for the prefs. A resync passes its new
         * choice here so it can write the prefs only once the set is
         * certain to land (#431).
         */
        val syncModeOverride: (walletId: String) -> Pair<SyncMode, Long?>? = { null },
    )

    /** Serialises every script registration; see [setScriptsAndRecord]. */
    private val registrationMutex = Mutex()

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
     *
     * Every registration in the app reaches the light client through here,
     * so [registrationMutex] serialises them: [beforeSet] (a staleness
     * check that may throw to abort) and the JNI set run as one step, and
     * no other registration can land between them (#431, #539). Only this
     * short section is locked, never the node or tip waits. [beforeSet]
     * must not call back into a registration path (the mutex is not
     * reentrant).
     */
    suspend fun setScriptsAndRecord(
        statuses: List<JniScriptStatus>,
        walletIds: List<String>,
        cmd: Int,
        network: NetworkType,
        allowRewind: Boolean = false,
        beforeSet: suspend () -> Unit = {},
    ): Boolean = registrationMutex.withLock {
        beforeSet()
        setScriptsAndRecordLocked(statuses, walletIds, cmd, network, allowRewind)
    }

    private suspend fun setScriptsAndRecordLocked(
        statuses: List<JniScriptStatus>,
        walletIds: List<String>,
        cmd: Int,
        network: NetworkType,
        allowRewind: Boolean,
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
                logger.w(TAG, "setScripts PARTIAL: clamped $clampedCount rewind(s) to current block (#332)")
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
            logger.i(
                TAG,
                "setScripts cmd=$cmd walletId=$walletId network=${network.name} " +
                    "startBlock=$startBlock (hex=${status.blockNumber})"
            )
        }
        val ok = lightClient.setScripts(jsonStr, cmd)
        if (!ok) {
            logger.w(TAG, "setScripts cmd=$cmd returned false — light client refused registration")
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
    ): List<CandidateRegistration> = runCatching {
        selectCandidatesForRegistration(
            subAccountCandidateDao.getForParent(walletId),
            accountAxisCap = MAX_CANDIDATE_SCRIPTS_PER_PARENT,
        ).map { candidate ->
            CandidateRegistration(
                candidate = candidate,
                status = JniScriptStatus(
                    script = lockScript.copy(args = candidate.scriptArgs),
                    scriptType = "lock",
                    blockNumber = blockNumberHex,
                ),
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Earliest cached transaction block for a wallet, or null when the cache
     * is empty or unreadable. Anchors [candidateScanStart].
     */
    suspend fun earliestCachedTxBlock(walletId: String, network: String): Long? =
        runCatching {
            transactionDao.getBlockNumbers(walletId, network)
                .mapNotNull { it.removePrefix("0x").toLongOrNull(16) }
                .filter { it > 0L }
                .minOrNull()
        }.getOrNull()

    /**
     * A candidate paired with the script status it registers as — identity
     * kept so a successful registration can be recorded back into
     * `registeredFromBlock` (the reconciler's EMPTY coverage gate is inert
     * while that column stays 0).
     */
    data class CandidateRegistration(
        val candidate: com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity,
        val status: JniScriptStatus,
    )

    /**
     * Persist the fromBlock each candidate was just registered to scan from
     * (keep-min semantics live in the DAO query). Call ONLY after the
     * CMD_SET_SCRIPTS_ALL that included these candidates succeeded.
     */
    suspend fun recordCandidateRegistrations(registrations: List<CandidateRegistration>) {
        registrations.forEach { reg ->
            val fromBlock = reg.status.blockNumber.removePrefix("0x").toLongOrNull(16) ?: return@forEach
            runCatching {
                subAccountCandidateDao.updateRegisteredFrom(
                    reg.candidate.parentWalletId,
                    reg.candidate.derivationPath,
                    fromBlock,
                )
            }.onFailure {
                logger.w(TAG, "recordCandidateRegistrations failed for ${reg.candidate.derivationPath}: ${it.message}")
            }
        }
    }

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
        // Per-wallet progress when the caller knows better than the stored
        // rows, e.g. a resync whose wallet restarts from 0 but whose row is
        // only zeroed once the set lands (#431).
        progressOf: (suspend (walletId: String) -> Long)? = null,
    ): List<WalletEntity> {
        if (wallets.size <= 1) return wallets

        val rows = syncProgressDao.getAllForNetwork(network.name)
            .associateBy { it.walletId }
        val progress = wallets.associate { wallet ->
            wallet.walletId to (
                progressOf?.invoke(wallet.walletId)
                    ?: rows[wallet.walletId]?.localSavedBlockNumber ?: 0L
                )
        }

        val (kept, dropped) = balancedFilterAlgorithm(
            wallets, progress, activeWalletId, BALANCED_LAG_THRESHOLD
        )

        if (dropped.isNotEmpty()) {
            val maxProgress = progress.values.maxOrNull() ?: 0L
            logger.i(
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
        val filteredFor = ctx.liveActiveWalletId()
        val filtered = applyBalancedFilter(allWallets, filteredFor, ctx.network)
        val newSet = filtered.map { it.walletId }.toSet()

        if (newSet == lastBalancedEligibleSet) return

        logger.i(
            TAG,
            "BALANCED set changed (was=$lastBalancedEligibleSet, now=$newSet): re-registering"
        )
        // Pass through the snapshot we just computed so registerAllWalletScripts
        // doesn't re-fetch + re-filter (avoids double I/O and a snapshot race
        // where wallet add/delete between calls would update the cache against
        // a different set than the comparison was made on).
        try {
            registerAllWalletScripts(
                ctx,
                preFetchedWallets = allWallets,
                preFilteredCandidates = filtered,
                preFilteredFor = filteredFor,
            )
        } catch (e: ActiveWalletChangedException) {
            // The switch's own registration supersedes this one.
            logger.d(TAG, "BALANCED re-registration skipped: ${e.message}")
        }
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
        // The active wallet [preFilteredCandidates] was computed for. The
        // under-lock check compares against it, so a switch between that
        // filter and this registration aborts the stale set (#431).
        preFilteredFor: String? = null,
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
        val strategy = syncPreferences.getSyncStrategy()
        // Read after the node wait: the wallet the user is on now is the one
        // the filter and the cap must keep. A caller-supplied filtered set
        // stays tied to the wallet it was computed for.
        val activeWalletId = if (preFilteredCandidates != null && preFilteredFor != null) {
            preFilteredFor
        } else {
            ctx.liveActiveWalletId()
        }

        // Step 1: BALANCED filter runs BEFORE the cap (Q2=A in design).
        val candidateWallets = preFilteredCandidates ?: when (strategy) {
            SyncStrategy.BALANCED -> applyBalancedFilter(
                allWallets, activeWalletId, ctx.network, progressOf = ctx.getWalletSyncBlock,
            )
            else -> allWallets
        }
        // Step 2: Cap (unchanged behavior for ALL_WALLETS). The active wallet
        // goes first so the cap can never drop it, whatever its lastActiveAt
        // (stable sort: the rest keep their recency order).
        val wallets = candidateWallets
            .sortedByDescending { it.walletId == activeWalletId }
            .take(MAX_CONCURRENT_WALLET_SCRIPTS)
        if (candidateWallets.size > wallets.size) {
            val keptIds = wallets.map { it.walletId }.toSet()
            val droppedIds = candidateWallets.map { it.walletId }.filterNot { it in keptIds }
            logger.i(
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
            logger.w(
                TAG,
                "tip header still null after ${TIP_WAIT_BUDGET_MS}ms ($tipPolls polls); " +
                    "falling back to checkpoint. fromBlock may be stale."
            )
        } else if (tipPolls > 0) {
            logger.i(TAG, "tip resolved after $tipPolls poll(s): $tipHeight")
        }

        // Per-wallet lock-script recovery. Address-only path — V2 wallets
        // boot without a BiometricPrompt (#213 sub-PR 5). The cached
        // WalletEntity already has the address; AddressUtils.decode
        // round-trips to the exact same Script.
        val perWallet = coroutineScope {
            wallets.map { wallet ->
                async(Dispatchers.IO) {
                    val lockScript = try {
                        keyManager.deriveLockScriptFromAddress(
                            wallet.testnetAddress.ifBlank { wallet.mainnetAddress }
                        )
                    } catch (e: Exception) {
                        logger.w(TAG, "Cannot decode address for wallet ${wallet.walletId}, skipping", e)
                        return@async null
                    }

                    // The wallet's sync mode + custom height: a resync passes
                    // its new choice through the context instead of writing
                    // the prefs before the set lands (#431).
                    val (syncMode, customHeight) = ctx.syncModeOverride(wallet.walletId)
                        ?: (syncPreferences.getSyncMode(walletId = wallet.walletId) to
                            syncPreferences.getCustomBlockHeight(walletId = wallet.walletId))
                    // Resume from saved per-wallet progress, or calculate from sync mode if first sync
                    val savedBlock = ctx.getWalletSyncBlock(wallet.walletId)
                    val blockNum: String
                    if (savedBlock > 0) {
                        blockNum = savedBlock.toString()
                    } else {
                        // Same guards as registerAccount: a start past the tip
                        // (e.g. a CUSTOM height above it) resets to the RECENT
                        // window, and 0 outside FULL_HISTORY uses the checkpoint.
                        blockNum = historicalStartBlock(syncMode, customHeight, tipHeight, ctx.network).toString()
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
                    // #382 P1: candidates get their own HISTORICAL start —
                    // inheriting the parent's resume height registered them
                    // at ~tip on synced wallets, where a scan finds nothing.
                    val candidateHex = "0x" + candidateScanStart(
                        historicalStartBlock(syncMode, customHeight, tipHeight, ctx.network),
                        earliestCachedTxBlock(wallet.walletId, ctx.network.name),
                        tipHeight,
                    ).toString(16)
                    val candidates =
                        pendingCandidateStatuses(wallet.walletId, lockScript, candidateHex)
                    Triple(wallet.walletId, own, candidates)
                }
            }.awaitAll().filterNotNull()
        }

        val registrations = perWallet.flatMap { it.third }
        val pairs = perWallet.flatMap { (_, own, candidates) ->
            listOf(own) + candidates.map { "" to it.status }
        }

        if (pairs.isEmpty()) {
            logger.w(TAG, "registerAllWalletScripts: no scripts to register")
            return@withContext
        }

        val scriptStatuses = pairs.map { it.second }
        val walletIds = pairs.map { it.first }
        logger.d(TAG, "Registering ${scriptStatuses.size} wallet scripts with light client")
        val result = setScriptsAndRecord(
            scriptStatuses, walletIds, LightClientNative.CMD_SET_SCRIPTS_ALL, ctx.network,
            // Under the registration mutex, atomically with the set: a set
            // computed for one active wallet must not land after a switch
            // to another (#431); the switch's own registration stands.
            beforeSet = {
                if (ctx.liveActiveWalletId() != activeWalletId) throw ActiveWalletChangedException()
                ctx.beforeSetScripts()
            },
        )
        if (!result) throw Exception("Failed to set scripts for all wallets")
        // Only a set that actually landed defines the eligible set the next
        // maybeReregisterBalanced compares against.
        if (strategy == SyncStrategy.BALANCED) {
            lastBalancedEligibleSet = candidateWallets.map { it.walletId }.toSet()
        }

        // #382: persist each candidate's scan-from block so the reconciler's
        // EMPTY coverage gate can actually pass (it is inert at 0).
        recordCandidateRegistrations(registrations)

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
