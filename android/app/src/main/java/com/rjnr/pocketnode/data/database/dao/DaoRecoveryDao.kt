package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.DaoRecoveryEntity
import com.rjnr.pocketnode.data.database.entity.DaoRecoveryRetryEntity

@Dao
interface DaoRecoveryDao {
    // Deliberately includes foreign wallets/genesis: collisions fail closed at consumers.
    @Query("SELECT * FROM dao_recovery_guards WHERE network = :network AND transactionHash = :hash AND outputIndex = :index")
    suspend fun guards(network: String, hash: String, index: Long): List<DaoRecoveryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGuard(guard: DaoRecoveryEntity)
    @Query("SELECT * FROM dao_recovery_retry_budget WHERE walletId = :wallet AND network = :network AND genesisHash = :genesis AND transactionHash = :hash AND outputIndex = :index")
    suspend fun budget(wallet: String, network: String, genesis: String, hash: String, index: Long): DaoRecoveryRetryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBudget(budget: DaoRecoveryRetryEntity)
    @Query("DELETE FROM dao_recovery_guards WHERE walletId = :wallet")
    suspend fun deleteGuardsForWallet(wallet: String)
    @Query("DELETE FROM dao_recovery_retry_budget WHERE walletId = :wallet")
    suspend fun deleteBudgetsForWallet(wallet: String)
}
