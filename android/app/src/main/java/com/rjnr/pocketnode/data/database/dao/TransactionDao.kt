package com.rjnr.pocketnode.data.database.dao

import androidx.paging.PagingSource
import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.TransactionEntity

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions WHERE network = :network ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, timestamp DESC LIMIT :limit")
    suspend fun getByNetwork(network: String, limit: Int = 50): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE network = :network AND status = 'PENDING'")
    suspend fun getPending(network: String): List<TransactionEntity>

    /** #382: hex block numbers of every cached tx for a wallet — anchors the gap-limit candidate scan depth. */
    @Query("SELECT blockNumber FROM transactions WHERE walletId = :walletId AND network = :network")
    suspend fun getBlockNumbers(walletId: String, network: String): List<String>

    @Query("SELECT * FROM transactions WHERE txHash = :txHash")
    suspend fun getByTxHash(txHash: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TransactionEntity>)

    @Query("UPDATE transactions SET status = :status, confirmations = :confirmations, blockNumber = :blockNumber, blockHash = :blockHash, isLocal = 0, cachedAt = :cachedAt WHERE txHash = :txHash")
    suspend fun updateStatus(txHash: String, status: String, confirmations: Int, blockNumber: String, blockHash: String, cachedAt: Long = System.currentTimeMillis())

    /**
     * Status-only update used by BroadcastWatchdog (no on-chain fields known yet).
     * Bumps `cachedAt` so any TTL/staleness logic treats the transition as fresh —
     * matches the convention of [updateStatus] above.
     */
    @Query("UPDATE transactions SET status = :status, cachedAt = :cachedAt WHERE txHash = :hash")
    suspend fun updateStatusOnly(hash: String, status: String, cachedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM transactions WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM transactions WHERE txHash = :hash")
    suspend fun deleteByHash(hash: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    // -- Wallet-scoped queries --

    @Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, timestamp DESC LIMIT :limit")
    suspend fun getByWalletAndNetwork(walletId: String, network: String, limit: Int = 50): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network ORDER BY timestamp DESC")
    suspend fun getAllByWalletAndNetwork(walletId: String, network: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network AND status = 'PENDING'")
    suspend fun getPendingByWallet(walletId: String, network: String): List<TransactionEntity>

    /**
     * Pending + Failed local rows. Used to surface non-confirmed activity in
     * the home list — the JNI feed only carries on-chain (confirmed) txs, so
     * pending/failed rows must be merged in from cache.
     */
    @Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network AND status IN ('PENDING', 'FAILED')")
    suspend fun getNonConfirmedByWallet(walletId: String, network: String): List<TransactionEntity>

    /**
     * Legacy PENDING `transactions` rows that have no matching `pending_broadcasts`
     * entry — i.e. they predate the broadcast state machine (#115). Used at init
     * to reconcile against the live chain so the user doesn't see stuck-pending
     * rows from before the fix landed.
     */
    @Query(
        "SELECT txHash FROM transactions " +
            "WHERE walletId = :walletId AND network = :network AND status = 'PENDING' " +
            "AND txHash NOT IN (SELECT txHash FROM pending_broadcasts)"
    )
    suspend fun getOrphanPendingHashes(walletId: String, network: String): List<String>

    @Query("DELETE FROM transactions WHERE walletId = :walletId AND network = :network")
    suspend fun deleteByWalletAndNetwork(walletId: String, network: String)

    @Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, timestamp DESC")
    fun getTransactionsPaged(walletId: String, network: String): PagingSource<Int, TransactionEntity>

    @Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network AND direction IN ('in', 'dao_unlock') ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, timestamp DESC")
    fun getReceivedTransactionsPaged(walletId: String, network: String): PagingSource<Int, TransactionEntity>

    @Query("SELECT * FROM transactions WHERE walletId = :walletId AND network = :network AND direction IN ('out', 'self', 'dao_deposit', 'dao_withdraw') ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, timestamp DESC")
    fun getSentTransactionsPaged(walletId: String, network: String): PagingSource<Int, TransactionEntity>
}
