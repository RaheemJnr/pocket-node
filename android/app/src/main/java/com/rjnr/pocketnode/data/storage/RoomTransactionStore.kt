package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.gateway.CacheManager
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room binding for the shared [TransactionStore] seam (M3 #3, widened in #4).
 *
 * Every member but [getBlockNumbers] forwards to [CacheManager] rather than
 * reimplement its fee-carry-forward, its swallow-and-log reads and writes, and
 * its deliberately propagating [updateTransactionStatus]. The one query
 * `SyncCoordinator` needs goes straight to the DAO, as it always has.
 */
@Singleton
class RoomTransactionStore @Inject constructor(
    private val dao: TransactionDao,
    private val cacheManager: CacheManager,
) : TransactionStore {

    override suspend fun getBlockNumbers(walletId: String, network: String): List<String> =
        dao.getBlockNumbers(walletId, network)

    override suspend fun cacheTransactions(
        records: List<TransactionRecord>,
        network: String,
        walletId: String,
    ) = cacheManager.cacheTransactions(records, network, walletId = walletId)

    override suspend fun getPendingNotIn(
        network: String,
        excludeHashes: Set<String>,
        walletId: String,
    ): List<TransactionRecord> =
        cacheManager.getPendingNotIn(network, excludeHashes, walletId = walletId)

    override suspend fun getOrphanPendingHashes(walletId: String, network: String): List<String> =
        cacheManager.getOrphanPendingHashes(walletId, network)

    override suspend fun updateTransactionStatus(hash: String, status: String) =
        cacheManager.updateTransactionStatus(hash, status)

    override suspend fun insertPendingTransaction(
        txHash: String,
        network: String,
        walletId: String,
        balanceChange: String,
        direction: String,
        fee: String,
        feeShannons: Long?,
    ) = cacheManager.insertPendingTransaction(
        txHash = txHash,
        network = network,
        walletId = walletId,
        balanceChange = balanceChange,
        direction = direction,
        fee = fee,
        feeShannons = feeShannons,
    )

    override suspend fun deleteTransaction(txHash: String) =
        cacheManager.deleteTransaction(txHash)

    // Any network, as public main's retryBroadcast read it: txHash is unique
    // across networks in practice, and deleteTransaction is unscoped too.
    override suspend fun cachedDirectionAndFee(txHash: String, network: String): Pair<String, Long?>? =
        dao.getByTxHash(txHash)?.let { it.direction to it.feeShannons }
}
