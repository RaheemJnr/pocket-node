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

    @Query(
        "UPDATE sub_account_candidates SET state = :state " +
            "WHERE parentWalletId = :parentId AND accountIndex = :accountIndex"
    )
    suspend fun updateState(parentId: String, accountIndex: Int, state: String)

    @Query("DELETE FROM sub_account_candidates WHERE parentWalletId = :parentId")
    suspend fun deleteForParent(parentId: String)
}
