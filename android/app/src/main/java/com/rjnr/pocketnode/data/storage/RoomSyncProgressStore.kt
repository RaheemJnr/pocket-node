package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Room binding for the shared [SyncProgressStore] seam. */
@Singleton
class RoomSyncProgressStore @Inject constructor(
    private val dao: SyncProgressDao,
) : SyncProgressStore {

    override suspend fun getAllForNetwork(network: String): List<SyncProgressRecord> =
        dao.getAllForNetwork(network).map { it.toRecord() }

    override suspend fun upsert(record: SyncProgressRecord) = dao.upsert(record.toEntity())

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
}

private fun SyncProgressEntity.toRecord() = SyncProgressRecord(
    walletId = walletId,
    network = network,
    lightStartBlockNumber = lightStartBlockNumber,
    localSavedBlockNumber = localSavedBlockNumber,
    updatedAt = updatedAt,
)

private fun SyncProgressRecord.toEntity() = SyncProgressEntity(
    walletId = walletId,
    network = network,
    lightStartBlockNumber = lightStartBlockNumber,
    localSavedBlockNumber = localSavedBlockNumber,
    updatedAt = updatedAt,
)
