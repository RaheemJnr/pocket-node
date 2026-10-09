package com.rjnr.pocketnode.data.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The v1 to v2 auto-migration, against a real v1 file.
 *
 * v2 adds three tables and changes nothing about `sync_progress`, so Room
 * generates the migration from the two exported schema JSONs. What this proves
 * is the part the generator cannot: that a device which installed the v1
 * build opens the upgraded database with its checkpoints intact instead of
 * being thrown back to its sync mode's start block, and that the three new
 * tables are usable immediately afterwards.
 *
 * The v1 file is written by hand rather than by an older build of the code,
 * which no longer exists. The statements are `schemas/...PocketNodeCoreDatabase/1.json`
 * verbatim, `room_master_table` identity hash included, which is exactly what
 * Room validates the old file against.
 */
class PocketNodeCoreDatabaseMigrationIosTest {

    private val path = NSTemporaryDirectory() + "m3-migration-${Random.nextLong()}.db"

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun tearDown() {
        val files = NSFileManager.defaultManager
        listOf(path, "$path-wal", "$path-shm").forEach { files.removeItemAtPath(it, null) }
    }

    @Test
    fun `a v1 file keeps its sync checkpoints and gains the three new tables`() = runTest {
        writeVersion1Database()

        val db = createIosPocketNodeCoreDatabase(path)
        try {
            // The checkpoint written against v1 is still there. This is the
            // whole point of an auto-migration rather than a destructive one:
            // losing it would re-register the wallet from its mode's start
            // block and resync from scratch.
            val progress = db.syncProgress().get("w1", "TESTNET")
            assertNotNull(progress, "the v1 row survived the migration")
            assertEquals(1_000L, progress.lightStartBlockNumber)
            assertEquals(2_000L, progress.localSavedBlockNumber)

            // And the three tables v2 adds are there and writable.
            val transactions = RoomKmpTransactionStore(db.transactions())
            transactions.insertPending("0xaa", "TESTNET", "w1")
            assertNotNull(transactions.getByHash("0xaa", "TESTNET"))

            val broadcasts = RoomKmpPendingBroadcastStore(db.pendingBroadcasts())
            broadcasts.insert(
                PendingBroadcastRecord(
                    txHash = "0xaa",
                    state = "BROADCASTING",
                    reservedInputs = "[]",
                    signedTxJson = "{}",
                    walletId = "w1",
                    network = "TESTNET",
                    submittedAtTipBlock = 1L,
                    nullCount = 0,
                    createdAt = 1L,
                    lastCheckedAt = 1L,
                )
            )
            assertEquals(1, broadcasts.getActive("w1", "TESTNET").size)

            val balances = RoomKmpBalanceCache(db.balanceCache())
            assertEquals(null, balances.getCachedBalance("TESTNET", "w1"))
        } finally {
            db.close()
        }
    }

    /** Creates the file exactly as the v1 schema declares it. */
    private fun writeVersion1Database() {
        val connection = BundledSQLiteDriver().open(path)
        try {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_progress` (`walletId` TEXT NOT NULL, " +
                    "`network` TEXT NOT NULL, `lightStartBlockNumber` INTEGER NOT NULL, " +
                    "`localSavedBlockNumber` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`walletId`, `network`))"
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            connection.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, '$VERSION_1_IDENTITY_HASH')"
            )
            connection.execSQL(
                "INSERT INTO sync_progress VALUES ('w1', 'TESTNET', 1000, 2000, 1726000000000)"
            )
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }

    private companion object {
        /** `schemas/com.rjnr.pocketnode.data.storage.PocketNodeCoreDatabase/1.json`. */
        const val VERSION_1_IDENTITY_HASH = "57ec7c6cc6d526c6c9dd53222857e7e8"
    }
}
