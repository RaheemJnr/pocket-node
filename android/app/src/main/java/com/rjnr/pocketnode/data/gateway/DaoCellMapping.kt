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

/** A retained withdrawal hash is only a historical locator until both headers resolve. */
internal fun DaoCellEntity.hasResolvedWithdrawalHeaders(): Boolean =
    withdrawBlockNumber != null && withdrawBlockHash != null &&
        withdrawEpochHex?.let(::parseEpochOrNull) != null && depositBlockHash.isNotEmpty() &&
        depositEpochHex?.let(::parseEpochOrNull) != null

private fun DaoCellEntity.evidenceStatus(freshStatus: String): String = when (status) {
    DaoCellStatus.WITHDRAWING.name, DaoCellStatus.UNLOCKING.name, DaoCellStatus.COMPLETED.name -> status
    else -> freshStatus
}

internal fun DaoCellEntity.withoutWithdrawalEvidence(): DaoCellEntity = copy(
    status = evidenceStatus(DaoCellStatus.LOCKED.name),
    depositBlockHash = "", depositEpochHex = null, depositTimestamp = 0L,
    // Keep the prior hash as a locator: erasing it would let a second refresh
    // accept the same conflicting height-map association as a new null-to-valid pair.
    withdrawEpochHex = null, compensation = 0L, unlockEpochHex = null,
)

/**
 * Accept metadata under the existing verified-native-store contract only.
 * A joined current height mapping does not independently prove that an old
 * transaction moved to a different block. Contradictions remain unresolved.
 */
internal fun DaoCellEntity.withWithdrawalEvidence(
    fresh: DaoCellEntity,
    historicalWithdrawalHashes: Set<String> = emptySet(),
): DaoCellEntity {
    if (!fresh.hasResolvedWithdrawalHeaders() || fresh.withdrawBlockNumber != withdrawBlockNumber ||
        fresh.capacity != capacity ||
        historicalWithdrawalHashes.any { !it.equals(fresh.withdrawBlockHash, ignoreCase = true) } ||
        (withdrawBlockHash != null && !withdrawBlockHash.equals(fresh.withdrawBlockHash, ignoreCase = true))) {
        return withoutWithdrawalEvidence()
    }
    return copy(status = evidenceStatus(fresh.status), depositBlockNumber = fresh.depositBlockNumber,
        depositBlockHash = fresh.depositBlockHash, depositEpochHex = fresh.depositEpochHex,
        depositTimestamp = fresh.depositTimestamp, withdrawBlockHash = fresh.withdrawBlockHash,
        withdrawEpochHex = fresh.withdrawEpochHex, compensation = fresh.compensation,
        unlockEpochHex = fresh.unlockEpochHex)
}

/**
 * The cached row as the screen's own record of the position (#529).
 *
 * Same fields as [toOutsideWindowDeposit] without the `outsideSyncWindow`
 * flag: used while an unlock for the cell is confirming, where the row is
 * absent from the live scan because it is being spent, not because it
 * predates the window. Flagging it would raise the deep-rescan banner for a
 * position that is mid-claim.
 */
internal fun DaoCellEntity.toCachedDeposit(): DaoDeposit =
    toOutsideWindowDeposit().copy(outsideSyncWindow = false)

internal fun DaoCellEntity.toOutsideWindowDeposit(): DaoDeposit {
    val projected = if (withdrawBlockNumber != null && !hasResolvedWithdrawalHeaders()) {
        withoutWithdrawalEvidence().copy(withdrawBlockHash = null)
    } else this
    return projected.toOutsideWindowProjection()
}

private fun DaoCellEntity.toOutsideWindowProjection(): DaoDeposit = DaoDeposit(
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
