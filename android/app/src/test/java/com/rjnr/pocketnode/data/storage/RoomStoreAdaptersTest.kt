package com.rjnr.pocketnode.data.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.CacheManager
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round trips for the Room bindings of the shared storage seams.
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
            transactionStore().getBlockNumbers("w1", "TESTNET").sorted(),
        )
    }

    @Test
    fun `transaction store round trips a cached history, its pending rows and a status move`() = runTest {
        val store = transactionStore()
        val confirmed = record("tx1", "in", status = "CONFIRMED")

        store.cacheTransactions(listOf(confirmed), "TESTNET", "w1")
        db.transactionDao().insert(
            transaction("tx2", "w1", "TESTNET", "").copy(
                status = "PENDING",
                isLocal = true,
            )
        )

        // The confirmed row came from the light client, so only the local
        // pending one is merged back into the feed.
        assertEquals(
            listOf("tx2"),
            store.getPendingNotIn("TESTNET", setOf("tx1"), "w1").map { it.txHash },
        )
        assertEquals(emptyList<String>(), store.getPendingNotIn("TESTNET", setOf("tx2"), "w1").map { it.txHash })
        assertEquals(listOf("tx2"), store.getOrphanPendingHashes("w1", "TESTNET"))

        store.updateTransactionStatus("tx2", "FAILED")
        assertEquals(emptyList<String>(), store.getOrphanPendingHashes("w1", "TESTNET"))
    }

    @Test
    fun `balance cache round trips through the store`() = runTest {
        val store = RoomBalanceCache(
            CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger)
        )
        val balance = BalanceResponse(
            address = "ckt1qexample",
            capacity = "0x374f10a5f2",
            capacityCkb = "2378.24357362",
            asOfBlock = "0x156445c",
        )

        store.cacheBalance(balance, "TESTNET", "w1")

        assertEquals(balance, store.getCachedBalance("TESTNET", "w1"))
        // Scoped by wallet AND network: neither key alone may answer.
        assertNull(store.getCachedBalance("MAINNET", "w1"))
        assertNull(store.getCachedBalance("TESTNET", "w2"))
    }

    @Test
    fun `header cache round trips every field the history read uses`() = runTest {
        val store = RoomHeaderCache(db.headerCacheDao())
        val header = JniHeaderView(
            hash = "0xfb27201670e48f65b93b58c4cac7348c54554ad831ed5c1b386c9bd3c24fa911",
            number = "0xc",
            epoch = "0x3e8000c000000",
            timestamp = "0x1723b9a0f32",
            parentHash = "0x18b8cda2aecd83b25df917e28fde1bff31f032085463dd805074df0e68edeec6",
            transactionsRoot = "0x556ef527219d99eaf3909a715facd3579e3afb8a50f79e00bc58ecff0da1b847",
            proposalsHash = "0x00",
            extraHash = "0xad22239f5cf73ca4a75261ac3ea96169e8543226b143205d0f6987a26c8aa39b",
            dao = "0x343d33782f21a12e7fe864f2f2862300b9e94907a0000000004f4b0b04fbfe06",
            nonce = "0x50e30c2a3df9de17fd0d1abb3b5e9206",
        )

        store.put(header, "TESTNET")

        val read = checkNotNull(store.get(header.hash))
        // The five fields the row actually stores must survive the round trip;
        // the rest of the header is not stored and comes back empty by design.
        assertEquals(header.hash, read.hash)
        assertEquals(header.number, read.number)
        assertEquals(header.timestamp, read.timestamp)
        assertEquals(header.epoch, read.epoch)
        assertEquals(header.dao, read.dao)
        assertNull(store.get("0xdeadbeef"))
    }

    @Test
    fun `pending broadcast store returns only the active rows for one wallet and network`() = runTest {
        val store = RoomPendingBroadcastStore(db.pendingBroadcastDao())
        db.pendingBroadcastDao().insert(broadcast("tx1", "w1", "TESTNET", "BROADCASTING"))
        db.pendingBroadcastDao().insert(broadcast("tx2", "w1", "TESTNET", "CONFIRMED"))
        db.pendingBroadcastDao().insert(broadcast("tx3", "w1", "MAINNET", "BROADCAST"))
        db.pendingBroadcastDao().insert(broadcast("tx4", "w2", "TESTNET", "BROADCAST"))

        val active = store.getActive("w1", "TESTNET")

        assertEquals(listOf("tx1"), active.map { it.txHash })
        assertEquals(PendingBroadcastRecord.STATE_BROADCASTING, active.single().state)
        assertEquals("[]", active.single().reservedInputs)
        assertEquals("{}", active.single().signedTxJson)
    }

    private fun record(
        hash: String,
        direction: String,
        status: String,
    ) = TransactionRecord(
        txHash = hash,
        blockNumber = "0xc",
        blockHash = "0x00",
        timestamp = 0L,
        balanceChange = "0x1",
        direction = direction,
        fee = "0x0",
        confirmations = 1,
        status = status,
    )

    private fun broadcast(
        hash: String,
        walletId: String,
        network: String,
        state: String,
    ) = PendingBroadcastEntity(
        txHash = hash,
        walletId = walletId,
        network = network,
        signedTxJson = "{}",
        reservedInputs = "[]",
        state = state,
        submittedAtTipBlock = 0L,
        nullCount = 0,
        createdAt = 0L,
        lastCheckedAt = 0L,
    )

    private fun transactionStore() = RoomTransactionStore(
        db.transactionDao(),
        CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger),
    )

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
