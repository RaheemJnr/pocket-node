package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room binding for the shared [PendingBroadcastStore] seam, widened with the
 * send path's and the watchdog's writes.
 *
 * A pure field-for-field mapping in both directions: the record carries every
 * column the entity does, so nothing is defaulted or dropped on the way
 * through, and the DAO's REPLACE-on-conflict and rows-affected semantics reach
 * shared code unchanged.
 */
@Singleton
class RoomPendingBroadcastStore @Inject constructor(
    private val dao: PendingBroadcastDao,
) : PendingBroadcastStore {

    override suspend fun getActive(walletId: String, network: String): List<PendingBroadcastRecord> =
        dao.getActive(walletId, network).map { it.toRecord() }

    override suspend fun insert(record: PendingBroadcastRecord) =
        dao.insert(record.toEntity())

    override suspend fun compareAndUpdateState(
        hash: String,
        expected: String,
        next: String,
        now: Long,
    ): Int = dao.compareAndUpdateState(hash = hash, expected = expected, next = next, now = now)

    override suspend fun updateNullCount(hash: String, count: Int, now: Long) =
        dao.updateNullCount(hash, count, now)

    override suspend fun delete(hash: String) = dao.delete(hash)

    override suspend fun getFailedRow(hash: String): PendingBroadcastRecord? =
        dao.getFailedRow(hash)?.toRecord()
}

private fun PendingBroadcastEntity.toRecord() = PendingBroadcastRecord(
    txHash = txHash,
    state = state,
    reservedInputs = reservedInputs,
    signedTxJson = signedTxJson,
    walletId = walletId,
    network = network,
    submittedAtTipBlock = submittedAtTipBlock,
    nullCount = nullCount,
    createdAt = createdAt,
    lastCheckedAt = lastCheckedAt,
)

private fun PendingBroadcastRecord.toEntity() = PendingBroadcastEntity(
    txHash = txHash,
    walletId = walletId,
    network = network,
    signedTxJson = signedTxJson,
    reservedInputs = reservedInputs,
    state = state,
    submittedAtTipBlock = submittedAtTipBlock,
    nullCount = nullCount,
    createdAt = createdAt,
    lastCheckedAt = lastCheckedAt,
)
