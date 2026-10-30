package com.rjnr.pocketnode.data.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

class VerificationCacheIosTest {
    private val path = NSTemporaryDirectory() + "verification-${Random.nextLong()}.db"
    private val db = createIosPocketNodeCoreDatabase(path)

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest fun cleanup() {
        db.close()
        listOf(path, "$path-wal", "$path-shm").forEach {
            NSFileManager.defaultManager.removeItemAtPath(it, null)
        }
    }

    @Test fun allWalletProgressIsClearedAndLocalPendingSurvives() = runTest {
        for (wallet in listOf("active", "inactive")) {
            db.syncProgress().upsert(SyncProgressRow(wallet, "MAINNET", 100, 200, 1))
        }
        db.syncProgress().upsert(SyncProgressRow("inactive", "TESTNET", 100, 200, 1))
        val remote = TransactionRow("remote", "0x64", "old-block", 1, "123", "in", "1", 5,
            "0x1", "MAINNET", "PENDING", false, 1, "inactive", 1)
        db.transactions().insert(remote)
        db.transactions().insert(remote.copy(txHash = "local", status = "PENDING", isLocal = true))
        db.transactions().insert(remote.copy(txHash = "failed", status = "FAILED", isLocal = true))
        val pending = PendingBroadcastRow("local", "inactive", "MAINNET", "{}", "[]", "BROADCAST", 100, 0, 1, 1)
        db.pendingBroadcasts().insert(pending)
        repeat(2) { db.verificationCache().invalidate("MAINNET") }
        assertNull(db.syncProgress().get("active", "MAINNET"))
        assertNull(db.syncProgress().get("inactive", "MAINNET"))
        assertNotNull(db.syncProgress().get("inactive", "TESTNET"))
        val quarantined = db.transactions().getByHash("remote", "MAINNET")!!
        assertEquals("UNVERIFIED", quarantined.status)
        assertEquals("0", quarantined.balanceChange)
        assertFailsWith<IllegalStateException> { quarantined.toRecord() }
        assertEquals(setOf("local", "failed"), db.transactions().pageAll("inactive", "MAINNET", 20, 0).map { it.txHash }.toSet())
        assertEquals(listOf(pending), db.pendingBroadcasts().getActive("inactive", "MAINNET"))
    }
}
