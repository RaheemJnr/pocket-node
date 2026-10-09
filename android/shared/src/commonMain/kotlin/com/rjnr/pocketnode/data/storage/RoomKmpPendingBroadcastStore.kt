package com.rjnr.pocketnode.data.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [PendingBroadcastStore] over the shared-core Room database.
 *
 * The iOS binding for the seam Android binds to `PendingBroadcastDao` through
 * `RoomPendingBroadcastStore`. [PendingBroadcastRow] and
 * [PendingBroadcastRecord] carry the same ten columns, so every member here is
 * a field-for-field mapping around one SQL statement.
 *
 * [observeAll] is the one member that is not on the interface. The interface
 * deliberately keeps the observable Flows off itself (nothing in the shared
 * send engine collects them), but the activity list does collect this one: it
 * is what lets a row move Broadcasting to Pending to Confirmed in place, with
 * no polling and no bridge call.
 */
class RoomKmpPendingBroadcastStore(
    private val dao: PendingBroadcastRoomDao,
) : PendingBroadcastStore {

    override suspend fun getActive(walletId: String, network: String): List<PendingBroadcastRecord> =
        dao.getActive(walletId, network).map { it.toRecord() }

    override suspend fun insert(record: PendingBroadcastRecord) =
        dao.insert(PendingBroadcastRow.from(record))

    override suspend fun compareAndUpdateState(
        hash: String,
        expected: String,
        next: String,
        now: Long,
    ): Int = dao.compareAndUpdateState(hash, expected, next, now)

    override suspend fun updateNullCount(hash: String, count: Int, now: Long) =
        dao.updateNullCount(hash, count, now)

    override suspend fun delete(hash: String) = dao.delete(hash)

    override suspend fun getFailedRow(hash: String): PendingBroadcastRecord? =
        dao.getFailedRow(hash)?.toRecord()

    /**
     * Every broadcast row for a wallet and network, terminal ones included.
     * What the activity list observes; see the class KDoc for why it is not on
     * the interface.
     */
    fun observeAll(walletId: String, network: String): Flow<List<PendingBroadcastRecord>> =
        dao.observeAll(walletId, network).map { rows -> rows.map { it.toRecord() } }
}
