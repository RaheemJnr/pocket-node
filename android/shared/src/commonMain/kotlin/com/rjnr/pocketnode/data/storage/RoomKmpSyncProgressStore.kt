package com.rjnr.pocketnode.data.storage

/**
 * [SyncProgressStore] over the shared-core Room database.
 *
 * The iOS binding for the seam Android binds to its own `SyncProgressDao`
 * through `RoomSyncProgressStore`. [SyncProgressRow] and [SyncProgressRecord]
 * were written to the same shape on purpose, so the mapping here is field for
 * field and the table needs no schema change.
 *
 * `updateLightStart` forwards to the DAO's `UPDATE`, which is the whole point
 * of the seam having that member: a read-modify-write here would reopen the
 * race between script registration and the sync poll's progress writes that
 * the atomic statement closes.
 */
class RoomKmpSyncProgressStore(
    private val dao: SyncProgressRoomDao,
) : SyncProgressStore {

    override suspend fun getAllForNetwork(network: String): List<SyncProgressRecord> =
        dao.getAllForNetwork(network).map { it.toRecord() }

    override suspend fun upsert(record: SyncProgressRecord) {
        dao.upsert(record.toRow())
    }

    override suspend fun updateLightStart(
        walletId: String,
        network: String,
        lightStart: Long,
        ts: Long,
    ): Int = dao.updateLightStart(walletId, network, lightStart, ts)

    override suspend fun updateLocalSaved(
        walletId: String,
        network: String,
        block: Long,
        ts: Long,
    ): Int = dao.updateLocalSaved(walletId, network, block, ts)

    private fun SyncProgressRow.toRecord() = SyncProgressRecord(
        walletId = walletId,
        network = network,
        lightStartBlockNumber = lightStartBlockNumber,
        localSavedBlockNumber = localSavedBlockNumber,
        updatedAt = updatedAt,
    )

    private fun SyncProgressRecord.toRow() = SyncProgressRow(
        walletId = walletId,
        network = network,
        lightStartBlockNumber = lightStartBlockNumber,
        localSavedBlockNumber = localSavedBlockNumber,
        updatedAt = updatedAt,
    )
}
