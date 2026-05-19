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
}
