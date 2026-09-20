package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.send.SendContext
import com.rjnr.pocketnode.data.send.SendPipeline
import com.rjnr.pocketnode.data.transaction.SweepInput
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.wallet.GapLimitResolution
import com.rjnr.pocketnode.data.wallet.GapLimitStatus
import com.rjnr.pocketnode.data.wallet.GapLimitSweepPreview
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.gapLimitResolution
import com.rjnr.pocketnode.data.wallet.nextScanWindow
import com.nervosnetwork.ckblightclient.LightClientNative
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The gap-limit recovery surface (#382), extracted from [GatewayRepository]
 * (#460).
 *
 * Wallets imported into a build that derived only m/44'/309'/0'/0/0 can have
 * funds sitting on chain-axis addresses the app never registered. This class
 * owns all three tiers of the recovery: the banner's visibility and dismissal
 * (Tier 1 signal), the explicit deep scan and the capacity it finds
 * (Tier 2), and the sweep that moves those funds back to the wallet's own
 * address (Tier 3).
 *
 * These are the repository's bodies MOVED, not rewritten. The single-flight
 * mutex that covers both the scan and the sweep, the wallet-switch aborts,
 * the derived-args verification before signing, and the key/seed zeroing are
 * all load-bearing and all unchanged. [GatewayRepository] keeps a one-line
 * forward per public member.
 *
 * Repository state arrives as [GapLimitContext]. Its members are suppliers
 * rather than snapshots on purpose: the scan and sweep deliberately re-read
 * the active wallet mid-flight to detect a switch, so each read has to happen
 * where the original code put it.
 */
class GapLimitGateway(
    private val appDatabase: AppDatabase,
    private val walletPreferences: WalletPreferences,
    private val keyManager: KeyManager,
    private val subAccountDiscovery: SubAccountDiscovery,
    private val transactionBuilder: TransactionBuilder,
    private val ledgerReader: LedgerReader,
    private val pendingBroadcastDao: PendingBroadcastDao,
    private val sendPipeline: SendPipeline,
    private val json: Json,
    private val logger: Logger,
) {

    /**
     * The repository-owned state and seams a gap-limit call reads. Supplied
     * per call by `GatewayRepository.gapLimitContext()`.
     */
    class GapLimitContext(
        val network: () -> NetworkType,
        val walletId: () -> String,
        val activeScript: () -> Script?,
        val savedSyncMode: () -> SyncMode,
        val savedCustomBlockHeight: () -> Long?,
        val getMnemonic: suspend () -> List<String>?,
        /** Sender snapshot for one [SendPipeline] call, same as the transfer path. */
        val sendContext: () -> SendContext,
        /** syncMode, customBlockHeight, savePreference -> result. */
        val registerAccountWithStrategy: suspend (SyncMode, Long?, Boolean) -> Result<Unit>,
    )

    // #382: single-flight for the explicit gap-limit scan (Home banner and
    // Settings both trigger it) and for the sweep.
    private val gapLimitScanMutex = Mutex()

    /** #382: gap-limit banner is visible when the signature was detected for the active wallet and not dismissed. */
    fun isGapLimitBannerVisible(ctx: GapLimitContext): Boolean {
        if (ctx.walletId().isEmpty()) return false
        return walletPreferences.isGapLimitSignalDetected(ctx.network(), ctx.walletId()) &&
            !walletPreferences.isGapLimitBannerDismissed(ctx.network(), ctx.walletId())
    }

    fun dismissGapLimitBanner(ctx: GapLimitContext) {
        if (ctx.walletId().isEmpty()) return
        walletPreferences.setGapLimitBannerDismissed(ctx.network(), ctx.walletId())
    }

    /**
     * #382 Tier 2: what the chain-axis candidate set means for the active
     * wallet, plus the live capacity sitting on FOUND slots. Side effect:
     * a CLEAR resolution (scan finished, nothing anywhere) retires the
     * Tier 1 signal so the banner stops firing on stale evidence.
     */
    suspend fun getGapLimitStatus(ctx: GapLimitContext): GapLimitStatus {
        val wId = ctx.walletId()
        if (wId.isEmpty()) return GapLimitStatus(GapLimitResolution.NOT_SCANNED, 0, 0L)
        val chain = runCatching {
            appDatabase.subAccountCandidateDao().getForParent(wId).filter { it.accountIndex == 0 }
        }.onFailure {
            logger.w(TAG, "getGapLimitStatus: candidate read failed, treating as not scanned: ${it.message}")
        }.getOrDefault(emptyList())
        val resolution = gapLimitResolution(chain)
        if (resolution == GapLimitResolution.CLEAR &&
            walletPreferences.isGapLimitSignalDetected(ctx.network(), wId)
        ) {
            logger.i(TAG, "gap-limit scan completed clean — retiring Tier 1 signal for $wId")
            walletPreferences.setGapLimitSignalDetected(false, ctx.network(), wId)
        }
        if (resolution != GapLimitResolution.FOUND) return GapLimitStatus(resolution, 0, 0L)

        val myScript = ctx.activeScript()
            ?: return GapLimitStatus(resolution, chain.count { it.state == SubAccountCandidateEntity.STATE_FOUND }, 0L)
        var total = 0L
        var count = 0
        chain.filter { it.state == SubAccountCandidateEntity.STATE_FOUND }.forEach { cand ->
            runCatching {
                val cap = liveUntypedCapacityFor(ctx, JniSearchKey(script = myScript.copy(args = cand.scriptArgs)))
                if (cap > 0L) {
                    total += cap
                    count++
                }
            }.onFailure { logger.w(TAG, "gap-limit capacity read failed for ${cand.derivationPath}: ${it.message}") }
        }
        return GapLimitStatus(resolution, count, total)
    }

    /**
     * #382 Tier 2: explicit deep scan. Covers wallets imported before the
     * auto-scan shipped, and extends the window (20 -> 40 -> 60) when the
     * current boundary slot shows activity. Needs the mnemonic (session-auth
     * gated by [getMnemonic]); inserts are IGNORE so already-resolved slots
     * keep their state, then scripts re-register so the new ones enter the
     * light-client filter. Returns the window that is now covered.
     */
    /**
     * V1 / session-auth entry: reads the seed via [getMnemonic] (which has an
     * EncryptedSharedPreferences fallback). V2 (kdfVersion=2) wallets cannot
     * decrypt without an authenticated Cipher, so their ViewModels unlock the
     * seed via a BiometricPrompt and call [runGapLimitScan] with words (#408).
     */
    suspend fun runGapLimitScan(ctx: GapLimitContext): Result<Int> = runCatching {
        val words = ctx.getMnemonic() ?: throw Exception("Recovery phrase unavailable for this wallet")
        runGapLimitScanInner(ctx, words)
    }

    /** V2 entry: [words] were already unlocked by the caller's BiometricPrompt (#408). */
    suspend fun runGapLimitScan(ctx: GapLimitContext, words: List<String>): Result<Int> = runCatching {
        runGapLimitScanInner(ctx, words)
    }

    private suspend fun runGapLimitScanInner(ctx: GapLimitContext, words: List<String>): Int {
        // Single-flight: the Home banner and Settings both trigger this, and
        // a second concurrent pass would double the derivation work and
        // interleave two CMD_SET_SCRIPTS_ALL registrations.
        if (!gapLimitScanMutex.tryLock()) throw Exception("A scan is already running")
        return try {
            val wId = ctx.walletId()
            if (wId.isEmpty()) throw Exception("No active wallet")
            val dao = appDatabase.subAccountCandidateDao()
            val existing = dao.getForParent(wId).filter { it.accountIndex == 0 }
            val window = nextScanWindow(existing)
            val now = System.currentTimeMillis()
            val candidates = subAccountDiscovery.deriveChainCandidates(words, window = window)
            // The mnemonic read and derivation are slow; if the user switched
            // wallets meanwhile, inserting rows for the OLD wallet and then
            // registering the NEW one would corrupt the scan. Abort instead.
            if (ctx.walletId() != wId) throw Exception("Wallet changed during the scan; try again")
            dao.insertAll(
                candidates.map {
                    SubAccountCandidateEntity(
                        parentWalletId = wId,
                        derivationPath = it.derivationPath,
                        accountIndex = it.accountIndex,
                        scriptArgs = it.scriptArgs,
                        createdAt = now,
                    )
                }
            )
            // An explicit scan means "look AGAIN": re-arm chain slots a past
            // pass retired as EMPTY. Without this the action was a silent
            // no-op after any completed scan — funds arriving later (or on a
            // different network than the pass that retired them) were
            // undiscoverable forever (device-verification, 2026-07). The
            // reconciler's probe re-judges them: activity -> FOUND, still
            // nothing -> EMPTY again shortly.
            val reArmed = dao.reArmEmptyChainSlots(wId)
            if (reArmed > 0) logger.i(TAG, "gap-limit scan: re-armed $reArmed retired slot(s)")
            // Fresh registration pass so new candidate scripts join the filter.
            ctx.registerAccountWithStrategy(
                ctx.savedSyncMode(), ctx.savedCustomBlockHeight(), false
            ).getOrThrow()
            window
        } finally {
            gapLimitScanMutex.unlock()
        }
    }

    /**
     * Live spendable capacity for one script: cells walked to the end, spent
     * outpoints subtracted, typed cells excluded — the same read-path rules
     * as refreshBalance (nativeGetCellsCapacity alone can overstate; an
     * inflated "found funds" number would re-create the exact panic #382 is
     * meant to end).
     */
    private suspend fun liveUntypedCapacityFor(ctx: GapLimitContext, searchKey: JniSearchKey): Long =
        liveUntypedCellsFor(ctx, searchKey).sumOf {
            it.output.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L
        }

    /**
     * The live untyped cells behind [liveUntypedCapacityFor] — the Tier 3
     * sweep spends them. Outpoints reserved by ACTIVE pending broadcasts are
     * excluded: a just-broadcast sweep's inputs are spent-in-flight, and
     * counting them keeps the found-funds card at the old amount until the
     * chain index catches up (and would let a double-tapped sweep try to
     * respend them).
     */
    private suspend fun liveUntypedCellsFor(ctx: GapLimitContext, searchKey: JniSearchKey): List<JniCell> {
        val searchKeyJson = json.encodeToString(searchKey)
        val spent = ledgerReader.fetchAllSpentOutpoints(searchKeyJson).toMutableSet()
        runCatching {
            pendingBroadcastDao.getActive(ctx.walletId(), ctx.network().name)
                .flatMap { json.decodeFromString<List<OutPoint>>(it.reservedInputs) }
                .forEach { spent += "${it.txHash}:${it.index}" }
        }.onFailure { logger.w(TAG, "liveUntypedCellsFor: reservation read failed: ${it.message}") }
        val live = mutableListOf<JniCell>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_CELL_PAGES) {
            val pageJson = LightClientNative.nativeGetCells(searchKeyJson, "desc", 100, cursor) ?: break
            val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
            page.objects.forEach { cell ->
                val key = "${cell.outPoint.txHash}:${cell.outPoint.index}"
                if (key !in spent && cell.output.type == null &&
                    cell.output.capacity.removePrefix("0x").toLongOrNull(16) != null
                ) {
                    live += cell
                }
            }
            pages++
            if (page.objects.isEmpty() || page.objects.size < 100 || page.lastCursor.isNullOrEmpty()) break
            cursor = page.lastCursor
        }
        return live
    }

    /**
     * #382 Tier 3: gather every live untyped cell sitting on FOUND chain-axis
     * slots as sweep inputs, tagged with the lock args that identify their
     * signing group. Second value = how many distinct addresses hold funds.
     */
    private suspend fun gatherSweepInputs(ctx: GapLimitContext, walletId: String): Pair<List<SweepInput>, Int> {
        val myScript = ctx.activeScript() ?: throw Exception("Wallet not initialized")
        val found = appDatabase.subAccountCandidateDao().getForParent(walletId)
            .filter { it.accountIndex == 0 && it.state == SubAccountCandidateEntity.STATE_FOUND }
        val inputs = mutableListOf<SweepInput>()
        var addresses = 0
        found.forEach { cand ->
            val cells = liveUntypedCellsFor(ctx, JniSearchKey(script = myScript.copy(args = cand.scriptArgs)))
            if (cells.isNotEmpty()) addresses++
            cells.forEach { cell ->
                inputs += SweepInput(
                    outPoint = OutPoint(cell.outPoint.txHash, cell.outPoint.index),
                    capacityShannons = cell.output.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L,
                    lockArgs = cand.scriptArgs,
                )
            }
        }
        return inputs to addresses
    }

    /**
     * #382 Tier 3: the numbers for the sweep confirm dialog — total found,
     * exact fee, distinct addresses. Key-free: gathering and planning need
     * no mnemonic, only the confirm step does.
     */
    suspend fun prepareGapLimitSweep(ctx: GapLimitContext): Result<GapLimitSweepPreview> = runCatching {
        val wId = ctx.walletId()
        if (wId.isEmpty()) throw Exception("No active wallet")
        val myScript = ctx.activeScript() ?: throw Exception("Wallet not initialized")
        val (inputs, addresses) = gatherSweepInputs(ctx, wId)
        val plan = transactionBuilder.buildSweep(inputs, myScript, ctx.network()).getOrThrow()
        GapLimitSweepPreview(
            totalShannons = plan.totalShannons,
            feeShannons = plan.feeShannons,
            addressCount = addresses,
        )
    }

    /**
     * #382 Tier 3: the sweep itself. Re-gathers cells (a preview can go
     * stale), derives each lock group's key from its candidate's derivation
     * path, VERIFIES each derived key reproduces the candidate's lock args
     * (a derivation mismatch must abort, never sign), signs one multi-group
     * transaction and hands it to the idempotent sendTransaction path
     * (pending row + watchdog). Keys and seed are zeroed after signing.
     */
    /**
     * V1 / session-auth entry: reads the seed via [getMnemonic]. V2 wallets
     * unlock the seed via a BiometricPrompt and call the words overload (#408).
     */
    suspend fun sweepGapLimitFunds(ctx: GapLimitContext): Result<String> = runCatching {
        val words = ctx.getMnemonic() ?: throw Exception("Recovery phrase unavailable for this wallet")
        sweepGapLimitFundsInner(ctx, words)
    }

    /** V2 entry: [words] were already unlocked by the caller's BiometricPrompt (#408). */
    suspend fun sweepGapLimitFunds(ctx: GapLimitContext, words: List<String>): Result<String> = runCatching {
        sweepGapLimitFundsInner(ctx, words)
    }

    private suspend fun sweepGapLimitFundsInner(ctx: GapLimitContext, words: List<String>): String {
        if (!gapLimitScanMutex.tryLock()) throw Exception("A scan or sweep is already running")
        return try {
            val wId = ctx.walletId()
            if (wId.isEmpty()) throw Exception("No active wallet")
            val myScript = ctx.activeScript() ?: throw Exception("Wallet not initialized")

            val (inputs, _) = gatherSweepInputs(ctx, wId)
            val plan = transactionBuilder.buildSweep(inputs, myScript, ctx.network()).getOrThrow()

            val pathByArgs = appDatabase.subAccountCandidateDao().getForParent(wId)
                .filter { it.accountIndex == 0 && it.state == SubAccountCandidateEntity.STATE_FOUND }
                .associate { it.scriptArgs to it.derivationPath }

            // BIP39 passphrase: the import UI has no passphrase field, so
            // every wallet's candidates were derived with "". If that ever
            // changes, the derived-args verification below aborts the sweep
            // rather than signing with a mismatched key.
            val seed = keyManager.mnemonicToSeed(words)
            val keys = mutableMapOf<String, ByteArray>()
            try {
                plan.inputLockArgs.distinct().forEach { args ->
                    val path = pathByArgs[args]
                        ?: throw Exception("No derivation path recorded for a sweep input")
                    val (chain, index) = com.rjnr.pocketnode.data.wallet.chainAndIndexFromPath(path)
                        ?: throw Exception("Unparseable derivation path for a sweep input")
                    val key = keyManager.deriveChainKey(seed, chainIndex = chain, addressIndex = index)
                    val derivedArgs = keyManager.deriveLockScript(keyManager.derivePublicKey(key)).args
                    if (!derivedArgs.equals(args, ignoreCase = true)) {
                        key.fill(0)
                        throw Exception("Derived key does not match the recorded address; sweep aborted")
                    }
                    keys[args] = key
                }
                if (ctx.walletId() != wId) throw Exception("Wallet changed during the sweep; try again")
                val signed = transactionBuilder.signSweep(plan.transaction, plan.inputLockArgs, keys).getOrThrow()
                val txHash = sendPipeline.sendTransaction(
                    ctx = ctx.sendContext(),
                    transaction = signed,
                    expectedWalletId = wId,
                ).getOrThrow()
                logger.i(TAG, "gap-limit sweep broadcast: ${plan.inputLockArgs.size} inputs, ${keys.size} groups")
                txHash
            } finally {
                keys.values.forEach { it.fill(0) }
                seed.fill(0)
            }
        } finally {
            gapLimitScanMutex.unlock()
        }
    }


    companion object {
        private const val TAG = "GapLimitGateway"

        /**
         * Cell cursor-walk cap - a runaway guard far above real usage
         * (100 cells/page, so 5k cells). Hitting it is logged.
         */
        private const val MAX_CELL_PAGES = 50
    }
}
