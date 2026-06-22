package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rjnr.pocketnode.data.database.entity.PendingDaoWithdrawEntity

@Dao
interface PendingDaoWithdrawDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingDaoWithdrawEntity)

    @Query("SELECT * FROM pending_dao_withdraws WHERE walletId = :walletId AND network = :network")
    suspend fun getByWalletAndNetwork(walletId: String, network: String): List<PendingDaoWithdrawEntity>

    @Query("DELETE FROM pending_dao_withdraws WHERE depositTxHash = :depositTxHash AND depositIndex = :depositIndex")
    suspend fun deleteByDeposit(depositTxHash: String, depositIndex: String)

    @Query("DELETE FROM pending_dao_withdraws WHERE network = :network")
    suspend fun deleteByNetwork(network: String)

    @Query("DELETE FROM pending_dao_withdraws")
    suspend fun deleteAll()
}
