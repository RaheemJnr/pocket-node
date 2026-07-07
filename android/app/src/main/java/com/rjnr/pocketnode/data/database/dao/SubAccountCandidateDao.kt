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

    /** All candidate script args across parents — feeds the #382 known-scripts set. */
    @Query("SELECT scriptArgs FROM sub_account_candidates")
    suspend fun getAllScriptArgs(): List<String>

    /** Flow — drives the "Found N sub-accounts" restore banner. */
    @Query("SELECT * FROM sub_account_candidates WHERE state = :state ORDER BY parentWalletId, accountIndex")
    fun observeByState(state: String): kotlinx.coroutines.flow.Flow<List<SubAccountCandidateEntity>>

    @Query(
        "UPDATE sub_account_candidates SET state = :state " +
            "WHERE parentWalletId = :parentId AND derivationPath = :derivationPath"
    )
    suspend fun updateState(parentId: String, derivationPath: String, state: String)

    /**
     * Record the scan start for a registration, keeping the LOWEST block ever
     * used (deepest coverage). 0 means never registered.
     */
    @Query(
        "UPDATE sub_account_candidates SET registeredFromBlock = :fromBlock " +
            "WHERE parentWalletId = :parentId AND derivationPath = :derivationPath " +
            "AND (registeredFromBlock = 0 OR registeredFromBlock > :fromBlock)"
    )
    suspend fun updateRegisteredFrom(parentId: String, derivationPath: String, fromBlock: Long)

    /**
     * Re-arm retired chain-axis slots for an explicit re-scan (#382). EMPTY
     * is a verdict about a PAST scan; a user-triggered "Scan Other Addresses"
     * must look again (funds may have arrived since, or the verdict came from
     * a different network's scan). Account-axis slots (accountIndex > 0) are
     * left alone — their EMPTY still gates the restore banner.
     * Returns rows re-armed.
     */
    @Query(
        "UPDATE sub_account_candidates SET state = 'PENDING' " +
            "WHERE parentWalletId = :parentId AND accountIndex = 0 AND state = 'EMPTY'"
    )
    suspend fun reArmEmptyChainSlots(parentId: String): Int

    @Query("DELETE FROM sub_account_candidates WHERE parentWalletId = :parentId")
    suspend fun deleteForParent(parentId: String)
}
