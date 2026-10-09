package com.rjnr.pocketnode.data.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes [PendingBroadcastRow].
 *
 * Every statement is the Android `PendingBroadcastDao` statement it replaces,
 * character for character, so the two state machines cannot drift.
 */
@Dao
interface PendingBroadcastRoomDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: PendingBroadcastRow)

    /**
     * CAS update: only writes if the current state matches [expected]. Returns
     * rows affected. Callers must check `== 1` before treating the transition
     * as committed; concurrent tip-event and fallback-timer checks race
     * otherwise.
     */
    @Query(
        "UPDATE pending_broadcasts SET state = :next, lastCheckedAt = :now " +
            "WHERE txHash = :hash AND state = :expected"
    )
    suspend fun compareAndUpdateState(
        hash: String,
        expected: String,
        next: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE pending_broadcasts SET nullCount = :count, lastCheckedAt = :now WHERE txHash = :hash"
    )
    suspend fun updateNullCount(hash: String, count: Int, now: Long)

    @Query("DELETE FROM pending_broadcasts WHERE txHash = :hash")
    suspend fun delete(hash: String)

    /** Snapshot for cell-reservation reads inside a mutex-guarded section. */
    @Query(
        "SELECT * FROM pending_broadcasts " +
            "WHERE walletId = :walletId AND network = :network " +
            "AND state IN ('BROADCASTING','BROADCAST')"
    )
    suspend fun getActive(walletId: String, network: String): List<PendingBroadcastRow>

    /**
     * Every broadcast row for a wallet and network, terminal ones included.
     *
     * UI observation only: the activity rows need BROADCASTING versus BROADCAST
     * to tell "still sending" from "waiting in the pool", and the FAILED rows
     * carry the `nullCount` the detail sheet turns into a reason. Deliberately
     * distinct from [getActive], which hides terminal rows because its callers
     * reserve cells.
     */
    @Query("SELECT * FROM pending_broadcasts WHERE walletId = :walletId AND network = :network")
    fun observeAll(walletId: String, network: String): Flow<List<PendingBroadcastRow>>

    @Query("SELECT * FROM pending_broadcasts WHERE txHash = :hash AND state = 'FAILED'")
    suspend fun getFailedRow(hash: String): PendingBroadcastRow?
}
