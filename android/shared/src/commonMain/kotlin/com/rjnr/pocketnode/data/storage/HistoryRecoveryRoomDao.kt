package com.rjnr.pocketnode.data.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Display-only historical hints. Never convert this row to a transaction receipt. */
@Entity(tableName = "history_archive", primaryKeys = ["walletId", "network", "txHash"])
data class HistoryArchiveRow(
    val walletId: String, val network: String, val txHash: String,
    val blockNumberHint: String?, val timestampHint: Long?, val balanceChangeHint: String?,
    val directionHint: String?, val feeHint: String?,
)

/** Historical DAO locators only; not an inclusion, withdrawal or balance proof. */
@Entity(tableName = "dao_history_archive", primaryKeys = ["walletId", "network", "txHash", "outputIndex"])
data class DaoHistoryArchiveRow(
    val walletId: String, val network: String, val txHash: String, val outputIndex: String,
    val depositBlockHint: Long?, val withdrawBlockHint: Long?, val capacityHint: Long?, val statusHint: String?,
)

/** Unknown original coverage is always genesis. Old progress is diagnostic only. */
@Entity(tableName = "history_recovery_jobs", primaryKeys = ["walletId", "network"])
data class HistoryRecoveryJob(
    val walletId: String, val network: String, val generation: String,
    val startBlock: Long, val previousLightStart: Long?, val previousSavedBlock: Long?,
    val targetBlock: Long?, val scriptJson: String?, val status: String,
)

@Entity(tableName = "history_recovery_epochs", primaryKeys = ["network"])
data class HistoryRecoveryEpoch(val network: String, val generation: String)

/** Discovery obligations retain real ownership and script identity independently of wallet jobs. */
@Entity(tableName = "history_script_recovery_jobs", primaryKeys = ["parentWalletId", "network", "scriptJson"])
data class HistoryScriptRecoveryJob(
    val parentWalletId: String, val network: String, val scriptJson: String,
    val generation: String, val targetBlock: Long?, val status: String,
)

/** Shared SQL contract used by both databases. Capture runs before evidence erasure. */
@Dao
interface HistoryRecoveryRoomDao {
    @Query("SELECT * FROM history_script_recovery_jobs WHERE network = :network")
    suspend fun scripts(network: String): List<HistoryScriptRecoveryJob>

    @Query("INSERT OR IGNORE INTO history_script_recovery_jobs VALUES (:parentWalletId, :network, :scriptJson, :generation, NULL, 'PENDING')")
    suspend fun ensureScript(parentWalletId: String, network: String, scriptJson: String, generation: String)

    @Query("UPDATE history_script_recovery_jobs SET targetBlock = COALESCE(targetBlock, NULLIF(:target, 0)), status = 'REGISTERED' WHERE parentWalletId = :parentWalletId AND network = :network AND scriptJson = :scriptJson AND generation = :generation AND status != 'COMPLETE'")
    suspend fun registerScript(parentWalletId: String, network: String, scriptJson: String, generation: String, target: Long)

    @Query("UPDATE history_script_recovery_jobs SET status = 'COMPLETE' WHERE parentWalletId = :parentWalletId AND network = :network AND scriptJson = :scriptJson AND generation = :generation AND targetBlock IS NOT NULL AND targetBlock <= :tip AND status = 'REGISTERED'")
    suspend fun completeScript(parentWalletId: String, network: String, scriptJson: String, generation: String, tip: Long): Int

    @Query("UPDATE history_script_recovery_jobs SET generation = :generation, targetBlock = NULL, status = 'PENDING' WHERE network = :network")
    suspend fun resetScripts(network: String, generation: String)

    @Query("SELECT * FROM dao_history_archive WHERE walletId = :walletId AND network = :network")
    suspend fun daoHints(walletId: String, network: String): List<DaoHistoryArchiveRow>

    @Query("INSERT OR IGNORE INTO history_recovery_jobs VALUES (:walletId, :network, :generation, 0, NULL, NULL, NULL, NULL, 'PENDING')")
    suspend fun ensureWallet(walletId: String, network: String, generation: String)

