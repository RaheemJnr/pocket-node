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

    /**
     * Insert a row, aborting (throwing `android.database.sqlite.SQLiteConstraintException`)
     * if a row with the same primary key already exists. Used by
     * `KeystoreV2MigrationHelper.writeNewV2Row` to make the "refuse to overwrite an
     * existing wallet" guard atomic at the SQLite level rather than relying on a
     * read-then-write check that can race under concurrent writers (#289 polish).
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOrAbort(entity: KeyMaterialEntity)

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

    /**
     * Peek the kdfVersion of a single wallet without loading its
     * ciphertext columns. Used by V2-aware call sites to decide whether
     * a BiometricPrompt CryptoObject step is needed before reading the
     * private key. Returns null when the wallet has no key_material row.
     */
    @Query("SELECT kdfVersion FROM key_material WHERE walletId = :walletId")
    suspend fun getKdfVersion(walletId: String): Int?

    /**
     * Plaintext flag columns. Safe to read without an authenticated
     * Cipher — these don't touch the encrypted key/mnemonic ciphertext.
     * Used by routing/decision code (e.g. "does this wallet need a
     * mnemonic backup prompt?") that must work on V2 rows pre-unlock.
     */
    @Query("SELECT mnemonicBackedUp FROM key_material WHERE walletId = :walletId")
    suspend fun getMnemonicBackedUp(walletId: String): Boolean?

    @Query("SELECT walletType FROM key_material WHERE walletId = :walletId")
    suspend fun getWalletType(walletId: String): String?
}
