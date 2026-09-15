package com.rjnr.pocketnode.data.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The M3 spike's acceptance test: a real Room database, opened by the bundled SQLite driver
 * on the iOS simulator, surviving an insert, a read back and an update.
 *
 * It lives in `iosTest` rather than `commonTest` because building the database needs a
 * platform file path. Android keeps its own database in M3, so there is no Android twin.
 */
class SyncProgressRoomDaoIosTest {

    private val path = NSTemporaryDirectory() + "m3-spike-${Random.nextLong()}.db"
    private val db = createIosPocketNodeCoreDatabase(path)

    // NSFileManager's trailing NSError** parameter is a cinterop pointer type.
    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun tearDown() {
        db.close()
        val files = NSFileManager.defaultManager
        // The bundled driver runs in WAL mode, so the sidecar files exist too.
        listOf(path, "$path-wal", "$path-shm").forEach { files.removeItemAtPath(it, null) }
    }

    @Test
    fun `round trips rows and updates one in place`() = runTest {
        val dao = db.syncProgress()

        assertNull(dao.get("wallet-a", "TESTNET"), "a fresh database must be empty")

        val mainnet = SyncProgressRow(
            walletId = "wallet-a",
            network = "MAINNET",
            lightStartBlockNumber = 18_300_000L,
            localSavedBlockNumber = 18_300_512L,
            updatedAt = 1_726_000_000_000L,
        )
        val testnet = SyncProgressRow(
            walletId = "wallet-a",
            network = "TESTNET",
            lightStartBlockNumber = 0L,
            localSavedBlockNumber = 42L,
            updatedAt = 1_726_000_001_000L,
        )
        dao.upsert(mainnet)
        dao.upsert(testnet)

        assertEquals(mainnet, dao.get("wallet-a", "MAINNET"))
        assertEquals(testnet, dao.get("wallet-a", "TESTNET"))
        assertEquals(listOf(mainnet, testnet), dao.all(), "both rows, ordered by the primary key")

        // Same primary key, new progress: the upsert must replace rather than duplicate.
        val advanced = mainnet.copy(localSavedBlockNumber = 18_400_000L, updatedAt = 1_726_000_002_000L)
        dao.upsert(advanced)

        assertEquals(advanced, dao.get("wallet-a", "MAINNET"))
        assertEquals(2, dao.all().size, "the upsert replaced the row instead of adding one")
    }
}
