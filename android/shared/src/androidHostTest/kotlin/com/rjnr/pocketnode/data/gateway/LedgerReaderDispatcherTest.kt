package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.prefs.FakeUiPreferences
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.storage.*
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Real separate dispatchers: blocking native admission must not occupy the UI caller. */
class LedgerReaderDispatcherTest {
    private val script = Script(Script.SECP256K1_CODE_HASH, "type", LightClientFixtures.LOCK_ARGS)
    private val address = AddressUtils.encode(script, NetworkType.TESTNET)
    private fun reader(api: LightClientApi, context: CoroutineContext, store: TransactionStore = FakeTransactionStore()) = LedgerReader(
        api, FakeBalanceCache(), store, FakeHeaderCache(), FakeWalletRegistry(),
        FakeSubAccountCandidateStore(), FakeSyncPreferences(), FakeUiPreferences(),
        Json { ignoreUnknownKeys = true }, NoopLogger, context,
    )
    private open class EmptyHistoryApi : FakeLightClientApi() {
        override fun getTransactions(searchKeyJson: String, order: String, limit: Int, cursor: String?) =
            """{"objects":[],"last_cursor":""}"""
    }

    @Test fun `send and both balance walks run every cell bridge call on query dispatcher`() = runBlocking {
        val caller = Thread.currentThread()
        val cells = AtomicInteger()
        val capacities = AtomicInteger()
        val worker = AtomicReference<Thread>()
        Executors.newSingleThreadExecutor { Thread(it, "ledger-query-test").also(worker::set) }.asCoroutineDispatcher().use { query ->
            val api = object : EmptyHistoryApi() {
                override fun getCells(searchKeyJson: String, order: String, limit: Int, cursor: String?): String {
                    assertNotSame(caller, Thread.currentThread())
                    assertSame(worker.get(), Thread.currentThread())
                    cells.incrementAndGet()
                    return LightClientFixtures.GET_CELLS_PAGE
                }
                override fun getCellsCapacity(searchKeyJson: String): String {
                    assertNotSame(caller, Thread.currentThread())
                    assertSame(worker.get(), Thread.currentThread())
                    capacities.incrementAndGet()
                    return LightClientFixtures.CELLS_CAPACITY
                }
            }
            val ledger = reader(api, query)
            ledger.getCells(script).getOrThrow()
            ledger.readBalance(address, script, NetworkType.TESTNET, "wallet", { false }, mutableSetOf(),
                {}, {}).getOrThrow()
            ledger.readBalanceForWallet("wallet", address, NetworkType.TESTNET).getOrThrow()
            assertEquals(3, cells.get())
            assertEquals(2, capacities.get())
            assertSame(caller, Thread.currentThread())
        }
    }

