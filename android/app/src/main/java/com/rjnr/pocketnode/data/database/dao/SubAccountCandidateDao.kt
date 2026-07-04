package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity

@Dao
interface SubAccountCandidateDao {

    /** IGNORE: re-import of the same parent must not clobber FOUND/RESTORED states. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(candidates: List<SubAccountCandidateEntity>)

    @Query("SELECT * FROM sub_account_candidates WHERE parentWalletId = :parentId ORDER BY accountIndex ASC")
    suspend fun getForParent(parentId: String): List<SubAccountCandidateEntity>

    @Query("SELECT * FROM sub_account_candidates WHERE state = :state")
    suspend fun getByState(state: String): List<SubAccountCandidateEntity>

    /** Flow — drives the "Found N sub-accounts" restore banner. */
    @Query("SELECT * FROM sub_account_candidates WHERE state = :state ORDER BY parentWalletId, accountIndex")
    fun observeByState(state: String): kotlinx.coroutines.flow.Flow<List<SubAccountCandidateEntity>>

    @Query(
        "UPDATE sub_account_candidates SET state = :state " +
            "WHERE parentWalletId = :parentId AND accountIndex = :accountIndex"
    )
    suspend fun updateState(parentId: String, accountIndex: Int, state: String)

    /**
     * Record the scan start for a registration, keeping the LOWEST block ever
     * used (deepest coverage). 0 means never registered.
     */
    @Query(
        "UPDATE sub_account_candidates SET registeredFromBlock = :fromBlock " +
            "WHERE parentWalletId = :parentId AND accountIndex = :accountIndex " +
            "AND (registeredFromBlock = 0 OR registeredFromBlock > :fromBlock)"
    )
    suspend fun updateRegisteredFrom(parentId: String, accountIndex: Int, fromBlock: Long)

    @Query("DELETE FROM sub_account_candidates WHERE parentWalletId = :parentId")
    suspend fun deleteForParent(parentId: String)
}
