package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.EpochInfo
import com.rjnr.pocketnode.data.gateway.models.JniCell
import com.rjnr.pocketnode.data.gateway.models.JniCellsCapacity
import com.rjnr.pocketnode.data.gateway.models.JniFetchHeaderResponse
import com.rjnr.pocketnode.data.gateway.models.JniFetchTransactionResponse
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniLocalNode
import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.JniRemoteNode
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.JniTransactionWithStatus
import com.rjnr.pocketnode.data.gateway.models.JniTxWithCell
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feeds every recorded bridge payload in [LightClientFixtures] through
 * [LightClientApi] and decodes it with the app's own `Json` configuration.
 *
 * This is the contract test for the seam: it proves the interface returns what
 * the bridge really emits and that the shared `@Serializable` models still
 * parse it. It runs on both `:shared:testAndroidHostTest` and
 * `:shared:iosSimulatorArm64Test`, so the models are proved on the iOS target
 * too, where nothing had exercised them before.
 */
class LightClientApiFixtureTest {

    /** Same configuration `AppModule.provideJson` hands the Android app. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun apiReturning(function: String, payload: String): LightClientApi =
        FakeLightClientApi().enqueue(function, payload)

    private fun hex(value: String): Long = value.removePrefix("0x").toLong(16)

    @Test
    fun tipHeaderFixtureDecodesAndItsNumberParses() {
        val api = apiReturning("getTipHeader", LightClientFixtures.TIP_HEADER)

        val raw = assertNotNull(api.getTipHeader())
        val header = json.decodeFromString<JniHeaderView>(raw)

        assertEquals(LightClientFixtures.TIP_HEADER_NUMBER, hex(header.number))
        assertEquals(
            "0x7f1786c8b1b6a7ed8fbe96aaa41b1be4401cc63101e27797a7c3a15ba4561eba",
            header.hash
        )
        // The epoch field is what EpochInfo.fromHex parses; it must be hex too.
        assertTrue(header.epoch.startsWith("0x"))
        assertEquals(64, header.dao.removePrefix("0x").length)
    }

    @Test
    fun getCellsPageDecodesWithACursorAndRealCapacities() {
        val api = apiReturning("getCells", LightClientFixtures.GET_CELLS_PAGE)

        val raw = assertNotNull(api.getCells("{}", "asc", 2, null))
        val page = json.decodeFromString<JniPagination<JniCell>>(raw)

        assertEquals(2, page.objects.size)
        val cursor = assertNotNull(page.lastCursor, "a full page must carry last_cursor")
        assertTrue(cursor.startsWith("0x"))

        val first = page.objects[0].toCell()
        assertEquals(LightClientFixtures.GET_CELLS_FIRST_CAPACITY, first.capacityAsLong())
        assertEquals("0x5fa5", first.blockNumber)
        assertEquals("type", first.lock.hashType)
        assertNull(first.type)
        assertEquals("0x", first.data)
    }

    @Test
    fun getCellsRecordsThePagingArgumentsItWasCalledWith() {
        val fake = FakeLightClientApi().enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)

        fake.getCells("{\"script_type\":\"lock\"}", "desc", 100, "0xcursor")

        val call = fake.callsTo("getCells").single()
        assertEquals(listOf("{\"script_type\":\"lock\"}", "desc", 100, "0xcursor"), call.args)
    }

    /**
     * Both cursor nullability paths, on both paged queries. A first page passes
     * null and a continuation passes the previous page's `last_cursor`; an
     * adapter that coerced one into the other would silently re-read page one
     * forever.
     */
    @Test
    fun bothPagedQueriesCarryTheCursorThroughOnEitherNullabilityPath() {
        val fake = FakeLightClientApi()
            .enqueue("getCells", LightClientFixtures.GET_CELLS_PAGE)
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)

