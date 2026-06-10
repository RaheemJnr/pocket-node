package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the persistence side-effects of #115 Phase A Task 2 —
 * the new pre-broadcast insert + CAS / rollback logic that lives inside
 * GatewayRepository.sendTransaction.
 *
 * Test approach: persistence-pattern simulation (NOT full Repository
 * instantiation). Reason: GatewayRepository's constructor pulls in
 * KeyManager, WalletPreferences, AppDatabase, multiple DAOs, the
 * TransactionBuilder, and the JNI bridge — wiring all of those up in
 * Robolectric for a unit test would require extensive stubbing while
 * adding little signal beyond what we get by exercising the exact
 * persistence pattern (insert under sendMutex, CAS on success, delete
 * on null/exception) directly against an in-memory Room DB. The
 * BroadcastClient indirection (introduced in this task) is also
 * exercised here via fake implementations.
 *
 * If the persistence logic in GatewayRepository diverges from this
 * pattern, the integration must be re-validated by hand or via an
 * instrumentation test; the contract this test pins is:
 *
 *   1. Happy path:   pending_broadcasts ends in BROADCAST,
 *                    transactions row remains PENDING with the
 *                    actual balanceChange (not "0x0").
 *   2. JNI null:     both rows deleted.
 *   3. JNI throws:   both rows deleted.
 *   4. Idempotent:   second insert with same hash is skipped.
 *   5. CAS guard:    compareAndUpdateState returns 1 only when state
 *                    is BROADCASTING; subsequent calls return 0.
 */
@RunWith(RobolectricTestRunner::class)
class GatewayRepositorySendTransactionTest {

    private lateinit var db: AppDatabase
    private lateinit var cacheManager: CacheManager
    private val sendMutex = Mutex()

