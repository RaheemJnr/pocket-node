package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rjnr.pocketnode.data.database.entity.WalletPendingCleanupEntity

@Dao
interface WalletPendingCleanupDao {
    /** Record the latest authorized removal inside the wallet-removal transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(intent: WalletPendingCleanupEntity)
    @Query("SELECT * FROM wallet_pending_cleanup WHERE walletId = :walletId")
    suspend fun get(walletId: String): WalletPendingCleanupEntity?
    @Query("SELECT * FROM wallet_pending_cleanup ORDER BY walletId")
    suspend fun all(): List<WalletPendingCleanupEntity>
    @Query("DELETE FROM wallet_pending_cleanup WHERE walletId = :walletId")
    suspend fun delete(walletId: String)
}