        val cellsPage = json.decodeFromString<JniPagination<JniCell>>(
            assertNotNull(fake.getCells("{}", "asc", 10, null))
        )
        val cursor = assertNotNull(cellsPage.lastCursor)
        fake.getCells("{}", "asc", 10, cursor)

        fake.getTransactions("{}", "asc", 10, cursor)
        fake.getTransactions("{}", "asc", 10, null)

        assertEquals(
            listOf(listOf("{}", "asc", 10, null), listOf("{}", "asc", 10, cursor)),
            fake.callsTo("getCells").map { it.args }
        )
        assertEquals(
            listOf(listOf("{}", "asc", 10, cursor), listOf("{}", "asc", 10, null)),
            fake.callsTo("getTransactions").map { it.args }
        )
    }

    @Test
    fun resetReturnsTheFakeToItsFreshlyConstructedState() {
        val fake = FakeLightClientApi().enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.statusCode = 1
        fake.initialized = true
        fake.maxWithdraw = 42L
        fake.init("/config.toml", "", object : LightClientStatusListener {
            override fun onStatusChanged(status: String, data: String) = Unit
        })

        fake.reset()

        assertEquals(0, fake.status())
        assertEquals(false, fake.isInitialized())
        assertEquals(-1L, fake.calculateMaxWithdraw("0x", "0x", 0, 0))
        assertNull(fake.listener)
        assertNull(fake.getTipHeader())
    }

    @Test
    fun getTransactionsPageDecodesAsUngroupedTxWithCell() {
        val api = apiReturning("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)

        val raw = assertNotNull(api.getTransactions("{}", "asc", 10, null))
        val page = json.decodeFromString<JniPagination<JniTxWithCell>>(raw)

        val entry = page.objects.single()
        assertEquals(
            "0xa6789f42b0568b1872e5a5858f0c42148dd8d313f844252f5fe3dfe556958ba9",
            entry.transaction.hash
        )
        assertEquals("output", entry.ioType)
        assertEquals(0xcL, hex(entry.blockNumber))
        assertEquals(hex(entry.transaction.outputs[0].capacity), hex(entry.ioCapacity))
        assertNotNull(page.lastCursor)
    }

    @Test
    fun getTransactionFixtureDecodesWithItsCommittedStatus() {
        val api = apiReturning("getTransaction", LightClientFixtures.GET_TRANSACTION_COMMITTED)

        val raw = assertNotNull(api.getTransaction("0xa6789f42"))
        val withStatus = json.decodeFromString<JniTransactionWithStatus>(raw)

        assertEquals("committed", withStatus.txStatus.status)
        assertEquals(
            "0xfb27201670e48f65b93b58c4cac7348c54554ad831ed5c1b386c9bd3c24fa911",
            withStatus.txStatus.blockHash
        )
        // The node also sends fee / min_replace_fee / time_added_to_pool, which
        // the model does not declare: ignoreUnknownKeys has to absorb them.
        val transaction = assertNotNull(withStatus.transaction)
        assertEquals(1, transaction.outputs.size)
        assertEquals(listOf("0x"), transaction.outputsData)
    }

    @Test
    fun fetchHeaderFixturesDecodeBothArmsOfTheTaggedEnum() {
        val fetched = json.decodeFromString<JniFetchHeaderResponse>(
            assertNotNull(
                apiReturning("fetchHeader", LightClientFixtures.FETCH_HEADER_FETCHED)
                    .fetchHeader("0xfb272016")
            )
        )
        assertEquals("fetched", fetched.status)
        assertEquals("0xc", assertNotNull(fetched.data).number)

        val notFound = json.decodeFromString<JniFetchHeaderResponse>(
            assertNotNull(
                apiReturning("fetchHeader", LightClientFixtures.FETCH_HEADER_NOT_FOUND)
                    .fetchHeader("0xdeadbeef")
            )
        )
        assertEquals("not_found", notFound.status)
        assertNull(notFound.data)
    }

    @Test
    fun cellsCapacityFixtureDecodesToTheRecordedShannonTotal() {
        val api = apiReturning("getCellsCapacity", LightClientFixtures.CELLS_CAPACITY)

        val raw = assertNotNull(api.getCellsCapacity("{}"))
        val capacity = json.decodeFromString<JniCellsCapacity>(raw)

        assertEquals(LightClientFixtures.CELLS_CAPACITY_SHANNONS, hex(capacity.capacity))
        assertEquals(0x156445cL, hex(capacity.blockNumber))
        assertEquals(66, capacity.blockHash.length)
    }

    @Test
    fun localNodeInfoFixtureDecodes() {
        val api = apiReturning("localNodeInfo", LightClientFixtures.LOCAL_NODE_INFO)

        val info = json.decodeFromString<JniLocalNode>(assertNotNull(api.localNodeInfo()))

        assertTrue(info.active)
        assertEquals("QmWQA9KpXekAmpkLsUBBKM6fHWuX5dVrDzmcmr9TuKmWiG", info.nodeId)
        assertEquals("0.5.4", info.version)
    }

    @Test
    fun getPeersFixtureDecodesToThePeerCountTheUiShows() {
        val api = apiReturning("getPeers", LightClientFixtures.GET_PEERS)

        val peers = json.decodeFromString<List<JniRemoteNode>>(assertNotNull(api.getPeers()))

        assertEquals(3, peers.size)
        assertTrue(peers.all { it.nodeId.startsWith("Qm") })
        assertTrue(peers.all { hex(it.connectedDuration) > 0 })
    }

    @Test
    fun getScriptsFixtureDecodesToOneRegisteredLock() {
        val api = apiReturning("getScripts", LightClientFixtures.GET_SCRIPTS)

        val scripts = json.decodeFromString<List<JniScriptStatus>>(assertNotNull(api.getScripts()))

        val status = scripts.single()
        assertEquals("lock", status.scriptType)
        assertEquals("0x5fa5", status.blockNumber)
        assertEquals(
            "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
            status.script.codeHash
        )
    }

    /**
     * The two arms that carry a timestamp rather than a payload. A caller polls
     * on these, so mistaking `added` or `fetching` for a terminal answer is the
     * bug this pins.
     */
    @Test
    fun fetchHeaderAddedAndFetchingArmsDecodeWithoutData() {
        val added = json.decodeFromString<JniFetchHeaderResponse>(
            assertNotNull(
                apiReturning("fetchHeader", LightClientFixtures.FETCH_HEADER_ADDED)
                    .fetchHeader("0xfb272016")
            )
        )
        assertEquals("added", added.status)
        assertNull(added.data)

        val fetching = json.decodeFromString<JniFetchHeaderResponse>(
            assertNotNull(
                apiReturning("fetchHeader", LightClientFixtures.FETCH_HEADER_FETCHING)
                    .fetchHeader("0xfb272016")
            )
        )
        assertEquals("fetching", fetching.status)
        assertNull(fetching.data)
    }

    @Test
    fun fetchTransactionAddedAndFetchingArmsCarryTheirTimestamps() {
        val added = json.decodeFromString<JniFetchTransactionResponse>(
            assertNotNull(
                apiReturning("fetchTransaction", LightClientFixtures.FETCH_TRANSACTION_ADDED)
                    .fetchTransaction("0xa6789f42")
            )
        )
        assertEquals("added", added.status)
        assertNull(added.data)
        assertTrue(hex(assertNotNull(added.timestamp)) > 0)

        val fetching = json.decodeFromString<JniFetchTransactionResponse>(
            assertNotNull(
                apiReturning("fetchTransaction", LightClientFixtures.FETCH_TRANSACTION_FETCHING)
                    .fetchTransaction("0xa6789f42")
            )
        )
        assertEquals("fetching", fetching.status)
        assertNull(fetching.data)
        assertTrue(hex(assertNotNull(fetching.firstSent)) > 0)
    }

    /**
     * The exception to the null-on-failure contract: `callRpc` answers with a
     * JSON-RPC error envelope instead of null, so a caller that tests for null
     * would read a failure as success.
     */
    @Test
    fun callRpcReportsAnUninitializedNodeInBandRatherThanAsNull() {
        val api = apiReturning("callRpc", LightClientFixtures.CALL_RPC_NOT_INITIALIZED)

        val raw = assertNotNull(api.callRpc("get_tip_header"), "callRpc must not answer null here")
        val envelope = json.parseToJsonElement(raw).jsonObject

        assertEquals("2.0", envelope.getValue("jsonrpc").jsonPrimitive.content)
        assertNull(envelope["result"])
        val error = assertNotNull(envelope["error"]).jsonObject
        assertEquals(-32603, error.getValue("code").jsonPrimitive.int)
        assertEquals(
            "Light client not initialized",
            error.getValue("message").jsonPrimitive.content
        )
    }

    @Test
    fun extractDaoFieldsDecodesToFourUnpaddedHexWords() {
        val api = apiReturning("extractDaoFields", LightClientFixtures.EXTRACT_DAO_FIELDS)

        val fields = json.parseToJsonElement(
            assertNotNull(api.extractDaoFields("0x343d33782f21a12e7fe864f2f2862300b9e94907a0000000004f4b0b04fbfe06"))
        ).jsonObject

        assertEquals(setOf("c", "ar", "s", "u"), fields.keys)
        // AR is the accumulated rate the compensation formula divides by, so a
        // zero or unparseable value would silently zero every DAO yield.
        assertTrue(hex(fields.getValue("ar").jsonPrimitive.content) > 0)
        assertTrue(hex(fields.getValue("c").jsonPrimitive.content) > 0)
    }

    @Test
    fun calculateUnlockEpochCarriesTheAbsoluteEpochFlagAndTheDepositFraction() {
        val api = apiReturning("calculateUnlockEpoch", LightClientFixtures.CALCULATE_UNLOCK_EPOCH)

        // A bare hex string, not JSON: the bridge returns format!("0x{:x}").
        val since = hex(assertNotNull(api.calculateUnlockEpoch("0x3e8000c000000", "0x70805c5003608")))

        assertEquals(
            LightClientFixtures.SINCE_ABSOLUTE_EPOCH_FLAG,
            since and LightClientFixtures.SINCE_ABSOLUTE_EPOCH_FLAG
        )
        // The fraction must be the deposit epoch's own index/length, not 0/1,
        // or the on-chain script rejects the phase-2 unlock (#315).
        val deposit = EpochInfo.fromHex("0x3e8000c000000")
        val unlock = EpochInfo.fromHex("0x" + (since and 0x00ffffffffffffffL).toString(16))
        assertEquals(deposit.index, unlock.index)
        assertEquals(deposit.length, unlock.length)
        assertTrue(unlock.number >= deposit.number)
    }

    @Test
    fun unscriptedQueriesAnswerNullLikeANodeThatIsNotRunning() {
        val api = FakeLightClientApi()

        assertNull(api.getTipHeader())
        assertNull(api.getCells("{}", "asc", 10, null))
        assertNull(api.sendTransaction("{}"))
        assertEquals(false, api.start())
        assertEquals(-1L, api.calculateMaxWithdraw("0x", "0x", 0, 0))
    }

    @Test
    fun theFakeHandsBackTheListenerSoTestsCanDriveStatusTransitions() {
        val seen = mutableListOf<Pair<String, String>>()
        val fake = FakeLightClientApi().enqueueFlag("init", true)

        val ok = fake.init("/tmp/testnet.toml", "/tmp/data", object : LightClientStatusListener {
            override fun onStatusChanged(status: String, data: String) {
                seen += status to data
            }
        })

        assertTrue(ok)
        assertNotNull(fake.listener).onStatusChanged("running", "")
        assertEquals(listOf("running" to ""), seen)
    }
}
