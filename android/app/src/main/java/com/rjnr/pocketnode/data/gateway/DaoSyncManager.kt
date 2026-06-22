package com.rjnr.pocketnode.data.gateway

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.DaoCellDao
import com.rjnr.pocketnode.data.database.dao.PendingDaoWithdrawDao
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DaoSyncManager @Inject constructor(
    private val headerCacheDao: HeaderCacheDao,
    private val daoCellDao: DaoCellDao,
    private val pendingDaoWithdrawDao: PendingDaoWithdrawDao,
) {
    // --- Header cache (permanent — block headers are immutable) ---

    suspend fun getCachedHeader(blockHash: String): HeaderCacheEntity? {
        return try {
            headerCacheDao.getByBlockHash(blockHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read header cache", e)
            null
        }
    }

    suspend fun cacheHeader(header: JniHeaderView, network: String) {
        try {
            headerCacheDao.upsert(HeaderCacheEntity.from(header, network))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write header cache", e)
        }
    }

    // --- DAO cell lifecycle ---

    suspend fun getActiveDeposits(network: String, walletId: String): List<DaoCellEntity> {
        require(walletId.isNotBlank()) { "walletId is required" }
        return try {
            daoCellDao.getActiveByWalletAndNetwork(walletId, network)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read active deposits", e)
            emptyList()
        }
    }

    suspend fun getCompletedDeposits(network: String, walletId: String): List<DaoCellEntity> {
        require(walletId.isNotBlank()) { "walletId is required" }
        return try {
            daoCellDao.getCompletedByWalletAndNetwork(walletId, network)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read completed deposits", e)
            emptyList()
        }
    }

    suspend fun getByOutPoint(txHash: String, index: String): DaoCellEntity? {
        return try {
            daoCellDao.getByOutPoint(txHash, index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read DAO cell", e)
            null
        }
    }

    suspend fun upsertDaoCell(entity: DaoCellEntity) {
        try {
            daoCellDao.upsert(entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upsert DAO cell", e)
        }
    }

    suspend fun upsertDaoCells(entities: List<DaoCellEntity>) {
        try {
            daoCellDao.upsertAll(entities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to upsert DAO cells", e)
        }
    }

    suspend fun updateStatus(txHash: String, index: String, status: String) {
        try {
            daoCellDao.updateStatus(txHash, index, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update DAO cell status", e)
        }
    }

    suspend fun insertPendingDeposit(txHash: String, capacity: Long, network: String, index: String = "0x0", walletId: String) {
        require(walletId.isNotBlank()) { "walletId is required" }
        try {
            daoCellDao.upsert(
                DaoCellEntity(
                    txHash = txHash,
                    index = index,
                    capacity = capacity,
                    status = "DEPOSITING",
                    depositBlockNumber = 0L,
                    depositBlockHash = "",
                    depositEpochHex = null,
                    withdrawBlockNumber = null,
                    withdrawBlockHash = null,
                    withdrawEpochHex = null,
                    compensation = 0L,
                    unlockEpochHex = null,
                    depositTimestamp = System.currentTimeMillis(),
                    network = network,
                    lastUpdatedAt = System.currentTimeMillis(),
                    walletId = walletId
                )
            )
            Log.d(TAG, "Pending DAO deposit cached: $txHash")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache pending deposit", e)
        }
    }

    // --- Cleanup ---

    suspend fun clearForNetwork(network: String) {
        try {
            headerCacheDao.deleteByNetwork(network)
            daoCellDao.deleteByNetwork(network)
            pendingDaoWithdrawDao.deleteByNetwork(network)
            Log.d(TAG, "DAO caches cleared for $network")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear DAO caches", e)
        }
    }

    suspend fun clearAll() {
        try {
            headerCacheDao.deleteAll()
            daoCellDao.deleteAll()
            pendingDaoWithdrawDao.deleteAll()
            Log.d(TAG, "All DAO caches cleared")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear DAO caches", e)
        }
    }

    companion object {
        private const val TAG = "DaoSyncManager"
    }
}
