package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room and production-cost vault encryption. Credential entry is an explicit native-UI double. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletKeyWriterTest {

    private lateinit var db: AppDatabase
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var writer: WalletKeyWriter
    private lateinit var activity: FragmentActivity
    private lateinit var vaultFixture: WalletVaultIntegrationFixture

    private val bundle = WalletKeyBundle(
        privateKeyHex = MnemonicManager().mnemonicToPrivateKey("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")).joinToString("") { "%02x".format(it) },
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keyMaterialDao = db.keyMaterialDao()
        vaultFixture = WalletVaultIntegrationFixture(db)
        writer = vaultFixture.writer
        activity = vaultFixture.activity
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `persistNewWallet writes a row at kdfVersion=3 with no session PIN (first-wallet path)`() = walletOperationTest {

        vaultFixture.phrase()
        val result = writer.persistNewWallet(activity, "w-1", bundle, "mnemonic", false)

        assertEquals(WalletKeyWriter.Result.Success, result)
        val entity = keyMaterialDao.getByWalletId("w-1")
        assertNotNull(entity)
        assertEquals(3, entity!!.kdfVersion)
    }

    // No device lock is needed for the independent passphrase vault.
    @Test
    fun `persistNewWallet protects independently when no secure lock`() = walletOperationTest {

        vaultFixture.phrase()
        val result = writer.persistNewWallet(activity, "w-nolock", bundle, "mnemonic", false)

        assertEquals(WalletKeyWriter.Result.Success, result)
        assertEquals(3, keyMaterialDao.getKdfVersion("w-nolock"))
        assertTrue(vaultFixture.store.verifyPassphrase(vaultFixture.pass.toCharArray()))
    }

    // Old activity-free persistence cannot bypass passphrase entry.
    @Test
    fun `persistNewWalletV1Fallback refuses activity free creation`() = walletOperationTest {
        val result = writer.persistNewWalletV1Fallback("w-v1", bundle, "mnemonic", false)

        assertTrue(result is WalletKeyWriter.Result.WriteFailed)
        assertNull(keyMaterialDao.getByWalletId("w-v1"))
    }

    @Test
    fun `persistNewWallet preserves weak backup until explicit authenticated cleanup`() = walletOperationTest {
        val f = vaultFixture
        f.wallet("w-2")
        val oldBackup = java.io.File(f.context.cacheDir, "integration-backups/w-2.enc")
        oldBackup.parentFile!!.mkdirs()
        val original = WalletKeyWriterTest::class.java.getResourceAsStream("/legacy-scalar2-pin111111.enc")!!.use { it.readBytes() }
        oldBackup.writeBytes(original)
        f.phrase()
        val result = writer.persistNewWallet(activity, "w-2", f.bundle, "raw_key", false)
        assertTrue(result is WalletKeyWriter.Result.WriteFailed)
        assertEquals(3, keyMaterialDao.getByWalletId("w-2")!!.kdfVersion)
        org.junit.Assert.assertArrayEquals(original, oldBackup.readBytes())
        assertTrue("w-2" in f.store.pendingLegacyCleanup())
        val recovery = com.rjnr.pocketnode.data.vault.LegacyBackupRecovery(f.db, f.backup, f.coordinator, f.prompt,
            com.rjnr.pocketnode.data.vault.WalletSecretValidator(MnemonicManager()), f.keyManager, f.reader, f.legacy)
        f.phrase("111111"); f.phrase()
        assertTrue(recovery.recover(activity, "w-2") is com.rjnr.pocketnode.data.vault.VaultAccessResult.Success)
        org.junit.Assert.assertFalse(oldBackup.exists())
        assertTrue(f.store.pendingLegacyCleanup().isEmpty())
    }

    @Test
    fun `persistNewWallet returns Cancelled when user dismisses passphrase setup, no Room write`() = walletOperationTest {

        vaultFixture.phrase(null)
        val result = writer.persistNewWallet(activity, "w-3", bundle, "mnemonic", false)

        assertEquals(WalletKeyWriter.Result.Cancelled, result)
        assertNull(keyMaterialDao.getByWalletId("w-3"))
    }

    @Test
    fun `persistNewWallet rolls back Room row when verified vault persistence fails`() = walletOperationTest {

        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_vault BEFORE INSERT ON wallet_vault BEGIN SELECT RAISE(ABORT, 'simulated full disk'); END")
        vaultFixture.phrase()
        val result = writer.persistNewWallet(activity, "w-4", bundle, "mnemonic", false)

        assertTrue(result is WalletKeyWriter.Result.WriteFailed)
        // Rollback should have removed the key_material row so we don't leave
        // an orphan Room row without a matching backup blob.
        assertNull(keyMaterialDao.getByWalletId("w-4"))
    }
}
