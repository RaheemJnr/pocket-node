package com.rjnr.pocketnode.data.gateway

import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.gateway.models.Cell
import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoConstants
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.EpochInfo
import com.rjnr.pocketnode.data.gateway.models.JniCell
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.JniSearchKey
import com.rjnr.pocketnode.data.gateway.models.JniTransactionWithStatus
import com.rjnr.pocketnode.data.gateway.models.JniTxWithCell
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.cyclePhaseFromProgress
import com.rjnr.pocketnode.data.gateway.models.determineDaoStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lists Nervos DAO deposits for a given wallet script (#106 phase 3).
 *
 * The largest single block inside `GatewayRepository.getDaoDeposits`
 * was ~210 lines of cell scanning + per-cell header lookup + APC
 * computation. Pulling it out: GatewayRepository keeps a one-line
 * delegation; the cell-walking and compensation math live here.
 *
 * ## What this does
 *
 * 1. Page through every cell registered under the wallet's lock script
 *    via `nativeGetCells` (cursor pagination, 100 per page).
 * 2. Filter locally for the DAO type-script code hash.
 * 3. Page through the wallet's transactions to discover which DAO
 *    cells have been spent (`previous_output` references), then drop
 *    those from the live set.
 * 4. For each live DAO cell, resolve its block header (via
 *    [DaoHeaderResolver]), compute compensation against either the
 *    deposit header (withdrawing cells) or the tip (deposited cells),
 *    and derive epoch + unlock-time metadata.
 * 5. Return a list of [DaoDeposit] enriched with APC, cycle progress,
 *    and a status from [determineDaoStatus].
 *
 * ## What this does not do
 *
 * - It does not own the DAO transaction-building flow
 *   (`depositToDao`/`withdrawFromDao`/`unlockDao`) — those stay on
 *   GatewayRepository because they orchestrate the send pipeline.
 * - It does not own the header lookup; that lives in
 *   [DaoHeaderResolver]. This class composes header lookups with
 *   the DAO-specific math.
 */
@Singleton
class DaoDepositReader @Inject constructor(
    private val json: Json,
    private val daoHeaderResolver: DaoHeaderResolver,
    private val logger: Logger,
) {

    /**
     * Enumerate every live DAO cell under [walletScript] and resolve
     * its compensation, epoch, and status fields.
     *
     * @param currentEpoch tip epoch, used to decide status (whether a
     *   withdrawing cell is yet unlockable). Caller passes null when
     *   the tip header was unreadable; this only degrades the status
     *   for withdrawing cells, deposit and APC math are unaffected.
     */
    suspend fun list(
        walletScript: Script,
        currentEpoch: EpochInfo?,
        network: NetworkType,
        // Reconciliation stages these headers with its other revision-bound writes.
        headersToCache: MutableList<JniHeaderView>? = null,
    ): List<DaoDeposit> {
        // Query ALL cells by lock script (like Neuron), then filter locally for DAO type
        val searchKey = JniSearchKey(
            script = walletScript,
            scriptType = "lock",
            withData = true,
        )
        val searchKeyJson = json.encodeToString(searchKey)

        // Paginate all cells by lock script. Cap is a runaway guard only
        // (logged when hit), matching the read-path convention in
        // GatewayRepository — never a functional limit that could truncate
        // real history and undercount deposits.
        val allCellObjects = mutableListOf<JniCell>()
        var cellsCursor: String? = null
        var cellPages = 0
        do {
            val pageJson = LightClientNative.nativeGetCells(searchKeyJson, "desc", 100, cellsCursor)
                ?: break
            val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
            allCellObjects += page.objects
            cellsCursor = page.lastCursor?.takeUnless { it == cellsCursor }
            cellPages++
        } while (cellsCursor != null && cellPages < MAX_PAGES)
        if (cellPages >= MAX_PAGES) {
            logger.w(TAG, "list: hit $MAX_PAGES-page cap walking DAO cells — list may be incomplete")
        }

        // Filter locally: only cells whose type script matches DAO code hash
        val daoCells = allCellObjects.filter { cell ->
            cell.output.type?.codeHash == DaoConstants.DAO_CODE_HASH
        }
        logger.d(TAG, "📋 getDaoDeposits: ${daoCells.size} DAO cells out of ${allCellObjects.size} total")

        // Paginate transactions to find spent outpoints (same runaway guard).
        val spentOutpoints = mutableSetOf<String>()
        var txCursor: String? = null
        var txPages = 0
        do {
            val txJson = LightClientNative.nativeGetTransactions(searchKeyJson, "desc", 100, txCursor)
                ?: break
            val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
            txPag.objects.forEach { txWithCell ->
                txWithCell.transaction.inputs.forEach { input ->
                    spentOutpoints.add("${input.previousOutput.txHash}:${input.previousOutput.index}")
                }
            }
            txCursor = txPag.lastCursor?.takeUnless { it == txCursor }
            txPages++
        } while (txCursor != null && txPages < MAX_PAGES)
        if (txPages >= MAX_PAGES) {
            logger.w(TAG, "list: hit $MAX_PAGES-page cap walking DAO spent-set — result may be incomplete")
        }

        val liveDaoCells = daoCells.filter { cell ->
            val key = "${cell.outPoint.txHash}:${cell.outPoint.index}"
            key !in spentOutpoints
        }
        logger.d(TAG, "📋 getDaoDeposits: ${liveDaoCells.size} live DAO cells")

        val deposits = mutableListOf<DaoDeposit>()
        for (jniCell in liveDaoCells) {
            // One malformed cell (bad capacity/epoch/timestamp hex from the
            // node) drops that row instead of failing the whole DAO list (#321).
            runCatching { resolveOneDeposit(jniCell, currentEpoch, network, headersToCache) }
                .onSuccess { deposits += it }
                .onFailure {
                    logger.w(
                        TAG,
                        "Skipping unparseable DAO cell " +
                            "${jniCell.outPoint.txHash.take(20)}...:${jniCell.outPoint.index} — ${it.message}"
                    )
                }
        }
        return deposits
    }

    /**
     * Resolve compensation + epoch metadata for one DAO cell.
     * Withdrawing metadata remains unresolved until its per-cell header
     * identity is established, independent of transaction dependency order.
     */
    private suspend fun resolveOneDeposit(
        jniCell: JniCell,
        currentEpoch: EpochInfo?,
        network: NetworkType,
        headersToCache: MutableList<JniHeaderView>?,
    ): DaoDeposit {
        suspend fun header(hash: String): JniHeaderView? =
            daoHeaderResolver.getOrFetchHeader(hash, network, cacheResult = headersToCache == null)
                ?.also { headersToCache?.add(it) }
        val cell = jniCell.toCell()
        val data = cell.data.removePrefix("0x")
        val cellId = "${cell.outPoint.txHash.take(20)}...:${cell.outPoint.index}"
        val capacityShannons = cell.capacity.removePrefix("0x").toLong(16)

        // Determine if deposit or withdrawing cell
        val isWithdrawing = data.length == 16 && data != "0000000000000000"
        logger.d(TAG, "🏦 Processing DAO cell: $cellId, data=$data, isWithdrawing=$isWithdrawing, capacity=$capacityShannons")

        var depositBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
        var depositBlockHash = ""
        var depositEpoch: EpochInfo? = null
        var withdrawBlockNumber: Long? = null
        var withdrawBlockHash: String? = null
        var withdrawEpoch: EpochInfo? = null
        var compensation = 0L
        var unlockEpoch: EpochInfo? = null
        var lockRemainingHours: Int? = null
        var depositTimestampMs = 0L
        var apc = 0.0
        var consumedDepositOutPoints: List<com.rjnr.pocketnode.data.gateway.models.OutPoint> = emptyList()

        // #434: read the phase-1 tx up front for withdrawing cells and record
        // what it consumed, INDEPENDENT of header availability. This drives
        // dedupeWithdrawnDeposits, which drops the original deposit's stale
        // DEPOSITED entry the light client still lists until its spent-filter
        // catches up. Previously this was only populated inside the
        // `cellBlockHeader != null` branch below, so the moment right after a
        // withdraw confirms — before the new withdrawing cell's header is
        // fetched — it stayed empty, dedup couldn't fire, and the pending
        // overlay painted the stale deposit a duplicate "Confirming…" card.
        val withdrawTx = if (isWithdrawing) {
            LightClientNative.nativeGetTransaction(cell.outPoint.txHash)
                ?.let { json.decodeFromString<JniTransactionWithStatus>(it) }
        } else null
        consumedDepositOutPoints = withdrawTx?.transaction?.inputs
            ?.map { it.previousOutput } ?: emptyList()

        // Get block header for compensation & epoch data (cache-first).
        val blockHash = daoHeaderResolver.getBlockHashForCell(cell.outPoint.txHash)
        val cellBlockHeader = if (blockHash != null) {
            header(blockHash)
        } else null

        if (isWithdrawing) {
            // Withdrawing data is an unsigned 8-byte LE deposit height. Heights
            // outside the model's signed Long range remain unparseable rows.
            depositBlockNumber = data.chunked(2).reversed().joinToString("").toLong(16)
            withdrawBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
            val original = resolveWithdrawingDepositHeader(
                cell, withdrawTx, cellBlockHeader, depositBlockNumber, ::header,
            )
            if (original != null && cellBlockHeader != null) {
                withdrawBlockHash = cellBlockHeader.hash
                withdrawEpoch = EpochInfo.fromHex(cellBlockHeader.epoch)
                depositBlockHash = original.hash
                depositEpoch = EpochInfo.fromHex(original.epoch)
                depositTimestampMs = original.timestamp.removePrefix("0x").toLong(16)
                compensationFor(original.dao, cellBlockHeader.dao, capacityShannons)
                    ?.let { compensation = it }
                val sinceHex = LightClientNative.nativeCalculateUnlockEpoch(
                    original.epoch, cellBlockHeader.epoch
                )
                if (sinceHex != null) {
                    val epochVal = sinceHex.removePrefix("0x").toLong(16) and 0x00FF_FFFF_FFFF_FFFFL
                    unlockEpoch = EpochInfo.fromHex("0x${epochVal.toString(16)}")
                    if (currentEpoch != null && unlockEpoch.value > currentEpoch.value) {
                        lockRemainingHours = ((unlockEpoch.value - currentEpoch.value) * DaoConstants.HOURS_PER_EPOCH).toInt()
                    }
                }
            } else {
                // Keep a basic withdrawing row, retrying all evidence next scan.
                // In particular, the withdraw header is never deposit metadata.
                logger.d(TAG, "Unresolved deposit header for $cellId")
            }
        } else if (cellBlockHeader != null) {
            depositBlockHash = cellBlockHeader.hash
            depositEpoch = EpochInfo.fromHex(cellBlockHeader.epoch)
            depositTimestampMs = cellBlockHeader.timestamp.removePrefix("0x").toLong(16)
            val tipJson = LightClientNative.nativeGetTipHeader()
            if (tipJson != null) {
                val tipHeader = json.decodeFromString<JniHeaderView>(tipJson)
                compensationFor(cellBlockHeader.dao, tipHeader.dao, capacityShannons)
                    ?.let { compensation = it }
            }
        }

        // Compute per-deposit APC when enough time has elapsed.
        //
        // Compensation stops accruing the moment the deposit cell is moved
        // into the withdrawing state — using wall-clock `now` for the upper
        // bound on a withdrawing cell understates the realised APC because
        // we'd divide by a window longer than the actual accrual period.
        //
        // For a withdrawing cell: end = withdraw-block timestamp.
        // For a deposited cell: end = wall-clock now (still accruing).
        //
        // Both branches above wrote `depositTimestampMs` to the original
        // deposit's block timestamp, so the only thing that varies is the
        // upper bound. The withdraw block header is `cellBlockHeader` in
        // the isWithdrawing branch (we re-read it earlier in this function).
        if (compensation > 0 && depositTimestampMs > 0 && capacityShannons > 0) {
            val endTimeMs = if (isWithdrawing) {
                cellBlockHeader?.timestamp?.removePrefix("0x")?.toLong(16) ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }
            val elapsedDays = (endTimeMs - depositTimestampMs) / 86_400_000.0
            if (elapsedDays >= 1.0) {
                apc = (compensation.toDouble() / capacityShannons) / (elapsedDays / 365.25) * 100.0
                logger.d(
                    TAG,
                    "APC for $cellId: compensation=$compensation capacity=$capacityShannons " +
                        "elapsedDays=${"%.2f".format(elapsedDays)} apc=${"%.4f".format(apc)}% " +
                        "(isWithdrawing=$isWithdrawing)"
                )
            }
        }

        // Calculate cycle progress (best-effort with available epoch data)
        val depositedEpochs = if (currentEpoch != null && depositEpoch != null) {
            (currentEpoch.value - depositEpoch.value).coerceAtLeast(0.0)
        } else 0.0
        val cycleProgress = ((depositedEpochs % DaoConstants.WITHDRAW_EPOCHS) / DaoConstants.WITHDRAW_EPOCHS).toFloat()

        // The three pending flags are deliberately false here: this class sees
        // only committed chain state, so it cannot know about a transaction
        // this wallet broadcast moments ago. The in-flight states are applied
        // afterwards by DaoGateway, from the persisted markers
        // (`pending_dao_withdraws` #347, `pending_dao_unlocks` #529), which is
        // also what lets them survive process death. Keep passing them rather
        // than dropping the parameters: they are the shared status contract
        // the iOS core will drive the same way.
        val status = determineDaoStatus(
            isWithdrawingCell = isWithdrawing,
            hasPendingWithdraw = false,
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = currentEpoch,
            unlockEpoch = unlockEpoch,
        )

        logger.d(TAG, "✅ DAO deposit added: $cellId, status=$status, capacity=$capacityShannons")
        return DaoDeposit(
            outPoint = cell.outPoint,
            capacity = capacityShannons,
            status = status,
            depositBlockNumber = depositBlockNumber,
            depositBlockHash = depositBlockHash,
            depositEpoch = depositEpoch,
            withdrawBlockNumber = withdrawBlockNumber,
            withdrawBlockHash = withdrawBlockHash,
            withdrawEpoch = withdrawEpoch,
            compensation = compensation.coerceAtLeast(0L),
            unlockEpoch = unlockEpoch,
            lockRemainingHours = lockRemainingHours,
            compensationCycleProgress = cycleProgress,
            cyclePhase = cyclePhaseFromProgress(cycleProgress),
            depositTimestamp = depositTimestampMs,
            apc = apc,
            consumedDepositOutPoints = consumedDepositOutPoints,
        )
    }

    /**
     * Resolve by the encoded height and current native canonical identity, never
     * dependency order. DaoGateway's verification revision bounds this read and
     * its staged cache writes; a cached or peer-fetched header alone is no proof
     * that its hash is canonical. No negative evidence is cached here.
     */
    private suspend fun resolveWithdrawingDepositHeader(
        cell: Cell,
        phaseOne: JniTransactionWithStatus?,
        withdrawHeader: JniHeaderView?,
        depositHeight: Long,
        header: suspend (String) -> JniHeaderView?,
    ): JniHeaderView? {
        val tx = phaseOne?.transaction ?: return null
        val index = cell.outPoint.index.removePrefix("0x").toIntOrNull(16) ?: return null
        if (phaseOne.txStatus.status != "committed" || tx.hash != cell.outPoint.txHash ||
            withdrawHeader == null || phaseOne.txStatus.blockHash != withdrawHeader.hash ||
            withdrawHeader.number.removePrefix("0x").toLongOrNull(16) !=
                cell.blockNumber.removePrefix("0x").toLongOrNull(16) ||
            tx.outputs.getOrNull(index) != CellOutput(cell.capacity, cell.lock, cell.type) || tx.outputsData.getOrNull(index) != cell.data
        ) return null

        fun canonical(height: Long): JniHeaderView? =
            LightClientNative.nativeGetHeaderByNumber("0x${height.toString(16)}")
                ?.let { json.decodeFromString<JniHeaderView>(it) }
                ?.takeIf { it.number.removePrefix("0x").toLongOrNull(16) == height }

        // Require the withdrawing transaction's block association to agree too.
        val withdrawHeight = cell.blockNumber.removePrefix("0x").toLong(16)
        if (canonical(withdrawHeight) != withdrawHeader || depositHeight >= withdrawHeight) return null
        val canonicalDeposit = canonical(depositHeight) ?: return null
        if (canonicalDeposit.hash !in tx.headerDeps) return null
        val original = header(canonicalDeposit.hash)?.takeIf { it == canonicalDeposit } ?: return null

        // DAO C validates phase-one input/output at the same GLOBAL index,
        // including normal cells. Never infer linkage from capacity or ordering
        // within the DAO group. A lock may change in this valid transition.
        val previous = tx.inputs.getOrNull(index)?.previousOutput ?: return null
        val parent = LightClientNative.nativeGetTransaction(previous.txHash)
            ?.let { json.decodeFromString<JniTransactionWithStatus>(it) }
        // Old parents may be outside the local scan window. Verified committed
        // phase-one data + canonical identity still resolve the header, but do
        // not claim independent parent evidence when it is unavailable.
        if (parent?.transaction != null) {
            val parentTx = parent.transaction ?: return null
            val parentIndex = previous.index.removePrefix("0x").toIntOrNull(16) ?: return null
            val deposited = parentTx.outputs.getOrNull(parentIndex) ?: return null
            if (parent.txStatus.status != "committed" || parentTx.hash != previous.txHash ||
                parent.txStatus.blockHash != original.hash || deposited.type != cell.type ||
                deposited.capacity.removePrefix("0x").toLongOrNull(16) !=
                    cell.capacity.removePrefix("0x").toLongOrNull(16) ||
                parentTx.outputsData.getOrNull(parentIndex) != "0x0000000000000000"
            ) return null
        }
        return original
    }

    /** Recheck old first-dependency metadata using the actual phase-one output.
     * Cache-only positions may need a local transaction rescan before this can
     * resolve; their existence and outside-window/pending overlays are retained.
     * All native reads happen before DaoGateway's revision-bound write phase.
     */
    suspend fun revalidateCachedWithdrawing(
        entity: DaoCellEntity,
        currentEpoch: EpochInfo?,
        network: NetworkType,
    ): DaoCellEntity {
        if (entity.withdrawBlockNumber == null) return entity
        val unresolved = entity.copy(status = DaoCellStatus.LOCKED.name,
            depositBlockHash = "", depositEpochHex = null, depositTimestamp = 0L,
            compensation = 0L, unlockEpochHex = null)
        return try {
            val phase = LightClientNative.nativeGetTransaction(entity.txHash)
                ?.let { json.decodeFromString<JniTransactionWithStatus>(it) } ?: return unresolved
            val tx = phase.transaction ?: return unresolved
            val index = entity.index.removePrefix("0x").toIntOrNull(16) ?: return unresolved
            val output = tx.outputs.getOrNull(index) ?: return unresolved
            val data = tx.outputsData.getOrNull(index) ?: return unresolved
            if (data.length != 18 || data == "0x0000000000000000" ||
                output.type != DaoConstants.DAO_TYPE_SCRIPT ||
                output.capacity.removePrefix("0x").toLongOrNull(16) != entity.capacity
            ) return unresolved
            val cell = JniCell(output, data, entity.toCachedDeposit().outPoint,
                "0x${entity.withdrawBlockNumber.toString(16)}", "0x0")
            val fresh = resolveOneDeposit(cell, currentEpoch, network, mutableListOf())
                .toDaoCellEntity(entity.network, entity.walletId, entity.lastUpdatedAt)
            // These are the deposit-derived claims being revalidated. Pending
            // intent and the stored withdrawing position remain independent.
            entity.copy(status = fresh.status, depositBlockNumber = fresh.depositBlockNumber,
                depositBlockHash = fresh.depositBlockHash, depositEpochHex = fresh.depositEpochHex,
                depositTimestamp = fresh.depositTimestamp, compensation = fresh.compensation,
                unlockEpochHex = fresh.unlockEpochHex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Cached DAO deposit evidence unavailable: ${e.message}")
            unresolved
        }
    }

    /**
     * Compensation a deposit of [capacityShannons] earns between the block
     * whose DAO field is [depositDao] and the one whose DAO field is [endDao],
     * or null when the bridge refuses the inputs.
     *
     * The occupied part is [DaoConstants.DEPOSIT_OCCUPIED_SHANNONS], the 102 CKB
     * a deposit cell takes up (#550). It earns no compensation, so passing the
     * 61 CKB of a plain secp256k1 cell here overstated every amount by
     * (C - 61) / (C - 102). The unlock transaction already used 102.
     */
    private fun compensationFor(depositDao: String, endDao: String, capacityShannons: Long): Long? {
        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
            depositDao, endDao, capacityShannons, DaoConstants.DEPOSIT_OCCUPIED_SHANNONS
        )
        return if (maxWithdraw >= 0) maxWithdraw - capacityShannons else null
    }

    /**
     * Fresh compensation for a cached dao_cells row the live scan no longer
     * returns (outside the sync window, or mid-unlock), or null when it cannot
     * be worked out without the network.
     *
     * Rows written before #550 carry an amount computed with the wrong occupied
     * capacity, and a cached-only row is never rewritten by a scan, so without
     * this a withdrawing position would show the inflated figure for good.
     * Headers come from [cachedDao] only (the header cache the original scan
     * filled), so this never waits on peers. A withdrawing row is measured to
     * its withdraw block, a deposited row to the current tip, the same ends the
     * live scan uses.
     */
    suspend fun recomputeCachedCompensation(
        entity: DaoCellEntity,
        cachedDao: suspend (blockHash: String) -> String?,
    ): Long? {
        if (entity.depositBlockHash.isEmpty()) return null
        val depositDao = cachedDao(entity.depositBlockHash) ?: return null
        val withdrawHash = entity.withdrawBlockHash
        val endDao = when {
            withdrawHash != null -> cachedDao(withdrawHash) ?: return null
            // Withdrawing, but its header was never resolved: the tip would be
            // the wrong end, so leave the stored value alone.
            entity.withdrawBlockNumber != null -> return null
            else -> LightClientNative.nativeGetTipHeader()
                ?.let { json.decodeFromString<JniHeaderView>(it).dao }
                ?: return null
        }
        return compensationFor(depositDao, endDao, entity.capacity)?.coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "DaoDepositReader"

        // Runaway guard for the cell / spent-set walks (100 items per page).
        // Generous — a real wallet's DAO history is far smaller; this only
        // bounds a pathological/looping cursor, and it logs when hit so a
        // genuine truncation is visible rather than silent.
        private const val MAX_PAGES = 200
    }
}
