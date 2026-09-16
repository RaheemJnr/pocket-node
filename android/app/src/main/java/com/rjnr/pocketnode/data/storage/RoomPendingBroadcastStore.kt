package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Room binding for the shared [PendingBroadcastStore] seam (M3 #4). */
@Singleton
class RoomPendingBroadcastStore @Inject constructor(
    private val dao: PendingBroadcastDao,
) : PendingBroadcastStore {

    override suspend fun getActive(walletId: String, network: String): List<PendingBroadcastRecord> =
        dao.getActive(walletId, network).map { it.toRecord() }
}

private fun PendingBroadcastEntity.toRecord() = PendingBroadcastRecord(
    txHash = txHash,
    state = state,
    reservedInputs = reservedInputs,
    signedTxJson = signedTxJson,
)
