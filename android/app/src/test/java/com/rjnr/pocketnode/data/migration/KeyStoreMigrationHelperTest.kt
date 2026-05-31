package com.rjnr.pocketnode.data.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.MIGRATION_4_5
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyStoreMigrationHelperTest {

    private lateinit var db: AppDatabase
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var encryptionManager: KeystoreEncryptionManager
    private lateinit var migrationPrefs: SharedPreferences
    private lateinit var helper: KeyStoreMigrationHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        keyMaterialDao = db.keyMaterialDao()
        encryptionManager = KeystoreEncryptionManager.createForTest()
        migrationPrefs = context.getSharedPreferences("test_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()

        helper = KeyStoreMigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `migrateWallet encrypts and stores key material`() = runTest {
        helper.migrateWallet("wallet-1", "aabb".repeat(16), "word1 word2 word3", "mnemonic", false)

        val entity = keyMaterialDao.getByWalletId("wallet-1")
        assertNotNull(entity)
        assertEquals("mnemonic", entity!!.walletType)
        assertFalse(entity.mnemonicBackedUp)

        // Decrypt and verify round-trip
        val decryptCipher = encryptionManager.newDecryptCipher(entity.iv)
        val decryptedKey = encryptionManager.decryptWithCipher(decryptCipher, entity.encryptedPrivateKey)
        assertEquals("aabb".repeat(16), String(decryptedKey, Charsets.UTF_8))
    }

    @Test
    fun `migrateWallet with null mnemonic stores null`() = runTest {
        helper.migrateWallet("wallet-2", "ccdd".repeat(16), null, "raw_key", false)

        val entity = keyMaterialDao.getByWalletId("wallet-2")
        assertNotNull(entity)
        assertNull(entity!!.encryptedMnemonic)
        assertEquals("raw_key", entity.walletType)
    }

    @Test
    fun `isMigrationComplete returns false initially`() {
        assertFalse(helper.isMigrationComplete())
    }

    @Test
    fun `markMigrationComplete sets flag`() {
        helper.markMigrationComplete()
        assertTrue(helper.isMigrationComplete())
    }

    @Test
    fun `readDecryptedKey returns correct data after migration`() = runTest {
        helper.migrateWallet("wallet-1", "aabb".repeat(16), "word1 word2 word3", "mnemonic", true)

        val result = helper.readDecryptedKey("wallet-1")
        assertNotNull(result)
        assertEquals("aabb".repeat(16), result!!.privateKeyHex)
        assertEquals("word1 word2 word3", result.mnemonic)
        assertEquals("mnemonic", result.walletType)
        assertTrue(result.mnemonicBackedUp)
    }

    @Test
    fun `readDecryptedKey returns null for missing wallet`() = runTest {
        val result = helper.readDecryptedKey("nonexistent")
        assertNull(result)
    }

    @Test
    fun `readDecryptedKey throws on V2 row when no cipher supplied`() = runTest {
        // Seed a V1 row, then migrate it to V2 via the V2 helper. After
        // migration the V1 readDecryptedKey overload must refuse with
        // V2KeyMaterialRequiresAuthException rather than silently returning
        // garbage from the V1 cipher applied to a V2 ciphertext.
        helper.migrateWallet("alpha", "aa".repeat(32), "alpha mnemonic", "mnemonic", false)
        val v2Helper = KeystoreV2MigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
        v2Helper.migrateWallet("alpha", encryptionManager.newEncryptCipherV2()).getOrThrow()

        try {
            helper.readDecryptedKey("alpha")
            fail("Expected V2KeyMaterialRequiresAuthException")
        } catch (e: V2KeyMaterialRequiresAuthException) {
            assertTrue(e.message?.contains("alpha") == true)
        }
    }

    @Test
    fun `readDecryptedKey with cipher returns V2 bundle data`() = runTest {
        helper.migrateWallet("beta", "bb".repeat(32), "beta mnemonic", "mnemonic", true)
        val v2Helper = KeystoreV2MigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
        v2Helper.migrateWallet("beta", encryptionManager.newEncryptCipherV2()).getOrThrow()

        val entity = keyMaterialDao.getByWalletId("beta")!!
        val decryptCipher = encryptionManager.newDecryptCipherV2(entity.iv)

        val result = helper.readDecryptedKey("beta", decryptCipher)
        assertNotNull(result)
        assertEquals("bb".repeat(32), result!!.privateKeyHex)
        assertEquals("beta mnemonic", result.mnemonic)
        assertEquals("mnemonic", result.walletType)
        assertTrue(result.mnemonicBackedUp)
    }

    @Test
    fun `readDecryptedKey with cipher still reads V1 rows`() = runTest {
        // A caller that doesn't know which kdf the wallet is on can pass a
        // V2 cipher unconditionally during migration. V1 rows fall through
        // to the V1 path; the supplied cipher is unused but not an error.
        helper.migrateWallet("gamma", "cc".repeat(32), null, "raw_key", false)
        val v2Cipher = encryptionManager.newDecryptCipherV2(ByteArray(12))

        val result = helper.readDecryptedKey("gamma", v2Cipher)
        assertNotNull(result)
        assertEquals("cc".repeat(32), result!!.privateKeyHex)
        assertNull(result.mnemonic)
    }

    // -- Flag-only path (v1.7.1 hotfix regression) --
    //
    // The crash that motivated v1.7.1 was KeyManager routing plaintext-flag
    // reads through readDecryptedKey, which throws V2KeyMaterialRequiresAuthException
    // after the keystore migration. These tests pin the flag-only accessors so
    // that future refactors don't reintroduce a decryption call on the
    // flag-read path.

    @Test
    fun `getMnemonicBackedUpFlag returns flag on V2 row without decrypting`() = runTest {
        helper.migrateWallet("v2-flag", "aa".repeat(32), "v2 mnemonic", "mnemonic", true)
        val v2Helper = KeystoreV2MigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
        v2Helper.migrateWallet("v2-flag", encryptionManager.newEncryptCipherV2()).getOrThrow()
        // kdfVersion is now 2; readDecryptedKey would throw here.

        assertEquals(true, helper.getMnemonicBackedUpFlag("v2-flag"))
    }

    @Test
    fun `getWalletTypeFlag returns walletType on V2 row without decrypting`() = runTest {
        helper.migrateWallet("v2-type", "bb".repeat(32), "type mnemonic", "mnemonic", false)
        val v2Helper = KeystoreV2MigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
        v2Helper.migrateWallet("v2-type", encryptionManager.newEncryptCipherV2()).getOrThrow()

        assertEquals("mnemonic", helper.getWalletTypeFlag("v2-type"))
    }

    @Test
    fun `getMnemonicBackedUpFlag returns null for missing wallet`() = runTest {
        assertNull(helper.getMnemonicBackedUpFlag("nonexistent"))
    }

    @Test
    fun `getWalletTypeFlag returns null for missing wallet`() = runTest {
        assertNull(helper.getWalletTypeFlag("nonexistent"))
    }

    @Test
    fun `setMnemonicBackedUpFlag flips flag on V2 row without re-encrypting`() = runTest {
        helper.migrateWallet("v2-set", "cc".repeat(32), "set mnemonic", "mnemonic", false)
        val v2Helper = KeystoreV2MigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
        v2Helper.migrateWallet("v2-set", encryptionManager.newEncryptCipherV2()).getOrThrow()

        // Capture the V2 ciphertext + iv so we can prove the setter didn't touch them.
        val before = keyMaterialDao.getByWalletId("v2-set")!!
        assertEquals(2, before.kdfVersion)
        assertFalse(before.mnemonicBackedUp)

        val updated = helper.setMnemonicBackedUpFlag("v2-set", true)
        assertTrue(updated)

        val after = keyMaterialDao.getByWalletId("v2-set")!!
        assertTrue(after.mnemonicBackedUp)
        // Bundle ciphertext and iv unchanged — flag flip is plaintext-only.
        assertArrayEquals(before.encryptedPrivateKey, after.encryptedPrivateKey)
        assertArrayEquals(before.iv, after.iv)
        assertEquals(before.kdfVersion, after.kdfVersion)
    }

    @Test
    fun `setMnemonicBackedUpFlag returns false for missing wallet`() = runTest {
        assertFalse(helper.setMnemonicBackedUpFlag("nonexistent", true))
    }
}
