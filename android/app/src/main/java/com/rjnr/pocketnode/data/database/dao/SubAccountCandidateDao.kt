package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /** Source state and recovery authorization are checked by the same SQL write. */
    @Query("UPDATE sub_account_candidates SET state = :state WHERE parentWalletId = :parentId AND derivationPath = :derivationPath AND scriptArgs = :scriptArgs AND state != 'RESTORED' AND EXISTS (SELECT 1 FROM history_script_recovery_jobs WHERE parentWalletId = :parentId AND network = :network AND scriptJson = :scriptJson AND generation = :generation AND status = 'COMPLETE' AND generation = (SELECT generation FROM history_recovery_epochs WHERE network = :network))")
    suspend fun applyRecoveryState(parentId: String, derivationPath: String, state: String, network: String, generation: String, scriptJson: String, scriptArgs: String)

    @Query("UPDATE history_script_recovery_jobs SET status = 'COMPLETE' WHERE parentWalletId = :parentId AND network = :network AND scriptJson = :scriptJson AND generation = :generation AND targetBlock IS NOT NULL AND targetBlock <= :tip AND status = 'REGISTERED' AND generation = (SELECT generation FROM history_recovery_epochs WHERE network = :network)")
    suspend fun completeRecoveryJob(parentId: String, network: String, scriptJson: String, generation: String, tip: Long): Int

    @Transaction
    suspend fun completeRecoveryState(parentId: String, derivationPath: String, state: String, network: String, generation: String, scriptJson: String, scriptArgs: String, tip: Long): Boolean {
        if (completeRecoveryJob(parentId, network, scriptJson, generation, tip) != 1) return false
        // A write failure rolls back completion, so a future poll can retry.
        applyRecoveryState(parentId, derivationPath, state, network, generation, scriptJson, scriptArgs)
        return true
    }

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
