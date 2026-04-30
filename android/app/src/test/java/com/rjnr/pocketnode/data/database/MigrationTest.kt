package com.rjnr.pocketnode.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity

/**
 * Verifies that the v2 database schema is correct and all four tables
 * (transactions, balance_cache, header_cache, dao_cells) are accessible.
 *
 * NOTE: These tests use an in-memory DB which creates a fresh v2 schema,
 * so MIGRATION_1_2 SQL is not actually exercised here. For true migration
 * path testing (v1 → v2 with data preservation), use MigrationTestHelper
 * in an instrumented test (src/androidTest/) with exported schemas.
 * TODO: Add instrumented migration test with MigrationTestHelper.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `v2 database has all four tables accessible`() = runTest {
        // Phase 1 tables still work
        assertNull(db.balanceCacheDao().getByNetwork("MAINNET"))
        assertEquals(0, db.transactionDao().getPending("MAINNET").size)

        // Phase 2 tables work
        assertNull(db.headerCacheDao().getByBlockHash("0xtest"))
        assertEquals(0, db.daoCellDao().getActiveByNetwork("MAINNET").size)
    }

    @Test
    fun `v2 header_cache table has correct schema`() = runTest {
        val entity = com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity(
            blockHash = "0xmigtest",
            number = "0x100",
            epoch = "0x7080291000032",
            timestamp = "0x18c8d0a7a00",
            dao = "0x40b4d9a3ddc9e730",
            network = "MAINNET",
            cachedAt = System.currentTimeMillis()
        )
        db.headerCacheDao().upsert(entity)

        val result = db.headerCacheDao().getByBlockHash("0xmigtest")
        assertNotNull(result)
        assertEquals("0x100", result!!.number)
    }

    @Test
    fun `v2 dao_cells table has correct schema`() = runTest {
        val entity = com.rjnr.pocketnode.data.database.entity.DaoCellEntity(
            txHash = "0xmigtest",
            index = "0x0",
            capacity = 10_200_000_000L,
            status = "DEPOSITED",
            depositBlockNumber = 100L,
            depositBlockHash = "0xblockhash",
            depositEpochHex = "0x7080291000032",
            withdrawBlockNumber = null,
            withdrawBlockHash = null,
            withdrawEpochHex = null,
            compensation = 500_000L,
            unlockEpochHex = null,
            depositTimestamp = 1700000000000L,
            network = "MAINNET",
            lastUpdatedAt = System.currentTimeMillis()
        )
        db.daoCellDao().upsert(entity)

        val result = db.daoCellDao().getByOutPoint("0xmigtest", "0x0")
        assertNotNull(result)
        assertEquals("DEPOSITED", result!!.status)
        assertEquals(10_200_000_000L, result.capacity)
    }

    @Test
    fun `v5 database has key_material table accessible`() = runTest {
        assertEquals(0, db.keyMaterialDao().count())
    }

    @Test
    fun `v5 key_material entity round-trip`() = runTest {
        val entity = KeyMaterialEntity(
            walletId = "test-wallet",
            encryptedPrivateKey = byteArrayOf(1, 2, 3),
            encryptedMnemonic = byteArrayOf(4, 5, 6),
            iv = byteArrayOf(7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18),
            walletType = "mnemonic",
            mnemonicBackedUp = true,
            updatedAt = System.currentTimeMillis()
        )
        db.keyMaterialDao().upsert(entity)

        val loaded = db.keyMaterialDao().getByWalletId("test-wallet")
        assertNotNull(loaded)
        assertEquals("test-wallet", loaded!!.walletId)
        assertArrayEquals(byteArrayOf(1, 2, 3), loaded.encryptedPrivateKey)
        assertArrayEquals(byteArrayOf(4, 5, 6), loaded.encryptedMnemonic)
        assertEquals("mnemonic", loaded.walletType)
        assertTrue(loaded.mnemonicBackedUp)
    }

    @Test
    fun `v5 key_material nullable mnemonic`() = runTest {
        val entity = KeyMaterialEntity(
            walletId = "raw-key-wallet",
            encryptedPrivateKey = byteArrayOf(10, 20, 30),
            encryptedMnemonic = null,
            iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            walletType = "raw_key",
            mnemonicBackedUp = false,
            updatedAt = System.currentTimeMillis()
        )
        db.keyMaterialDao().upsert(entity)

        val loaded = db.keyMaterialDao().getByWalletId("raw-key-wallet")
        assertNotNull(loaded)
        assertNull(loaded!!.encryptedMnemonic)
        assertEquals("raw_key", loaded.walletType)
    }

    @Test
    fun `v5 key_material updateMnemonicBackedUp`() = runTest {
        val entity = KeyMaterialEntity(
            walletId = "w1",
            encryptedPrivateKey = byteArrayOf(1),
            encryptedMnemonic = null,
            iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            walletType = "mnemonic",
            mnemonicBackedUp = false,
            updatedAt = 100L
        )
        db.keyMaterialDao().upsert(entity)

        db.keyMaterialDao().updateMnemonicBackedUp("w1", true, 200L)

        val loaded = db.keyMaterialDao().getByWalletId("w1")
        assertTrue(loaded!!.mnemonicBackedUp)
        assertEquals(200L, loaded.updatedAt)
    }

    @Test
    fun `v5 key_material delete`() = runTest {
        val entity = KeyMaterialEntity(
            walletId = "to-delete",
            encryptedPrivateKey = byteArrayOf(1),
            encryptedMnemonic = null,
            iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            walletType = "raw_key",
            mnemonicBackedUp = false,
            updatedAt = 0L
        )
        db.keyMaterialDao().upsert(entity)
        assertEquals(1, db.keyMaterialDao().count())

        db.keyMaterialDao().delete("to-delete")
        assertEquals(0, db.keyMaterialDao().count())
    }

    @Test
    fun `v8 pending_broadcasts table is accessible`() = runTest {
        val dao = db.pendingBroadcastDao()
        assertEquals(0, dao.getActive("any", "TESTNET").size)
    }

    @Test
    fun `v7 sync_progress table is accessible and round-trips`() = runTest {
        val entity = com.rjnr.pocketnode.data.database.entity.SyncProgressEntity(
            walletId = "w-mig",
            network = "MAINNET",
            lightStartBlockNumber = 1000L,
            localSavedBlockNumber = 1500L,
            updatedAt = System.currentTimeMillis()
        )
        db.syncProgressDao().upsert(entity)

        val r = db.syncProgressDao().get("w-mig", "MAINNET")
        assertNotNull(r)
        assertEquals(1500L, r!!.localSavedBlockNumber)
        assertEquals(1000L, r.lightStartBlockNumber)
    }
}
