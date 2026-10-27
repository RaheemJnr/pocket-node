package com.rjnr.pocketnode.data.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** Chain evidence only. Pending broadcasts, user settings and keys are never touched. */
@Dao
interface VerificationCacheRoomDao {
    @Query("DELETE FROM sync_progress WHERE network = :network")
    suspend fun clearProgress(network: String)

    @Query("DELETE FROM balance_cache WHERE network = :network")
    suspend fun clearBalances(network: String)

    @Query("UPDATE transactions SET status = 'UNVERIFIED', confirmations = 0, blockNumber = '0x0', blockHash = '', blockTimestampHex = NULL, balanceChange = '0', fee = '', fee_shannons = NULL WHERE network = :network AND NOT (isLocal = 1 AND status IN ('PENDING', 'FAILED'))")
    suspend fun quarantineHistory(network: String)

    @Throws(Exception::class)
    @Transaction
    suspend fun invalidate(network: String) {
        clearProgress(network)
        clearBalances(network)
        quarantineHistory(network)
    }
}
