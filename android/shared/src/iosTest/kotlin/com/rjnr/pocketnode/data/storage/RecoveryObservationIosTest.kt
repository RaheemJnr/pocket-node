package com.rjnr.pocketnode.data.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

/** Real iOS Room Flow cancellation and file reopen; no native node or wallet secrets. */
class RecoveryObservationIosTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test fun cancelledWalletObservationDoesNotReceiveLaterInvalidationsAndReopenRetainsJob() = runTest {
        val path = NSTemporaryDirectory() + "recovery-scope-${Random.nextLong()}.db"
        var db = createIosPocketNodeCoreDatabase(path)
        try {
            db.historyRecovery().ensureWallet("old", "MAINNET", "generation")
            val reader = HistoryRecoveryReader(db.historyRecovery())
            assertTrue(reader.observePresentation("old", "MAINNET").first().isRevalidating)
            var emissions = 0
            val observation = launch {
                reader.observePresentation("old", "MAINNET").collect { emissions++ }
            }
            // Wait on the actual observed result rather than a fixed sleep.
            while (emissions == 0) kotlinx.coroutines.yield()
            observation.cancelAndJoin()
            val stoppedAt = emissions
            db.historyRecovery().registered("old", "MAINNET", "generation", 23_000_000, "owned-script")
            val newScope = reader.observePresentation("new", "TESTNET").first()
            assertFalse(newScope.isVisible)
            assertEquals(stoppedAt, emissions)
            db.close()
            db = createIosPocketNodeCoreDatabase(path)
            val job = db.historyRecovery().job("old", "MAINNET")!!
            assertEquals("REGISTERED", job.status)
            assertEquals(0L, job.startBlock)
            assertEquals(23_000_000L, job.targetBlock)
            assertTrue(HistoryRecoveryReader(db.historyRecovery()).observePresentation("old", "MAINNET").first().isRevalidating)
            assertNull(db.historyRecovery().job("new", "TESTNET"))
        } finally {
            db.close()
            listOf(path, "$path-wal", "$path-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }
}
