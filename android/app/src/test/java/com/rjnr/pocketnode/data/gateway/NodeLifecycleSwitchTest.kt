package com.rjnr.pocketnode.data.gateway

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.NetworkPreferences
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NodeLifecycleSwitchTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).allowMainThreadQueries().build()
    private val gate = VerificationCacheGate(base, db)
    private var epoch = "a".repeat(32)
    private var handshakes = 0
    private val client = mockk<LightClientApi>(relaxed = true).apply {
        every { init(any(), any(), any()) } returns true
        every { start() } returns true
        every { callRpc("get_verification_epoch") } answers {
            handshakes++
            """{"result":{"version":1,"generation":"$epoch"}}"""
        }
    }
    private val preferences = Selection()
    private val cache = mockk<CacheManager>(relaxed = true)
    private val dao = mockk<DaoSyncManager>(relaxed = true)
    private val restart = RestartContext(base)
    private val lifecycle = NodeLifecycle(restart, client, preferences, cache, dao, NoopLogger, gate)

    private class Selection : NetworkPreferences {
        var selected = NetworkType.MAINNET
        val writes = mutableListOf<NetworkType>()
        var failWrite: (NetworkType) -> Boolean = { false }
        override fun getSelectedNetwork() = selected
        override fun setSelectedNetwork(network: NetworkType) {
            // Like SharedPreferences, memory can change even when durable publication fails.
            selected = network
            writes += network
            check(!failWrite(network)) { "preference commit failed: $network" }
        }
    }

    private class RestartContext(base: Context) : ContextWrapper(base) {
        var launches = 0
        val launchFailure = IllegalStateException("fixture launch failure")
        private val manager = mockk<PackageManager> {
            every { getLaunchIntentForPackage(any()) } returns Intent()
        }
        private val configs = mockk<AssetManager> {
            every { open(any<String>()) } answers {
                "path = \"data/store\"\npath = \"data/network\"".byteInputStream()
            }
        }
        override fun getPackageManager() = manager
        override fun getAssets() = configs
        override fun startActivity(intent: Intent) {
            launches++
            // Never reach killProcess, even under Robolectric.
            throw launchFailure
        }
    }

    @After fun close() = db.close()
    private suspend fun start() {
        lifecycle.initializeNode(NetworkType.MAINNET) {}
        assertTrue(lifecycle.awaitNodeReady())
        assertTrue(gate.isReady("MAINNET"))
    }
    private fun assertOldNetworkRestored() {
        assertEquals(listOf(NetworkType.TESTNET, NetworkType.MAINNET), preferences.writes)
        assertEquals(NetworkType.MAINNET, preferences.selected)
        assertEquals(NetworkType.MAINNET, lifecycle.currentNetwork)
        assertFalse(lifecycle.isSwitchingNetwork.value)
        assertEquals(setOf("MAINNET"), gate.readyNetworks.value)
    }

    @Test fun `same network leaves readiness and revision untouched`() = runTest {
        start()
        val revision = gate.currentRevision()
        assertTrue(lifecycle.switchNetwork(NetworkType.MAINNET).isSuccess)
        assertEquals(revision, gate.currentRevision())
        assertEquals(setOf("MAINNET"), gate.readyNetworks.value)
        assertTrue(preferences.writes.isEmpty())
        assertEquals(0, restart.launches)
        assertFalse(lifecycle.isSwitchingNetwork.value)
    }

    @Test fun `same network does not open an initially closed gate`() = runTest {
        val revision = gate.currentRevision()
        assertTrue(lifecycle.switchNetwork(NetworkType.MAINNET).isSuccess)
        assertEquals(revision, gate.currentRevision())
        assertTrue(gate.readyNetworks.value.isEmpty())
        assertEquals(0, handshakes)
        assertTrue(preferences.writes.isEmpty())
    }

    @Test fun `cancellation after closing still reconciles original readiness and releases ownership`() = runTest {
        start()
        val clearing = CompletableDeferred<Unit>()
        coEvery { cache.clearAll() } coAnswers { clearing.complete(Unit); CompletableDeferred<Unit>().await() }
        val attempt = async(start = CoroutineStart.UNDISPATCHED) { lifecycle.switchNetwork(NetworkType.TESTNET) }
        clearing.await()
        assertTrue(gate.readyNetworks.value.isEmpty())
        attempt.cancelAndJoin()
        assertFalse(lifecycle.isSwitchingNetwork.value)
        assertEquals(setOf("MAINNET"), gate.readyNetworks.value)
        assertEquals(2, handshakes)
        assertTrue(preferences.writes.isEmpty())
        assertEquals(0, restart.launches)
    }

    @Test fun `switch ownership is reserved before waiting for gate close`() = runTest {
        start()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ticket = gate.requireReadyRevision("MAINNET")
        val mutation = async {
            gate.commitIfCurrent("MAINNET", ticket) {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()
        val first = async(start = CoroutineStart.UNDISPATCHED) { lifecycle.switchNetwork(NetworkType.TESTNET) }
        try {
            assertTrue(lifecycle.isSwitchingNetwork.value)
            val second = async(start = CoroutineStart.UNDISPATCHED) { lifecycle.switchNetwork(NetworkType.TESTNET) }
            assertTrue("the second attempt must reject without waiting for the gate", second.isCompleted)
            assertTrue(second.await().isFailure)
            assertEquals(ticket, gate.currentRevision())
        } finally {
            release.complete(Unit)
            mutation.await()
            first.await()
        }
    }

    @Test fun `overlapping switch cannot invalidate the owning attempt`() = runTest {
        start()
        val clearing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { cache.clearAll() } coAnswers { clearing.complete(Unit); release.await() }
        val first = async(start = CoroutineStart.UNDISPATCHED) { lifecycle.switchNetwork(NetworkType.TESTNET) }
        clearing.await()
        val revision = gate.currentRevision()
        try {
            assertTrue(lifecycle.switchNetwork(NetworkType.TESTNET).isFailure)
            assertEquals(revision, gate.currentRevision())
            assertTrue(lifecycle.isSwitchingNetwork.value)
            assertTrue(preferences.writes.isEmpty())
        } finally { release.complete(Unit); first.await() }
    }

    @Test fun `launch failure rolls back selection and reconciles only running network`() = runTest {
        start()
        val result = lifecycle.switchNetwork(NetworkType.TESTNET)
        assertSame(restart.launchFailure, result.exceptionOrNull())
        assertOldNetworkRestored()
        assertEquals(2, handshakes)
        assertEquals(1, restart.launches)
    }

    @Test fun `failed target commit still explicitly rolls back its in-memory publication`() = runTest {
        start()
        preferences.failWrite = { it == NetworkType.TESTNET }
        assertTrue(lifecycle.switchNetwork(NetworkType.TESTNET).isFailure)
        assertOldNetworkRestored()
        assertEquals(0, restart.launches)
        assertEquals(2, handshakes)
    }

    @Test fun `failed rollback cannot authorize from an old value visible only in memory`() = runTest {
        start()
        preferences.failWrite = { it == NetworkType.MAINNET }
        val failure = lifecycle.switchNetwork(NetworkType.TESTNET).exceptionOrNull()!!
        assertSame(restart.launchFailure, failure)
        assertEquals(listOf(NetworkType.TESTNET, NetworkType.MAINNET), preferences.writes)
        assertEquals(NetworkType.MAINNET, preferences.selected)
        assertEquals(1, failure.suppressed.size)
        assertEquals(1, handshakes)
        assertTrue(gate.readyNetworks.value.isEmpty())
        assertFalse(lifecycle.isSwitchingNetwork.value)
    }

    @Test fun `failed reconciliation keeps gate closed and retains original launch error`() = runTest {
        start()
        epoch = "invalid"
        val failure = lifecycle.switchNetwork(NetworkType.TESTNET).exceptionOrNull()!!
        assertSame(restart.launchFailure, failure)
        assertEquals(1, failure.suppressed.size)
        assertEquals(NetworkType.MAINNET, preferences.selected)
        assertTrue(gate.readyNetworks.value.isEmpty())
        assertFalse(lifecycle.isSwitchingNetwork.value)
    }

    @Test fun `unstarted node cannot gain readiness through failed restart recovery`() = runTest {
        assertTrue(lifecycle.switchNetwork(NetworkType.TESTNET).isFailure)
        assertEquals(NetworkType.MAINNET, preferences.selected)
        assertEquals(0, handshakes)
        assertTrue(gate.readyNetworks.value.isEmpty())
        assertFalse(lifecycle.isSwitchingNetwork.value)
    }

    @Test fun `readiness for another network does not authorize recovery of current network`() = runTest {
        start()
        gate.reconcile(client, NetworkType.TESTNET)
        val before = handshakes
        assertTrue(lifecycle.switchNetwork(NetworkType.TESTNET).isFailure)
        assertEquals(before, handshakes)
        assertTrue(gate.readyNetworks.value.isEmpty())
        assertEquals(NetworkType.MAINNET, preferences.selected)
    }

    @Test fun `a close during recovery supersedes the reconciliation ticket`() = runTest {
        start()
        epoch = "b".repeat(32)
        val write = gate.writeGeneration
        gate.writeGeneration = { network, generation ->
            write(network, generation).also { gate.close() }
        }
        val failure = lifecycle.switchNetwork(NetworkType.TESTNET).exceptionOrNull()!!
        assertSame(restart.launchFailure, failure)
        assertEquals(1, failure.suppressed.size)
        assertTrue(gate.readyNetworks.value.isEmpty())
        assertFalse(lifecycle.isSwitchingNetwork.value)
    }
}
