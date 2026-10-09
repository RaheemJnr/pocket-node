package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three tables the activity read path added, against a real database on the simulator.
 *
 * What an in-memory fake cannot prove is here and nowhere else: the SQL of the
 * pending-first sort with its `rowid` tie-break (which is what makes offset
 * paging safe), the `getKnownFees` null filter behind the fee carry-forward,
 * the CAS row count the broadcast state machine depends on, and the `NOT IN
 * (SELECT txHash FROM pending_broadcasts)` join behind the orphan query.
 */
class ActivityTablesIosTest {

    private val path = NSTemporaryDirectory() + "m3-activity-${Random.nextLong()}.db"
    private val db = createIosPocketNodeCoreDatabase(path)
    private val clock = Clock { NOW }
    private val transactions = RoomKmpTransactionStore(db.transactions(), clock = clock)
    private val broadcasts = RoomKmpPendingBroadcastStore(db.pendingBroadcasts())
    private val balances = RoomKmpBalanceCache(db.balanceCache(), clock = clock)

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun tearDown() {
        db.close()
        val files = NSFileManager.defaultManager
        listOf(path, "$path-wal", "$path-shm").forEach { files.removeItemAtPath(it, null) }
    }

    private fun record(
        txHash: String,
        direction: String = "out",
        confirmations: Int = 3,
        feeShannons: Long? = null,
        timestamp: Long = 0L,
        blockNumber: String = "0x10",
    ) = TransactionRecord(
        txHash = txHash,
        blockNumber = blockNumber,
        blockHash = "0xdead",
        timestamp = timestamp,
        balanceChange = "0x5f5e100",
        direction = direction,
        fee = "0x0",
        confirmations = confirmations,
        feeShannons = feeShannons,
    )

    private fun broadcastRow(
        txHash: String,
        state: String = "BROADCASTING",
        nullCount: Int = 0,
    ) = PendingBroadcastRecord(
        txHash = txHash,
        state = state,
        reservedInputs = "[]",
        signedTxJson = """{"hash":"$txHash"}""",
        walletId = WALLET,
        network = NETWORK,
        submittedAtTipBlock = 1_000L,
        nullCount = nullCount,
        createdAt = NOW,
        lastCheckedAt = NOW,
    )

    // ---- transactions ----

    @Test
    fun `a transaction round trips through the table unchanged`() = runTest {
        transactions.cacheTransactions(
            listOf(record("0xaa", feeShannons = 4_321L)),
            NETWORK,
            WALLET,
        )

        val row = assertNotNull(transactions.getByHash("0xaa", NETWORK))
        assertEquals("0xaa", row.txHash)
        assertEquals("0x10", row.blockNumber)
        assertEquals("out", row.direction)
        assertEquals(3, row.confirmations)
        assertEquals(4_321L, row.feeShannons)
        assertEquals("CONFIRMED", row.status)
    }

    @Test
    fun `a rewrite without a fee keeps the fee already stored`() = runTest {
        transactions.cacheTransactions(listOf(record("0xaa", feeShannons = 4_321L)), NETWORK, WALLET)
        transactions.cacheTransactions(listOf(record("0xaa", feeShannons = null)), NETWORK, WALLET)

        assertEquals(4_321L, transactions.getByHash("0xaa", NETWORK)?.feeShannons)
    }

    @Test
    fun `the same hash on two networks is two rows`() = runTest {
        transactions.cacheTransactions(listOf(record("0xaa")), NETWORK, WALLET)
        transactions.cacheTransactions(listOf(record("0xaa")), "MAINNET", WALLET)

        assertNotNull(transactions.getByHash("0xaa", NETWORK))
        assertNotNull(transactions.getByHash("0xaa", "MAINNET"))
        assertEquals(1, transactions.page(WALLET, NETWORK, emptyList(), 50, 0).size)
    }

    @Test
    fun `paging sorts pending first and does not repeat a row across pages`() = runTest {
        // Every confirmed row has timestamp 0 (the history walk writes the
        // block timestamp to its own column, not here), so without the rowid
        // tie-break the two pages below could overlap or skip.
        transactions.cacheTransactions(
            (0 until 25).map { record("0x${it.toString(16)}") },
            NETWORK,
            WALLET,
        )
        transactions.insertPending("0xpending", NETWORK, WALLET)

        val first = transactions.page(WALLET, NETWORK, emptyList(), 20, 0)
        val second = transactions.page(WALLET, NETWORK, emptyList(), 20, 20)

        assertEquals("0xpending", first.first().txHash, "the pending row sorts first")
        assertEquals(20, first.size)
        assertEquals(6, second.size)
        assertEquals(26, (first + second).map { it.txHash }.toSet().size, "no overlap, no gap")
    }

    @Test
    fun `a stale row cached late sorts below the newer blocks it was written after`() = runTest {
        // The history walk re-caches rows whenever it runs, so insertion order
        // says when a row was last WRITTEN, not when it happened. This is the
        // case the old rowid-only tie-break got wrong: an old block written
        // last would sort to the top of the list.
        transactions.cacheTransactions(listOf(record("0xnew", blockNumber = "0x1170ea8")), NETWORK, WALLET)
        transactions.cacheTransactions(listOf(record("0xmid", blockNumber = "0x1170e00")), NETWORK, WALLET)
        transactions.cacheTransactions(listOf(record("0xold", blockNumber = "0xc")), NETWORK, WALLET)

        val page = transactions.page(WALLET, NETWORK, emptyList(), 50, 0)

        assertEquals(
            listOf("0xnew", "0xmid", "0xold"),
            page.map { it.txHash },
            "newest block first, whatever order they were written in",
        )
    }

