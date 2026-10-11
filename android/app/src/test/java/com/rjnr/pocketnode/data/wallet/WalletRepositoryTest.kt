package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.MIGRATION_4_5
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.PendingDaoUnlockEntity
import com.rjnr.pocketnode.data.database.entity.PendingDaoWithdrawEntity
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
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
     * production, encrypt the bundle with a fresh V2 cipher and write
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
        keyManager = KeyManager(context, mnemonicManager, NoopLogger)
        encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("test_repo_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        keyManager.keyStoreMigrationHelper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs, NoopLogger)
        v2Helper = KeystoreV2MigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs, logger = NoopLogger)
        walletPreferences = WalletPreferences(context, NoopLogger)
        repo = WalletRepository(
            walletDao, keyManager, walletPreferences, walletPreferences, mnemonicManager, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(),
            db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), db.keyMaterialDao(),
            db.subAccountCandidateDao(), SubAccountDiscovery(mnemonicManager, keyManager), walletPreferences, NoopLogger
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newRepo(dao: WalletDao = walletDao, km: KeyManager = keyManager) = WalletRepository(
        dao, km, walletPreferences, walletPreferences, mnemonicManager, db,
        db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(),
        db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(), db.keyMaterialDao(),
        db.subAccountCandidateDao(), SubAccountDiscovery(mnemonicManager, km), walletPreferences, NoopLogger
    )

    /** #563 review F3: a switch that fails part-way leaves the old wallet active, never none. */
    @Test
    fun `a switch that fails part-way leaves the previous wallet active`() = runTest {
        val first = repo.createWallet(
            name = "First",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val second = repo.createWallet(
            name = "Second",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        assertEquals(second.walletId, walletDao.getActive()?.walletId)
        val failing = object : WalletDao by walletDao {
            override suspend fun activate(walletId: String) = throw IllegalStateException("disk I/O error")
        }

        val error = runCatching { newRepo(dao = failing).switchActiveWallet(first.walletId) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(second.walletId, walletDao.getActive()?.walletId)
        assertEquals(second.walletId, walletPreferences.getActiveWalletId())
    }

    /** #563 review F3: the same holds for the switch inside a rollback. */
    @Test
    fun `a rollback whose switch fails part-way keeps the added wallet active`() = runTest {
        val previous = repo.createWallet(
            name = "Previous",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val added = repo.createWallet(
            name = "Added",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val failing = object : WalletDao by walletDao {
            override suspend fun activate(walletId: String) = throw IllegalStateException("disk I/O error")
        }

        val error = runCatching { newRepo(dao = failing).rollbackAddedWallet(added.walletId, previous.walletId) }
            .exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(added.walletId, walletDao.getActive()?.walletId)
        assertNotNull(walletDao.getById(added.walletId))
        assertNotNull(walletDao.getById(previous.walletId))
    }

    /** #563 review F4: a transient key-deletion failure is retried once. */
    @Test
    fun `deleteWallet retries a failed key deletion once`() = runTest {
        val gone = repo.createWallet(
            name = "Gone",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        repo.createWallet(
            name = "Keep",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val km = io.mockk.spyk(keyManager)
        var calls = 0
        io.mockk.coEvery { km.deleteWalletKeys(any()) } coAnswers {
            calls++
            if (calls == 1) throw IllegalStateException("keystore busy")
            keyManager.deleteWalletKeys(firstArg())
        }

        newRepo(km = km).deleteWallet(gone.walletId)

        assertEquals(2, calls)
        assertNull(walletDao.getById(gone.walletId))
        assertNull(db.keyMaterialDao().getByWalletId(gone.walletId))
    }

    /** #563 review F4: the rows are gone, so a key deletion that keeps failing does not fail the delete. */
    @Test
    fun `deleteWallet whose key deletion keeps failing still completes`() = runTest {
        val gone = repo.createWallet(
            name = "Gone",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        repo.createWallet(
            name = "Keep",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val km = io.mockk.spyk(keyManager)
        io.mockk.coEvery { km.deleteWalletKeys(any()) } throws IllegalStateException("keystore gone")

        newRepo(km = km).deleteWallet(gone.walletId)

        io.mockk.coVerify(exactly = 2) { km.deleteWalletKeys(gone.walletId) }
        assertNull(walletDao.getById(gone.walletId))
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
    fun `deleteWallet clears the in-flight DAO markers of that wallet only`() = runTest {
        val wallet1 = repo.createWallet(
            name = "Wallet 1",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val wallet2 = repo.createWallet(
            name = "Wallet 2",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        // Distinct outpoints per wallet: both marker tables are keyed by the
        // outpoint alone, so reusing one would just overwrite the other's row
        // and the scoping assertion below would prove nothing.
        listOf(wallet1 to "aa", wallet2 to "bb").forEach { (wallet, tag) ->
            db.pendingDaoWithdrawDao().upsert(
                PendingDaoWithdrawEntity(
                    depositTxHash = "0x" + tag.repeat(32),
                    depositIndex = "0x0",
                    withdrawTxHash = "0x" + "22".repeat(32),
                    walletId = wallet.walletId,
                    network = "TESTNET",
                    createdAt = 0L,
                )
            )
            db.pendingDaoUnlockDao().upsert(
                PendingDaoUnlockEntity(
                    withdrawingTxHash = "0x" + tag.repeat(32),
                    withdrawingIndex = "0x1",
                    unlockTxHash = "0x" + "44".repeat(32),
                    walletId = wallet.walletId,
                    network = "TESTNET",
                    createdAt = 0L,
                )
            )
        }
        assertEquals(1, db.pendingDaoUnlockDao().getByWalletAndNetwork(wallet1.walletId, "TESTNET").size)

        repo.deleteWallet(wallet1.walletId)

        // Left behind, a marker re-attaches to a wallet re-imported under the
        // same id and overlays a confirming state on a position with nothing
        // in flight.
        assertTrue(db.pendingDaoWithdrawDao().getByWalletAndNetwork(wallet1.walletId, "TESTNET").isEmpty())
        assertTrue(db.pendingDaoUnlockDao().getByWalletAndNetwork(wallet1.walletId, "TESTNET").isEmpty())
        assertEquals(1, db.pendingDaoWithdrawDao().getByWalletAndNetwork(wallet2.walletId, "TESTNET").size)
        assertEquals(1, db.pendingDaoUnlockDao().getByWalletAndNetwork(wallet2.walletId, "TESTNET").size)
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

    /**
     * Discovery restore (#82 phase 2): explicitIndex recreates the account
     * at the EXACT index whose script had history, not max+1. Same index
     * must derive the same address a manual creation would have, and a
     * taken index must be refused rather than silently shifted.
     */
    @Test
    fun `createSubAccount explicitIndex restores the requested index`() = runTest {
        val parentWords = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE)
        val parent = repo.importFromMnemonic(
            words = parentWords,
            name = "Parent",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        // Restore index 3 directly (indices 1-2 not yet restored).
        val sub3 = repo.createSubAccount(
            parentWalletId = parent.walletId,
            name = "Sub 3",
            parentMnemonic = parentWords,
            explicitIndex = 3,
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        assertEquals(3, sub3.accountIndex)
        assertEquals("m/44'/309'/3'/0/0", sub3.derivationPath)

        // Same index again: refused.
        val dup = repo.createSubAccount(
            parentWalletId = parent.walletId,
            name = "Sub 3 again",
            parentMnemonic = parentWords,
            explicitIndex = 3,
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        )
        assertTrue(dup.isFailure)

        // Address parity with the derivation contract: restoring index 3
        // yields the address the discovery candidate's args imply.
        val expectedArgs = SubAccountDiscovery(mnemonicManager, keyManager)
            .deriveCandidates(parentWords).first { it.accountIndex == 3 }.scriptArgs
        val restoredArgs = keyManager.deriveLockScriptFromAddress(sub3.testnetAddress).args
        assertEquals(expectedArgs, restoredArgs)

        // Default max+1 path still works around the explicit row: next
        // auto-create lands at 4 (max existing is 3).
        val sub4 = repo.createSubAccount(
            parentWalletId = parent.walletId,
            name = "Sub 4",
            parentMnemonic = parentWords,
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        assertEquals(4, sub4.accountIndex)
    }

    /**
     * #382 Tier 2: importing a seed must also record gap-limit candidates
     * along account 0's receiving/change chains, the paths Neuron spreads
     * funds across, so the auto-scan can register them for sync.
     */
    @Test
    fun `importFromMnemonic records chain-axis gap-limit candidates`() = runTest {
        val words = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE)
        val parent = repo.importFromMnemonic(
            words = words,
            name = "Neuron Import",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        val candidates = db.subAccountCandidateDao().getForParent(parent.walletId)
        val chain = candidates.filter { it.accountIndex == 0 }
        val account = candidates.filter { it.accountIndex >= 1 }

        // chains {0,1} x {0..20} minus the parent's own 0/0 slot
        assertEquals(41, chain.size)
        assertTrue(chain.all { it.state == com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity.STATE_PENDING })
        assertTrue(chain.any { it.derivationPath == "m/44'/309'/0'/1/0" })
        assertTrue(chain.any { it.derivationPath == "m/44'/309'/0'/0/20" })
        assertTrue(chain.none { it.derivationPath == "m/44'/309'/0'/0/0" })

        // the parent's own args never appear as a candidate
        val parentArgs = keyManager.deriveLockScriptFromAddress(parent.testnetAddress).args
        assertTrue(chain.none { it.scriptArgs == parentArgs })

        // account-axis discovery (#82) is untouched
        assertEquals(SubAccountDiscovery.CANDIDATE_WINDOW, account.size)
    }

    /**
     * Chain-axis slots are scan targets, not wallets. createWallet (a fresh
     * seed that never existed elsewhere) must NOT record them, auto-scan is
     * an import-time behavior.
     */
    @Test
    fun `createWallet does not record chain-axis candidates`() = runTest {
        val wallet = repo.createWallet(
            name = "Fresh",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val chain = db.subAccountCandidateDao().getForParent(wallet.walletId)
            .filter { it.accountIndex == 0 }
        assertEquals(0, chain.size)
    }

    @Test
    fun `deleteWallet clears that wallet's sync progress and discovery slots only`() = runTest {
        val words = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE)
        val keep = repo.importFromMnemonic(
            words = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE),
            name = "Keep",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val gone = repo.importFromMnemonic(
            words = words,
            name = "Gone",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        for (id in listOf(keep.walletId, gone.walletId)) {
            db.syncProgressDao().upsert(
                com.rjnr.pocketnode.data.database.entity.SyncProgressEntity(id, "TESTNET", 1L, 1L, 0L)
            )
        }
        val keepSlots = db.subAccountCandidateDao().getForParent(keep.walletId).size
        assertTrue(keepSlots > 0)
        repo.switchActiveWallet(keep.walletId)

        repo.deleteWallet(gone.walletId)

        assertTrue(db.subAccountCandidateDao().getForParent(gone.walletId).isEmpty())
        assertNull(db.syncProgressDao().get(gone.walletId, "TESTNET"))
        assertEquals(keepSlots, db.subAccountCandidateDao().getForParent(keep.walletId).size)
        assertNotNull(db.syncProgressDao().get(keep.walletId, "TESTNET"))
    }

    @Test
    fun `rollbackAddedWallet removes only the added wallet and reactivates the previous one`() = runTest {
        val previous = repo.createWallet(
            name = "Previous",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val added = repo.createWallet(
            name = "Added",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        assertEquals(added.walletId, walletDao.getActive()?.walletId)

        val active = repo.rollbackAddedWallet(added.walletId, previous.walletId)

        assertEquals(previous.walletId, active.walletId)
        assertTrue(active.isActive)
        assertEquals(previous.walletId, walletDao.getActive()?.walletId)
        assertEquals(previous.walletId, walletPreferences.getActiveWalletId())
        assertNull(walletDao.getById(added.walletId))
        assertNull(db.keyMaterialDao().getByWalletId(added.walletId))
        assertNotNull(db.keyMaterialDao().getByWalletId(previous.walletId))
        assertEquals(1, walletDao.count())
    }

    @Test
    fun `rollbackAddedWallet with a missing previous wallet changes nothing`() = runTest {
        val added = repo.createWallet(
            name = "Added",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        val error = runCatching { repo.rollbackAddedWallet(added.walletId, "no-such-wallet") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(added.walletId, walletDao.getActive()?.walletId)
        assertNotNull(db.keyMaterialDao().getByWalletId(added.walletId))
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

    /**
     * #431: restored wallets used to start at the tip and show no history
     * because import left the sync mode unset, which defaults to NEW_WALLET.
     * Imports must now record RECENT explicitly, on both networks, so the
     * wallet's history window is fetched even if the post-import sheet is
     * dismissed.
     */
    @Test
    fun `importFromMnemonic defaults sync mode to RECENT on both networks`() = runTest {
        val words = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE)
        val wallet = repo.importFromMnemonic(
            words = words,
            name = "Restored",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        assertEquals(
            SyncMode.RECENT,
            walletPreferences.getSyncMode(NetworkType.MAINNET, wallet.walletId)
        )
        assertEquals(
            SyncMode.RECENT,
            walletPreferences.getSyncMode(NetworkType.TESTNET, wallet.walletId)
        )
    }

    @Test
    fun `importRawKey defaults sync mode to RECENT on both networks`() = runTest {
        @Suppress("SpellCheckingInspection")
        val privateKeyHex = "c".repeat(64)

        val wallet = repo.importRawKey(
            privateKeyHex = privateKeyHex,
            name = "Restored Raw Key",
            persistKeys = { walletId, bundle ->
                fakePersistV2(walletId, bundle, walletType = KeyManager.WALLET_TYPE_RAW_KEY)
            },
        ).getOrThrow()

        assertEquals(
            SyncMode.RECENT,
            walletPreferences.getSyncMode(NetworkType.MAINNET, wallet.walletId)
        )
        assertEquals(
            SyncMode.RECENT,
            walletPreferences.getSyncMode(NetworkType.TESTNET, wallet.walletId)
        )
    }

    @Test
    fun `createWallet still defaults sync mode to NEW_WALLET on both networks`() = runTest {
        val wallet = repo.createWallet(
            name = "Fresh Wallet",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()

        assertEquals(
            SyncMode.NEW_WALLET,
            walletPreferences.getSyncMode(NetworkType.MAINNET, wallet.walletId)
        )
        assertEquals(
            SyncMode.NEW_WALLET,
            walletPreferences.getSyncMode(NetworkType.TESTNET, wallet.walletId)
        )
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

    private fun failedBroadcast(hash: String, walletId: String) =
        com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity(
            txHash = hash,
            walletId = walletId,
            network = "TESTNET",
            signedTxJson = "{}",
            reservedInputs = "[]",
            state = "FAILED",
            submittedAtTipBlock = 0L,
            nullCount = 0,
            createdAt = 0L,
            lastCheckedAt = 0L,
        )

    @Test
    fun `deleteWallet removes that wallet's pending broadcasts only`() = runTest {
        val gone = repo.createWallet(
            name = "Gone",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val keep = repo.createWallet(
            name = "Keep",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val goneHash = "0x" + "aa".repeat(32)
        val keepHash = "0x" + "bb".repeat(32)
        db.pendingBroadcastDao().insert(failedBroadcast(goneHash, gone.walletId))
        db.pendingBroadcastDao().insert(failedBroadcast(keepHash, keep.walletId))

        repo.deleteWallet(gone.walletId)

        assertNull(db.pendingBroadcastDao().getFailedRow(goneHash))
        assertNotNull(db.pendingBroadcastDao().getFailedRow(keepHash))
    }

    private fun pinManagerWithPin(): com.rjnr.pocketnode.data.auth.PinManager {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return com.rjnr.pocketnode.data.auth.PinManager(
            ctx, com.rjnr.pocketnode.data.crypto.Blake2b(), NoopLogger,
        ).apply {
            testPrefs = ctx.getSharedPreferences("test_local_reset_pin", Context.MODE_PRIVATE)
            testPrefs!!.edit().clear().commit()
            argon2Iterations = 1
            argon2MemoryKb = 8
            argon2Parallelism = 1
            setBackupChecker { true }
            setPin("123456")
        }
    }

    @Test
    fun `LocalWalletReset removes every wallet's rows and keys, then the PIN`() = runTest {
        val first = repo.importFromMnemonic(
            words = mnemonicManager.generateMnemonic(MnemonicManager.WordCount.TWELVE),
            name = "First",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val second = repo.createWallet(
            name = "Second",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val ids = listOf(first.walletId, second.walletId)
        ids.forEachIndexed { i, id ->
            db.syncProgressDao().upsert(
                com.rjnr.pocketnode.data.database.entity.SyncProgressEntity(id, "TESTNET", 1L, 1L, 0L)
            )
            db.pendingBroadcastDao().insert(failedBroadcast("0x" + "c$i".repeat(32), id))
        }
        assertTrue(db.subAccountCandidateDao().getForParent(first.walletId).isNotEmpty())
        assertEquals(2, db.keyMaterialDao().count())
        val pinManager = pinManagerWithPin()
        val cacheManager = io.mockk.mockk<com.rjnr.pocketnode.data.gateway.CacheManager>(relaxed = true)
        val daoSyncManager = io.mockk.mockk<com.rjnr.pocketnode.data.gateway.DaoSyncManager>(relaxed = true)

        com.rjnr.pocketnode.data.gateway.LocalWalletReset(repo, cacheManager, daoSyncManager, pinManager).removeAllWalletsAndPin()

        assertEquals(0, walletDao.count())
        assertEquals(0, db.keyMaterialDao().count())
        ids.forEachIndexed { i, id ->
            assertNull(db.syncProgressDao().get(id, "TESTNET"))
            assertTrue(db.subAccountCandidateDao().getForParent(id).isEmpty())
            assertNull(db.pendingBroadcastDao().getFailedRow("0x" + "c$i".repeat(32)))
        }
        io.mockk.coVerify { cacheManager.clearAll() }
        io.mockk.coVerify { daoSyncManager.clearAll() }
        assertFalse(pinManager.hasPin())
    }

    @Test
    fun `LocalWalletReset keeps the PIN when a wallet could not be removed`() = runTest {
        repo.createWallet(
            name = "Stuck",
            persistKeys = { walletId, bundle -> fakePersistV2(walletId, bundle) },
        ).getOrThrow()
        val stuckDao = object : WalletDao by walletDao {
            override suspend fun delete(walletId: String) = throw IllegalStateException("disk I/O error")
        }
        val pinManager = pinManagerWithPin()

        val error = runCatching {
            com.rjnr.pocketnode.data.gateway.LocalWalletReset(
                newRepo(dao = stuckDao),
                io.mockk.mockk(relaxed = true),
                io.mockk.mockk(relaxed = true),
                pinManager,
            ).removeAllWalletsAndPin()
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(1, walletDao.count())
        assertTrue(pinManager.hasPin())
    }
}
