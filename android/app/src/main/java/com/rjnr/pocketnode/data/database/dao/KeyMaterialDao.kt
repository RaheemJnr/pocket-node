package com.rjnr.pocketnode.data.database.dao

import androidx.room.*
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity

@Dao
interface KeyMaterialDao {

    @Query("SELECT * FROM key_material WHERE walletId = :walletId")
    suspend fun getByWalletId(walletId: String): KeyMaterialEntity?

    @Query("SELECT * FROM key_material")
    suspend fun getAll(): List<KeyMaterialEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KeyMaterialEntity)

    @Query("DELETE FROM key_material WHERE walletId = :walletId")
    suspend fun delete(walletId: String)

    @Query("SELECT COUNT(*) FROM key_material")
    suspend fun count(): Int

    @Query("UPDATE key_material SET mnemonicBackedUp = :backedUp, updatedAt = :updatedAt WHERE walletId = :walletId")
    suspend fun updateMnemonicBackedUp(walletId: String, backedUp: Boolean, updatedAt: Long)

    /**
     * Wallets still encrypted under the legacy V1 keystore key. The v1.6.x →
     * v1.7.0 KeystoreV2 migration iterates this list one wallet at a time,
     * each driven by its own BiometricPrompt-bound CryptoObject.
     */
    @Query("SELECT walletId FROM key_material WHERE kdfVersion = 1 ORDER BY walletId")
    suspend fun getV1WalletIds(): List<String>

    @Query("SELECT COUNT(*) FROM key_material WHERE kdfVersion = 1")
    suspend fun countV1Wallets(): Int

    @Query("SELECT COUNT(*) FROM key_material WHERE kdfVersion = 2")
    suspend fun countV2Wallets(): Int
}
