package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rjnr.pocketnode.data.database.entity.PendingDaoUnlockEntity

@Dao
interface PendingDaoUnlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingDaoUnlockEntity)

    @Query("SELECT * FROM pending_dao_unlocks WHERE walletId = :walletId AND network = :network")
    suspend fun getByWalletAndNetwork(walletId: String, network: String): List<PendingDaoUnlockEntity>

    @Query(
        "DELETE FROM pending_dao_unlocks " +
            "WHERE withdrawingTxHash = :withdrawingTxHash AND withdrawingIndex = :withdrawingIndex"
    )
    suspend fun deleteByWithdrawingCell(withdrawingTxHash: String, withdrawingIndex: String)

    @Query("DELETE FROM pending_dao_unlocks WHERE walletId = :walletId AND network = :network")
    suspend fun deleteByWalletAndNetwork(walletId: String, network: String)

    @Query("DELETE FROM pending_dao_unlocks WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM pending_dao_unlocks")
    suspend fun deleteAll()
}
