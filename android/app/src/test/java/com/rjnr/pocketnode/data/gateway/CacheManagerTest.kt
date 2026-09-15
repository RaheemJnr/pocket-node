package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CacheManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var cacheManager: CacheManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheManager = CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `cacheBalance and getCachedBalance round-trip`() = runTest {
        val response = BalanceResponse(
            address = "ckt1qz...",
            capacity = "0x174876e800",
            capacityCkb = "1000.0",
            asOfBlock = "0x100"
        )

        cacheManager.cacheBalance(response, "MAINNET")
        val cached = cacheManager.getCachedBalance("MAINNET")

        assertNotNull(cached)
        assertEquals("ckt1qz...", cached!!.address)
        assertEquals("0x174876e800", cached.capacity)
    }

    @Test
    fun `getCachedBalance returns null for missing network`() = runTest {
        assertNull(cacheManager.getCachedBalance("MAINNET"))
    }

    @Test
    fun `insertPendingTransaction and getPendingNotIn`() = runTest {
        cacheManager.insertPendingTransaction("0xtx1", "MAINNET")
        cacheManager.insertPendingTransaction("0xtx2", "MAINNET")

        val pending = cacheManager.getPendingNotIn("MAINNET", setOf("0xtx1"))
        assertEquals(1, pending.size)
        assertEquals("0xtx2", pending[0].txHash)
    }

    @Test
    fun `cacheTransactions stores records`() = runTest {
        val records = listOf(
            TransactionRecord(
                txHash = "0xabc",
                blockNumber = "0x100",
                blockHash = "0xhash",
                timestamp = 1700000000000L,
                balanceChange = "0x174876e800",
                direction = "in",
                fee = "0x0",
                confirmations = 5
            )
        )

        cacheManager.cacheTransactions(records, "MAINNET")

        val stored = db.transactionDao().getByTxHash("0xabc")
        assertNotNull(stored)
        assertEquals("0x174876e800", stored!!.balanceChange)
        assertEquals("CONFIRMED", stored.status)
    }

    @Test
    fun `clearAll removes all data`() = runTest {
        val response = BalanceResponse("addr", "0x100", "1.0", "0x1")
        cacheManager.cacheBalance(response, "MAINNET")
        cacheManager.insertPendingTransaction("0xtx1", "MAINNET")

        cacheManager.clearAll()

        assertNull(cacheManager.getCachedBalance("MAINNET"))
        assertEquals(0, cacheManager.getPendingNotIn("MAINNET", emptySet()).size)
    }

    // --- Network fee caching (#497) ---

    /**
     * A complete history walk hands `cacheTransactions` every transaction the
     * wallet has ever made. The known-fee lookup behind it binds one SQLite
     * variable per hash, and Android's SQLite refuses past 999 — an unchunked
     * query threw, the catch inside `cacheTransactions` swallowed it, and
     * `insertAll` never ran, so history caching stopped silently for large
     * wallets.
     *
     * The chunk size is asserted directly rather than inferred from a thrown
     * statement: Robolectric links a newer SQLite whose variable ceiling is
     * 32,766, so 1,500 hashes in one statement succeed here and would still
     * break on a real device. Checking what the DAO is actually handed is the
     * only assertion that holds on both.
     */
    @Test
    fun `cacheTransactions chunks the known-fee lookup under the SQLite variable limit`() = runTest {
        val realDao = db.transactionDao()
        val batchSizes = mutableListOf<Int>()
        val spyDao = spyk(realDao)
        coEvery { spyDao.getKnownFees(any()) } coAnswers {
            val hashes = firstArg<List<String>>()
            batchSizes.add(hashes.size)
            realDao.getKnownFees(hashes)
        }
        val manager = CacheManager(spyDao, db.balanceCacheDao(), NoopLogger)

        val records = (0 until 1_500).map { i ->
            record(txHash = "0x" + i.toString().padStart(64, '0'), feeShannons = null)
        }
        manager.cacheTransactions(records, "TESTNET", walletId = "wallet-1")

        assertTrue(
            "no chunk may reach SQLite's 999-variable ceiling: $batchSizes",
            batchSizes.isNotEmpty() && batchSizes.all { it < 999 }
        )
        assertEquals("every hash must still be looked up", 1_500, batchSizes.sum())
        // And the insert itself still ran — the failure mode this guards is a
        // swallowed exception that silently skipped insertAll.
        assertEquals(1_500, realDao.getAllByWalletAndNetwork("wallet-1", "TESTNET").size)
    }

    @Test
    fun `a confirmed row that cannot score its own fee keeps the fee planned when it was sent`() = runTest {
        // The DAO unlock shape: the pending row carries the only fee anyone
        // will ever know, and cacheTransactions inserts with REPLACE.
        cacheManager.insertPendingTransaction(
            txHash = txHashAt(1),
            network = "TESTNET",
            walletId = "wallet-1",
            direction = "out",
            feeShannons = 10_000L,
        )

        cacheManager.cacheTransactions(
            listOf(record(txHash = txHashAt(1), direction = "dao_unlock", feeShannons = null)),
            "TESTNET",
            walletId = "wallet-1",
        )

        assertEquals(10_000L, db.transactionDao().getByTxHash(txHashAt(1))!!.feeShannons)
    }

    @Test
    fun `a freshly computed fee overwrites the planned one`() = runTest {
        cacheManager.insertPendingTransaction(
            txHash = txHashAt(2),
            network = "TESTNET",
            walletId = "wallet-1",
            direction = "out",
            feeShannons = 10_000L,
        )

        cacheManager.cacheTransactions(
            listOf(record(txHash = txHashAt(2), feeShannons = 12_345L)),
            "TESTNET",
            walletId = "wallet-1",
        )

        assertEquals(12_345L, db.transactionDao().getByTxHash(txHashAt(2))!!.feeShannons)
    }

    @Test
    fun `an unknown fee stays null when nothing was ever cached for the hash`() = runTest {
        cacheManager.cacheTransactions(
            listOf(record(txHash = txHashAt(3), direction = "in", feeShannons = null)),
            "TESTNET",
            walletId = "wallet-1",
        )

        assertNull(db.transactionDao().getByTxHash(txHashAt(3))!!.feeShannons)
    }

    private fun txHashAt(i: Int) = "0x" + i.toString().padStart(64, 'a')

    private fun record(
        txHash: String,
        direction: String = "out",
        feeShannons: Long? = null,
    ) = TransactionRecord(
        txHash = txHash,
        blockNumber = "0x100",
        blockHash = "0x" + "cd".repeat(32),
        timestamp = 0L,
        balanceChange = "0x5f5e100",
        direction = direction,
        fee = "0x0",
        confirmations = 12,
        feeShannons = feeShannons,
    )
}
