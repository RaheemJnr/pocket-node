package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.send.*
import com.rjnr.pocketnode.data.storage.*
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.validation.NetworkValidator
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoKnownUnsentTest {
    @Test fun `late refusal releases real reservations and preserves retryable activity without tip progress`() = checkRefusal(null)
    @Test fun `concurrent pending broadcast wins over known unsent cleanup`() = checkRefusal("BROADCAST")
    @Test fun `concurrent pending confirmation wins over known unsent cleanup`() = checkRefusal("CONFIRMED")
    @Test fun `concurrent activity confirmation wins even while pending lags`() = checkRefusal("ACTIVITY_CONFIRMED")
    @Test fun `replacement attempt with same hash keeps its own reservation`() = checkRefusal("REPLACED")

    private fun checkRefusal(advance: String?) = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val json = Json { encodeDefaults = true }
            val api = mockk<LightClientApi>(relaxed = true)
            every { api.getTipHeader() } returns null
            val script = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "11".repeat(20))
            val tx = Transaction(cellDeps = emptyList(), cellInputs = listOf(CellInput(previousOutput = OutPoint("0x" + "aa".repeat(32), "0x0"))),
                cellOutputs = listOf(CellOutput("0x174876e800", script)), outputsData = listOf("0x"), witnesses = listOf("0x1234"))
            val builder = TransactionBuilder(NetworkValidator())
            val hash = builder.computeTxHash(tx)
            val pending = RoomPendingBroadcastStore(db.pendingBroadcastDao())
            val transactions = RoomTransactionStore(db.transactionDao(), CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger, mockk(relaxed = true)))
            val pipeline = SendPipeline(api, builder, mockk(relaxed = true), pending, transactions,
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), json, NoopLogger,
                inputAuthorizer = NoDaoRecoveryAdoption)
            var checks = 0
            val result = pipeline.sendTransaction(SendContext(NetworkType.TESTNET, "wallet", script, { false }, this), tx,
                requireCurrentSelection = {
                    if (++checks == 3) {
                        if (advance == "ACTIVITY_CONFIRMED") db.transactionDao().updateStatusOnly(hash, "CONFIRMED")
                        else if (advance == "REPLACED") {
                            val replacement = db.pendingBroadcastDao().getActive("wallet", "TESTNET").single()
                            db.pendingBroadcastDao().insert(replacement.copy(createdAt = replacement.createdAt + 1))
                        }
                        else if (advance != null) db.pendingBroadcastDao().compareAndUpdateState(hash, "BROADCASTING", advance, 999)
                        error("Receipt invalidated")
                    }
                })
            assertTrue(result.isFailure)
            verify(exactly = 0) { api.sendTransaction(any()) }
            val activity = db.transactionDao().getByTxHash(hash)!!
            if (advance == null) {
                assertTrue(pending.getActive("wallet", "TESTNET").isEmpty())
                val failed = pending.getFailedRow(hash)!!
                assertEquals(json.encodeToString(tx), failed.signedTxJson)
                assertEquals(0L, failed.submittedAtTipBlock)
                assertEquals("FAILED", activity.status)
                assertEquals("out", activity.direction)
            } else {
                assertEquals(if (advance == "ACTIVITY_CONFIRMED") "CONFIRMED" else "PENDING", activity.status)
                assertNull(pending.getFailedRow(hash))
            }
        } finally { db.close() }
    }
}
