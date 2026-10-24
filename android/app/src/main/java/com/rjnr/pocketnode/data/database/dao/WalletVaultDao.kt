package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rjnr.pocketnode.data.database.entity.*

/** SQL bounds blobs before Room allocates them. Invalid sizes become undecodable empty input. */
@Dao
interface WalletVaultDao {
    @Query("SELECT walletId, generation, profileGeneration, CASE WHEN length(CAST(envelope AS BLOB)) BETWEEN 1 AND 524288 THEN envelope ELSE X'' END AS envelope, cleanupPending FROM wallet_vault WHERE walletId = :walletId")
    suspend fun get(walletId: String): WalletVaultEntity?
    @Query("SELECT walletId, generation, profileGeneration, CASE WHEN length(CAST(envelope AS BLOB)) BETWEEN 1 AND 524288 THEN envelope ELSE X'' END AS envelope, cleanupPending FROM wallet_vault ORDER BY walletId")
    suspend fun all(): List<WalletVaultEntity>
    @Query("SELECT id, CASE WHEN length(CAST(envelope AS BLOB)) BETWEEN 1 AND 524288 THEN envelope ELSE X'' END AS envelope FROM wallet_passphrase_profile WHERE id = 1")
    suspend fun profile(): WalletPassphraseProfileEntity?
    @Query("SELECT * FROM wallet_vault_legacy WHERE walletId = :walletId")
    suspend fun legacy(walletId: String): WalletVaultLegacyEntity?
    @Insert suspend fun insert(row: WalletVaultEntity)
    @Update suspend fun update(row: WalletVaultEntity): Int
    @Insert suspend fun insertProfile(row: WalletPassphraseProfileEntity)
    @Update suspend fun updateProfile(row: WalletPassphraseProfileEntity): Int
    @Insert suspend fun archive(row: WalletVaultLegacyEntity)
    @Query("DELETE FROM wallet_vault WHERE walletId = :walletId")
    suspend fun deleteVault(walletId: String)
    @Query("DELETE FROM wallet_vault_legacy WHERE walletId = :walletId")
    suspend fun deleteLegacy(walletId: String)
    @Query("SELECT walletId FROM wallet_vault WHERE cleanupPending = 1 ORDER BY walletId")
    suspend fun pendingCleanup(): List<String>
    @Query("UPDATE wallet_vault SET cleanupPending = 0 WHERE walletId = :walletId AND generation = :generation")
    suspend fun markCleanupComplete(walletId: String, generation: Long): Int
}
