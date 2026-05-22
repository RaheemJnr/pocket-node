package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
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
    suspend fun list(walletScript: Script, currentEpoch: EpochInfo?, network: NetworkType): List<DaoDeposit> {
        // Query ALL cells by lock script (like Neuron), then filter locally for DAO type
        val searchKey = JniSearchKey(
            script = walletScript,
            scriptType = "lock",
            withData = true,
        )
        val searchKeyJson = json.encodeToString(searchKey)

        // Paginate all cells by lock script
        val allCellObjects = mutableListOf<JniCell>()
        var cellsCursor: String? = null
        do {
            val pageJson = LightClientNative.nativeGetCells(searchKeyJson, "desc", 100, cellsCursor)
                ?: break
            val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
            allCellObjects += page.objects
            cellsCursor = page.lastCursor?.takeUnless { it == cellsCursor }
        } while (cellsCursor != null)

        // Filter locally: only cells whose type script matches DAO code hash
        val daoCells = allCellObjects.filter { cell ->
            cell.output.type?.codeHash == DaoConstants.DAO_CODE_HASH
        }
        Log.d(TAG, "📋 getDaoDeposits: ${daoCells.size} DAO cells out of ${allCellObjects.size} total")

        // Paginate transactions to find spent outpoints
        val spentOutpoints = mutableSetOf<String>()
        var txCursor: String? = null
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
        } while (txCursor != null)

        val liveDaoCells = daoCells.filter { cell ->
            val key = "${cell.outPoint.txHash}:${cell.outPoint.index}"
            key !in spentOutpoints
        }
        Log.d(TAG, "📋 getDaoDeposits: ${liveDaoCells.size} live DAO cells")

        val deposits = mutableListOf<DaoDeposit>()
        for (jniCell in liveDaoCells) {
            deposits += resolveOneDeposit(jniCell, currentEpoch, network)
        }
        return deposits
    }

    /**
     * Resolve compensation + epoch metadata for one DAO cell.
     * Extracted from the monolithic loop body purely for readability;
     * behavior is unchanged from the original `GatewayRepository.getDaoDeposits`.
     */
    private suspend fun resolveOneDeposit(
        jniCell: JniCell,
        currentEpoch: EpochInfo?,
        network: NetworkType,
    ): DaoDeposit {
        val cell = jniCell.toCell()
        val data = cell.data.removePrefix("0x")
        val cellId = "${cell.outPoint.txHash.take(20)}...:${cell.outPoint.index}"
        val capacityShannons = cell.capacity.removePrefix("0x").toLong(16)

        // Determine if deposit or withdrawing cell
        val isWithdrawing = data.length == 16 && data != "0000000000000000"
        Log.d(TAG, "🏦 Processing DAO cell: $cellId, data=$data, isWithdrawing=$isWithdrawing, capacity=$capacityShannons")

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

        // Get block header for compensation & epoch data (cache-first).
        val blockHash = daoHeaderResolver.getBlockHashForCell(cell.outPoint.txHash)
        val cellBlockHeader = if (blockHash != null) {
            daoHeaderResolver.getOrFetchHeader(blockHash, network)
        } else null

        if (cellBlockHeader != null) {
            val cellBlockEpoch = EpochInfo.fromHex(cellBlockHeader.epoch)
            depositBlockHash = cellBlockHeader.hash
            depositEpoch = cellBlockEpoch
            depositTimestampMs = cellBlockHeader.timestamp.removePrefix("0x").toLong(16)

            if (isWithdrawing) {
                // Cell data contains deposit block number as 8-byte LE
                val depositBlockNum = data.chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
                    .let { bytes ->
                        var num = 0L
                        for (i in bytes.indices) {
                            num = num or ((bytes[i].toLong() and 0xFF) shl (i * 8))
                        }
                        num
                    }

                withdrawBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
                withdrawBlockHash = cellBlockHeader.hash
                withdrawEpoch = cellBlockEpoch
                depositBlockNumber = depositBlockNum

                // Get original deposit header for compensation (cache-first)
                val withdrawTxJson = LightClientNative.nativeGetTransaction(cell.outPoint.txHash)
                val withdrawTx = withdrawTxJson?.let { json.decodeFromString<JniTransactionWithStatus>(it) }
                val origDepositBlockHash = withdrawTx?.transaction?.headerDeps?.firstOrNull()
                if (origDepositBlockHash != null) {
                    depositBlockHash = origDepositBlockHash
                }
                val origDepositHeader = origDepositBlockHash?.let { h ->
                    daoHeaderResolver.getOrFetchHeader(h, network)
                }

                if (origDepositHeader != null) {
                    depositBlockHash = origDepositHeader.hash
                    depositEpoch = EpochInfo.fromHex(origDepositHeader.epoch)
                    depositTimestampMs = origDepositHeader.timestamp.removePrefix("0x").toLong(16)
                    val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
                        origDepositHeader.dao, cellBlockHeader.dao, capacityShannons, 61_00000000L
                    )
                    if (maxWithdraw >= 0) compensation = maxWithdraw - capacityShannons
                    val sinceHex = LightClientNative.nativeCalculateUnlockEpoch(
                        origDepositHeader.epoch, cellBlockHeader.epoch
                    )
                    if (sinceHex != null) {
                        val epochVal = sinceHex.removePrefix("0x").toLong(16) and 0x00FF_FFFF_FFFF_FFFFL
                        unlockEpoch = EpochInfo.fromHex("0x${epochVal.toString(16)}")
                        if (currentEpoch != null && unlockEpoch!!.value > currentEpoch.value) {
                            lockRemainingHours = ((unlockEpoch!!.value - currentEpoch.value) * DaoConstants.HOURS_PER_EPOCH).toInt()
                        }
                    }
                }
            } else {
                // Deposited cell — calculate compensation using current tip header
                val tipJson = LightClientNative.nativeGetTipHeader()
                if (tipJson != null) {
                    val tipHeader = json.decodeFromString<JniHeaderView>(tipJson)
                    val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
                        cellBlockHeader.dao, tipHeader.dao, capacityShannons, 61_00000000L
                    )
                    if (maxWithdraw >= 0) compensation = maxWithdraw - capacityShannons
                }
            }
        } else {
            Log.d(TAG, "  No header available for $cellId — showing deposit with basic info")
            if (isWithdrawing) {
                val depositBlockNum = data.chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
                    .let { bytes ->
                        var num = 0L
                        for (i in bytes.indices) {
                            num = num or ((bytes[i].toLong() and 0xFF) shl (i * 8))
                        }
                        num
                    }
                withdrawBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
                depositBlockNumber = depositBlockNum
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
                Log.d(
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

        val status = determineDaoStatus(
            isWithdrawingCell = isWithdrawing,
            hasPendingWithdraw = false,
            hasPendingUnlock = false,
            hasPendingDeposit = false,
            currentEpoch = currentEpoch,
            unlockEpoch = unlockEpoch,
        )

        Log.d(TAG, "✅ DAO deposit added: $cellId, status=$status, capacity=$capacityShannons")
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
        )
    }

    companion object {
        private const val TAG = "DaoDepositReader"
    }
}
