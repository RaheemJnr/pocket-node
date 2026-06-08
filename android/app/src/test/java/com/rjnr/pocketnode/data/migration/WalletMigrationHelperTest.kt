package com.rjnr.pocketnode.data.migration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.MIGRATION_4_5
import com.rjnr.pocketnode.data.database.MIGRATION_5_6
import com.rjnr.pocketnode.data.database.MIGRATION_6_7
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletMigrationHelperTest {

    private lateinit var db: AppDatabase
    private lateinit var walletDao: WalletDao
    private lateinit var keyManager: KeyManager
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var helper: WalletMigrationHelper

    /**
     * Direct handle to the same SharedPreferences file WalletPreferences uses.
     * Used only to seed legacy state (pre-v7 keys) and inspect post-migration
     * state — production code goes through WalletPreferences' encapsulated API.
     * Hardcoded name "ckb_wallet_prefs" matches PREFS_NAME in WalletPreferences.
     */
    private lateinit var legacyPrefs: android.content.SharedPreferences

    private lateinit var keyStoreMigrationHelper: KeyStoreMigrationHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()
        walletDao = db.walletDao()
        keyManager = KeyManager(context, MnemonicManager())
        val encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("test_wallet_mig", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        keyStoreMigrationHelper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs)
        keyManager.keyStoreMigrationHelper = keyStoreMigrationHelper
        legacyPrefs = context.getSharedPreferences("ckb_wallet_prefs", Context.MODE_PRIVATE)
        legacyPrefs.edit().clear().commit()
        walletPreferences = WalletPreferences(context)
        helper = WalletMigrationHelper(walletDao, keyManager, walletPreferences, db, db.syncProgressDao(), keyStoreMigrationHelper)
    }

    /**
     * Seed a legacy single-wallet "default" Room row at kdfVersion=1.
     * Pre-#289 chunk 3 these tests called `keyManager.generateWallet()` /
     * `generateWalletWithMnemonic()`; those wallet-create paths moved to
     * [com.rjnr.pocketnode.data.wallet.WalletKeyWriter]. The migration helper
     * under test reads via `keyManager.{getPrivateKey,getMnemonic,getWalletType,
     * hasMnemonicBackup}` which consult Room first, so seeding the V1 row
     * directly reproduces the legacy state the helper expects.
     */
    private suspend fun seedLegacyDefaultWallet(
        privateKeyHex: String,
        mnemonic: String?,
        walletType: String,
    ) {
        keyStoreMigrationHelper.migrateWallet(
            walletId = "default",
            privateKeyHex = privateKeyHex,
            mnemonic = mnemonic,
            walletType = walletType,
            mnemonicBackedUp = false,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `no-op when no legacy wallet exists`() = runTest {
        // No wallet in KeyManager
        helper.migrateIfNeeded()
        assertEquals(0, walletDao.count())
    }

    @Test
    fun `migrates existing single wallet`() = runTest {
        // Set up a legacy single raw-key wallet directly in Room (kdfVersion=1).
        seedLegacyDefaultWallet(
            privateKeyHex = "aa".repeat(32),
            mnemonic = null,
            walletType = KeyManager.WALLET_TYPE_RAW_KEY,
        )
        val legacyInfo = keyManager.getWalletInfo()

        helper.migrateIfNeeded()

        // Should have created one wallet entity
        assertEquals(1, walletDao.count())

        val activeWalletId = walletPreferences.getActiveWalletId()
        assertNotNull(activeWalletId)

        val wallet = walletDao.getById(activeWalletId!!)
        assertNotNull(wallet)
        assertEquals("Primary Wallet", wallet!!.name)
        assertEquals("raw_key", wallet.type)
        assertTrue(wallet.isActive)
        assertEquals(legacyInfo.mainnetAddress, wallet.mainnetAddress)
        assertEquals(legacyInfo.testnetAddress, wallet.testnetAddress)

        // Keys should be copied to wallet-scoped storage
        val scopedKey = keyManager.getPrivateKeyForWallet(activeWalletId)
        assertNotNull(scopedKey)
    }

    @Test
    fun `idempotent - no-op when already migrated`() = runTest {
        seedLegacyDefaultWallet(
            privateKeyHex = "aa".repeat(32),
            mnemonic = null,
            walletType = KeyManager.WALLET_TYPE_RAW_KEY,
        )

        helper.migrateIfNeeded()
        val firstWalletId = walletPreferences.getActiveWalletId()
        assertEquals(1, walletDao.count())

        // Run again
        helper.migrateIfNeeded()
        assertEquals(1, walletDao.count())
        assertEquals(firstWalletId, walletPreferences.getActiveWalletId())
    }

    @Test
    fun `migrates mnemonic wallet with derivation path`() = runTest {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        seedLegacyDefaultWallet(
            privateKeyHex = "bb".repeat(32),
            mnemonic = mnemonic,
            walletType = KeyManager.WALLET_TYPE_MNEMONIC,
        )

        helper.migrateIfNeeded()

        val activeWalletId = walletPreferences.getActiveWalletId()!!
        val wallet = walletDao.getById(activeWalletId)!!
        assertEquals("mnemonic", wallet.type)
        assertEquals("m/44'/309'/0'/0/0", wallet.derivationPath)

        // Mnemonic should be copied
        val migratedMnemonic = keyManager.getMnemonicForWallet(activeWalletId)
        assertNotNull(migratedMnemonic)
        assertTrue(migratedMnemonic!!.size >= 12)
    }

    @Test
    fun `migrateSyncProgressToRoomIfNeeded copies prefs values to Room and deletes prefs keys`() = runTest {
        // Seed: one wallet, two networks, both with progress in SharedPrefs
        val walletId = "wallet-mig-1"
        walletDao.insert(WalletEntity(
            walletId = walletId, name = "X", type = "mnemonic",
            derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null,
            accountIndex = 0, mainnetAddress = "ckb1x", testnetAddress = "ckt1x",
            isActive = true, createdAt = 0L
        ))
        legacyPrefs.edit()
            .putLong("${walletId}_mainnet_last_synced_block", 12345L)
            .putLong("${walletId}_testnet_last_synced_block", 678L)
            .commit()

        // Run migration
        val ran = helper.migrateSyncProgressToRoomIfNeeded()

        // Assert
        assertTrue(ran)
        val mainnet = db.syncProgressDao().get(walletId, "MAINNET")
        val testnet = db.syncProgressDao().get(walletId, "TESTNET")
        assertEquals(12345L, mainnet!!.localSavedBlockNumber)
        assertEquals(12345L, mainnet.lightStartBlockNumber)   // seed = local per Q4=i
        assertEquals(678L, testnet!!.localSavedBlockNumber)
        assertEquals(678L, testnet.lightStartBlockNumber)

        // Prefs keys deleted
        assertFalse(legacyPrefs.contains("${walletId}_mainnet_last_synced_block"))
        assertFalse(legacyPrefs.contains("${walletId}_testnet_last_synced_block"))
        // Guard flag set
        assertTrue(legacyPrefs.getBoolean("sync_progress_migrated_to_room_v7", false))
    }

    @Test
    fun `migrateSyncProgressToRoomIfNeeded is no-op on second run`() = runTest {
        legacyPrefs.edit()
            .putBoolean("sync_progress_migrated_to_room_v7", true)
            .commit()

        val ran = helper.migrateSyncProgressToRoomIfNeeded()
        assertFalse(ran)
    }

    @Test
    fun `migrateSyncProgressToRoomIfNeeded skips wallets with no prefs entry`() = runTest {
        val walletId = "wallet-mig-2"
        walletDao.insert(WalletEntity(
            walletId = walletId, name = "Y", type = "mnemonic",
            derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null,
            accountIndex = 0, mainnetAddress = "ckb1y", testnetAddress = "ckt1y",
            isActive = true, createdAt = 0L
        ))
        // No prefs seeded

        val ran = helper.migrateSyncProgressToRoomIfNeeded()
        assertTrue(ran)  // ran (set guard flag), but no rows written

        assertNull(db.syncProgressDao().get(walletId, "MAINNET"))
        assertNull(db.syncProgressDao().get(walletId, "TESTNET"))
    }

    @Test
    fun `migrateSyncProgressToRoomIfNeeded skips entries with non-positive block`() = runTest {
        val walletId = "wallet-mig-3"
        walletDao.insert(WalletEntity(
            walletId = walletId, name = "Z", type = "mnemonic",
            derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null,
            accountIndex = 0, mainnetAddress = "ckb1z", testnetAddress = "ckt1z",
            isActive = true, createdAt = 0L
        ))
        legacyPrefs.edit()
            .putLong("${walletId}_mainnet_last_synced_block", 0L)
            .commit()

        helper.migrateSyncProgressToRoomIfNeeded()
        assertNull(db.syncProgressDao().get(walletId, "MAINNET"))
    }

    @Test
    fun `migrateSyncProgressToRoomIfNeeded fresh install is no-op`() = runTest {
        // No wallets, no prefs
        val ran = helper.migrateSyncProgressToRoomIfNeeded()
        assertTrue(ran)
        assertTrue(legacyPrefs.getBoolean("sync_progress_migrated_to_room_v7", false))
    }
}
