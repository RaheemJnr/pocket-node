package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.prefs.FakeUiPreferences
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.storage.FakeBalanceCache
import com.rjnr.pocketnode.data.storage.FakeHeaderCache
import com.rjnr.pocketnode.data.storage.FakeSubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.FakeTransactionStore
import com.rjnr.pocketnode.data.storage.FakeWalletRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared read path over recorded bridge payloads.
 *
 * Every JSON these tests hand the fake client is either a [LightClientFixtures]
 * constant recorded off the chain, or is assembled here from those same
 * recorded values with one field rewritten — the rewrite is always named in the
 * constant's comment. Nothing is invented: the capacities, hashes, outpoints
 * and block numbers all came off testnet.
 *
 * The bugs this guards are the expensive ones: a balance computed from a single
 * page, a rescue rescan that re-fires forever (#332), a history page that loses
 * older transactions (#386/#388), and a header refetched on every page load.
 */
class LedgerReaderTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val script = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = LightClientFixtures.LOCK_ARGS,
    )

    private val balanceCache = FakeBalanceCache()
    private val transactionStore = FakeTransactionStore()
    private val headerCache = FakeHeaderCache()
    private val walletRegistry = FakeWalletRegistry()
    private val candidates = FakeSubAccountCandidateStore()
    private val syncPreferences = FakeSyncPreferences()
    private val uiPreferences = FakeUiPreferences()

    private fun readerOver(api: FakeLightClientApi) = LedgerReader(
        lightClient = api,
        balanceCache = balanceCache,
        transactionStore = transactionStore,
        headerCache = headerCache,
        walletRegistry = walletRegistry,
        candidates = candidates,
        syncPreferences = syncPreferences,
        uiPreferences = uiPreferences,
        json = json,
        logger = NoopLogger,
    )

    // ---- balance ----

    @Test
    fun `balance sums the live untyped cells rather than the raw indexer capacity`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getCellsCapacity", LightClientFixtures.CELLS_CAPACITY)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)

        val resp = readerOver(api).readBalance(
            address = ADDRESS,
            script = script,
            network = NetworkType.TESTNET,
            walletId = WALLET,
            isSyncing = { false },
            rescanAttempted = mutableSetOf(),
            emitCached = {},
            requestPartialRescan = {},
        ).getOrThrow()

        // The recorded cellbase spends only the null outpoint, so both recorded
        // cells are live and untyped and the raw CellsCapacity is discarded.
        val expected = LightClientFixtures.GET_CELLS_FIRST_CAPACITY +
            LightClientFixtures.GET_CELLS_SECOND_CAPACITY
        assertEquals("0x${expected.toString(16)}", resp.capacity)
        assertEquals(ADDRESS, resp.address)
        // as_of_block still comes from the capacity call, as it always has.
        assertEquals("0x156445c", resp.asOfBlock)
        assertTrue(resp.capacity != LightClientFixtures.CELLS_CAPACITY_SHANNONS.toString(16))
    }

    @Test
    fun `balance emits the cached value before it walks the chain`() = runTest {
        val cached = BalanceResponse(ADDRESS, "0x1", "0.00000001", "0x1")
        balanceCache.rows[WALLET to "TESTNET"] = cached
        val api = FakeLightClientApi()
            .enqueue("getCellsCapacity", LightClientFixtures.CELLS_CAPACITY)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)

        val emitted = mutableListOf<BalanceResponse>()
        val resp = readerOver(api).readBalance(
            address = ADDRESS,
            script = script,
            network = NetworkType.TESTNET,
            walletId = WALLET,
            isSyncing = { false },
            rescanAttempted = mutableSetOf(),
            emitCached = { emitted += it },
            requestPartialRescan = {},
        ).getOrThrow()

        assertEquals(listOf(cached), emitted)
        // The fresh read is NOT written back here: the caller owns the cache write.
        assertEquals(cached, balanceCache.rows[WALLET to "TESTNET"])
        assertTrue(resp.capacity != cached.capacity)
    }

    @Test
    fun `balance fails with no wallet when the active script is null`() = runTest {
        val result = readerOver(FakeLightClientApi()).readBalance(
            address = ADDRESS,
            script = null,
            network = NetworkType.TESTNET,
            walletId = WALLET,
            isSyncing = { false },
            rescanAttempted = mutableSetOf(),
            emitCached = {},
            requestPartialRescan = {},
        )
        assertEquals("No wallet", result.exceptionOrNull()?.message)
    }

    // ---- the #332 rescue rescan ----

    @Test
    fun `an empty wallet with history asks for one partial rescan and never a second`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getCellsCapacity", LightClientFixtures.CELLS_CAPACITY)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)
            .enqueue("getTransactions", SPENDS_BOTH_RECORDED_CELLS)
        val reader = readerOver(api)
        val attempted = mutableSetOf<String>()
        val requested = mutableListOf<List<JniScriptStatus>>()

        repeat(2) {
            reader.readBalance(
                address = ADDRESS,
                script = script,
                network = NetworkType.TESTNET,
                walletId = WALLET,
                isSyncing = { false },
                rescanAttempted = attempted,
                emitCached = {},
                requestPartialRescan = { requested += it },
            ).getOrThrow()
        }

        assertEquals(1, requested.size)
        val status = requested.single().single()
        assertEquals(script, status.script)
        assertEquals("lock", status.scriptType)
        // Earliest transaction is block 12, so the rewind floors at 0.
        assertEquals("0x0", status.blockNumber)
        assertTrue(WALLET in attempted)
        assertTrue(syncPreferences.isZeroCellRescanDone(WALLET))
    }

    @Test
    fun `an empty wallet mid sync is left alone`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getCellsCapacity", LightClientFixtures.CELLS_CAPACITY)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)
            .enqueue("getTransactions", SPENDS_BOTH_RECORDED_CELLS)
        val requested = mutableListOf<List<JniScriptStatus>>()

        readerOver(api).readBalance(
            address = ADDRESS,
            script = script,
            network = NetworkType.TESTNET,
            walletId = WALLET,
            isSyncing = { true },
            rescanAttempted = mutableSetOf(),
            emitCached = {},
            requestPartialRescan = { requested += it },
        ).getOrThrow()

        assertTrue(requested.isEmpty())
        assertTrue(!syncPreferences.isZeroCellRescanDone(WALLET))
    }

    @Test
    fun `the sync state is read at the rescan decision and not when the read started`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getCellsCapacity", LightClientFixtures.CELLS_CAPACITY)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)
            .enqueue("getTransactions", SPENDS_BOTH_RECORDED_CELLS)
        val requested = mutableListOf<List<JniScriptStatus>>()

        // Not syncing when the read starts; the poll flips the flag while the
        // cursor walks are in flight. A snapshot taken at call time would be a
        // stale `false` here and would rewind the filter scan mid-catch-up.
        var syncing = false
        var reads = 0
        // Which bridge calls had already happened each time the supplier ran.
        val callsWhenRead = mutableListOf<List<String>>()

        readerOver(api).readBalance(
            address = ADDRESS,
            script = script,
            network = NetworkType.TESTNET,
            walletId = WALLET,
            isSyncing = {
                reads++
                callsWhenRead += api.calls.map { it.function }
                syncing = true
                syncing
            },
            rescanAttempted = mutableSetOf(),
            emitCached = {},
            requestPartialRescan = { requested += it },
        ).getOrThrow()

        // Read exactly once, and only after all three walks: a call-time
        // snapshot would have seen an empty call log instead.
        assertEquals(1, reads)
        val seen = callsWhenRead.single()
        assertTrue(seen.contains("getCellsCapacity"), seen.toString())
        assertTrue(seen.contains("getCells"), seen.toString())
        // Both the descending spent walk and the ascending history page.
        assertEquals(2, seen.count { it == "getTransactions" }, seen.toString())

        assertTrue(requested.isEmpty())
        assertTrue(!syncPreferences.isZeroCellRescanDone(WALLET))
    }

    // ---- cells ----

    @Test
    fun `getCells walks the cursor until a page comes back short`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE, LAST_CELL_PAGE)

        val resp = readerOver(api).getCells(script, limit = 2).getOrThrow()

        assertEquals(3, resp.items.size)
        val calls = api.callsTo("getCells")
        assertEquals(2, calls.size)
        assertNull(calls[0].args[3])
        // The second page is asked for with the first page's recorded cursor.
        assertEquals(RECORDED_CELL_CURSOR, calls[1].args[3])
        assertEquals("", resp.nextCursor)
    }

    @Test
    fun `getCells stops at the page cap rather than walking a runaway cursor`() = runTest {
        // One scripted page that never shortens and never empties its cursor:
        // exactly the shape a runaway would take.
        val api = FakeLightClientApi()
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)

        val resp = readerOver(api).getCells(script, limit = 2).getOrThrow()

        assertEquals(50, api.callsTo("getCells").size)
        assertEquals(100, resp.items.size)
    }

    @Test
    fun `getCells drops spent and typed cells`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransactions", SPENDS_FIRST_RECORDED_CELL)
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE, TYPED_CELL_PAGE)

        val resp = readerOver(api).getCells(script, limit = 2).getOrThrow()

        // Three cells came back: one spent, one typed, one spendable.
        assertEquals(1, resp.items.size)
        assertEquals(
            LightClientFixtures.GET_CELLS_SECOND_OUTPOINT,
            "${resp.items.single().outPoint.txHash}:${resp.items.single().outPoint.index}",
        )
    }

    @Test
    fun `getCells names the readiness of the light client when the first page is null`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)

        val result = readerOver(api).getCells(script)

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("light client not ready"), message)
    }

    // ---- history ----

    @Test
    fun `history groups an interaction page into one record per transaction`() = runTest {
        headerCache.headers[COMMITTED_BLOCK_HASH] =
            json.decodeFromString<JniHeaderView>(LightClientFixtures.HEADER_BLOCK_12)
        val api = FakeLightClientApi()
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getTransaction", LightClientFixtures.GET_TRANSACTION_COMMITTED)

        val resp = readerOver(api).getTransactions(
            activeScript = script,
            activeWalletId = WALLET,
            network = NetworkType.TESTNET,
        ).getOrThrow()

        val record = resp.items.single()
        assertEquals(COMMITTED_TX_HASH, record.txHash)
        assertEquals("0xc", record.blockNumber)
        assertEquals("in", record.direction)
        assertEquals("0x2ecbd7f365", record.balanceChange)
        assertEquals(COMMITTED_BLOCK_HASH, record.blockHash)
        assertEquals("0x1723b9a0f32", record.blockTimestampHex)
        assertEquals((LightClientFixtures.TIP_HEADER_NUMBER - 0xc).toInt(), record.confirmations)
        // The walk resolves no input capacity for this cellbase, so the fee is
        // unknown rather than a misleading zero (#497).
        assertNull(record.feeShannons)
        // The complete walk is what gets cached, not the trimmed page.
        assertEquals(listOf(record), transactionStore.cached[WALLET to "TESTNET"])
        assertNull(resp.nextCursor)
    }

    @Test
    fun `history nets the two interactions of one transaction into a single record`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransactions", TWO_INTERACTIONS_ONE_TX)
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getTransaction", LightClientFixtures.GET_TRANSACTION_COMMITTED)
            .enqueue("getHeader", LightClientFixtures.HEADER_BLOCK_12)

        val resp = readerOver(api).getTransactions(
            activeScript = script,
            activeWalletId = WALLET,
            network = NetworkType.TESTNET,
        ).getOrThrow()

        // Two rows for one hash collapse to one record, and the net is computed
        // across BOTH of them: an input we owned and a change output that came
        // back. Scoring either row alone is the #388 bug.
        val record = resp.items.single()
        assertEquals(COMMITTED_TX_HASH, record.txHash)
        assertEquals("out", record.direction)
        val net = LightClientFixtures.GET_CELLS_FIRST_CAPACITY - CHANGE_CAPACITY
        assertEquals("0x${net.toString(16)}", record.balanceChange)
        // The whole input side is resolved here, so the fee IS scorable (#497):
        // the single declared input minus the two declared outputs.
        assertEquals(
            LightClientFixtures.GET_CELLS_FIRST_CAPACITY - CHANGE_CAPACITY - SENT_CAPACITY,
            record.feeShannons,
        )
    }

    @Test
    fun `history reads a cached header before it asks the light client`() = runTest {
        headerCache.headers[COMMITTED_BLOCK_HASH] =
            json.decodeFromString<JniHeaderView>(LightClientFixtures.HEADER_BLOCK_12)
        val api = FakeLightClientApi()
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getTransaction", LightClientFixtures.GET_TRANSACTION_COMMITTED)

        readerOver(api).getTransactions(
            activeScript = script,
            activeWalletId = WALLET,
            network = NetworkType.TESTNET,
        ).getOrThrow()

        assertTrue(api.callsTo("getHeader").isEmpty())
        assertTrue(headerCache.writtenNetworks.isEmpty())
    }

    @Test
    fun `history falls through to the light client on a header cache miss and caches it`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getTransaction", LightClientFixtures.GET_TRANSACTION_COMMITTED)
            .enqueue("getHeader", LightClientFixtures.HEADER_BLOCK_12)

        val resp = readerOver(api).getTransactions(
            activeScript = script,
            activeWalletId = WALLET,
            network = NetworkType.TESTNET,
        ).getOrThrow()

        assertEquals(1, api.callsTo("getHeader").size)
        assertEquals("0x1723b9a0f32", resp.items.single().blockTimestampHex)
        assertNotNull(headerCache.headers[COMMITTED_BLOCK_HASH])
        assertEquals(listOf("TESTNET"), headerCache.writtenNetworks)
    }

    @Test
    fun `history arms the gap limit signal on an outgoing tx whose change we do not know`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransactions", UNKNOWN_CHANGE_PAGE)
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)

        readerOver(api).getTransactions(
            activeScript = script,
            activeWalletId = WALLET,
            network = NetworkType.TESTNET,
        ).getOrThrow()

        assertTrue(syncPreferences.isGapLimitSignalDetected(NetworkType.TESTNET, WALLET))
    }

    @Test
    fun `history leaves the gap limit signal alone when the change came back to us`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getTransaction", LightClientFixtures.GET_TRANSACTION_COMMITTED)

        readerOver(api).getTransactions(
            activeScript = script,
            activeWalletId = WALLET,
            network = NetworkType.TESTNET,
        ).getOrThrow()

        assertTrue(!syncPreferences.isGapLimitSignalDetected(NetworkType.TESTNET, WALLET))
    }

    @Test
    fun `history merges the local pending rows ahead of the confirmed ones`() = runTest {
        transactionStore.pending[WALLET to "TESTNET"] = listOf(PENDING_ROW)
        uiPreferences.addBulkTxHash(PENDING_ROW.txHash)
        headerCache.headers[COMMITTED_BLOCK_HASH] =
            json.decodeFromString<JniHeaderView>(LightClientFixtures.HEADER_BLOCK_12)
        val api = FakeLightClientApi()
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getTransaction", LightClientFixtures.GET_TRANSACTION_COMMITTED)

        val resp = readerOver(api).getTransactions(
            activeScript = script,
            activeWalletId = WALLET,
            network = NetworkType.TESTNET,
        ).getOrThrow()

        assertEquals(listOf(PENDING_ROW.txHash, COMMITTED_TX_HASH), resp.items.map { it.txHash })
        assertTrue(resp.items.first().isBulk)
        assertTrue(!resp.items.last().isBulk)
    }

    @Test
    fun `history throws rather than reporting an empty wallet when the first read fails`() = runTest {
        val result = readerOver(FakeLightClientApi()).getTransactions(
            activeScript = script,
            activeWalletId = WALLET,
            network = NetworkType.TESTNET,
        )
        assertEquals("Failed to get transactions", result.exceptionOrNull()?.message)
    }

    // ---- transaction status ----

    @Test
    fun `status of a committed tx counts the confirmations from the tip`() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransaction", LightClientFixtures.GET_TRANSACTION_COMMITTED)
            .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getHeader", LightClientFixtures.HEADER_BLOCK_12)

        val resp = readerOver(api).getTransactionStatus(COMMITTED_TX_HASH).getOrThrow()

        assertEquals("committed", resp.status)
        assertEquals(COMMITTED_BLOCK_HASH, resp.blockHash)
        assertEquals((LightClientFixtures.TIP_HEADER_NUMBER - 0xc + 1).toInt(), resp.confirmations)
    }

    @Test
    fun `status of a pooled tx is pending with no confirmations`() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", TRANSACTION_PENDING)

        val resp = readerOver(api).getTransactionStatus(COMMITTED_TX_HASH).getOrThrow()

        assertEquals("pending", resp.status)
        assertEquals(0, resp.confirmations)
        assertNull(resp.blockHash)
    }

    @Test
    fun `a null lookup is unknown rather than a failure`() = runTest {
        val resp = readerOver(FakeLightClientApi())
            .getTransactionStatus(COMMITTED_TX_HASH).getOrThrow()

        assertEquals("unknown", resp.status)
        assertEquals(0, resp.confirmations)
        assertNull(resp.blockHash)
    }

    // ---- registered script block ----

    @Test
    fun `the registered script block is read off the matching lock args`() {
        val api = FakeLightClientApi().enqueue("getScripts", LightClientFixtures.GET_SCRIPTS)

        assertEquals(0x5fa5L, readerOver(api).existingScriptBlock(LightClientFixtures.LOCK_ARGS))
        assertEquals(0L, readerOver(api).existingScriptBlock("0xdeadbeef"))
        assertEquals(0L, readerOver(FakeLightClientApi()).existingScriptBlock(null))
    }

    private companion object {
        const val WALLET = "w1"

        /**
         * The testnet address for [LightClientFixtures.LOCK_ARGS]. Only ever
         * echoed back on the balance response, never decoded here.
         */
        const val ADDRESS = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqmfjggsjmwmnglywx36zw6z0jwd8g80pcgtzupsz"

        const val COMMITTED_TX_HASH =
            "0xa6789f42b0568b1872e5a5858f0c42148dd8d313f844252f5fe3dfe556958ba9"

        const val COMMITTED_BLOCK_HASH =
            "0xfb27201670e48f65b93b58c4cac7348c54554ad831ed5c1b386c9bd3c24fa911"

        /** The `last_cursor` [LightClientFixtures.GET_CELLS_PAGE] carries. */
        const val RECORDED_CELL_CURSOR =
            "0x409bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce801da648442dbb7347e467d1d09da13e5cd3a0ef0e10000000000005fa60000000000000000"

        /**
         * `get_cells` -> `Pagination<Cell>`, terminal page: the second recorded
         * cell of [LightClientFixtures.GET_CELLS_PAGE] under a different
         * out_point index, with `last_cursor` emptied the way the indexer
         * empties it on the last page.
         */
        const val LAST_CELL_PAGE: String = """
            {
              "last_cursor": "",
              "objects": [
                {
                  "block_number": "0x5fa7",
                  "out_point": {
                    "index": "0x1",
                    "tx_hash": "0xcfe64b1cd6ac96e4a5daf295a6d395cc6f237df47212515086c22e76a8e2dbbf"
                  },
                  "output": {
                    "capacity": "0x7c4e4e2fb1",
                    "lock": {
                      "args": "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1",
                      "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                      "hash_type": "type"
                    },
                    "type": null
                  },
                  "output_data": "0x",
                  "tx_index": "0x0"
                }
              ]
            }
        """

        /**
         * `get_cells` -> `Pagination<Cell>`, one Nervos DAO cell: the second
         * recorded cell with the real testnet DAO type script attached and
         * `last_cursor` emptied. Coin selection must never offer it.
         */
        const val TYPED_CELL_PAGE: String = """
            {
              "last_cursor": "",
              "objects": [
                {
                  "block_number": "0x5fa8",
                  "out_point": {
                    "index": "0x2",
                    "tx_hash": "0xcfe64b1cd6ac96e4a5daf295a6d395cc6f237df47212515086c22e76a8e2dbbf"
                  },
                  "output": {
                    "capacity": "0x7c4e4e2fb1",
                    "lock": {
                      "args": "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1",
                      "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                      "hash_type": "type"
                    },
                    "type": {
                      "args": "0x",
                      "code_hash": "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e",
                      "hash_type": "type"
                    }
                  },
                  "output_data": "0x0000000000000000",
                  "tx_index": "0x0"
                }
              ]
            }
        """

        /**
         * `get_transactions` -> `Pagination<Tx>` spending BOTH recorded cells of
         * [LightClientFixtures.GET_CELLS_PAGE].
         *
         * [LightClientFixtures.GET_TRANSACTIONS_PAGE] with its single cellbase
         * input replaced by the two recorded out_points, so the spent set covers
         * every cell the wallet holds — the 0-live-cells shape the #332 rescue
         * rescan exists for. Everything else is that fixture, unchanged.
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
                  "io_type": "output",
                  "io_capacity": "0x2ecbd7f365"
                }
              ]
            }
        """

        /**
         * The same page as [SPENDS_BOTH_RECORDED_CELLS] with only the FIRST
         * recorded cell's out_point as an input, so exactly one of the two
         * recorded cells reads as spent.
         */
        const val SPENDS_FIRST_RECORDED_CELL: String = """
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
                  "io_type": "output",
                  "io_capacity": "0x2ecbd7f365"
                }
              ]
            }
        """

        /** The 1,000 CKB leg of [TWO_INTERACTIONS_ONE_TX] that leaves the wallet. */
        const val SENT_CAPACITY: Long = 0x174876e800

        /** The change leg of [TWO_INTERACTIONS_ONE_TX] that comes back to us. */
        const val CHANGE_CAPACITY: Long = 0x2006983752

        /** The transaction body both rows of [TWO_INTERACTIONS_ONE_TX] inline. */
        const val SPENDING_TRANSACTION: String = """
            {
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
                }
              ],
              "outputs": [
                {
                  "capacity": "0x174876e800",
                  "lock": {
                    "args": "0x3333333333333333333333333333333333333333",
                    "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                    "hash_type": "type"
                  },
                  "type": null
                },
                {
                  "capacity": "0x2006983752",
                  "lock": {
                    "args": "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1",
                    "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                    "hash_type": "type"
                  },
                  "type": null
                }
              ],
              "outputs_data": ["0x", "0x"],
              "version": "0x0",
              "witnesses": ["0x"]
            }
        """

        /**
         * `get_transactions` -> `Pagination<Tx>`, TWO `TxWithCell` rows for the
         * SAME transaction hash: the input we owned and the change output that
         * came back. This is the shape `groupBy { hash }` and `netShannonsByTx`
         * exist for, and the shape the #388 boundary bug scored from half of.
         *
         * Derived from [LightClientFixtures.GET_TRANSACTIONS_PAGE]: same hash,
         * same block number, same lock. Its single cellbase input is replaced
         * by the recorded out_point of [LightClientFixtures.GET_CELLS_PAGE]'s
         * first cell, and its single output by a 1,000 CKB send to a foreign
         * lock plus change back to our own, leaving a 100,000 shannon fee —
         * the app's own default. The two rows carry the input's recorded
         * capacity and the change capacity as their `io_capacity`.
         */
        const val TWO_INTERACTIONS_ONE_TX: String = """
            {
              "last_cursor": "",
              "objects": [
                {
                  "transaction": $SPENDING_TRANSACTION,
                  "block_number": "0xc",
                  "tx_index": "0x0",
                  "io_index": "0x0",
                  "io_type": "input",
                  "io_capacity": "0x374f10a5f2"
                },
                {
                  "transaction": $SPENDING_TRANSACTION,
                  "block_number": "0xc",
                  "tx_index": "0x0",
                  "io_index": "0x1",
                  "io_type": "output",
                  "io_capacity": "0x2006983752"
                }
              ]
            }
        """

        /**
         * The #382 signature: an OUTGOING interaction (`io_type` input, so the
         * net is negative) whose two outputs are both plain secp256k1 locks on
         * args we do not derive. Built from
         * [LightClientFixtures.GET_TRANSACTIONS_PAGE] with the io_type flipped
         * and the output lock args replaced by two Neuron-shaped siblings.
         */
        const val UNKNOWN_CHANGE_PAGE: String = """
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
                      }
                    ],
                    "outputs": [
                      {
                        "capacity": "0x1a13b8600",
                        "lock": {
                          "args": "0x1111111111111111111111111111111111111111",
                          "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                          "hash_type": "type"
                        },
                        "type": null
                      },
                      {
                        "capacity": "0x2d2a0ed65",
                        "lock": {
                          "args": "0x2222222222222222222222222222222222222222",
                          "code_hash": "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
                          "hash_type": "type"
                        },
                        "type": null
                      }
                    ],
                    "outputs_data": ["0x", "0x"],
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

        /**
         * `get_transaction` -> `TransactionWithStatus`, pooled.
         * [LightClientFixtures.GET_TRANSACTION_COMMITTED] with its `tx_status`
         * replaced by the pending arm (`block_hash` null), which is what the
         * bridge answers for a tx the node holds but no block carries yet.
         */
        const val TRANSACTION_PENDING: String = """
            {
              "cycles": null,
              "transaction": null,
              "tx_status": {
                "status": "pending",
                "block_hash": null
              }
            }
        """

        val PENDING_ROW = TransactionRecord(
            txHash = "0x1111111111111111111111111111111111111111111111111111111111111111",
            blockNumber = "",
            blockHash = "",
            timestamp = 0L,
            balanceChange = "0x1",
            direction = "out",
            fee = "0x0",
            confirmations = 0,
            status = "PENDING",
        )
    }
}
