package com.rjnr.pocketnode.data.history

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.prefs.FakeUiPreferences
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.LightClientFixtures
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.storage.FakeBalanceCache
import com.rjnr.pocketnode.data.storage.FakePendingBroadcastRoomDao
import com.rjnr.pocketnode.data.storage.FakeSubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.FakeSyncProgressStore
import com.rjnr.pocketnode.data.storage.FakeTransactionRoomDao
import com.rjnr.pocketnode.data.storage.FakeWalletRegistry
import com.rjnr.pocketnode.data.storage.InMemoryHeaderCache
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import com.rjnr.pocketnode.data.storage.PendingBroadcastRow
import com.rjnr.pocketnode.data.storage.RoomKmpPendingBroadcastStore
import com.rjnr.pocketnode.data.storage.RoomKmpTransactionStore
import com.rjnr.pocketnode.data.sync.SyncEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feed's two jobs: joining a cached page against the broadcast rows, and
 * running the balance read with the wiring `GatewayRepository.refreshBalance`
 * supplies on Android.
 */
class ActivityFeedTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val script = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = LightClientFixtures.LOCK_ARGS,
    )

    private val txDao = FakeTransactionRoomDao()
    private val broadcastDao = FakePendingBroadcastRoomDao()
    private val transactionStore = RoomKmpTransactionStore(txDao, clock = Clock { NOW })
    private val broadcastStore = RoomKmpPendingBroadcastStore(broadcastDao)
    private val balanceCache = FakeBalanceCache()
    private val walletRegistry = FakeWalletRegistry()
    private val candidates = FakeSubAccountCandidateStore()
    private val syncPreferences = FakeSyncPreferences()
    private val uiPreferences = FakeUiPreferences()
    private val progressStore = FakeSyncProgressStore()

    private fun feedOver(api: FakeLightClientApi, now: Long = NOW): ActivityFeed {
        val ledger = LedgerReader(
            lightClient = api,
            balanceCache = balanceCache,
            transactionStore = transactionStore,
            headerCache = InMemoryHeaderCache(),
            walletRegistry = walletRegistry,
            candidates = candidates,
            syncPreferences = syncPreferences,
            uiPreferences = uiPreferences,
            json = json,
            logger = NoopLogger,
        )
        val coordinator = SyncCoordinator(
            walletRegistry = walletRegistry,
            syncProgressStore = progressStore,
            subAccountCandidateStore = candidates,
            transactionStore = transactionStore,
            lightClient = api,
            syncPreferences = syncPreferences,
            json = json,
            logger = NoopLogger,
        )
        val engine = SyncEngine(
            lightClient = api,
            syncPreferences = syncPreferences,
            json = json,
            logger = NoopLogger,
        )
        return ActivityFeed(
            ledger = ledger,
            transactions = transactionStore,
            pendingBroadcasts = broadcastStore,
            balanceCache = balanceCache,
            coordinator = coordinator,
            engine = engine,
            uiPreferences = uiPreferences,
            clock = Clock { now },
        )
    }

    private fun record(
        txHash: String,
        direction: String = "out",
        status: String = "CONFIRMED",
        confirmations: Int = 4,
    ) = TransactionRecord(
        txHash = txHash,
        blockNumber = "0x10",
        blockHash = "0xdead",
        timestamp = 0L,
        balanceChange = "0x5f5e100",
        direction = direction,
        fee = "0x0",
        confirmations = confirmations,
        status = status,
    )

    private fun broadcastRow(
        txHash: String,
        state: String,
        nullCount: Int = 0,
        createdAt: Long = NOW - 5 * 60_000L,
    ) = PendingBroadcastRecord(
        txHash = txHash,
        state = state,
        reservedInputs = "[]",
        signedTxJson = "{}",
        walletId = WALLET,
        network = NETWORK.name,
        submittedAtTipBlock = 100L,
        nullCount = nullCount,
        createdAt = createdAt,
        lastCheckedAt = createdAt,
    )

    // ---- page ----

    @Test
    fun `a page joins each row against its broadcast row`() = runTest {
        val feed = feedOver(FakeLightClientApi())
        transactionStore.cacheTransactions(
            listOf(
                record("0xconfirmed"),
                record("0xsending", status = "PENDING", confirmations = 0),
            ),
            NETWORK.name,
            WALLET,
        )
        broadcastDao.seed(PendingBroadcastRow.from(broadcastRow("0xsending", state = "BROADCASTING")))
        feed.observeBroadcasts(WALLET, NETWORK)
        // The flow replays to its collector on the feed's own scope; read once
        // it has landed rather than asserting on a race.
        awaitBroadcasts(feed, expected = 1)

        val items = feed.page(ActivityFilter.ALL, WALLET, NETWORK, 0)

        val sending = items.first { it.record.txHash == "0xsending" }
        assertEquals(TxDisplayState.BROADCASTING, sending.displayState)
        assertNotNull(sending.broadcast)
        assertTrue(sending.isInFlight)
        assertEquals(ElapsedBucket(ElapsedUnit.MINUTES, 5), sending.elapsed)
        assertNull(sending.failureReason)

        val confirmed = items.first { it.record.txHash == "0xconfirmed" }
        assertEquals(TxDisplayState.CONFIRMED, confirmed.displayState)
        assertNull(confirmed.broadcast)
        assertNull(confirmed.elapsed, "a confirmed row has no in-flight clock")
    }

    @Test
    fun `a failed row carries the reason its broadcast row implies`() = runTest {
        val feed = feedOver(FakeLightClientApi())
        transactionStore.cacheTransactions(
            listOf(record("0xdead1", status = "FAILED", confirmations = 0)),
            NETWORK.name,
            WALLET,
        )
        broadcastDao.seed(PendingBroadcastRow.from(broadcastRow("0xdead1", state = "FAILED", nullCount = 4)))
        feed.observeBroadcasts(WALLET, NETWORK)
        awaitBroadcasts(feed, expected = 1)

        val item = feed.page(ActivityFilter.ALL, WALLET, NETWORK, 0).single()

        assertEquals(TxDisplayState.FAILED, item.displayState)
        assertEquals(TxFailureReason.DROPPED, item.failureReason)
    }

    @Test
    fun `a filtered page only returns that tab's directions`() = runTest {
        val feed = feedOver(FakeLightClientApi())
        transactionStore.cacheTransactions(
            listOf(
                record("0x01", direction = "in"),
                record("0x02", direction = "out"),
                record("0x03", direction = "dao_unlock"),
            ),
            NETWORK.name,
            WALLET,
        )

        assertEquals(
            setOf("0x01", "0x03"),
            feed.page(ActivityFilter.RECEIVED, WALLET, NETWORK, 0).map { it.record.txHash }.toSet(),
        )
        assertEquals(
            setOf("0x02"),
            feed.page(ActivityFilter.SENT, WALLET, NETWORK, 0).map { it.record.txHash }.toSet(),
        )
        assertEquals(3, feed.page(ActivityFilter.ALL, WALLET, NETWORK, 0).size)
    }

    @Test
    fun `a bulk hash is badged from the persisted set rather than the row`() = runTest {
        val feed = feedOver(FakeLightClientApi())
        transactionStore.cacheTransactions(listOf(record("0xbulk")), NETWORK.name, WALLET)
        uiPreferences.addBulkTxHash("0xbulk")

        val item = feed.page(ActivityFilter.ALL, WALLET, NETWORK, 0).single()

        assertTrue(item.isBulk)
        assertTrue(item.record.isBulk, "and the record the UI reads carries it too")
    }

    @Test
    fun `pages do not overlap`() = runTest {
        val feed = feedOver(FakeLightClientApi())
        transactionStore.cacheTransactions(
            (0 until ActivityFeed.PAGE_SIZE + 5).map { record("0x${it.toString(16)}") },
            NETWORK.name,
            WALLET,
        )

        val first = feed.page(ActivityFilter.ALL, WALLET, NETWORK, 0)
        val second = feed.page(ActivityFilter.ALL, WALLET, NETWORK, 1)

        assertEquals(ActivityFeed.PAGE_SIZE, first.size)
        assertEquals(5, second.size)
        assertEquals(
            ActivityFeed.PAGE_SIZE + 5,
            (first + second).map { it.record.txHash }.toSet().size,
        )
    }

    // ---- refreshBalance ----

    @Test
    fun `refreshBalance publishes the cached value first and caches the computed one`() = runTest {
        val cached = BalanceResponse(ADDRESS, "0x1", "0.00000001", "0x1")
        balanceCache.rows[WALLET to NETWORK.name] = cached
        val api = FakeLightClientApi()
            .enqueue("getCellsCapacity", LightClientFixtures.CELLS_CAPACITY)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
        val feed = feedOver(api)

        // Recorded AT the moment the cached value is published, so this proves
        // ordering rather than just that both things happened. The bridge has
        // been asked for nothing yet when the cache answers.
        var bridgeCallsWhenCachedEmitted = -1
        // Unconfined so the collector starts now and resumes eagerly on the
        // emission itself. A default-dispatched collector under `runTest` would
        // not run until `refreshBalance` returned, by which point the count is
        // the total for the whole read and proves nothing about order.
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) {
            feed.cachedBalance.collect { value ->
                if (value != null && bridgeCallsWhenCachedEmitted < 0) {
                    bridgeCallsWhenCachedEmitted = api.calls.size
                }
            }
        }

        val response = feed.refreshBalance(ADDRESS, script, NETWORK, WALLET)
        watcher.cancel()

        // The cached value went out on the flow, which is what paints the
        // screen before the cell walk finishes.
        assertEquals(cached, feed.cachedBalance.value)
        assertEquals(
            0,
            bridgeCallsWhenCachedEmitted,
            "the cache answered before the first bridge call, not merely before the last",
        )
        assertTrue(api.calls.isNotEmpty(), "and the walk did then run")
        // And the computed one replaced it in the cache: the repository does
        // this write at its own call site, the feed does it here.
        assertEquals(response, balanceCache.rows[WALLET to NETWORK.name])
        val expected = LightClientFixtures.GET_CELLS_FIRST_CAPACITY +
            LightClientFixtures.GET_CELLS_SECOND_CAPACITY
        assertEquals("0x${expected.toString(16)}", response.capacity)
    }

    @Test
    fun `primeCachedBalance publishes without touching the node`() = runTest {
        val cached = BalanceResponse(ADDRESS, "0x2", "0.00000002", "0x1")
        balanceCache.rows[WALLET to NETWORK.name] = cached
        val api = FakeLightClientApi()
        val feed = feedOver(api)

        assertEquals(cached, feed.primeCachedBalance(WALLET, NETWORK))
        assertEquals(cached, feed.cachedBalance.value)
        assertTrue(api.calls.isEmpty(), "no bridge call was made")
    }

    @Test
    fun `an empty wallet with history forwards one partial rescan to the coordinator`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getCellsCapacity", LightClientFixtures.CELLS_CAPACITY)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)
            .enqueue("getTransactions", SPENDS_BOTH_RECORDED_CELLS)
            // What the coordinator's PARTIAL path reads before it clamps, then
            // the setScripts it issues.
            .enqueue("getScripts", "[]")
            .enqueueFlag("setScripts", true)
        val feed = feedOver(api)

        feed.refreshBalance(ADDRESS, script, NETWORK, WALLET)

        val setScripts = api.callsTo("setScripts").singleOrNull()
        assertNotNull(setScripts, "the rescue rescan reached the coordinator")
        // PARTIAL, which is the command the feed forwards, and the rewind is
        // allowed: the rescue rescan IS the intentional rewind, so the #332
        // clamp must not silently move it forward to the current block.
        assertEquals(SyncCoordinator.CMD_SET_SCRIPTS_PARTIAL, setScripts.args[1])
        val statuses = json.decodeFromString<List<JniScriptStatus>>(setScripts.args[0] as String)
        assertEquals(script, statuses.single().script)
        assertEquals("lock", statuses.single().scriptType)
        // Earliest transaction is block 12, so the rewind floors at 0. A clamp
        // would have produced the currently registered block instead.
        assertEquals("0x0", statuses.single().blockNumber)
    }

    /**
     * Drains the feed's broadcast flow until it reports [expected] rows.
     *
     * The subscription runs on the feed's own scope, which under `runTest` is a
     * separate dispatcher from the test body, so reading `broadcasts.value`
     * straight after `observeBroadcasts` is a race. Bounded so a wiring bug
     * fails the test rather than hanging it.
     */
    private suspend fun awaitBroadcasts(feed: ActivityFeed, expected: Int) {
        repeat(200) {
            if (feed.broadcasts.value.size == expected) return
            kotlinx.coroutines.delay(5)
        }
        assertEquals(expected, feed.broadcasts.value.size, "broadcast flow never reported rows")
    }

    private companion object {
        val NETWORK = NetworkType.TESTNET
        const val WALLET = "w1"
        const val ADDRESS = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"
        const val NOW = 1_726_000_000_000L

        /**
         * A `get_transactions` page whose single transaction spends BOTH cells
         * in [LightClientFixtures.GET_CELLS_PAGE], so the wallet reads as
         * empty (no spendable capacity, no typed cells) while still having
         * history. That is the exact shape the #332 rescue rescan fires on.
         *
         * Assembled from the recorded values: the two `previous_output`s are
         * [LightClientFixtures.GET_CELLS_FIRST_OUTPOINT] and
         * [LightClientFixtures.GET_CELLS_SECOND_OUTPOINT], and the lock is the
         * recorded [LightClientFixtures.LOCK_ARGS].
         */
        const val SPENDS_BOTH_RECORDED_CELLS: String = """
            {
              "last_cursor": "",
              "objects": [
                {
                  "transaction": {
                    "cell_deps": [],
                    "hash": "0xa6789f42b0568b1872e5a5858f0c42148dd8d313f844252f5fe3dfe556958ba9",
                    "header_deps": [],
                    "inputs": [
                      {
                        "previous_output": {
                          "index": "0x0",
                          "tx_hash": "0x68b6cabb75821b0e316bda61f5b8fe5555215b637db29d3bab6ca7f42714db57"
                        },
                        "since": "0x0"
                      },
                      {
                        "previous_output": {
                          "index": "0x0",
                          "tx_hash": "0xcfe64b1cd6ac96e4a5daf295a6d395cc6f237df47212515086c22e76a8e2dbbf"
                        },
                        "since": "0x0"
                      }
                    ],
                    "outputs": [
                      {
                        "capacity": "0x2ecbd7f365",
                        "lock": {
                          "args": "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1",
                          "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                          "hash_type": "type"
                        },
                        "type": null
                      }
                    ],
                    "outputs_data": ["0x"],
                    "version": "0x0",
                    "witnesses": ["0x"]
                  },
                  "block_number": "0xc",
                  "tx_index": "0x0",
                  "io_index": "0x0",
                  "io_type": "input",
                  "io_capacity": "0x2ecbd7f365"
                }
              ]
            }
        """
    }
}