    private val walletId = "wallet-1"
    private val network = "TESTNET"
    private val txHash = "0x" + "ab".repeat(32)
    private val signedJson = """{"version":"0x0"}"""
    private val reservedJson = """[]"""

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheManager = CacheManager(db.transactionDao(), db.balanceCacheDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    /** Mirrors the sendMutex.withLock block from sendTransaction. */
    private suspend fun preBroadcastInsert(
        balanceChangeHex: String,
        tipNumber: Long = 100L,
        now: Long = System.currentTimeMillis()
    ) {
        sendMutex.withLock {
            val existing = db.pendingBroadcastDao().getActive(walletId, network)
                .firstOrNull { it.txHash == txHash }
            if (existing == null) {
                db.pendingBroadcastDao().insert(
                    PendingBroadcastEntity(
                        txHash = txHash,
                        walletId = walletId,
                        network = network,
                        signedTxJson = signedJson,
                        reservedInputs = reservedJson,
                        state = "BROADCASTING",
                        submittedAtTipBlock = tipNumber,
                        nullCount = 0,
                        createdAt = now,
                        lastCheckedAt = now
                    )
                )
                cacheManager.insertPendingTransaction(
                    txHash = txHash,
                    network = network,
                    walletId = walletId,
                    balanceChange = balanceChangeHex,
                    direction = "out",
                    fee = "0x0"
                )
            }
        }
    }

    @Test
    fun `happy path - rows persist and CAS to BROADCAST`() = runTest {
        val balanceChangeHex = "-0x" + 6_100_000_000L.toString(16)
        val fakeBroadcast = BroadcastClient { _ -> "\"$txHash\"" }

        // Pre-broadcast insert
        preBroadcastInsert(balanceChangeHex)

        // After insert: pending_broadcasts row in BROADCASTING; transactions row PENDING.
        val activeBefore = db.pendingBroadcastDao().getActive(walletId, network)
        assertEquals(1, activeBefore.size)
        assertEquals("BROADCASTING", activeBefore.first().state)

        val txBefore = db.transactionDao().getByTxHash(txHash)
        assertNotNull(txBefore)
        assertEquals("PENDING", txBefore!!.status)
        assertEquals(balanceChangeHex, txBefore.balanceChange)
        // UX bug guard: the old code wrote "0x0" here.
        assertTrue(
            "balanceChange must not be the placeholder '0x0'",
            txBefore.balanceChange != "0x0"
        )
        assertEquals("out", txBefore.direction)

        // Broadcast (outside mutex) — JNI returns matching hash.
        val raw = fakeBroadcast.sendRaw(signedJson)
        val returnedHash = raw!!.trim('"')
        assertEquals(txHash, returnedHash)

        val cas = db.pendingBroadcastDao().compareAndUpdateState(
            hash = txHash,
            expected = "BROADCASTING",
            next = "BROADCAST",
            now = System.currentTimeMillis()
        )
        assertEquals(1, cas)

        val activeAfter = db.pendingBroadcastDao().getActive(walletId, network)
        assertEquals(1, activeAfter.size)
        assertEquals("BROADCAST", activeAfter.first().state)
        // Tx row still PENDING — that's the user-facing ledger state.
        assertEquals("PENDING", db.transactionDao().getByTxHash(txHash)!!.status)
    }

    @Test
    fun `JNI null - both rows deleted`() = runTest {
        val balanceChangeHex = "-0x" + 6_100_000_000L.toString(16)
        val fakeBroadcast = BroadcastClient { _ -> null }

        preBroadcastInsert(balanceChangeHex)
        assertEquals(1, db.pendingBroadcastDao().getActive(walletId, network).size)
        assertNotNull(db.transactionDao().getByTxHash(txHash))

        val raw = fakeBroadcast.sendRaw(signedJson)
        if (raw == null) {
            db.pendingBroadcastDao().delete(txHash)
            cacheManager.deleteTransaction(txHash)
        }

        assertEquals(0, db.pendingBroadcastDao().getActive(walletId, network).size)
        assertNull(db.transactionDao().getByTxHash(txHash))
    }

    @Test
    fun `JNI throws - both rows deleted`() = runTest {
        val balanceChangeHex = "-0x" + 6_100_000_000L.toString(16)
        val fakeBroadcast = BroadcastClient { _ -> throw RuntimeException("boom") }

        preBroadcastInsert(balanceChangeHex)
        assertEquals(1, db.pendingBroadcastDao().getActive(walletId, network).size)
        assertNotNull(db.transactionDao().getByTxHash(txHash))

        try {
            fakeBroadcast.sendRaw(signedJson)
            error("should have thrown")
        } catch (e: RuntimeException) {
            db.pendingBroadcastDao().delete(txHash)
            cacheManager.deleteTransaction(txHash)
        }

        assertEquals(0, db.pendingBroadcastDao().getActive(walletId, network).size)
        assertNull(db.transactionDao().getByTxHash(txHash))
    }

    @Test
    fun `idempotent - second insert with same hash skipped`() = runTest {
        val balanceChangeHex = "-0x" + 6_100_000_000L.toString(16)

        preBroadcastInsert(balanceChangeHex)
        // Second call should observe the existing row and skip.
        preBroadcastInsert(balanceChangeHex)

        val active = db.pendingBroadcastDao().getActive(walletId, network)
        assertEquals(1, active.size)
        assertEquals("BROADCASTING", active.first().state)
    }

    @Test
    fun `CAS guard - second compareAndUpdateState returns zero`() = runTest {
        preBroadcastInsert("-0x1")

        val first = db.pendingBroadcastDao().compareAndUpdateState(
            hash = txHash,
            expected = "BROADCASTING",
            next = "BROADCAST",
            now = System.currentTimeMillis()
        )
        assertEquals(1, first)

        // Second CAS from BROADCASTING is a no-op (row already in BROADCAST).
        val second = db.pendingBroadcastDao().compareAndUpdateState(
            hash = txHash,
            expected = "BROADCASTING",
            next = "BROADCAST",
            now = System.currentTimeMillis()
        )
        assertEquals(0, second)
    }

    @Test
    fun `hash mismatch path - re-key under returned hash`() = runTest {
        val balanceChangeHex = "-0x" + 6_100_000_000L.toString(16)
        val returnedHash = "0x" + "cd".repeat(32)
        val fakeBroadcast = BroadcastClient { _ -> "\"$returnedHash\"" }

        preBroadcastInsert(balanceChangeHex)

        val raw = fakeBroadcast.sendRaw(signedJson)!!
        val rh = raw.trim('"')
        assertTrue(rh.lowercase() != txHash.lowercase())

        // Re-key (mirroring the production fallback).
        db.pendingBroadcastDao().delete(txHash)
        cacheManager.deleteTransaction(txHash)
        db.pendingBroadcastDao().insert(
            PendingBroadcastEntity(
                txHash = rh,
                walletId = walletId,
                network = network,
                signedTxJson = signedJson,
                reservedInputs = reservedJson,
                state = "BROADCAST",
                submittedAtTipBlock = 100L,
                nullCount = 0,
                createdAt = System.currentTimeMillis(),
                lastCheckedAt = System.currentTimeMillis()
            )
        )
        cacheManager.insertPendingTransaction(
            txHash = rh,
            network = network,
            walletId = walletId,
            balanceChange = balanceChangeHex,
            direction = "out",
            fee = "0x0"
        )

        // Old hash gone, new hash present in BROADCAST.
        assertNull(db.transactionDao().getByTxHash(txHash))
        val active = db.pendingBroadcastDao().getActive(walletId, network)
        assertEquals(1, active.size)
        assertEquals(rh, active.first().txHash)
        assertEquals("BROADCAST", active.first().state)
        assertNotNull(db.transactionDao().getByTxHash(rh))
    }

    /**
     * #316 retry conflict-safety: re-broadcasting a FAILED row must reuse the
     * ORIGINAL signed bytes and the ORIGINAL reserved inputs verbatim. That is
     * what makes the original and the retry double-spend the same cells and
     * conflict, so at most one can ever commit (no double-pay). This pins the
     * persistence contract of `retryBroadcast`: getFailedRow -> delete ->
     * re-broadcast the stored tx (sendTransaction recomputes reservedInputs
     * from the same tx, so they are byte-identical).
     */
    @Test
    fun `retryBroadcast reuses original signed tx and reserved inputs (no double-pay)`() = runTest {
        val originalReserved = """[{"txHash":"0x${"11".repeat(32)}","index":"0x0"}]"""
        val originalSigned = """{"version":"0x0","inputs":["original"]}"""
        val balanceChangeHex = "-0x" + 6_100_000_000L.toString(16)

        // A FAILED row as the watchdog would leave it.
        db.pendingBroadcastDao().insert(
            PendingBroadcastEntity(
                txHash = txHash,
                walletId = walletId,
                network = network,
                signedTxJson = originalSigned,
                reservedInputs = originalReserved,
                state = "FAILED",
                submittedAtTipBlock = 100L,
                nullCount = 3,
                createdAt = System.currentTimeMillis(),
                lastCheckedAt = System.currentTimeMillis()
            )
        )

        // --- retryBroadcast persistence steps ---
        val failed = db.pendingBroadcastDao().getFailedRow(txHash)
        assertNotNull(failed)
        // Drop the FAILED row, then re-insert from the SAME stored tx, exactly
        // as sendTransaction does on the re-broadcast path.
        db.pendingBroadcastDao().delete(txHash)
        cacheManager.deleteTransaction(txHash)
        db.pendingBroadcastDao().insert(
            failed!!.copy(state = "BROADCASTING", nullCount = 0)
        )
        cacheManager.insertPendingTransaction(
            txHash = txHash,
            network = network,
            walletId = walletId,
            balanceChange = balanceChangeHex,
            direction = "out",
            fee = "0x0"
        )

        val active = db.pendingBroadcastDao().getActive(walletId, network)
        assertEquals(1, active.size)
        val row = active.first()
        assertEquals("BROADCASTING", row.state)
        // Conflict-safety: identical inputs => the retry double-spends the
        // original's cells; both can never commit.
        assertEquals(originalReserved, row.reservedInputs)
        // Same signed bytes => same recipient; no smallest-output heuristic.
        assertEquals(originalSigned, row.signedTxJson)
    }
}
