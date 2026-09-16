package com.rjnr.pocketnode.data.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round trips for the four Room bindings of the shared storage seams (M3 #3).
 *
 * The seams are what `SyncCoordinator` now reads and writes through, so an
 * entity-to-record mapping that drops or transposes a column would show up as
 * a wrong sync start block, a wallet whose lock script cannot be recovered, or
 * a candidate that never retires. Each adapter is exercised against the real
 * in-memory database rather than a mocked DAO, so the column names and the
 * mapping are proved together.
 */
@RunWith(RobolectricTestRunner::class)
class RoomStoreAdaptersTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `sync progress round trips through the store`() = runTest {
        val store = RoomSyncProgressStore(db.syncProgressDao())
        val record = SyncProgressRecord(
            walletId = "w1",
            network = "TESTNET",
            lightStartBlockNumber = 1_000L,
            localSavedBlockNumber = 2_000L,
            updatedAt = 42L,
        )

        store.upsert(record)

        assertEquals(listOf(record), store.getAllForNetwork("TESTNET"))
        assertEquals(emptyList<SyncProgressRecord>(), store.getAllForNetwork("MAINNET"))
    }

    @Test
    fun `updateLightStart reports the row count and preserves local progress`() = runTest {
        val store = RoomSyncProgressStore(db.syncProgressDao())

        // No row yet: 0 rows affected is the signal the coordinator falls back
        // to upsert on.
        assertEquals(0, store.updateLightStart("w1", "TESTNET", 500L, 7L))

        store.upsert(
            SyncProgressRecord(
                walletId = "w1",
                network = "TESTNET",
                lightStartBlockNumber = 1_000L,
                localSavedBlockNumber = 2_000L,
                updatedAt = 42L,
            )
        )
        assertEquals(1, store.updateLightStart("w1", "TESTNET", 500L, 99L))

        val updated = store.getAllForNetwork("TESTNET").single()
        assertEquals(500L, updated.lightStartBlockNumber)
        assertEquals(2_000L, updated.localSavedBlockNumber)
        assertEquals(99L, updated.updatedAt)
    }

    @Test
    fun `wallet entity maps onto the fields the sync code reads`() = runTest {
        db.walletDao().insert(
            WalletEntity(
                walletId = "w1",
                name = "Main",
                type = "mnemonic",
                derivationPath = "m/44'/309'/0'/0/0",
                parentWalletId = null,
                accountIndex = 0,
                mainnetAddress = "ckb1main",
                testnetAddress = "ckt1test",
                isActive = true,
                createdAt = 1L,
                lastActiveAt = 5L,
            )
        )

        assertEquals(
            listOf(WalletRecord("w1", "ckb1main", "ckt1test", lastActiveAt = 5L)),
            RoomWalletRegistry(db.walletDao()).allWallets(),
        )
    }

    @Test
    fun `candidate maps with its state constant and keeps the deepest scan start`() = runTest {
        val store = RoomSubAccountCandidateStore(db.subAccountCandidateDao())
        val path = "m/44'/309'/0'/1/4"
        db.subAccountCandidateDao().insertAll(
            listOf(
                SubAccountCandidateEntity(
                    parentWalletId = "p",
                    derivationPath = path,
                    accountIndex = 0,
                    scriptArgs = "0xcc",
                    state = SubAccountCandidateEntity.STATE_FOUND,
                    createdAt = 1L,
                )
            )
        )

        val loaded = store.getForParent("p").single()
        assertEquals(
            SubAccountCandidateRecord(
                parentWalletId = "p",
                derivationPath = path,
                accountIndex = 0,
                scriptArgs = "0xcc",
                state = SubAccountCandidateRecord.STATE_FOUND,
                registeredFromBlock = 0L,
            ),
            loaded,
        )
        // The record's constants must be the same strings the entity stores,
        // or every state comparison in the shared coordinator silently fails.
        assertEquals(SubAccountCandidateEntity.STATE_FOUND, SubAccountCandidateRecord.STATE_FOUND)

        store.updateRegisteredFrom("p", path, 256L)
        assertEquals(256L, store.getForParent("p").single().registeredFromBlock)
        // Keep-min: a shallower later registration never erases deeper coverage.
        store.updateRegisteredFrom("p", path, 36_864L)
        assertEquals(256L, store.getForParent("p").single().registeredFromBlock)
    }

    @Test
    fun `transaction store returns the cached block numbers for one wallet and network`() = runTest {
        db.transactionDao().insert(transaction("tx1", "w1", "TESTNET", "0x64"))
        db.transactionDao().insert(transaction("tx2", "w1", "TESTNET", "0xc8"))
        db.transactionDao().insert(transaction("tx3", "w2", "TESTNET", "0x12c"))
        db.transactionDao().insert(transaction("tx4", "w1", "MAINNET", "0x190"))

        assertEquals(
            listOf("0x64", "0xc8"),
            RoomTransactionStore(db.transactionDao()).getBlockNumbers("w1", "TESTNET").sorted(),
        )
    }

    private fun transaction(
        hash: String,
        walletId: String,
        network: String,
        blockNumber: String,
    ) = TransactionEntity(
        txHash = hash,
        blockNumber = blockNumber,
        blockHash = "0x00",
        timestamp = 0L,
        balanceChange = "0",
        direction = "in",
        fee = "0",
        confirmations = 1,
        blockTimestampHex = null,
        network = network,
        status = "CONFIRMED",
        isLocal = false,
        cachedAt = 0L,
        walletId = walletId,
    )
}
