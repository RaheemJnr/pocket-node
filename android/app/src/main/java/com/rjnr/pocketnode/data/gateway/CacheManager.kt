package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.database.dao.BalanceCacheDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow surface used by BroadcastWatchdog. Lets tests fake without
 * mocking the full CacheManager.
 */
interface TransactionStatusUpdater {
    suspend fun updateTransactionStatus(hash: String, status: String)
}

@Singleton
class CacheManager @Inject constructor(
    private val transactionDao: TransactionDao,
    private val balanceCacheDao: BalanceCacheDao,
    private val logger: Logger,
) : TransactionStatusUpdater {
    // --- Balance cache ---

    suspend fun getCachedBalance(network: String, walletId: String = ""): BalanceResponse? {
        return try {
            balanceCacheDao.getByWalletAndNetwork(walletId, network)?.toBalanceResponse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to read balance cache", e)
            null
        }
    }

    suspend fun cacheBalance(response: BalanceResponse, network: String, walletId: String = "") {
        try {
            balanceCacheDao.upsert(BalanceCacheEntity.from(response, network, walletId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to write balance cache", e)
        }
    }

    // --- Transaction cache ---

    suspend fun cacheTransactions(records: List<TransactionRecord>, network: String, walletId: String = "") {
        try {
            // A confirmed row that could not resolve its own fee must not
            // erase the planned fee we wrote when the user sent it — the
            // insert below is REPLACE, so carry the cached value forward.
            val knownFees = if (records.any { it.feeShannons == null }) {
                transactionDao.getKnownFees(records.map { it.txHash })
                    .associate { it.txHash to it.feeShannons }
            } else {
                emptyMap()
            }
            val entities = records.map { record ->
                TransactionEntity.fromTransactionRecord(
                    txHash = record.txHash,
                    blockNumber = record.blockNumber,
                    blockHash = record.blockHash,
                    timestamp = record.timestamp,
                    balanceChange = record.balanceChange,
                    direction = record.direction,
                    fee = record.fee,
                    confirmations = record.confirmations,
                    blockTimestampHex = record.blockTimestampHex,
                    network = network,
                    walletId = walletId,
                    feeShannons = record.feeShannons ?: knownFees[record.txHash]
                )
            }
            transactionDao.insertAll(entities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to write transaction cache", e)
        }
    }

    suspend fun insertPendingTransaction(
        txHash: String,
        network: String,
        walletId: String = "",
        balanceChange: String = "0x0",
        direction: String = "out",
        fee: String = "0x0",
        feeShannons: Long? = null
    ) {
        try {
            transactionDao.insert(
                TransactionEntity(
                    txHash = txHash,
                    blockNumber = "",
                    blockHash = "",
                    timestamp = System.currentTimeMillis(),
                    balanceChange = balanceChange,
                    direction = direction,
                    fee = fee,
                    confirmations = 0,
                    blockTimestampHex = null,
                    network = network,
                    status = "PENDING",
                    isLocal = true,
                    cachedAt = System.currentTimeMillis(),
                    walletId = walletId,
                    feeShannons = feeShannons
                )
            )
            logger.d(TAG, "Pending transaction cached in Room: $txHash")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to cache pending tx", e)
        }
    }

    override suspend fun updateTransactionStatus(hash: String, status: String) {
        // Propagate failures — BroadcastWatchdog runs this BEFORE the terminal
        // CAS specifically so a DB hiccup leaves the pending row recoverable.
        // Swallowing here would silently break that contract.
        transactionDao.updateStatusOnly(hash, status)
    }

    /** Legacy PENDING `transactions` rows with no `pending_broadcasts` entry (#115). */
    suspend fun getOrphanPendingHashes(walletId: String, network: String): List<String> =
        transactionDao.getOrphanPendingHashes(walletId, network)

    suspend fun deleteTransaction(txHash: String) {
        try {
            transactionDao.deleteByHash(txHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to delete transaction $txHash", e)
        }
    }

    /**
     * Returns local PENDING + FAILED rows not present in [excludeHashes]. Used
     * by `GatewayRepository.getTransactions` to merge non-confirmed activity
     * into the JNI-derived (confirmed-only) feed. FAILED rows are written by
     * `BroadcastWatchdog` after the timeout ladder fires.
     */
    suspend fun getPendingNotIn(network: String, excludeHashes: Set<String>, walletId: String = ""): List<TransactionRecord> {
        return try {
            transactionDao.getNonConfirmedByWallet(walletId, network)
                .filter { it.isLocal && it.txHash !in excludeHashes }
                .map { it.toTransactionRecord() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to read pending transactions", e)
            emptyList()
        }
    }

    // --- Cleanup ---

    suspend fun clearAll() {
        try {
            transactionDao.deleteAll()
            balanceCacheDao.deleteAll()
            logger.d(TAG, "All caches cleared")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Failed to clear caches", e)
        }
    }

    companion object {
        private const val TAG = "CacheManager"
    }
}
