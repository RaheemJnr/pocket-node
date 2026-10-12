package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.crypto.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.crypto.Cipher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RecoveredKeyRestorer] against a real in-memory Room database and the real
 * [WalletKeyWriter] / [WalletKeyReader] (test Keystore keys). Only the
 * screen-lock prompt ([AuthManager]) and the backup files are mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecoveredKeyRestorerTest {

    private lateinit var db: AppDatabase
    private lateinit var walletDao: WalletDao
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var v1Helper: KeyStoreMigrationHelper
    private lateinit var writer: WalletKeyWriter
    private lateinit var reader: WalletKeyReader
    private lateinit var authManager: AuthManager
    private lateinit var keyBackupManager: KeyBackupManager
    private lateinit var keyManager: KeyManager
    private lateinit var restorer: RecoveredKeyRestorer
    private val activity = mockk<FragmentActivity>(relaxed = true)

    private val keyHex = "11".repeat(32)
    private val otherKeyHex = "22".repeat(32)
    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletDao = db.walletDao()
        keyMaterialDao = db.keyMaterialDao()
        val encryption = KeystoreEncryptionManager.createForTest()
        val prefs = ctx.getSharedPreferences("test_recovered_key_restorer", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        v1Helper = KeyStoreMigrationHelper(keyMaterialDao, encryption, prefs, NoopLogger)
        val v2Helper = KeystoreV2MigrationHelper(keyMaterialDao, encryption, prefs, logger = NoopLogger)
        authManager = mockk(relaxed = true)
        every { authManager.getSessionPin() } returns null
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers { AuthManager.CipherAuthResult.Success(args[1] as Cipher) }
        keyBackupManager = mockk(relaxed = true)
        writer = WalletKeyWriter(keyMaterialDao, v2Helper, v1Helper, encryption, authManager, keyBackupManager, NoopLogger)
        reader = WalletKeyReader(keyMaterialDao, v1Helper, encryption, authManager, keyBackupManager, NoopLogger)
        keyManager = mockk(relaxed = true)
        restorer = newRestorer(reader)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newRestorer(keyReader: WalletKeyReader) = RecoveredKeyRestorer(
        walletDao, keyMaterialDao, writer, keyReader, authManager, keyManager, NoopLogger,
    )

    private fun secureLock(present: Boolean) {
        every { authManager.isBiometricEnrolled() } returns present
        every { authManager.hasDeviceCredential() } returns present
    }

    private suspend fun insertWallet(walletId: String, keyHex: String) {
        val info = WalletDerivation.walletInfo(keyHex.hexToByteArray())
        walletDao.insert(
            WalletEntity(
                walletId = walletId,
                name = walletId,
                type = KeyManager.WALLET_TYPE_MNEMONIC,
                derivationPath = "m/44'/309'/0'/0/0",
                parentWalletId = null,
                mainnetAddress = info.mainnetAddress,
                testnetAddress = info.testnetAddress,
            )
        )
    }

    private fun material(keyHex: String = this.keyHex) = KeyMaterial(
        privateKey = keyHex,
        mnemonic = mnemonic,
        walletType = KeyManager.WALLET_TYPE_MNEMONIC,
        mnemonicBackedUp = true,
    )

    @Test
    fun `with a screen lock the keys are written at V2 and read back to the wallet address`() = runTest {
        secureLock(true)
        insertWallet("w1", keyHex)

        val outcome = restorer.restore(activity, "w1", material())

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
        assertEquals(2, keyMaterialDao.getKdfVersion("w1"))
        // One prompt to write, one to read back.
        coVerify(exactly = 2) { authManager.authenticateForCipher(activity, any(), any(), any()) }
        val read = reader.readKeyMaterial(activity, "w1", "t", "s") as WalletKeyReader.MaterialResult.Success
        assertArrayEquals(keyHex.hexToByteArray(), read.privateKey)
        assertEquals(mnemonic, read.mnemonic)
        assertEquals(KeyManager.WALLET_TYPE_MNEMONIC, read.walletType)
        assertTrue(read.mnemonicBackedUp)
    }

    @Test
    fun `without a screen lock the keys are written at V1, the strength of the store they replace`() = runTest {
        secureLock(false)
        insertWallet("w1", keyHex)

        val outcome = restorer.restore(activity, "w1", material())

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
        assertEquals(1, keyMaterialDao.getKdfVersion("w1"))
        assertEquals(keyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
        assertEquals(mnemonic, v1Helper.readDecryptedKey("w1")!!.mnemonic)
    }

    @Test
    fun `each wallet gets its own keys`() = runTest {
        secureLock(false)
        insertWallet("w1", keyHex)
        insertWallet("w2", otherKeyHex)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(keyHex)))
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w2", material(otherKeyHex)))

        assertEquals(keyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
        assertEquals(otherKeyHex, v1Helper.readDecryptedKey("w2")!!.privateKeyHex)
    }

    @Test
    fun `a backup whose key does not derive the wallet address writes nothing`() = runTest {
        secureLock(true)
        insertWallet("w1", keyHex)

        val outcome = restorer.restore(activity, "w1", material(otherKeyHex))

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
        coVerify(exactly = 0) { authManager.authenticateForCipher(any(), any(), any(), any()) }
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `a key already stored for the wallet is never overwritten`() = runTest {
        secureLock(false)
        insertWallet("w1", keyHex)
        // A different key is already stored under w1.
        v1Helper.migrateWallet("w1", otherKeyHex, null, KeyManager.WALLET_TYPE_RAW_KEY, false)

        val outcome = restorer.restore(activity, "w1", material())

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertEquals(otherKeyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
    }

    @Test
    fun `a backup with no wallet row is skipped and its file kept`() = runTest {
        secureLock(true)

        val outcome = restorer.restore(activity, "orphan", material())

        assertEquals(RecoveredKeyRestorer.Outcome.NoWalletRow, outcome)
        assertNull(keyMaterialDao.getByWalletId("orphan"))
        assertNull(walletDao.getById("orphan"))
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `a cancelled write prompt stores nothing`() = runTest {
        secureLock(true)
        insertWallet("w1", keyHex)
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } returns AuthManager.CipherAuthResult.Cancelled

        val outcome = restorer.restore(activity, "w1", material())

        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
    }

    @Test
    fun `a cancelled read-back keeps the row, and a retry only verifies it`() = runTest {
        secureLock(true)
        insertWallet("w1", keyHex)
        var prompts = 0
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers {
            prompts++
            if (prompts == 2) AuthManager.CipherAuthResult.Cancelled
            else AuthManager.CipherAuthResult.Success(args[1] as Cipher)
        }

        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, restorer.restore(activity, "w1", material()))
        val written = keyMaterialDao.getByWalletId("w1")
        assertNotNull(written)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material()))
        // The retry read the existing row (prompt 3) and wrote nothing new.
        assertEquals(3, prompts)
        assertArrayEquals(written!!.encryptedPrivateKey, keyMaterialDao.getByWalletId("w1")!!.encryptedPrivateKey)
    }

    @Test
    fun `a read-back that does not derive the address removes the row it wrote`() = runTest {
        secureLock(false)
        insertWallet("w1", keyHex)
        val lyingReader = mockk<WalletKeyReader>()
        coEvery { lyingReader.readPrivateKey(any(), any(), any(), any()) } returns
            WalletKeyReader.Result.Success(otherKeyHex.hexToByteArray())

        val outcome = newRestorer(lyingReader).restore(activity, "w1", material())

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `an unreadable stored row is a failure and the row it wrote is removed`() = runTest {
        secureLock(false)
        insertWallet("w1", keyHex)
        val failingReader = mockk<WalletKeyReader>()
        coEvery { failingReader.readPrivateKey(any(), any(), any(), any()) } returns
            WalletKeyReader.Result.NotAvailable("decrypt failed")

        val outcome = newRestorer(failingReader).restore(activity, "w1", material())

        assertTrue(outcome is RecoveredKeyRestorer.Outcome.Failed)
        assertNull(keyMaterialDao.getByWalletId("w1"))
    }

    @Test
    fun `clearCorruptionFlag resets the key manager flag`() {
        restorer.clearCorruptionFlag()
        @Suppress("DEPRECATION")
        verify(exactly = 1) { keyManager.resetCorruptionFlag() }
    }
}
