package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.EpochInfo
import com.rjnr.pocketnode.data.gateway.models.OutPoint

/**
 * dao_cells ↔ DaoDeposit mapping (#332 windowing recovery).
 *
 * Every live scan writes its deposits through to Room so they survive a
 * later narrowing of the sync window (CUSTOM resync with a higher start
 * block, RECENT switch). A cached deposit older than the current window is
 * merged back into the UI list flagged [DaoDeposit.outsideSyncWindow] —
 * visible, but marked as needing a deeper rescan for fresh compensation
 * and spend state.
 */

private fun EpochInfo.toHexString(): String {
    val packed = (length shl 40) or (index shl 24) or number
    return "0x${packed.toString(16)}"
}

internal fun DaoDeposit.toDaoCellEntity(
    network: String,
    walletId: String,
    nowMs: Long,
): DaoCellEntity = DaoCellEntity(
    txHash = outPoint.txHash,
    index = outPoint.index,
    capacity = capacity,
    status = status.name,
    depositBlockNumber = depositBlockNumber,
    depositBlockHash = depositBlockHash,
    depositEpochHex = depositEpoch?.toHexString(),
    withdrawBlockNumber = withdrawBlockNumber,
    withdrawBlockHash = withdrawBlockHash,
    withdrawEpochHex = withdrawEpoch?.toHexString(),
    compensation = compensation,
    unlockEpochHex = unlockEpoch?.toHexString(),
    depositTimestamp = depositTimestamp,
    network = network,
    lastUpdatedAt = nowMs,
    walletId = walletId,
)

internal fun DaoCellEntity.toOutsideWindowDeposit(): DaoDeposit = DaoDeposit(
    outPoint = OutPoint(txHash = txHash, index = index),
    capacity = capacity,
    status = runCatching { DaoCellStatus.valueOf(status) }.getOrDefault(DaoCellStatus.DEPOSITED),
    depositBlockNumber = depositBlockNumber,
    depositBlockHash = depositBlockHash,
    depositEpoch = depositEpochHex?.let { parseEpochOrNull(it) },
    withdrawBlockNumber = withdrawBlockNumber,
    withdrawBlockHash = withdrawBlockHash,
    withdrawEpoch = withdrawEpochHex?.let { parseEpochOrNull(it) },
    compensation = compensation,
    unlockEpoch = unlockEpochHex?.let { parseEpochOrNull(it) },
    depositTimestamp = depositTimestamp,
    outsideSyncWindow = true,
)

private fun parseEpochOrNull(hex: String): EpochInfo? =
    runCatching { EpochInfo.fromHex(hex) }.getOrNull()