    @Test
    fun `newest first survives a page boundary`() = runTest {
        // Blocks 1 through 25, inserted oldest first so insertion order is the
        // exact opposite of the order the list must show.
        transactions.cacheTransactions(
            (1..25).map { record("0x" + it.toString(16), blockNumber = "0x" + it.toString(16)) },
            NETWORK,
            WALLET,
        )

        val first = transactions.page(WALLET, NETWORK, emptyList(), 20, 0)
        val second = transactions.page(WALLET, NETWORK, emptyList(), 20, 20)
        val blocks = (first + second).map { it.blockNumber.removePrefix("0x").toLong(16) }

        assertEquals(25, blocks.size)
        assertEquals(blocks.sortedDescending(), blocks, "descending by block across both pages")
        assertEquals(25L, blocks.first())
        assertEquals(1L, blocks.last())
    }

    @Test
    fun `a filtered page narrows to the tab's directions`() = runTest {
        transactions.cacheTransactions(
            listOf(
                record("0x01", direction = "in"),
                record("0x02", direction = "out"),
                record("0x03", direction = "dao_unlock"),
                record("0x04", direction = "dao_deposit"),
            ),
            NETWORK,
            WALLET,
        )

        assertEquals(
            setOf("0x01", "0x03"),
            transactions.page(WALLET, NETWORK, listOf("in", "dao_unlock"), 50, 0)
                .map { it.txHash }.toSet(),
        )
        assertEquals(
            setOf("0x02", "0x04"),
            transactions.page(WALLET, NETWORK, listOf("out", "self", "dao_deposit", "dao_withdraw"), 50, 0)
                .map { it.txHash }.toSet(),
        )
    }

    @Test
    fun `the orphan query only names pending rows with no broadcast row`() = runTest {
        transactions.insertPending("0xorphan", NETWORK, WALLET)
        transactions.insertPending("0xwatched", NETWORK, WALLET)
        broadcasts.insert(broadcastRow("0xwatched"))

        assertEquals(listOf("0xorphan"), transactions.getOrphanPendingHashes(WALLET, NETWORK))
    }

    @Test
    fun `a status update propagates rather than being swallowed`() = runTest {
        transactions.insertPending("0xaa", NETWORK, WALLET)
        transactions.updateTransactionStatus("0xaa", "FAILED")

        assertEquals("FAILED", transactions.getByHash("0xaa", NETWORK)?.status)
    }

    // ---- pending_broadcasts ----

    @Test
    fun `a broadcast row round trips with all ten columns`() = runTest {
        broadcasts.insert(broadcastRow("0xaa", state = "BROADCAST", nullCount = 2))

        val active = broadcasts.getActive(WALLET, NETWORK).single()
        assertEquals("0xaa", active.txHash)
        assertEquals("BROADCAST", active.state)
        assertEquals("[]", active.reservedInputs)
        assertEquals("""{"hash":"0xaa"}""", active.signedTxJson)

        val row = broadcasts.observeAll(WALLET, NETWORK).first().single()
        assertEquals(2, row.nullCount)
        assertEquals(1_000L, row.submittedAtTipBlock)
        assertEquals(NOW, row.createdAt)
    }

    @Test
    fun `the CAS writes only when the current state matches`() = runTest {
        broadcasts.insert(broadcastRow("0xaa", state = "BROADCASTING"))

        assertEquals(
            0,
            broadcasts.compareAndUpdateState("0xaa", "BROADCAST", "CONFIRMED", NOW),
            "the expected state did not match, so nothing was written",
        )
        assertEquals(1, broadcasts.compareAndUpdateState("0xaa", "BROADCASTING", "BROADCAST", NOW))
        assertEquals("BROADCAST", broadcasts.getActive(WALLET, NETWORK).single().state)
    }

    @Test
    fun `getActive hides terminal rows and getFailedRow finds them`() = runTest {
        broadcasts.insert(broadcastRow("0xlive", state = "BROADCASTING"))
        broadcasts.insert(broadcastRow("0xdone", state = "CONFIRMED"))
        broadcasts.insert(broadcastRow("0xdead", state = "FAILED", nullCount = 5))

        assertEquals(listOf("0xlive"), broadcasts.getActive(WALLET, NETWORK).map { it.txHash })
        assertEquals(5, broadcasts.getFailedRow("0xdead")?.nullCount)
        assertNull(broadcasts.getFailedRow("0xlive"), "a live row is not a failed one")
        // observeAll is the UI's view and keeps the terminal rows.
        assertEquals(3, broadcasts.observeAll(WALLET, NETWORK).first().size)
    }

    @Test
    fun `updateNullCount and delete reach the row`() = runTest {
        broadcasts.insert(broadcastRow("0xaa"))
        broadcasts.updateNullCount("0xaa", 3, NOW + 1)
        assertEquals(3, broadcasts.observeAll(WALLET, NETWORK).first().single().nullCount)

        broadcasts.delete("0xaa")
        assertTrue(broadcasts.observeAll(WALLET, NETWORK).first().isEmpty())
    }

    // ---- balance_cache ----

    @Test
    fun `a balance round trips and one row is kept per wallet and network`() = runTest {
        val first = BalanceResponse("ckt1...", "0x64", "0.000001", "0x10")
        val second = BalanceResponse("ckt1...", "0xc8", "0.000002", "0x11")

        balances.cacheBalance(first, NETWORK, WALLET)
        assertEquals(first, balances.getCachedBalance(NETWORK, WALLET))

        balances.cacheBalance(second, NETWORK, WALLET)
        assertEquals(second, balances.getCachedBalance(NETWORK, WALLET))
        assertNull(balances.getCachedBalance("MAINNET", WALLET), "the other network is untouched")
    }

    private companion object {
        const val NETWORK = "TESTNET"
        const val WALLET = "w1"
        const val NOW = 1_726_000_000_000L
    }
}
