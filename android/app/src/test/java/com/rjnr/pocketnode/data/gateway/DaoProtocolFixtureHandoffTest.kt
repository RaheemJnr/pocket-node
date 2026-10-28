package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.*
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.send.SendContext
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fixture handoff, NOT live JNI: literal envelopes emitted by the genuine native
 * recovery_dao_protocol_bridge_fixture dispatch test. Transport replays bytes;
 * it never constructs successful proof envelopes. See the resource README.
 */
@RunWith(RobolectricTestRunner::class)
class DaoProtocolFixtureHandoffTest {
    private val json = Json { encodeDefaults = true }
    private val fixture = json.parseToJsonElement(requireNotNull(javaClass.getResource("/dao-protocol-fixture.json")).readText()).jsonObject
    private fun envelope(name: String) = fixture.getValue(name).toString()
    private val receipt = json.decodeFromJsonElement<DaoRecoveryReceipt>(fixture.getValue("poll").jsonObject.getValue("result").jsonObject.getValue("receipt"))
    private val oldHeader = json.decodeFromJsonElement<DaoRecoveryHeader>(fixture.getValue("old_header"))
    private val network = NetworkType.TESTNET
    private val point = OutPoint(receipt.transactionHash, "0x0")
    private val script = receipt.rawTransaction.outputs.single().lock
    private val capacity = receipt.rawTransaction.outputs.single().capacity.removePrefix("0x").toLong(16)
    private fun row(index: String) = DaoCellEntity(point.txHash, index, capacity, "UNLOCKABLE", 13,
        receipt.rawTransaction.headerDeps.single(), oldHeader.epoch, 23, oldHeader.hash, oldHeader.epoch,
        123, oldHeader.epoch, 999, network.name, 8, "fixture-wallet")

