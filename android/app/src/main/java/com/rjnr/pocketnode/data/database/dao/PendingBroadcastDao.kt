package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingBroadcastDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: PendingBroadcastEntity)

    /**
     * CAS update — only writes if current state matches [expected].
     * Returns rows-affected. Watchdog must check `== 1` before treating
     * the transition as committed; concurrent tip-event + fallback-timer
     * checks would otherwise race.
     */
    @Query(
        "UPDATE pending_broadcasts SET state = :next, lastCheckedAt = :now " +
            "WHERE txHash = :hash AND state = :expected"
    )
    suspend fun compareAndUpdateState(
        hash: String,
        expected: String,
        next: String,
        now: Long
    ): Int

    @Query(
        "UPDATE pending_broadcasts SET nullCount = :count, lastCheckedAt = :now WHERE txHash = :hash"
    )
    suspend fun updateNullCount(hash: String, count: Int, now: Long)

    @Query("DELETE FROM pending_broadcasts WHERE txHash = :hash")
    suspend fun delete(hash: String)

    /** Snapshot — for cell-reservation reads inside a mutex-guarded section. */
    @Query(
        "SELECT * FROM pending_broadcasts " +
            "WHERE walletId = :walletId AND network = :network " +
            "AND state IN ('BROADCASTING','BROADCAST')"
    )
    suspend fun getActive(walletId: String, network: String): List<PendingBroadcastEntity>

    /** Flow — UI observation only. */
    @Query(
        "SELECT * FROM pending_broadcasts " +
            "WHERE walletId = :walletId AND network = :network " +
            "AND state IN ('BROADCASTING','BROADCAST')"
    )
    fun observeActive(walletId: String, network: String): Flow<List<PendingBroadcastEntity>>

    @Query(
        "SELECT * FROM pending_broadcasts " +
            "WHERE walletId = :walletId AND network = :network AND state = 'FAILED'"
    )
    fun observeFailed(walletId: String, network: String): Flow<List<PendingBroadcastEntity>>

    /**
     * Every broadcast row for a wallet+network, terminal ones included.
     *
     * UI observation only (#432): the activity/home rows need BROADCASTING vs
     * BROADCAST to tell "still sending" from "waiting in the pool", and the
     * FAILED rows carry the `nullCount` the detail sheet turns into a reason.
     * Distinct from [observeActive], which deliberately hides terminal rows
     * because its callers reserve cells.
     */
    @Query("SELECT * FROM pending_broadcasts WHERE walletId = :walletId AND network = :network")
    fun observeAll(walletId: String, network: String): Flow<List<PendingBroadcastEntity>>

    @Query("SELECT * FROM pending_broadcasts WHERE txHash = :hash AND state = 'FAILED'")
    suspend fun getFailedRow(hash: String): PendingBroadcastEntity?
}
