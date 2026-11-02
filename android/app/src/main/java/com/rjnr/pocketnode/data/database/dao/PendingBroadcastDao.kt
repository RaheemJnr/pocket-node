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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFresh(row: PendingBroadcastEntity): Long

    @Query("SELECT * FROM pending_broadcasts WHERE txHash = :hash")
    suspend fun getByHash(hash: String): PendingBroadcastEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE txHash = :hash AND network = :network " +
        "AND (walletId != :wallet OR status != 'PENDING' OR isLocal != 1))")
    suspend fun hasAdvancedActivity(hash: String, network: String, wallet: String): Boolean

    @Query("UPDATE transactions SET status = 'FAILED', cachedAt = :now WHERE txHash = :hash " +
        "AND network = :network AND walletId = :wallet AND status = 'PENDING' AND isLocal = 1")
    suspend fun failLocalActivity(hash: String, network: String, wallet: String, now: Long)

    @androidx.room.Transaction
    suspend fun failKnownUnsent(expected: PendingBroadcastEntity, now: Long): Boolean {
        val current = getByHash(expected.txHash) ?: return false
        // Watchdog observations do not transfer ownership; submission/confirmation do.
        if (expected.state != "BROADCASTING" ||
            current.copy(nullCount = expected.nullCount, lastCheckedAt = expected.lastCheckedAt) != expected ||
            hasAdvancedActivity(expected.txHash, expected.network, expected.walletId)) return false
        if (compareAndUpdateState(expected.txHash, "BROADCASTING", "FAILED", now) != 1) return false
        failLocalActivity(expected.txHash, expected.network, expected.walletId, now)
        return true
    }

    @Query("UPDATE transactions SET status = :status, cachedAt = :now WHERE txHash = :hash " +
        "AND network = :network AND walletId = :wallet AND status = 'PENDING' AND isLocal = 1")
    suspend fun reconcileLocalActivity(hash: String, network: String, wallet: String, status: String, now: Long)

    /** Commit an observation only while this exact attempt is still active. */
    @androidx.room.Transaction
    suspend fun reconcileObservation(expected: PendingBroadcastEntity, next: String, nullCount: Int, now: Long): Boolean {
        require(next in setOf("BROADCASTING", "BROADCAST", "CONFIRMED", "FAILED"))
        if (expected.state !in setOf("BROADCASTING", "BROADCAST") || getByHash(expected.txHash) != expected) return false
        // Never turn already advanced activity into a retryable failure.
        if (next == "FAILED" && hasAdvancedActivity(expected.txHash, expected.network, expected.walletId)) return false
        if (next == "CONFIRMED" || next == "FAILED") {
            reconcileLocalActivity(expected.txHash, expected.network, expected.walletId, next, now)
        }
        if (next == "CONFIRMED") {
            delete(expected.txHash)
        } else {
            compareAndUpdateState(expected.txHash, expected.state, next, now)
            updateNullCount(expected.txHash, nullCount, now)
        }
        return true
    }

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

    @Query("UPDATE pending_broadcasts SET state = 'BROADCAST', submittedAtTipBlock = :submittedAt, " +
        "nullCount = 0, lastCheckedAt = :now WHERE txHash = :hash AND state = 'FAILED' " +
        "AND lastCheckedAt = :expectedLastCheckedAt")
    suspend fun rearmPendingRetry(hash: String, expectedLastCheckedAt: Long, submittedAt: Long, now: Long): Int

    @Query("UPDATE transactions SET status = 'PENDING', cachedAt = :now WHERE txHash = :hash " +
        "AND network = :network AND walletId = :wallet AND status = 'FAILED' AND isLocal = 1")
    suspend fun rearmLocalFailedActivity(hash: String, network: String, wallet: String, now: Long)

    @androidx.room.Transaction
    suspend fun rearmAcceptedRetry(hash: String, expectedLastCheckedAt: Long, submittedAt: Long, now: Long): Int {
        val current = getByHash(hash) ?: return 0
        val changed = rearmPendingRetry(hash, expectedLastCheckedAt, submittedAt, now)
        if (changed == 1) {
            // The two rows publish together; chain-derived or advanced activity always wins.
            rearmLocalFailedActivity(hash, current.network, current.walletId, now)
        }
        return changed
    }

    @Query(
        "UPDATE pending_broadcasts SET nullCount = :count, lastCheckedAt = :now WHERE txHash = :hash"
    )
    suspend fun updateNullCount(hash: String, count: Int, now: Long)

    @Query("DELETE FROM pending_broadcasts WHERE txHash = :hash")
    suspend fun delete(hash: String)

    /** Every broadcast row of a wallet, on both networks (wallet removal). */
    @Query("DELETE FROM pending_broadcasts WHERE walletId = :walletId")
    suspend fun deleteForWallet(walletId: String)

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