    @Query("SELECT * FROM history_archive WHERE walletId = :walletId AND network = :network AND txHash NOT IN (SELECT txHash FROM transactions WHERE walletId = :walletId AND network = :network AND status != 'UNVERIFIED')")
    fun observeArchive(walletId: String, network: String): Flow<List<HistoryArchiveRow>>

    @Query("SELECT * FROM history_archive WHERE walletId = :walletId AND network = :network AND txHash NOT IN (SELECT txHash FROM transactions WHERE walletId = :walletId AND network = :network AND status != 'UNVERIFIED')")
    suspend fun archive(walletId: String, network: String): List<HistoryArchiveRow>

    @Query("SELECT * FROM history_recovery_jobs WHERE walletId = :walletId AND network = :network")
    suspend fun job(walletId: String, network: String): HistoryRecoveryJob?

    @Query("SELECT * FROM history_recovery_jobs WHERE network = :network AND status != 'COMPLETE'")
    suspend fun pending(network: String): List<HistoryRecoveryJob>

    @Query("SELECT * FROM history_recovery_jobs WHERE walletId = :walletId AND network = :network")
    fun observeJob(walletId: String, network: String): Flow<HistoryRecoveryJob?>

    @Query("SELECT generation FROM history_recovery_epochs WHERE network = :network")
    suspend fun generation(network: String): String?

    @Query("INSERT OR IGNORE INTO history_archive SELECT walletId, network, txHash, CASE WHEN status = 'UNVERIFIED' THEN NULL ELSE blockNumber END, CASE WHEN timestamp > 0 THEN timestamp ELSE NULL END, CASE WHEN status = 'UNVERIFIED' THEN NULL ELSE balanceChange END, NULLIF(direction, ''), CASE WHEN status = 'UNVERIFIED' THEN NULL ELSE fee END FROM transactions WHERE network = :network AND NOT (isLocal = 1 AND status IN ('PENDING', 'FAILED'))")
    suspend fun captureArchive(network: String)

    @Query("INSERT OR IGNORE INTO history_recovery_jobs SELECT walletId, network, '', 0, lightStartBlockNumber, localSavedBlockNumber, NULL, NULL, 'PENDING' FROM sync_progress WHERE network = :network")
    suspend fun captureProgress(network: String)

    @Query("INSERT OR IGNORE INTO history_recovery_jobs SELECT DISTINCT walletId, network, '', 0, NULL, NULL, NULL, NULL, 'PENDING' FROM transactions WHERE network = :network")
    suspend fun captureIdentities(network: String)

    @Query("UPDATE history_recovery_jobs SET generation = :generation, startBlock = 0, targetBlock = NULL, scriptJson = NULL, status = 'PENDING' WHERE network = :network")
    suspend fun resetGeneration(network: String, generation: String)

    @Query("INSERT OR REPLACE INTO history_recovery_epochs VALUES (:network, :generation)")
    suspend fun recordEpoch(network: String, generation: String)

    /** True means a schema/generation handoff, even when old preference markers match. */
    @Transaction
    suspend fun prepare(network: String, generation: String): Boolean {
        if (generation(network) == generation) return false
        captureArchive(network)
        captureProgress(network)
        captureIdentities(network)
        resetGeneration(network, generation)
        resetScripts(network, generation)
        recordEpoch(network, generation)
        return true
    }

    @Query("UPDATE history_recovery_jobs SET targetBlock = COALESCE(targetBlock, NULLIF(:target, 0)), scriptJson = :scriptJson, status = 'REGISTERED' WHERE walletId = :walletId AND network = :network AND generation = :generation AND status != 'COMPLETE'")
    suspend fun registered(walletId: String, network: String, generation: String, target: Long, scriptJson: String)

    @Query("UPDATE history_recovery_jobs SET status = 'COMPLETE' WHERE walletId = :walletId AND network = :network AND generation = :generation AND scriptJson = :scriptJson AND targetBlock IS NOT NULL AND targetBlock <= :tip AND status = 'REGISTERED'")
    suspend fun complete(walletId: String, network: String, generation: String, scriptJson: String, tip: Long)
}
