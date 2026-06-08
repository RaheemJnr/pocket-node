package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.MIGRATION_4_5
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [KeyManager] after the #289 chunk 3 surgery.
 *
 * Pre-chunk-3, KeyManager owned wallet creation (generateWallet,
 * generateWalletWithMnemonic, importWallet, importWalletFromMnemonic).
 * Those persistence paths moved to [WalletKeyWriter] (covered by
 * `WalletKeyWriterTest`). What remains here is the pure-crypto surface
 * (encodePlaintextBundle) and the existing flag-only read/write helpers
 * for the legacy "default" wallet bucket and per-wallet API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyManagerTest {

    private lateinit var keyManager: KeyManager
    private lateinit var mnemonicManager: MnemonicManager
    private lateinit var backupManager: KeyBackupManager
    private lateinit var db: AppDatabase
    private lateinit var migrationHelper: KeyStoreMigrationHelper

    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        .split(" ")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
        // Use plain SharedPreferences for testing (EncryptedSharedPreferences needs real KeyStore)
        keyManager.testPrefs = context.getSharedPreferences("test_keys", Context.MODE_PRIVATE)
        val backupDir = File(context.cacheDir, "test_key_backups")
        backupDir.deleteRecursively()
        backupManager = KeyBackupManager(backupDir)
        backupManager.kdfIterations = 1000
        keyManager.keyBackupManager = backupManager

        // Set up Room-backed KeyStoreMigrationHelper
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        val encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("test_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        migrationHelper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs)
        keyManager.keyStoreMigrationHelper = migrationHelper

        keyManager.deleteWallet()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Pure-crypto bundle producer (#289 chunk 3.1) --

    @Test
    fun `encodePlaintextBundle is deterministic for a given key and mnemonic`() {
        val key = ByteArray(32) { 0xAA.toByte() }
        val mnemonic = testMnemonic
        val a = keyManager.encodePlaintextBundle(key, mnemonic)
        val b = keyManager.encodePlaintextBundle(key, mnemonic)
        assertEquals(a, b)
        assertEquals("aa".repeat(32), a.privateKeyHex)
        assertEquals(testMnemonic.joinToString(" "), a.mnemonic)
    }

    @Test
    fun `encodePlaintextBundle null mnemonic produces null in bundle (raw key wallet)`() {
        val key = ByteArray(32) { 0xBB.toByte() }
        val bundle = keyManager.encodePlaintextBundle(key, mnemonic = null)
        assertEquals("bb".repeat(32), bundle.privateKeyHex)
        assertNull(bundle.mnemonic)
    }

    // -- ESP fallback read path (legacy single-wallet, kept for pre-migration installs) --

    @Test
    fun `ESP fallback works when Room has no data`() = runTest {
        // Simulate a pre-migration user: keys exist only in ESP, not Room.
        // Write directly to the testPrefs (standing in for EncryptedSharedPreferences).
        val context = ApplicationProvider.getApplicationContext<Context>()
        val espPrefs = context.getSharedPreferences("test_keys", Context.MODE_PRIVATE)
        espPrefs.edit()
            .putString("private_key", "ab".repeat(32))
            .putString("wallet_type", KeyManager.WALLET_TYPE_MNEMONIC)
            .putString("mnemonic_words", testMnemonic.joinToString(" "))
            .putBoolean("mnemonic_backed_up", false)
            .commit()

        // Room is empty — reads should fall back to ESP
        assertTrue(keyManager.hasWallet())
        assertEquals(testMnemonic, keyManager.getMnemonic())
        assertEquals(KeyManager.WALLET_TYPE_MNEMONIC, keyManager.getWalletType())
        assertFalse(keyManager.hasMnemonicBackup())
    }
}
