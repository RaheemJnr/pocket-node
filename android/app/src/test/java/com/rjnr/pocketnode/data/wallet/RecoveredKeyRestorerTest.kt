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
        walletDao, keyMaterialDao, writer, keyReader, authManager, keyManager, mnemonicManager, state,
        keyBackupManager, NoopLogger,
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
        type: String = KeyManager.WALLET_TYPE_MNEMONIC,
        parentWalletId: String? = null,
    ) {
        val info = WalletDerivation.walletInfo(keyHex.hexToByteArray())
        walletDao.insert(
            WalletEntity(
                walletId = walletId,
                name = walletId,
                type = type,
                derivationPath = derivationPath,
                parentWalletId = parentWalletId,
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
    fun `nothing to recover at all counts as complete and clears a stale marker`() = runTest {
        restorer.markIncomplete()
        every { keyBackupManager.hasAnyBackups() } returns false

        assertTrue(restorer.completeIfDone())
        assertFalse(state.isIncomplete())
    }

    @Test
    fun `no wallet rows but backup files left is not complete`() = runTest {
        restorer.markIncomplete()
        every { keyBackupManager.hasAnyBackups() } returns true

        assertFalse(restorer.completeIfDone())
        assertTrue(state.isIncomplete())
    }

    @Test
    fun `no wallet rows but a key row left is not complete`() = runTest {
        v1Helper.migrateWallet("stray", otherKeyHex, null, KeyManager.WALLET_TYPE_RAW_KEY, false)
        every { keyBackupManager.hasAnyBackups() } returns false

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

    // --- Re-key a missing wallet from its phrase or private key ---

    private val words = phrase.split(" ")
    private val otherPhrase =
        "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    @Test
    fun `a missing root wallet is re-keyed from its phrase, verified, and recovery completes`() = runTest {
        secureLock(true)
        insertWallet("root", phraseKeyHex)
        restorer.markIncomplete()

        val result = restorer.rekeyFromPhrase(activity, "root", words, allowV1 = false)

        assertEquals(mapOf("root" to RecoveredKeyRestorer.Outcome.Restored), (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes)
        val read = reader.readKeyMaterial(activity, "root", "t", "s") as WalletKeyReader.MaterialResult.Success
        assertArrayEquals(phraseKeyHex.hexToByteArray(), read.privateKey)
        assertEquals(phrase, read.mnemonic)
        assertTrue(restorer.completeIfDone())
        assertFalse(state.isIncomplete())
    }

    @Test
    fun `a phrase that does not belong to the wallet writes nothing`() = runTest {
        secureLock(true)
        insertWallet("root", phraseKeyHex)

        val result = restorer.rekeyFromPhrase(activity, "root", otherPhrase, allowV1 = false)

        assertEquals(RecoveredKeyRestorer.RekeyResult.NotThisWallet, result)
        assertNull(keyMaterialDao.getByWalletId("root"))
        coVerify(exactly = 0) { authManager.authenticateForCipher(any(), any(), any(), any()) }
    }

    @Test
    fun `an invalid phrase writes nothing`() = runTest {
        insertWallet("root", phraseKeyHex)

        val result = restorer.rekeyFromPhrase(activity, "root", words.dropLast(1) + "zzzz", allowV1 = true)

        assertEquals(RecoveredKeyRestorer.RekeyResult.InvalidInput, result)
        assertNull(keyMaterialDao.getByWalletId("root"))
    }

    @Test
    fun `a missing raw-key wallet is re-keyed from its private key`() = runTest {
        secureLock(false)
        insertWallet("raw", otherKeyHex, derivationPath = null, type = KeyManager.WALLET_TYPE_RAW_KEY)

        val result = restorer.rekeyFromPrivateKey(activity, "raw", otherKeyHex.hexToByteArray(), allowV1 = true)

        assertEquals(mapOf("raw" to RecoveredKeyRestorer.Outcome.Restored), (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes)
        val stored = v1Helper.readDecryptedKey("raw")!!
        assertEquals(otherKeyHex, stored.privateKeyHex)
        assertNull(stored.mnemonic)
        assertEquals(KeyManager.WALLET_TYPE_RAW_KEY, stored.walletType)
    }

    @Test
    fun `a private key that does not belong to the wallet writes nothing`() = runTest {
        secureLock(false)
        insertWallet("raw", otherKeyHex, derivationPath = null, type = KeyManager.WALLET_TYPE_RAW_KEY)

        val result = restorer.rekeyFromPrivateKey(activity, "raw", phraseKeyHex.hexToByteArray(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.RekeyResult.NotThisWallet, result)
        assertNull(keyMaterialDao.getByWalletId("raw"))
    }

    @Test
    fun `a missing sub-account is re-keyed from its parent's phrase and the parent is not touched`() = runTest {
        secureLock(false)
        insertWallet("root", phraseKeyHex)
        v1Helper.migrateWallet("root", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)
        val rootRow = keyMaterialDao.getByWalletId("root")!!
        insertWallet("sub", accountOneKeyHex, derivationPath = "m/44'/309'/1'/0/0", accountIndex = 1, parentWalletId = "root")

        val result = restorer.rekeyFromPhrase(activity, "sub", words, allowV1 = true)

        assertEquals(mapOf("sub" to RecoveredKeyRestorer.Outcome.Restored), (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes)
        val sub = v1Helper.readDecryptedKey("sub")!!
        assertEquals(accountOneKeyHex, sub.privateKeyHex)
        // Sub-accounts store no phrase.
        assertNull(sub.mnemonic)
        assertArrayEquals(rootRow.iv, keyMaterialDao.getByWalletId("root")!!.iv)
        assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `a parent's phrase re-keys the parent and its missing sub-account, each verified`() = runTest {
        secureLock(true)
        insertWallet("root", phraseKeyHex)
        insertWallet("sub", accountOneKeyHex, derivationPath = "m/44'/309'/1'/0/0", accountIndex = 1, parentWalletId = "root")
        insertWallet("raw", otherKeyHex, derivationPath = null, type = KeyManager.WALLET_TYPE_RAW_KEY)

        val result = restorer.rekeyFromPhrase(activity, "root", words, allowV1 = false)

        assertEquals(
            mapOf("root" to RecoveredKeyRestorer.Outcome.Restored, "sub" to RecoveredKeyRestorer.Outcome.Restored),
            (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes,
        )
        // Two prompts each (write, read-back); the raw-key wallet is untouched.
        coVerify(exactly = 4) { authManager.authenticateForCipher(any(), any(), any(), any()) }
        assertNull(keyMaterialDao.getByWalletId("raw"))
        assertEquals(listOf("raw"), restorer.missingWallets().map { it.walletId })
    }

    @Test
    fun `a wallet that is not missing is never re-keyed`() = runTest {
        secureLock(false)
        insertWallet("root", phraseKeyHex)
        v1Helper.migrateWallet("root", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)

        assertEquals(
            RecoveredKeyRestorer.RekeyResult.NotMissing,
            restorer.rekeyFromPhrase(activity, "root", words, allowV1 = true),
        )
    }

    @Test
    fun `without a screen lock a re-key needs the consent`() = runTest {
        secureLock(false)
        insertWallet("root", phraseKeyHex)

        val result = restorer.rekeyFromPhrase(activity, "root", words, allowV1 = false)

        assertEquals(
            mapOf("root" to RecoveredKeyRestorer.Outcome.NeedsNoLockConsent),
            (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes,
        )
        assertNull(keyMaterialDao.getByWalletId("root"))
    }

    // --- An unverified row that no longer reads back is written again ---

    private fun readerFailingFirst(times: Int): WalletKeyReader {
        val failing = mockk<WalletKeyReader>()
        var calls = 0
        coEvery { failing.readPrivateKey(any(), any(), any(), any()) } coAnswers {
            calls++
            if (calls <= times) WalletKeyReader.Result.NotAvailable("decrypt failed")
            else reader.readPrivateKey(arg(0), arg(1), arg(2), arg(3))
        }
        return failing
    }

    @Test
    fun `an unverified row that no longer reads back is replaced when the material matches`() = runTest {
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
        // Written, then the read-back was cancelled: the row stays unverified.
        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, restorer.restore(activity, "w1", material(), true))
        val stale = keyMaterialDao.getByWalletId("w1")!!

        // Now the row no longer reads back (say, the screen lock changed).
        val outcome = newRestorer(readerFailingFirst(1)).restore(activity, "w1", material(), true)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
        val fresh = keyMaterialDao.getByWalletId("w1")!!
        assertFalse(stale.iv.contentEquals(fresh.iv))
        assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `an unverified row that no longer reads back is kept when there is no material`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        state.addUnverified("w1")
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)
        val before = keyMaterialDao.getByWalletId("w1")!!

        val outcome = newRestorer(readerFailingFirst(1)).restore(activity, "w1", null, true)

        assertTrue(outcome is RecoveredKeyRestorer.Outcome.Failed)
        assertArrayEquals(before.iv, keyMaterialDao.getByWalletId("w1")!!.iv)
    }

    @Test
    fun `a verified row that does not read back is never deleted`() = runTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        // Not in the unverified set: a row from before, or one already verified.
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)
        val before = keyMaterialDao.getByWalletId("w1")!!

        val outcome = newRestorer(readerFailingFirst(1)).restore(activity, "w1", material(), true)

        assertTrue(outcome is RecoveredKeyRestorer.Outcome.Failed)
        assertArrayEquals(before.iv, keyMaterialDao.getByWalletId("w1")!!.iv)
    }

    // --- Re-keyed rows keep their own type ---

    @Test
    fun `a raw-key wallet re-keyed from a phrase stays a raw key with no phrase`() = runTest {
        secureLock(false)
        // A raw key that is account 0 of the phrase.
        insertWallet("raw", phraseKeyHex, derivationPath = null, type = KeyManager.WALLET_TYPE_RAW_KEY)

        val result = restorer.rekeyFromPhrase(activity, "raw", words, allowV1 = true)

        assertEquals(mapOf("raw" to RecoveredKeyRestorer.Outcome.Restored), (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes)
        val stored = v1Helper.readDecryptedKey("raw")!!
        assertEquals(KeyManager.WALLET_TYPE_RAW_KEY, stored.walletType)
        assertNull(stored.mnemonic)
    }
}
