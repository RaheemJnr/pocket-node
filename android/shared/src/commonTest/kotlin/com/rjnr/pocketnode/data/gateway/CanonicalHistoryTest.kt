package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.prefs.FakeUiPreferences
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.storage.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class CanonicalHistoryTest {
    private val script = Script("0x" + "1".repeat(64), "type", "0x01")
    private val hash = "0x" + "a".repeat(64)
    private fun proof() = CanonicalHistorySnapshot(1, true, "TESTNET", "0x" + "1".repeat(64), "store", script, "a".repeat(32), "0", "20", "0x" + "2".repeat(64), "20", emptySet())
    private fun row() = TransactionRow(hash, "0xa", "old", 0, "0x1", "in", "0x0", 2, null, "TESTNET", "CONFIRMED", false, 10, "w1")
    private suspend fun read(snapshot: String?, dao: FakeTransactionRoomDao, wallet: String = "w1", network: NetworkType = NetworkType.TESTNET, recheck: String? = snapshot, pages: Array<String?> = arrayOf("""{"objects":[],"last_cursor":null}""")) {
        val api = FakeLightClientApi().enqueue("getTransactions", *pages).enqueue("getHistorySnapshot", snapshot, recheck)
        val reader = LedgerReader(api, FakeBalanceCache(), RoomKmpTransactionStore(dao), FakeHeaderCache(), FakeWalletRegistry(), FakeSubAccountCandidateStore(), FakeSyncPreferences(), FakeUiPreferences(), Json { ignoreUnknownKeys = true }, NoopLogger)
        assertTrue(reader.getTransactions(script, wallet, network).isSuccess)
    }
    @Test fun `actual history read reconciles orphan and keeps pending and uncovered history`() = runTest {
        val dao = FakeTransactionRoomDao()
        dao.rows += row()
        dao.rows += row().copy(txHash = "local", status = "PENDING", isLocal = true)
        dao.rows += row().copy(txHash = "older", blockNumber = "0x1")
        read(Json.encodeToString(proof().copy(startHeight = "5")), dao)
        assertEquals(setOf("local", "older"), dao.rows.map { it.txHash }.toSet())
        dao.insert(row().copy(blockNumber = "0xb"))
        read(Json.encodeToString(proof().copy(hashes = setOf(hash))), dao)
        assertEquals("0xb", dao.rows.single { it.txHash == hash }.blockNumber)
    }
    @Test fun `unavailable incomplete malformed and wrong script evidence retain cached receipt`() = runTest {
        for (snapshot in listOf(null, "{}", Json.encodeToString(proof().copy(complete = false)), Json.encodeToString(proof().copy(script = script.copy(args = "0x02"))), Json.encodeToString(proof().copy(processedThrough = "19")))) {
            val dao = FakeTransactionRoomDao().also { it.rows += row() }
            read(snapshot, dao)
            assertEquals(listOf(row()), dao.rows)
        }
    }
    @Test fun `wallet and network boundaries retain unrelated cached receipt`() = runTest {
        val dao = FakeTransactionRoomDao().also { it.rows += row() }
        read(Json.encodeToString(proof()), dao, wallet = "w2")
        read(Json.encodeToString(proof()), dao, network = NetworkType.MAINNET)
        assertEquals(listOf(row()), dao.rows)
    }
    @Test fun `tip or registration changes before storage mutation retain cached receipt`() = runTest {
        for (changed in listOf(proof().copy(tipHash = "0x" + "3".repeat(64)), proof().copy(registration = "b".repeat(32)))) {
            val dao = FakeTransactionRoomDao().also { it.rows += row() }
            read(Json.encodeToString(proof()), dao, recheck = Json.encodeToString(changed))
            assertEquals(listOf(row()), dao.rows)
        }
    }
    @Test fun `middle page failure and capped walk do not delete cached receipts without native authority`() = runTest {
        val fixture = Json { ignoreUnknownKeys = true }.decodeFromString<com.rjnr.pocketnode.data.gateway.models.JniPagination<com.rjnr.pocketnode.data.gateway.models.JniTxWithCell>>(LightClientFixtures.GET_TRANSACTIONS_PAGE)
        val full = Json.encodeToString(fixture.copy(objects = List(100) { fixture.objects.first() }, lastCursor = "cursor"))
        for (pages in listOf(arrayOf(full, null), arrayOf<String?>(full))) {
            val dao = FakeTransactionRoomDao().also { it.rows += row() }
            read(null, dao, pages = pages)
            assertEquals(row(), dao.rows.single { it.txHash == hash })
        }
    }
}
