package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RecoveredKeyRestorer] against a real in-memory Room database, the real
 * [WalletKeyWriter] / [WalletKeyReader] (test Keystore keys), the real
 * BIP-39 derivation and the real [RecoveryStateStore]. Only the screen-lock
 * prompt ([AuthManager]) and the backup files are mocked.
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
    private lateinit var state: RecoveryStateStore
    private lateinit var restorer: RecoveredKeyRestorer
    private val mnemonicManager = MnemonicManager()
    private val activity = mockk<FragmentActivity>(relaxed = true)

    private val phrase =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val phraseKeyHex = derive(0)
    private val accountOneKeyHex = derive(1)
    private val otherKeyHex = "22".repeat(32)

    private fun derive(account: Int): String {
        val seed = mnemonicManager.mnemonicToSeed(phrase.split(" "))
        return mnemonicManager.derivePrivateKey(seed, accountIndex = account).toHexStringNoPrefix()
    }

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
        state = RecoveryStateStore(ctx)
        state.clear()
        restorer = newRestorer(reader)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newRestorer(keyReader: WalletKeyReader) = RecoveredKeyRestorer(
        walletDao, keyMaterialDao, writer, keyReader, authManager, keyManager, mnemonicManager, state, NoopLogger,
    )

    private fun secureLock(present: Boolean) {
        every { authManager.isBiometricEnrolled() } returns present
        every { authManager.hasDeviceCredential() } returns present
    }

    private suspend fun insertWallet(
        walletId: String,
        keyHex: String,
        derivationPath: String? = "m/44'/309'/0'/0/0",
        accountIndex: Int = 0,
        blankTestnet: Boolean = false,
    ) {
        val info = WalletDerivation.walletInfo(keyHex.hexToByteArray())
        walletDao.insert(
            WalletEntity(
                walletId = walletId,
                name = walletId,
                type = KeyManager.WALLET_TYPE_MNEMONIC,
                derivationPath = derivationPath,
                parentWalletId = null,
                accountIndex = accountIndex,
                mainnetAddress = info.mainnetAddress,
                testnetAddress = if (blankTestnet) "" else info.testnetAddress,
            )
        )
    }

    private fun material(keyHex: String = phraseKeyHex, mnemonic: String? = phrase) = KeyMaterial(
        privateKey = keyHex,
        mnemonic = mnemonic,
        walletType = if (mnemonic != null) KeyManager.WALLET_TYPE_MNEMONIC else KeyManager.WALLET_TYPE_RAW_KEY,
        mnemonicBackedUp = true,
    )

    @Test
    fun `with a screen lock the keys are written at V2 and read back to the wallet`() = runTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)

        val outcome = restorer.restore(activity, "w1", material(), allowV1 = false)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
        assertEquals(2, keyMaterialDao.getKdfVersion("w1"))
        // One prompt to write, one to read back.
        coVerify(exactly = 2) { authManager.authenticateForCipher(activity, any(), any(), any()) }
        val read = reader.readKeyMaterial(activity, "w1", "t", "s") as WalletKeyReader.MaterialResult.Success
        assertArrayEquals(phraseKeyHex.hexToByteArray(), read.privateKey)
        assertEquals(phrase, read.mnemonic)
        assertTrue(read.mnemonicBackedUp)
        assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `without a screen lock and with consent the keys are written at V1`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)

        val outcome = restorer.restore(activity, "w1", material(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
        assertEquals(1, keyMaterialDao.getKdfVersion("w1"))
        assertEquals(phraseKeyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
        assertEquals(phrase, v1Helper.readDecryptedKey("w1")!!.mnemonic)
    }

    @Test
    fun `without a screen lock and without consent nothing is written`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)

        val outcome = restorer.restore(activity, "w1", material(), allowV1 = false)

        assertEquals(RecoveredKeyRestorer.Outcome.NeedsNoLockConsent, outcome)
        assertTrue(restorer.needsNoLockConsent())
        assertNull(keyMaterialDao.getByWalletId("w1"))
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `each wallet gets its own keys`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        insertWallet("w2", otherKeyHex, derivationPath = null)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), true))
        assertEquals(
            RecoveredKeyRestorer.Outcome.Restored,
            restorer.restore(activity, "w2", material(otherKeyHex, mnemonic = null), true),
        )

        assertEquals(phraseKeyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
        assertEquals(otherKeyHex, v1Helper.readDecryptedKey("w2")!!.privateKeyHex)
    }

    @Test
    fun `a phrase is checked at the wallet's own derivation path`() = runTest {
        secureLock(false)
        insertWallet("acct1", accountOneKeyHex, derivationPath = "m/44'/309'/1'/0/0", accountIndex = 1)

        val outcome = restorer.restore(activity, "acct1", material(accountOneKeyHex), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
    }

    @Test
    fun `a backup whose key does not give the wallet's lock script writes nothing`() = runTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)

        val outcome = restorer.restore(activity, "w1", material(otherKeyHex), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
        coVerify(exactly = 0) { authManager.authenticateForCipher(any(), any(), any(), any()) }
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `a backup whose phrase does not derive its key writes nothing`() = runTest {
        secureLock(true)
        // The row and the key agree; the phrase in the backup does not.
        insertWallet("w1", otherKeyHex)

        val outcome = restorer.restore(activity, "w1", material(otherKeyHex, mnemonic = phrase), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
        coVerify(exactly = 0) { authManager.authenticateForCipher(any(), any(), any(), any()) }
    }

    @Test
    fun `a blank stored testnet address is tolerated, the mainnet one is checked`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex, blankTestnet = true)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), true))
    }

    @Test
    fun `a key already stored for the wallet is never overwritten`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        // A different key is already stored under w1.
        v1Helper.migrateWallet("w1", otherKeyHex, null, KeyManager.WALLET_TYPE_RAW_KEY, false)

        val outcome = restorer.restore(activity, "w1", material(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertEquals(otherKeyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
    }

    @Test
    fun `the V1 write refuses to replace an existing row`() = runTest {
        v1Helper.migrateWallet("w1", otherKeyHex, null, KeyManager.WALLET_TYPE_RAW_KEY, false)
        try {
            v1Helper.migrateWallet("w1", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true, overwrite = false)
            fail("expected the insert to be refused")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals(otherKeyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
    }

    @Test
    fun `a backup with no wallet row is skipped and its file kept`() = runTest {
        secureLock(true)

        val outcome = restorer.restore(activity, "orphan", material(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.NoWalletRow, outcome)
        assertNull(keyMaterialDao.getByWalletId("orphan"))
        assertNull(walletDao.getById("orphan"))
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `a wallet with no key row and no backup is NoBackup and stays missing`() = runTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)

        assertEquals(RecoveredKeyRestorer.Outcome.NoBackup, restorer.restore(activity, "w1", null, true))
        assertEquals(listOf("w1"), restorer.missingWallets().map { it.walletId })
        assertFalse(restorer.completeIfDone())
    }

    @Test
    fun `a cancelled write prompt stores nothing`() = runTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } returns AuthManager.CipherAuthResult.Cancelled

        val outcome = restorer.restore(activity, "w1", material(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
    }

    @Test
    fun `a row cancelled at read-back stays missing until a later attempt verifies it`() = runTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)
        var prompts = 0
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers {
            prompts++
            if (prompts == 2) AuthManager.CipherAuthResult.Cancelled
            else AuthManager.CipherAuthResult.Success(args[1] as Cipher)
        }

        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, restorer.restore(activity, "w1", material(), true))
        val written = keyMaterialDao.getByWalletId("w1")
        assertNotNull(written)
        // The row exists but is not verified: recovery is not complete.
        assertEquals(listOf("w1"), restorer.missingWallets().map { it.walletId })
        assertFalse(restorer.completeIfDone())
        assertTrue(state.unverifiedWalletIds().contains("w1"))

        // A later attempt, even without the backup, verifies the stored row.
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", null, true))
        assertEquals(3, prompts)
        assertArrayEquals(written!!.encryptedPrivateKey, keyMaterialDao.getByWalletId("w1")!!.encryptedPrivateKey)
        assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `a read-back that does not give the lock script removes the row it wrote`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        val lyingReader = mockk<WalletKeyReader>()
        coEvery { lyingReader.readPrivateKey(any(), any(), any(), any()) } returns
            WalletKeyReader.Result.Success(otherKeyHex.hexToByteArray())

        val outcome = newRestorer(lyingReader).restore(activity, "w1", material(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `a row replaced by someone else after the write is never removed`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        val racingReader = mockk<WalletKeyReader>()
        coEvery { racingReader.readPrivateKey(any(), any(), any(), any()) } coAnswers {
            // Another writer replaces the row between the write and the check.
            v1Helper.migrateWallet("w1", otherKeyHex, null, KeyManager.WALLET_TYPE_RAW_KEY, false)
            WalletKeyReader.Result.NotAvailable("decrypt failed")
        }

        val outcome = newRestorer(racingReader).restore(activity, "w1", material(), allowV1 = true)

        assertTrue(outcome is RecoveredKeyRestorer.Outcome.Failed)
        assertEquals(otherKeyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
    }

    @Test
    fun `an unreadable stored row is a failure and the row it wrote is removed`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        val failingReader = mockk<WalletKeyReader>()
        coEvery { failingReader.readPrivateKey(any(), any(), any(), any()) } returns
            WalletKeyReader.Result.NotAvailable("decrypt failed")

        val outcome = newRestorer(failingReader).restore(activity, "w1", material(), allowV1 = true)

        assertTrue(outcome is RecoveredKeyRestorer.Outcome.Failed)
        assertNull(keyMaterialDao.getByWalletId("w1"))
    }

    @Test
    fun `completion needs every wallet row, sub-accounts included, to have verified keys`() = runTest {
        secureLock(false)
        insertWallet("root", phraseKeyHex)
        insertWallet("sub", accountOneKeyHex, derivationPath = "m/44'/309'/1'/0/0", accountIndex = 1)
        restorer.markIncomplete()

        restorer.restore(activity, "root", material(), true)
        assertFalse(restorer.completeIfDone())
        assertTrue(state.isIncomplete())
        @Suppress("DEPRECATION")
        verify(exactly = 0) { keyManager.resetCorruptionFlag() }

        restorer.restore(activity, "sub", material(accountOneKeyHex, mnemonic = null), true)
        assertTrue(restorer.completeIfDone())
        assertFalse(state.isIncomplete())
        @Suppress("DEPRECATION")
        verify(exactly = 1) { keyManager.resetCorruptionFlag() }
    }

    @Test
    fun `no wallet rows at all is never complete`() = runTest {
        assertFalse(restorer.completeIfDone())
    }

    @Test
    fun `with a screen lock no consent is needed and the keys go to V2`() = runTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)

        val outcome = restorer.restore(activity, "w1", material(), allowV1 = false)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
        assertFalse(restorer.needsNoLockConsent())
        assertEquals(2, keyMaterialDao.getKdfVersion("w1"))
    }
}
