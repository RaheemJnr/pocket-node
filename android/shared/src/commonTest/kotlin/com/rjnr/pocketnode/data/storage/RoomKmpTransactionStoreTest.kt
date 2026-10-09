package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The store logic that is not SQL: the fee carry-forward, the chunking that
 * protects it, and the local-only filter on the pending merge.
 *
 * The rule under test is `CacheManager.cacheTransactions`'s and it exists
 * because the insert is REPLACE: a confirmed row whose own fee the light client
 * could not resolve must not erase the fee the send path wrote.
 */
class RoomKmpTransactionStoreTest {

    private val dao = FakeTransactionRoomDao()
    private val clock = Clock { NOW }
    private val store = RoomKmpTransactionStore(dao, clock = clock)

    private fun record(
        txHash: String,
        feeShannons: Long? = null,
        confirmations: Int = 3,
        direction: String = "out",
    ) = TransactionRecord(
        txHash = txHash,
        blockNumber = "0x10",
        blockHash = "0xdead",
        timestamp = 0L,
        balanceChange = "0x1",
        direction = direction,
        fee = "0x0",
        confirmations = confirmations,
        feeShannons = feeShannons,
    )

    @Test
    fun `a cached fee survives a confirmed row that could not resolve its own`() = runTest {
        store.cacheTransactions(listOf(record("0xaa", feeShannons = 1_234L)), NETWORK, WALLET)

        // The history walk comes back with no fee for the same transaction.
        store.cacheTransactions(listOf(record("0xaa", feeShannons = null)), NETWORK, WALLET)

        assertEquals(1_234L, dao.rows.single().feeShannons)
    }

    @Test
    fun `a resolved fee overwrites the remembered one`() = runTest {
        store.cacheTransactions(listOf(record("0xaa", feeShannons = 1_234L)), NETWORK, WALLET)
        store.cacheTransactions(listOf(record("0xaa", feeShannons = 9_999L)), NETWORK, WALLET)

        assertEquals(9_999L, dao.rows.single().feeShannons)
    }

    @Test
    fun `the fee lookup is skipped entirely when every record already has one`() = runTest {
        store.cacheTransactions(
            listOf(record("0xaa", feeShannons = 1L), record("0xbb", feeShannons = 2L)),
            NETWORK,
            WALLET,
        )

        assertTrue(dao.knownFeeQueries.isEmpty(), "no record was missing a fee, so nothing to look up")
    }

    @Test
    fun `one record without a fee looks up the whole batch in chunks`() = runTest {
        // 2,000 hashes is past SQLite's 999 bound-variable limit. Unchunked,
        // the query would throw, the catch would swallow it, and insertAll
        // would never run: history caching would stop silently.
        val records = (0 until 2_000).map { record("0x${it.toString(16)}") }

        store.cacheTransactions(records, NETWORK, WALLET)

        assertTrue(dao.knownFeeQueries.size > 1, "the lookup was chunked")
        assertTrue(dao.knownFeeQueries.all { it.size <= 900 }, "every chunk is within the limit")
        assertEquals(2_000, dao.rows.size, "and every record was still written")
    }

    @Test
    fun `status and cachedAt come from the confirmation count and the clock`() = runTest {
        store.cacheTransactions(
            listOf(record("0xaa", confirmations = 0), record("0xbb", confirmations = 1)),
            NETWORK,
            WALLET,
        )

        val pending = dao.rows.first { it.txHash == "0xaa" }
        val confirmed = dao.rows.first { it.txHash == "0xbb" }
        assertEquals("PENDING", pending.status)
        assertEquals("CONFIRMED", confirmed.status)
        assertEquals(NOW, confirmed.cachedAt)
        assertTrue(!confirmed.isLocal, "a walked row is not a local one")
    }

    @Test
    fun `the pending merge returns only local rows the caller has not seen`() = runTest {
        store.insertPending("0xlocal", NETWORK, WALLET)
        store.insertPending("0xseen", NETWORK, WALLET)
        // A walked PENDING row is not local, so it must not be merged back in:
        // the light client is already reporting it.
        store.cacheTransactions(listOf(record("0xwalked", confirmations = 0)), NETWORK, WALLET)

        val merged = store.getPendingNotIn(NETWORK, setOf("0xseen"), WALLET)

        assertEquals(listOf("0xlocal"), merged.map { it.txHash })
    }

    @Test
    fun `a locally inserted pending row carries the clock's time and its planned fee`() = runTest {
        store.insertPending(
            txHash = "0xlocal",
            network = NETWORK,
            walletId = WALLET,
            balanceChange = "0x5f5e100",
            direction = "out",
            fee = "0x0",
            feeShannons = 100_000L,
        )

        val row = dao.rows.single()
        assertEquals(NOW, row.timestamp)
        assertEquals(NOW, row.cachedAt)
        assertEquals(100_000L, row.feeShannons)
        assertTrue(row.isLocal)
        assertEquals("PENDING", row.status)
    }

    @Test
    fun `deleting a transaction removes it`() = runTest {
        store.insertPending("0xlocal", NETWORK, WALLET)
        store.deleteTransaction("0xlocal", NETWORK)

        assertTrue(dao.rows.isEmpty())
        assertNull(store.getByHash("0xlocal", NETWORK))
    }

    @Test
    fun `paging filters by direction and pages by offset`() = runTest {
        store.cacheTransactions(
            listOf(
                record("0x01", direction = "in"),
                record("0x02", direction = "out"),
                record("0x03", direction = "dao_unlock"),
                record("0x04", direction = "dao_deposit"),
            ),
            NETWORK,
            WALLET,
        )

        val received = store.page(WALLET, NETWORK, listOf("in", "dao_unlock"), 10, 0)
        assertEquals(setOf("0x01", "0x03"), received.map { it.txHash }.toSet())

        val firstPage = store.page(WALLET, NETWORK, emptyList(), 2, 0)
        val secondPage = store.page(WALLET, NETWORK, emptyList(), 2, 2)
        assertEquals(2, firstPage.size)
        assertEquals(2, secondPage.size)
        assertTrue(
            (firstPage + secondPage).map { it.txHash }.toSet().size == 4,
            "the two pages do not overlap",
        )
    }

    private companion object {
        const val NETWORK = "TESTNET"
        const val WALLET = "w1"
        const val NOW = 1_726_000_000_000L
    }
}
