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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFresh(row: PendingBroadcastRow): Long

    @Query("SELECT * FROM pending_broadcasts WHERE txHash = :hash")
    suspend fun getByHash(hash: String): PendingBroadcastRow?

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE txHash = :hash AND network = :network " +
        "AND (walletId != :wallet OR status != 'PENDING' OR isLocal != 1))")
    suspend fun hasAdvancedActivity(hash: String, network: String, wallet: String): Boolean

    @Query("UPDATE transactions SET status = 'FAILED', cachedAt = :now WHERE txHash = :hash " +
        "AND network = :network AND walletId = :wallet AND status = 'PENDING' AND isLocal = 1")
    suspend fun failLocalActivity(hash: String, network: String, wallet: String, now: Long)

    @androidx.room.Transaction
    suspend fun failKnownUnsent(expected: PendingBroadcastRow, now: Long): Boolean {
        val current = getByHash(expected.txHash) ?: return false
        // Watchdog observations do not transfer ownership; submission/confirmation do.
        if (expected.state != "BROADCASTING" ||
            current.copy(nullCount = expected.nullCount, lastCheckedAt = expected.lastCheckedAt) != expected ||
            hasAdvancedActivity(expected.txHash, expected.network, expected.walletId)) return false
        if (compareAndUpdateState(expected.txHash, "BROADCASTING", "FAILED", now) != 1) return false
        failLocalActivity(expected.txHash, expected.network, expected.walletId, now)
        return true
    }

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

    @Query("UPDATE pending_broadcasts SET state = 'BROADCAST', submittedAtTipBlock = :submittedAt, " +
        "nullCount = 0, lastCheckedAt = :now WHERE txHash = :hash AND state = 'FAILED' " +
        "AND lastCheckedAt = :expectedLastCheckedAt")
    suspend fun rearmAcceptedRetry(hash: String, expectedLastCheckedAt: Long, submittedAt: Long, now: Long): Int

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
