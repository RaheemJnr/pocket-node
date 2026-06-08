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
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.migration.WalletKeyBundle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var walletDao: WalletDao
    private lateinit var keyManager: KeyManager
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var mnemonicManager: MnemonicManager
    private lateinit var encryptionManager: KeystoreEncryptionManager
    private lateinit var v2Helper: KeystoreV2MigrationHelper
    private lateinit var repo: WalletRepository

    /**
     * Fake persistKeys closure that mirrors what `WalletKeyWriter` does in
     * production — encrypt the bundle with a fresh V2 cipher and write
     * directly to `key_material` at kdfVersion=2. Skips the BiometricPrompt
     * (the test fixture has no Activity).
     */
    private suspend fun fakePersistV2(
        walletId: String,
        bundle: WalletKeyBundle,
        walletType: String = KeyManager.WALLET_TYPE_MNEMONIC,
    ): WalletKeyWriter.Result {
        val cipher = encryptionManager.newEncryptCipherV2()
        return v2Helper.writeNewV2Row(walletId, bundle, cipher, walletType, false).fold(
            onSuccess = { WalletKeyWriter.Result.Success },
            onFailure = { WalletKeyWriter.Result.WriteFailed(it) },
        )
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        walletDao = db.walletDao()
        mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
        encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("test_repo_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        keyManager.keyStoreMigrationHelper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs)
        v2Helper = KeystoreV2MigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs)
        walletPreferences = WalletPreferences(context)
        repo = WalletRepository(
            walletDao, keyManager, walletPreferences, mnemonicManager, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(), db.keyMaterialDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `createWallet inserts entity and sets active`() = runTest {
        val wallet = repo.createWallet(
            name = "Test Wallet",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        assertEquals("Test Wallet", wallet.name)
        assertEquals("mnemonic", wallet.type)
        assertTrue(wallet.isActive)
        assertTrue(wallet.mainnetAddress.startsWith("ckb1"))
        assertTrue(wallet.testnetAddress.startsWith("ckt1"))
        assertEquals("m/44'/309'/0'/0/0", wallet.derivationPath)

        // Verify stored in DB
        val fromDb = walletDao.getById(wallet.walletId)
        assertNotNull(fromDb)
        assertEquals(wallet.walletId, fromDb!!.walletId)

        // Verify a key_material row exists at kdfVersion=2.
        val keyEntity = db.keyMaterialDao().getByWalletId(wallet.walletId)
        assertNotNull(keyEntity)
        assertEquals(2, keyEntity!!.kdfVersion)

        // Verify active wallet pref
        assertEquals(wallet.walletId, walletPreferences.getActiveWalletId())
    }

    @Test
    fun `switchActiveWallet deactivates old and activates new`() = runTest {
        val wallet1 = repo.createWallet(
            name = "Wallet 1",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val wallet2 = repo.createWallet(
            name = "Wallet 2",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        // Wallet 2 is active after creation
        assertFalse(walletDao.getById(wallet1.walletId)!!.isActive)
        assertTrue(walletDao.getById(wallet2.walletId)!!.isActive)

        // Switch back to wallet 1
        repo.switchActiveWallet(wallet1.walletId)
        assertTrue(walletDao.getById(wallet1.walletId)!!.isActive)
        assertFalse(walletDao.getById(wallet2.walletId)!!.isActive)
        assertEquals(wallet1.walletId, walletPreferences.getActiveWalletId())
    }

    @Test
    fun `deleteWallet removes entity and key material`() = runTest {
        val wallet1 = repo.createWallet(
            name = "Wallet 1",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val wallet2 = repo.createWallet(
            name = "Wallet 2",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        // wallet1 is no longer active, so we can delete it
        repo.deleteWallet(wallet1.walletId)

        assertNull(walletDao.getById(wallet1.walletId))
        assertNull(db.keyMaterialDao().getByWalletId(wallet1.walletId))

        // wallet2 should still exist
        assertNotNull(walletDao.getById(wallet2.walletId))
    }

    @Test
    fun `importRawKey creates raw_key wallet`() = runTest {
        @Suppress("SpellCheckingInspection")
        val privateKeyHex = "a".repeat(64) // 32 bytes of 0xaa

        val wallet = repo.importRawKey(
            privateKeyHex = privateKeyHex,
            name = "Raw Key Wallet",
            persistKeys = { walletId, bundle ->
                fakePersistV2(walletId, bundle, walletType = KeyManager.WALLET_TYPE_RAW_KEY)
            },
        ).getOrThrow()

        assertEquals("Raw Key Wallet", wallet.name)
        assertEquals("raw_key", wallet.type)
        assertNull(wallet.derivationPath)
        assertTrue(wallet.isActive)
        assertTrue(wallet.mainnetAddress.startsWith("ckb1"))
    }

    @Test
    fun `createSubAccount derives from parent mnemonic`() = runTest {
        // Generate a fresh mnemonic; we need to feed it back in as parentMnemonic
        // since the repo no longer reads from V1 storage.
        val parentWords = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE)
        val parent = repo.importFromMnemonic(
            words = parentWords,
            name = "Parent",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        val sub = repo.createSubAccount(
            parentWalletId = parent.walletId,
            name = "Sub Account",
            parentMnemonic = parentWords,
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        assertEquals("Sub Account", sub.name)
        assertEquals(parent.walletId, sub.parentWalletId)
        assertTrue(sub.accountIndex > 0)
        assertTrue(sub.isActive)
        // Sub-account should have different address than parent
        assertNotEquals(parent.mainnetAddress, sub.mainnetAddress)
    }

    @Test
    fun `deleteWallet refuses to delete active wallet`() = runTest {
        val wallet1 = repo.createWallet(
            name = "Wallet 1",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        // wallet1 is still active
        val error = try {
            repo.deleteWallet(wallet1.walletId)
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("Expected IllegalStateException", error)
        assertNotNull(walletDao.getById(wallet1.walletId))
    }

    @Test
    fun `importFromMnemonic rejects duplicate mnemonic by address`() = runTest {
        val words = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE)
        repo.importFromMnemonic(
            words = words,
            name = "Original",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        val result = repo.importFromMnemonic(
            words = words,
            name = "Duplicate",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        )
        val error = result.exceptionOrNull()
        assertNotNull("Expected IllegalArgumentException", error)
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("already imported"))
    }

    @Test
    fun `importRawKey rejects duplicate key by address`() = runTest {
        @Suppress("SpellCheckingInspection")
        val pk = "b".repeat(64)
        repo.importRawKey(
            privateKeyHex = pk,
            name = "First",
            persistKeys = { walletId, bundle ->
                fakePersistV2(walletId, bundle, walletType = KeyManager.WALLET_TYPE_RAW_KEY)
            },
        ).getOrThrow()

        val result = repo.importRawKey(
            privateKeyHex = pk,
            name = "Second",
            persistKeys = { walletId, bundle ->
                fakePersistV2(walletId, bundle, walletType = KeyManager.WALLET_TYPE_RAW_KEY)
            },
        )
        val error = result.exceptionOrNull()
        assertNotNull("Expected IllegalArgumentException", error)
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `only one wallet is active at a time`() = runTest {
        repo.createWallet(
            name = "W1",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        repo.createWallet(
            name = "W2",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        repo.createWallet(
            name = "W3",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        val allWallets = walletDao.getAllWallets().first()
        val activeCount = allWallets.count { it.isActive }
        assertEquals(1, activeCount)
    }
}