    @Test fun `blocked cell query leaves caller free and cancellation drops result before another page`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val cells = AtomicInteger()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { query ->
            val api = object : EmptyHistoryApi() {
                override fun getCells(searchKeyJson: String, order: String, limit: Int, cursor: String?): String {
                    cells.incrementAndGet()
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS)) { "caller could not release blocked bridge" }
                    return LightClientFixtures.GET_CELLS_PAGE
                }
            }
            val operation = async { reader(api, query).getCells(script, limit = 1).getOrThrow() }
            try {
                withTimeout(5_000) { entered.await() }
                // This continuation must run while the synchronous native call is still blocked.
                assertFalse(operation.isCompleted)
                operation.cancel()
                release.countDown()
                operation.join()
                assertTrue(operation.isCancelled)
                assertEquals(1, cells.get())
            } finally {
                release.countDown()
                operation.cancelAndJoin()
            }
        }
    }

    private fun proof() = CanonicalHistorySnapshot(1, true, "TESTNET", "0x" + "1".repeat(64),
        "store", script, "a".repeat(32), "0", "20", "0x" + "2".repeat(64), "20", emptySet())
    private fun cachedReceipt() = TransactionRow("0x" + "a".repeat(64), "0xa", "old", 0,
        "0x1", "in", "0x0", 2, null, "TESTNET", "CONFIRMED", false, 10, "wallet")

    @Test fun `initial history snapshot runs on query dispatcher`() = assertHistoryDispatcher(1)
    @Test fun `history revalidation runs on query dispatcher after candidate read`() = assertHistoryDispatcher(2)

    private fun assertHistoryDispatcher(checkedCall: Int) = runBlocking {
        val caller = Thread.currentThread()
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val dao = FakeTransactionRoomDao().also { it.rows += cachedReceipt() }
        Executors.newSingleThreadExecutor { Thread(it, "history-query-test").also(worker::set) }.asCoroutineDispatcher().use { query ->
            val api = object : EmptyHistoryApi() {
                override fun getHistorySnapshot(scriptJson: String): String {
                    assertEquals(script, Json.decodeFromString<Script>(scriptJson))
                    if (calls.incrementAndGet() == checkedCall) {
                        assertNotSame(caller, Thread.currentThread())
                        assertSame(worker.get(), Thread.currentThread())
                    }
                    return Json.encodeToString(proof())
                }
            }
            reader(api, query, RoomKmpTransactionStore(dao)).getTransactions(script, "wallet", NetworkType.TESTNET).getOrThrow()
            assertEquals(2, calls.get())
            assertTrue(dao.rows.isEmpty(), "matching revalidation removes the proven orphan")
            assertSame(caller, Thread.currentThread())
        }
    }

    @Test fun `cancelling blocked initial snapshot retains cached receipt`() = assertHistoryCancellation(1)
    @Test fun `cancelling blocked revalidation retains cached receipt`() = assertHistoryCancellation(2)

    private fun assertHistoryCancellation(blockedCall: Int) = runBlocking {
        val caller = Thread.currentThread()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val dao = FakeTransactionRoomDao().also { it.rows += cachedReceipt() }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { query ->
            val api = object : EmptyHistoryApi() {
                override fun getHistorySnapshot(scriptJson: String): String {
                    if (calls.incrementAndGet() == blockedCall) {
                        assertNotSame(caller, Thread.currentThread())
                        entered.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS)) { "caller could not release blocked snapshot" }
                    }
                    return Json.encodeToString(proof())
                }
            }
            val operation = async { reader(api, query, RoomKmpTransactionStore(dao))
                .getTransactions(script, "wallet", NetworkType.TESTNET).getOrThrow() }
            try {
                withTimeout(5_000) { entered.await() }
                assertFalse(operation.isCompleted, "UI caller is free while native snapshot is blocked")
                operation.cancel()
                release.countDown()
                operation.join()
                assertTrue(operation.isCancelled)
                assertEquals(blockedCall, calls.get())
                assertEquals(listOf(cachedReceipt()), dao.rows, "cancelled authority cannot delete a receipt")
            } finally {
                release.countDown()
                operation.cancelAndJoin()
            }
        }
    }

    @Test fun `changed or unavailable background revalidation retains cached receipt`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { query ->
            for (recheck in listOf(null, "{}", Json.encodeToString(proof().copy(tipHash = "0x" + "3".repeat(64))),
                Json.encodeToString(proof().copy(registration = "b".repeat(32))))) {
                val calls = AtomicInteger()
                val dao = FakeTransactionRoomDao().also { it.rows += cachedReceipt() }
                val api = object : EmptyHistoryApi() {
                    override fun getHistorySnapshot(scriptJson: String): String? =
                        if (calls.incrementAndGet() == 1) Json.encodeToString(proof()) else recheck
                }
                reader(api, query, RoomKmpTransactionStore(dao)).getTransactions(script, "wallet", NetworkType.TESTNET).getOrThrow()
                assertEquals(2, calls.get())
                assertEquals(listOf(cachedReceipt()), dao.rows)
            }
        }
    }

    @Test fun `cancellation during revalidation retains receipt when caller already uses query dispatcher`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val dao = FakeTransactionRoomDao().also { it.rows += cachedReceipt() }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { query ->
            val api = object : EmptyHistoryApi() {
                override fun getHistorySnapshot(scriptJson: String): String {
                    if (calls.incrementAndGet() == 2) {
                        entered.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS)) { "caller could not release revalidation" }
                    }
                    return Json.encodeToString(proof())
                }
            }
            val operation = async(query) { reader(api, query, RoomKmpTransactionStore(dao))
                .getTransactions(script, "wallet", NetworkType.TESTNET).getOrThrow() }
            try {
                withTimeout(5_000) { entered.await() }
                operation.cancel()
                release.countDown()
                operation.join()
                assertTrue(operation.isCancelled)
                assertEquals(2, calls.get())
                assertEquals(listOf(cachedReceipt()), dao.rows)
            } finally {
                release.countDown()
                operation.cancelAndJoin()
            }
        }
    }
}
