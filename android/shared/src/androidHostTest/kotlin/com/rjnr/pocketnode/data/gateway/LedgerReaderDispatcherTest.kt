package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.prefs.FakeUiPreferences
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.storage.*
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlinx.coroutines.*
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
    private fun reader(api: LightClientApi, context: CoroutineContext) = LedgerReader(
        api, FakeBalanceCache(), FakeTransactionStore(), FakeHeaderCache(), FakeWalletRegistry(),
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
}
