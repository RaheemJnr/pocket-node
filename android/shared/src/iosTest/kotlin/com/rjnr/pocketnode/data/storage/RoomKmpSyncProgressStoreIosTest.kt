package com.rjnr.pocketnode.data.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RoomKmpSyncProgressStore] against a real database on the simulator.
 *
 * The seam's contract is only half about the mapping. The other half is the
 * `updateLightStart` row count, which is what tells `setScriptsAndRecord`
 * whether to fall back to an upsert, and which an in-memory fake can only
 * imitate. This is the test that proves the SQL behind it.
 */
class RoomKmpSyncProgressStoreIosTest {

    private val path = NSTemporaryDirectory() + "m3-sync-store-${Random.nextLong()}.db"
    private val db = createIosPocketNodeCoreDatabase(path)
    private val store = RoomKmpSyncProgressStore(db.syncProgress())

    // NSFileManager's trailing NSError** parameter is a cinterop pointer type.
    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun tearDown() {
        db.close()
        val files = NSFileManager.defaultManager
        // The bundled driver runs in WAL mode, so the sidecar files exist too.
        listOf(path, "$path-wal", "$path-shm").forEach { files.removeItemAtPath(it, null) }
    }

    private fun record(
        walletId: String = "wallet-a",
        network: String = "TESTNET",
        lightStart: Long = 1_000L,
        localSaved: Long = 2_000L,
        updatedAt: Long = 1_726_000_000_000L,
    ) = SyncProgressRecord(
        walletId = walletId,
        network = network,
        lightStartBlockNumber = lightStart,
        localSavedBlockNumber = localSaved,
        updatedAt = updatedAt,
    )

    @Test
    fun `a record round trips through the database unchanged`() = runTest {
        assertTrue(store.getAllForNetwork("TESTNET").isEmpty(), "a fresh database is empty")

        val testnet = record()
        val mainnet = record(network = "MAINNET", lightStart = 18_300_000L, localSaved = 18_300_512L)
        store.upsert(testnet)
        store.upsert(mainnet)

        assertEquals(listOf(testnet), store.getAllForNetwork("TESTNET"))
        assertEquals(listOf(mainnet), store.getAllForNetwork("MAINNET"))
    }

    @Test
    fun `an upsert on the same key replaces rather than duplicates`() = runTest {
        store.upsert(record())
        store.upsert(record(lightStart = 9_000L, localSaved = 9_500L, updatedAt = 1L))

        val rows = store.getAllForNetwork("TESTNET")
        assertEquals(1, rows.size)
        assertEquals(9_000L, rows.single().lightStartBlockNumber)
        assertEquals(9_500L, rows.single().localSavedBlockNumber)
        assertEquals(1L, rows.single().updatedAt)
    }

    @Test
    fun `updateLightStart reports no rows when the wallet has never registered`() = runTest {
        assertEquals(0, store.updateLightStart("nobody", "TESTNET", 500L, 7L))
        assertTrue(store.getAllForNetwork("TESTNET").isEmpty(), "and it inserted nothing")
    }

    @Test
    fun `updateLightStart moves the start block and leaves the processed block alone`() = runTest {
        store.upsert(record(lightStart = 1_000L, localSaved = 2_000L))

        assertEquals(1, store.updateLightStart("wallet-a", "TESTNET", 500L, 7L))

        val row = store.getAllForNetwork("TESTNET").single()
        assertEquals(500L, row.lightStartBlockNumber)
        assertEquals(2_000L, row.localSavedBlockNumber, "the poll's own progress survives")
        assertEquals(7L, row.updatedAt)
    }

    @Test
    fun `updateLightStart touches only the wallet and network it names`() = runTest {
        store.upsert(record(network = "TESTNET"))
        store.upsert(record(network = "MAINNET"))
        store.upsert(record(walletId = "wallet-b", network = "TESTNET"))

        assertEquals(1, store.updateLightStart("wallet-a", "TESTNET", 42L, 7L))

        assertEquals(42L, store.getAllForNetwork("TESTNET").single { it.walletId == "wallet-a" }.lightStartBlockNumber)
        assertEquals(1_000L, store.getAllForNetwork("TESTNET").single { it.walletId == "wallet-b" }.lightStartBlockNumber)
        assertEquals(1_000L, store.getAllForNetwork("MAINNET").single().lightStartBlockNumber)
    }
}