    @Test fun `real protocol envelopes repair aliases atomically then expiry and restart block actions despite retained maps`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "dao-protocol-fixture-handoff.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        val api = mockk<LightClientApi>(relaxed = true)
        var validation = "validate"
        every { api.callRpc("get_verification_epoch") } returns """{"result":{"version":1,"generation":"${receipt.identity.storeGeneration}"}}"""
        every { api.requestDaoRecovery(point.txHash) } returns envelope("request")
        every { api.pollDaoRecovery(receipt.requestId) } returns envelope("poll")
        every { api.validateDaoRecovery(receipt.requestId, receipt.receiptId) } answers { envelope(validation) }
        // These retained lookup maps remain unchanged when native authority expires.
        every { api.getHeaderByNumber("0x17") } returns fixture.getValue("old_header").toString()
        every { api.getHeaderByNumber("0x19") } returns json.encodeToString(DaoRecoveryHeader.serializer(), receipt.header)
        try {
            var gate = VerificationCacheGate(context, db)
            gate.reconcile(api, network)
            db.walletDao().insert(WalletEntity("fixture-wallet", "Synthetic", "raw_key", null, null, isActive = true))
            db.daoCellDao().upsert(row("0x0"))
            db.daoCellDao().upsert(row("0000"))
            var recovery = DaoRecoveryCoordinator(db, gate, api, json)
            val attempt = recovery.begin("fixture-wallet", network, script, receipt.identity.genesisHash, point)
            val revision = gate.currentRevision()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_fail BEFORE INSERT ON dao_recovery_guards BEGIN SELECT RAISE(ABORT, 'synthetic SQL failure'); END")
            assertEquals(RecoveryOutcome.Unavailable, recovery.recover(attempt))
            assertEquals(revision, gate.currentRevision())
            assertTrue(recovery.guards(network, point).isEmpty())
            assertTrue(db.daoCellDao().allRows().all { it.withdrawBlockNumber == 23L && it.compensation == 123L })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fixture_fail")
            assertEquals(RecoveryOutcome.Adopted, recovery.recover(attempt))
            assertTrue(gate.currentRevision() > revision)
            assertEquals(setOf("0x0", "0000"), db.daoCellDao().allRows().map { it.index }.toSet())
            assertTrue(db.daoCellDao().allRows().all { it.withdrawBlockNumber == 25L && it.withdrawBlockHash == receipt.header.hash && it.compensation == 0L && it.withdrawEpochHex == null })
            assertNotEquals(oldHeader.hash, receipt.header.hash)
            assertNotNull(recovery.validateGuard(attempt, point))
            val send = SendContext(network, "fixture-wallet", script, { false }, this)
            val tx = Transaction(cellDeps = emptyList(), headerDeps = listOf(receipt.rawTransaction.headerDeps.single(), receipt.header.hash),
                cellInputs = listOf(CellInput(previousOutput = point)), cellOutputs = listOf(CellOutput(receipt.rawTransaction.outputs.single().capacity, script)),
                outputsData = listOf("0x"), witnesses = listOf("0x"))
            val authorization = recovery.authorize(send, tx)
            authorization.requireCurrent()
            validation = "expired_validate"
            assertTrue(runCatching { authorization.requireCurrent() }.isFailure)
            assertTrue(runCatching { recovery.authorize(send, tx) }.isFailure)
            assertNull(recovery.validateGuard(attempt, point))
            repeat(2) {
                for (adopted in db.daoCellDao().allRows()) {
                    // Even persisted derived metadata plus matching retained maps grants no authority.
                    val stale = adopted.copy(depositBlockHash = row("0x0").depositBlockHash, depositEpochHex = oldHeader.epoch,
                        withdrawEpochHex = receipt.header.epoch, unlockEpochHex = receipt.header.epoch, compensation = 123)
                    assertEquals(0L, stale.toCachedDeposit(recovery).compensation)
                    assertNull(stale.toCachedDeposit(recovery).withdrawEpoch)
                }
            }
            db.close()
            db = open()
            gate = VerificationCacheGate(context, db)
            gate.reconcile(api, network)
            recovery = DaoRecoveryCoordinator(db, gate, api, json)
            assertEquals(1, recovery.guards(network, point).size)
            assertNull(recovery.validateGuard(network, point, "fixture-wallet"))
            assertTrue(runCatching { recovery.authorize(send, tx) }.isFailure)
            validation = "restart_validate"
            val restarted = recovery.begin("fixture-wallet", network, script, receipt.identity.genesisHash, point)
            assertEquals(RecoveryOutcome.Unavailable, recovery.recover(restarted))
            assertEquals(1, recovery.guards(network, point).size)
            verify(exactly = 0) { api.sendTransaction(any()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `native expired poll and failed validation never create a durable guard`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val api = mockk<LightClientApi>(relaxed = true)
        every { api.callRpc("get_verification_epoch") } returns """{"result":{"version":1,"generation":"${receipt.identity.storeGeneration}"}}"""
        every { api.requestDaoRecovery(point.txHash) } returns envelope("request")
        every { api.pollDaoRecovery(receipt.requestId) } returns envelope("expired_poll")
        every { api.validateDaoRecovery(receipt.requestId, receipt.receiptId) } returns envelope("expired_validate")
        try {
            val gate = VerificationCacheGate(context, db); gate.reconcile(api, network)
            db.walletDao().insert(WalletEntity("fixture-wallet", "Synthetic", "raw_key", null, null, isActive = true))
            db.daoCellDao().upsert(row("0x0"))
            val recovery = DaoRecoveryCoordinator(db, gate, api, json)
            val attempt = recovery.begin("fixture-wallet", network, script, receipt.identity.genesisHash, point)
            assertEquals(RecoveryOutcome.Unavailable, recovery.recover(attempt))
            every { api.pollDaoRecovery(receipt.requestId) } returns envelope("poll")
            assertEquals(RecoveryOutcome.Unavailable, recovery.recover(attempt))
            assertTrue(recovery.guards(network, point).isEmpty())
            assertEquals(23L, db.daoCellDao().allRows().single().withdrawBlockNumber)
        } finally { db.close() }
    }
}
