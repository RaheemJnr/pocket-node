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
    private lateinit var activity: FragmentActivity
    private lateinit var fixture: WalletVaultIntegrationFixture

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
        fixture = WalletVaultIntegrationFixture(db)
        activity = fixture.activity
        repeat(12) { fixture.phrase() }
        v1Helper = fixture.legacy
        authManager = fixture.auth
        keyBackupManager = mockk(relaxed = true)
        writer = fixture.writer
        keyManager = mockk(relaxed = true)
        state = fixture.recovery
        state.clear()
        reader = fixture.reader
        restorer = newRestorer(reader)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newRestorer(keyReader: WalletKeyReader) = RecoveredKeyRestorer(
        walletDao, keyMaterialDao, keyReader, keyManager, mnemonicManager, state,
        keyBackupManager, NoopLogger, fixture.coordinator, fixture.prompt,
        com.rjnr.pocketnode.data.vault.LegacyBackupRecovery(db, fixture.backup, fixture.coordinator, fixture.prompt, com.rjnr.pocketnode.data.vault.WalletSecretValidator(mnemonicManager), fixture.keyManager, fixture.reader, fixture.legacy),
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
    fun `recovery writes V3 and authenticated readback matches wallet and seed`() = walletOperationTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), false))
        assertEquals(3, keyMaterialDao.getKdfVersion("w1"))
        val read = reader.readKeyMaterial(activity, "w1", "t", "s") as WalletKeyReader.MaterialResult.Success
        try { assertArrayEquals(phraseKeyHex.hexToByteArray(), read.privateKey); assertEquals(phrase, read.mnemonic) }
        finally { read.privateKey.fill(0) }
        assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `without Android lock recovered keys still require V3 passphrase protection`() = walletOperationTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), true))
        assertEquals(3, keyMaterialDao.getKdfVersion("w1"))
        val read = fixture.coordinator.read(activity, "w1", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success
        assertEquals(phraseKeyHex, read.material.privateKey); assertEquals(phrase, read.material.mnemonic)
    }

    @Test
    fun `without Android lock weak fallback consent is unnecessary and never writes V1`() = walletOperationTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), false))
        assertFalse(restorer.needsNoLockConsent()); assertEquals(3, keyMaterialDao.getKdfVersion("w1"))
        assertFalse(fixture.backup.hasAnyBackups())
    }

    @Test
    fun `each wallet gets its own keys`() = walletOperationTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        insertWallet("w2", otherKeyHex, derivationPath = null)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), true))
        assertEquals(
            RecoveredKeyRestorer.Outcome.Restored,
            restorer.restore(activity, "w2", material(otherKeyHex, mnemonic = null), true),
        )

        assertEquals(phraseKeyHex, (fixture.coordinator.read(activity, "w1", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success).material.privateKey)
        assertEquals(otherKeyHex, (fixture.coordinator.read(activity, "w2", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success).material.privateKey)
    }

    @Test
    fun `a phrase is checked at the wallet's own derivation path`() = walletOperationTest {
        secureLock(false)
        insertWallet("acct1", accountOneKeyHex, derivationPath = "m/44'/309'/1'/0/0", accountIndex = 1)

        val outcome = restorer.restore(activity, "acct1", material(accountOneKeyHex), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
    }

    @Test
    fun `a backup whose key does not give the wallet's lock script writes nothing`() = walletOperationTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)

        val outcome = restorer.restore(activity, "w1", material(otherKeyHex), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
        coVerify(exactly = 0) { authManager.authenticateForCipher(any(), any(), any(), any()) }
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `a backup whose phrase does not derive its key writes nothing`() = walletOperationTest {
        secureLock(true)
        // The row and the key agree; the phrase in the backup does not.
        insertWallet("w1", otherKeyHex)

        val outcome = restorer.restore(activity, "w1", material(otherKeyHex, mnemonic = phrase), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, outcome)
        assertNull(keyMaterialDao.getByWalletId("w1"))
        coVerify(exactly = 0) { authManager.authenticateForCipher(any(), any(), any(), any()) }
    }

    @Test
    fun `both stored addresses are required before recovery mutation`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex, blankTestnet = true)
        assertEquals(RecoveredKeyRestorer.Outcome.AddressMismatch, restorer.restore(activity, "w1", material(), true))
        assertNull(keyMaterialDao.getByWalletId("w1"))
    }

    @Test
    fun `present unusable mismatched key can be repaired by matching external seed`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        v1Helper.migrateWallet("w1", otherKeyHex, null, KeyManager.WALLET_TYPE_RAW_KEY, false)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), true))
        val read = fixture.coordinator.read(activity, "w1", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success
        assertEquals(phrase, read.material.mnemonic)
    }

    @Test
    fun `the V1 write refuses to replace an existing row`() = walletOperationTest {
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
    fun `a backup with no wallet row is skipped and its file kept`() = walletOperationTest {
        secureLock(true)

        val outcome = restorer.restore(activity, "orphan", material(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.NoWalletRow, outcome)
        assertNull(keyMaterialDao.getByWalletId("orphan"))
        assertNull(walletDao.getById("orphan"))
        verify(exactly = 0) { keyBackupManager.deleteBackup(any()) }
    }

    @Test
    fun `a wallet with no key row and no backup is NoBackup and stays missing`() = walletOperationTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)

        assertEquals(RecoveredKeyRestorer.Outcome.NoBackup, restorer.restore(activity, "w1", null, true))
        assertEquals(listOf("w1"), restorer.missingWallets().map { it.walletId })
        assertFalse(restorer.completeIfDone())
    }

    @Test
    fun `a cancelled passphrase prompt stores nothing`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        fixture.credentials.clear(); fixture.phrase(null)
        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, restorer.restore(activity, "w1", material(), true))
        assertNull(keyMaterialDao.getByWalletId("w1"))
    }

    @Test
    fun `pending cleanup keeps verified row unhealthy until authenticated retry completes`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex); fixture.cleanupOk = false
        assertTrue(restorer.restore(activity, "w1", material(), true) is RecoveredKeyRestorer.Outcome.Failed)
        assertEquals(3, keyMaterialDao.getKdfVersion("w1")); assertFalse(restorer.completeIfDone())
        assertTrue("w1" in state.unverifiedWalletIds())
        fixture.cleanupOk = true
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), true))
        assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `failed durable envelope readback rolls activation back and preserves original source`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true)
        val before = keyMaterialDao.getByWalletId("w1")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER corrupt_readback AFTER INSERT ON wallet_vault BEGIN UPDATE wallet_vault SET envelope=X'00' WHERE walletId=NEW.walletId; END")
        assertTrue(restorer.restore(activity, "w1", material(), true) is RecoveredKeyRestorer.Outcome.Failed)
        assertEquals(before, keyMaterialDao.getByWalletId("w1")); assertNull(fixture.store.get("w1"))
    }

    @Test
    fun `a concurrent source replacement during passphrase prompt survives source CAS rejection`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true)
        fixture.onCredential = { v1Helper.migrateWallet("w1", otherKeyHex, null, "raw_key", false) }
        assertTrue(restorer.restore(activity, "w1", material(), true) is RecoveredKeyRestorer.Outcome.Failed)
        assertEquals(otherKeyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
    }

    @Test
    fun `storage failure preserves original encrypted source and recovery marker`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true)
        val before = keyMaterialDao.getByWalletId("w1")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_vault BEFORE INSERT ON wallet_vault BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        assertTrue(restorer.restore(activity, "w1", material(), true) is RecoveredKeyRestorer.Outcome.Failed)
        assertEquals(before, keyMaterialDao.getByWalletId("w1")); assertTrue("w1" in state.unverifiedWalletIds())
    }

    @Test
    fun `completion needs every wallet row, sub-accounts included, to have verified keys`() = walletOperationTest {
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
    fun `nothing to recover at all counts as complete and clears a stale marker`() = walletOperationTest {
        restorer.markIncomplete()
        every { keyBackupManager.hasAnyBackups() } returns false

        assertTrue(restorer.completeIfDone())
        assertFalse(state.isIncomplete())
    }

    @Test
    fun `no wallet rows but backup files left is not complete`() = walletOperationTest {
        restorer.markIncomplete()
        every { keyBackupManager.hasAnyBackups() } returns true

        assertFalse(restorer.completeIfDone())
        assertTrue(state.isIncomplete())
    }

    @Test
    fun `no wallet rows but a key row left is not complete`() = walletOperationTest {
        v1Helper.migrateWallet("stray", otherKeyHex, null, KeyManager.WALLET_TYPE_RAW_KEY, false)
        every { keyBackupManager.hasAnyBackups() } returns false

        assertFalse(restorer.completeIfDone())
    }

    @Test
    fun `with a screen lock no weak fallback consent is needed and keys go to V3`() = walletOperationTest {
        secureLock(true)
        insertWallet("w1", phraseKeyHex)

        val outcome = restorer.restore(activity, "w1", material(), allowV1 = false)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
        assertFalse(restorer.needsNoLockConsent())
        assertEquals(3, keyMaterialDao.getKdfVersion("w1"))
    }

    // --- Re-key a missing wallet from its phrase or private key ---

    private val words = phrase.split(" ")
    private val otherPhrase =
        "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    @Test
    fun `a missing root wallet is re-keyed from its phrase, verified, and recovery completes`() = walletOperationTest {
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
    fun `a phrase that does not belong to the wallet writes nothing`() = walletOperationTest {
        secureLock(true)
        insertWallet("root", phraseKeyHex)

        val result = restorer.rekeyFromPhrase(activity, "root", otherPhrase, allowV1 = false)

        assertEquals(RecoveredKeyRestorer.RekeyResult.NotThisWallet, result)
        assertNull(keyMaterialDao.getByWalletId("root"))
        coVerify(exactly = 0) { authManager.authenticateForCipher(any(), any(), any(), any()) }
    }

    @Test
    fun `an invalid phrase writes nothing`() = walletOperationTest {
        insertWallet("root", phraseKeyHex)

        val result = restorer.rekeyFromPhrase(activity, "root", words.dropLast(1) + "zzzz", allowV1 = true)

        assertEquals(RecoveredKeyRestorer.RekeyResult.InvalidInput, result)
        assertNull(keyMaterialDao.getByWalletId("root"))
    }

    @Test
    fun `a missing raw-key wallet is re-keyed from its private key`() = walletOperationTest {
        secureLock(false)
        insertWallet("raw", otherKeyHex, derivationPath = null, type = KeyManager.WALLET_TYPE_RAW_KEY)

        val result = restorer.rekeyFromPrivateKey(activity, "raw", otherKeyHex.hexToByteArray(), allowV1 = true)

        assertEquals(mapOf("raw" to RecoveredKeyRestorer.Outcome.Restored), (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes)
        val stored = (fixture.coordinator.read(activity, "raw", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success).material
        assertEquals(otherKeyHex, stored.privateKey)
        assertNull(stored.mnemonic)
        assertEquals(KeyManager.WALLET_TYPE_RAW_KEY, stored.walletType)
    }

    @Test
    fun `a private key that does not belong to the wallet writes nothing`() = walletOperationTest {
        secureLock(false)
        insertWallet("raw", otherKeyHex, derivationPath = null, type = KeyManager.WALLET_TYPE_RAW_KEY)

        val result = restorer.rekeyFromPrivateKey(activity, "raw", phraseKeyHex.hexToByteArray(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.RekeyResult.NotThisWallet, result)
        assertNull(keyMaterialDao.getByWalletId("raw"))
    }

    @Test
    fun `a missing sub-account is re-keyed from its parent's phrase and the parent is not touched`() = walletOperationTest {
        secureLock(false)
        insertWallet("root", phraseKeyHex)
        v1Helper.migrateWallet("root", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)
        val rootRow = keyMaterialDao.getByWalletId("root")!!
        insertWallet("sub", accountOneKeyHex, derivationPath = "m/44'/309'/1'/0/0", accountIndex = 1, parentWalletId = "root")

        val result = restorer.rekeyFromPhrase(activity, "sub", words, allowV1 = true)

        assertEquals(mapOf("sub" to RecoveredKeyRestorer.Outcome.Restored), (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes)
        val sub = (fixture.coordinator.read(activity, "sub", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success).material
        assertEquals(accountOneKeyHex, sub.privateKey)
        // Sub-accounts store no phrase.
        assertNull(sub.mnemonic)
        assertArrayEquals(rootRow.iv, keyMaterialDao.getByWalletId("root")!!.iv)
        assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `a parent's phrase re-keys the parent and its missing sub-account, each verified`() = walletOperationTest {
        secureLock(true)
        insertWallet("root", phraseKeyHex)
        insertWallet("sub", accountOneKeyHex, derivationPath = "m/44'/309'/1'/0/0", accountIndex = 1, parentWalletId = "root")
        insertWallet("raw", otherKeyHex, derivationPath = null, type = KeyManager.WALLET_TYPE_RAW_KEY)

        val result = restorer.rekeyFromPhrase(activity, "root", words, allowV1 = false)

        assertEquals(
            mapOf("root" to RecoveredKeyRestorer.Outcome.Restored, "sub" to RecoveredKeyRestorer.Outcome.Restored),
            (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes,
        )
        assertEquals(3, keyMaterialDao.getKdfVersion("root"))
        assertEquals(3, keyMaterialDao.getKdfVersion("sub"))
        assertNull(keyMaterialDao.getByWalletId("raw"))
        assertEquals(listOf("raw"), restorer.missingWallets().map { it.walletId })
    }

    @Test
    fun `a wallet that is not missing is never re-keyed`() = walletOperationTest {
        secureLock(false)
        insertWallet("root", phraseKeyHex)
        v1Helper.migrateWallet("root", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)

        assertEquals(
            RecoveredKeyRestorer.RekeyResult.NotMissing,
            restorer.rekeyFromPhrase(activity, "root", words, allowV1 = true),
        )
    }

    @Test
    fun `without Android lock rekey requires wallet passphrase instead of weak fallback consent`() = walletOperationTest {
        insertWallet("root", phraseKeyHex)
        val result = restorer.rekeyFromPhrase(activity, "root", words, false) as RecoveredKeyRestorer.RekeyResult.Done
        assertEquals(mapOf("root" to RecoveredKeyRestorer.Outcome.Restored), result.outcomes)
        assertEquals(3, keyMaterialDao.getKdfVersion("root"))
    }

    // --- An unverified row that no longer reads back is written again ---



    @Test
    fun `unverified present ciphertext is replaced after matching external seed and current passphrase`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true); state.addUnverified("w1")
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), true))
        assertEquals(3, keyMaterialDao.getKdfVersion("w1")); assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `an unverified row that no longer reads back is kept when there is no material`() = walletOperationTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        state.addUnverified("w1")
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)
        val before = keyMaterialDao.getByWalletId("w1")!!

        val outcome = restorer.restore(activity, "w1", null, true)

        assertEquals(RecoveredKeyRestorer.Outcome.NoBackup, outcome)
        assertArrayEquals(before.iv, keyMaterialDao.getByWalletId("w1")!!.iv)
    }

    @Test
    fun `wrong current passphrase never replaces existing verified vault`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), true))
        val before = fixture.db.walletVaultDao().get("w1")!!
        fixture.credentials.clear(); fixture.phrase("wrong public disposable passphrase")
        assertTrue(restorer.restore(activity, "w1", material(), true) is RecoveredKeyRestorer.Outcome.Failed)
        assertArrayEquals(before.envelope, fixture.db.walletVaultDao().get("w1")!!.envelope)
    }

    // --- Re-keyed rows keep their own type ---

    @Test
    fun `a raw-key wallet re-keyed from a phrase stays a raw key with no phrase`() = walletOperationTest {
        secureLock(false)
        // A raw key that is account 0 of the phrase.
        insertWallet("raw", phraseKeyHex, derivationPath = null, type = KeyManager.WALLET_TYPE_RAW_KEY)

        val result = restorer.rekeyFromPhrase(activity, "raw", words, allowV1 = true)

        assertEquals(mapOf("raw" to RecoveredKeyRestorer.Outcome.Restored), (result as RecoveredKeyRestorer.RekeyResult.Done).outcomes)
        val stored = (fixture.coordinator.read(activity, "raw", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success).material
        assertEquals(KeyManager.WALLET_TYPE_RAW_KEY, stored.walletType)
        assertNull(stored.mnemonic)
    }

    // --- Round 3 ---



    @Test
    fun `cancelled current passphrase keeps unverified source without deletion`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex); state.addUnverified("w1")
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true)
        val before = keyMaterialDao.getByWalletId("w1")
        fixture.credentials.clear(); fixture.phrase(null)
        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, restorer.restore(activity, "w1", material(), true))
        assertEquals(before, keyMaterialDao.getByWalletId("w1")); assertTrue("w1" in state.unverifiedWalletIds())
    }

    @Test
    fun `external recovery cancellation without Android lock preserves old ciphertext`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex); state.addUnverified("w1")
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true)
        val before = keyMaterialDao.getByWalletId("w1")
        fixture.credentials.clear(); fixture.phrase(null)
        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, restorer.restore(activity, "w1", material(), false))
        assertEquals(before, keyMaterialDao.getByWalletId("w1"))
    }

    @Test
    fun `unverified legacy ciphertext is replaced after external seed verification`() = walletOperationTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        state.addUnverified("w1")
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)
        val before = keyMaterialDao.getByWalletId("w1")!!

        val outcome = restorer.restore(activity, "w1", material(), allowV1 = true)

        assertEquals(RecoveredKeyRestorer.Outcome.Restored, outcome)
        assertFalse(before.iv.contentEquals(keyMaterialDao.getByWalletId("w1")!!.iv))
    }

    @Test
    fun `any good read of a wallet clears its unverified mark`() = walletOperationTest {
        secureLock(false)
        insertWallet("w1", phraseKeyHex)
        state.addUnverified("w1")
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, KeyManager.WALLET_TYPE_MNEMONIC, true)

        val read = reader.readPrivateKey(activity, "w1", "t", "s")

        assertTrue(read is WalletKeyReader.Result.Success)
        assertFalse(state.unverifiedWalletIds().contains("w1"))
        assertTrue(restorer.missingWallets().isEmpty())
    }

    @Test
    fun `nothing to recover keeps the corruption flag for onboarding's notice`() = walletOperationTest {
        every { keyBackupManager.hasAnyBackups() } returns false

        assertTrue(restorer.completeIfDone())

        @Suppress("DEPRECATION")
        verify(exactly = 0) { keyManager.resetCorruptionFlag() }
    }

    // --- Upgrade from before key_material: keys still in the legacy store ---

    @Test
    fun `a wallet whose keys are still in the legacy store is not missing`() = walletOperationTest {
        insertWallet("legacy", phraseKeyHex)
        every { keyManager.hasLegacyKeyFile("legacy") } returns true

        assertTrue(restorer.missingWallets().isEmpty())
        assertTrue(restorer.completeIfDone())
    }

    @Test
    fun `a wallet with neither a key row nor a legacy key is missing`() = walletOperationTest {
        insertWallet("lost", phraseKeyHex)
        every { keyManager.hasLegacyKeyFile("lost") } returns false

        assertEquals(listOf("lost"), restorer.missingWallets().map { it.walletId })
    }

    @Test fun `private key recovery preserves a readable legacy seed instead of downgrading`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true)
        state.addUnverified("w1")
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(mnemonic = null), false))
        val read = fixture.coordinator.read(activity, "w1", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success
        assertEquals(phrase, read.material.mnemonic)
    }
    @Test fun `temporary existing seed authentication failure cannot authorize raw replacement`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true)
        val before = keyMaterialDao.getByWalletId("w1")
        val failing = mockk<WalletKeyReader>()
        coEvery { failing.readKeyMaterial(any(), any(), any(), any()) } returns WalletKeyReader.MaterialResult.AuthError(7, "lockout")
        assertTrue(newRestorer(failing).restore(activity, "w1", material(mnemonic = null), false) is RecoveredKeyRestorer.Outcome.Failed)
        assertEquals(before, keyMaterialDao.getByWalletId("w1"))
        assertTrue("w1" in state.unverifiedWalletIds()); assertTrue(state.isIncomplete())
    }
    @Test fun `pending seed upgrade cleanup never becomes permission for raw downgrade`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        v1Helper.migrateWallet("w1", phraseKeyHex, phrase, "mnemonic", true)
        fixture.cleanupOk = false
        assertTrue(restorer.restore(activity, "w1", material(mnemonic = null), false) is RecoveredKeyRestorer.Outcome.Failed)
        fixture.cleanupOk = true
        val read = fixture.coordinator.read(activity, "w1", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success
        assertEquals(phrase, read.material.mnemonic)
    }

    @Test fun `generic cancellation stops phrase recovery before other matching wallets`() = walletOperationTest {
        insertWallet("root", phraseKeyHex)
        insertWallet("sub", accountOneKeyHex, "m/44'/309'/1'/0/0", 1, parentWalletId = "root")
        fixture.credentials.clear(); fixture.phrase(null); fixture.phrase()
        val result = restorer.rekeyFromPhrase(activity, "root", words, false) as RecoveredKeyRestorer.RekeyResult.Done
        assertEquals(mapOf("root" to RecoveredKeyRestorer.Outcome.Cancelled), result.outcomes)
        assertNull(keyMaterialDao.getByWalletId("root")); assertNull(keyMaterialDao.getByWalletId("sub"))
    }

    @Test fun `independent phrase choice preserves V3 seed during private key recovery`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), false))
        val before = fixture.db.walletVaultDao().get("w1")!!
        fixture.choices += true
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(mnemonic = null), false))
        assertArrayEquals(before.envelope, fixture.db.walletVaultDao().get("w1")!!.envelope)
    }
    @Test fun `wrong independent phrase never becomes consent for spending only recovery`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), false))
        val before = fixture.db.walletVaultDao().get("w1")!!
        fixture.choices += true; fixture.credentials.clear(); fixture.phrase("wrong public wallet passphrase")
        assertTrue(restorer.restore(activity, "w1", material(mnemonic = null), false) is RecoveredKeyRestorer.Outcome.Failed)
        assertArrayEquals(before.envelope, fixture.db.walletVaultDao().get("w1")!!.envelope)
    }
    @Test fun `deliberate spending only choice still retains current phrase readable seed`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), false))
        fixture.choices += false; fixture.choices += true
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(mnemonic = null), false))
        val read = fixture.coordinator.read(activity, "w1", "Read", "Read") as com.rjnr.pocketnode.data.vault.VaultAccessResult.Success
        assertEquals(phrase, read.material.mnemonic)
    }
    @Test fun `cancel recovery route choice preserves current V3 envelope`() = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer.restore(activity, "w1", material(), false))
        val before = fixture.db.walletVaultDao().get("w1")!!
        fixture.choices += null
        assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, restorer.restore(activity, "w1", material(mnemonic = null), false))
        assertArrayEquals(before.envelope, fixture.db.walletVaultDao().get("w1")!!.envelope)
    }
    @Test fun `external raw recovery honors exact inaccessible V2 acknowledgment`() = externalV2Acknowledgment(true)
    @Test fun `external raw recovery declines inaccessible V2 acknowledgment`() = externalV2Acknowledgment(false)
    @Test fun `external raw recovery rejects source changed during acknowledgment`() = externalV2Acknowledgment(true, true)
    @Test fun `external raw recovery acknowledgment cannot retire opaque backup`() = externalV2Acknowledgment(true, backup = true)
    @Test fun `external raw recovery cancels inaccessible V2 acknowledgment`() = externalV2Acknowledgment(null)
    @Test fun `external raw recovery background aborts inaccessible V2 acknowledgment`() = externalV2Acknowledgment(true, background = true)
    private fun externalV2Acknowledgment(consent: Boolean?, changed: Boolean = false, backup: Boolean = false, background: Boolean = false) = walletOperationTest {
        insertWallet("w1", phraseKeyHex)
        fixture.v2.writeNewV2Row("w1", com.rjnr.pocketnode.data.crypto.WalletKeyBundle(phraseKeyHex, phrase),
            fixture.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
        val source = keyMaterialDao.getByWalletId("w1")!!
        val invalidated = mockk<WalletKeyReader>()
        coEvery { invalidated.readKeyMaterial(any(), any(), any(), any()) } returns WalletKeyReader.MaterialResult.KeyInvalidated
        fixture.choices += consent
        fixture.onChoice = {
            if (changed) keyMaterialDao.upsert(source.copy(updatedAt = source.updatedAt + 1))
            if (background) fixture.activityController.pause().stop()
        }
        if (backup) fixture.backup.writeBackup("w1", material(), "111111".toCharArray())
        val operation = runCatching { newRestorer(invalidated).restore(activity, "w1", material(mnemonic = null), false) }
        if (background) {
            assertTrue(operation.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(source, keyMaterialDao.getByWalletId("w1")); assertNull(fixture.store.get("w1"))
            return@walletOperationTest
        }
        val result = operation.getOrThrow()
        if (consent == true && !changed && !backup) {
            assertEquals(RecoveredKeyRestorer.Outcome.Restored, result)
            assertNull(db.walletVaultDao().legacy("w1"))
        } else if (backup) {
            assertTrue(result is RecoveredKeyRestorer.Outcome.Failed)
            assertEquals(source, db.walletVaultDao().legacy("w1")?.source())
            assertTrue(fixture.backup.hasBackup("w1"))
        } else {
            if (changed) assertTrue(result is RecoveredKeyRestorer.Outcome.Failed) else assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, result)
            assertEquals(if (changed) source.copy(updatedAt = source.updatedAt + 1) else source, keyMaterialDao.getByWalletId("w1"))
            assertNull(fixture.store.get("w1"))
        }
    }

}
