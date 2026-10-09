package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.LightClientFixtures
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.toFromBlock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The derivation that used to sit inline in `GatewayRepository.getAccountStatus()`.
 *
 * [SyncEngine.readChainSyncState] is exercised against the recorded bridge
 * payloads in [LightClientFixtures]; [SyncEngine.computeStatus] is exercised
 * against hand-built states, because what it does with the tip and the script
 * list is the whole point and hex fixtures would only obscure it.
 */
class SyncEngineTest {

    /** Same configuration `AppModule.provideJson` hands the Android app. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fake = FakeLightClientApi()

    private fun engine(api: FakeLightClientApi = fake) =
        SyncEngine(api, FakeSyncPreferences(), json, NoopLogger)

    private fun script(args: String, blockNumberHex: String) = JniScriptStatus(
        script = Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = args,
        ),
        blockNumber = blockNumberHex,
    )

    // --- readChainSyncState ---

    @Test
    fun `reads the tip and the registered scripts off the bridge`() = runTest {
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueue("getScripts", LightClientFixtures.GET_SCRIPTS)

        val state = engine().readChainSyncState()

        assertEquals(LightClientFixtures.TIP_HEADER_NUMBER, state.tipNumber)
        assertEquals(1, state.scripts.size)
        assertEquals(
            "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1",
            state.scripts.single().script.args,
        )
        assertEquals("0x5fa5", state.scripts.single().blockNumber)
    }

    @Test
    fun `a null tip reads as block zero`() = runTest {
        // Nothing enqueued: the fake answers null, which is what the bridge
        // returns from every query when the node is not running.
        val state = engine().readChainSyncState()

        assertEquals(0L, state.tipNumber)
        assertTrue(state.scripts.isEmpty())
    }

    @Test
    fun `a null script list reads as nothing registered`() = runTest {
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)

        val state = engine().readChainSyncState()

        assertEquals(LightClientFixtures.TIP_HEADER_NUMBER, state.tipNumber)
        assertTrue(state.scripts.isEmpty())
    }

    // --- computeStatus ---

    @Test
    fun `the active script wins over the first registered one`() {
        val state = ChainSyncState(
            tipNumber = 1_000L,
            scripts = listOf(
                script("0xaaaa", "0x64"),   // 100
                script("0xbbbb", "0x1f4"),  // 500
            ),
        )

        assertEquals(500L, engine().computeStatus(state, "0xbbbb").scriptBlockNumber)
        assertEquals(100L, engine().computeStatus(state, "0xaaaa").scriptBlockNumber)
    }

    @Test
    fun `no active wallet falls back to the first registered script`() {
        val state = ChainSyncState(
            tipNumber = 1_000L,
            scripts = listOf(script("0xaaaa", "0x64"), script("0xbbbb", "0x1f4")),
        )

        assertEquals(100L, engine().computeStatus(state, null).scriptBlockNumber)
    }

    @Test
    fun `an unregistered active script reads as block zero`() {
        val state = ChainSyncState(tipNumber = 1_000L, scripts = listOf(script("0xaaaa", "0x64")))

        val snapshot = engine().computeStatus(state, "0xcccc")

        assertEquals(0L, snapshot.scriptBlockNumber)
        assertFalse(snapshot.isSynced)
    }

    @Test
    fun `isSynced holds across the whole ten block window and fails outside it`() {
        fun syncedAt(scriptBlock: Long): Boolean {
            val hex = "0x${scriptBlock.toString(16)}"
            val state = ChainSyncState(tipNumber = 1_000L, scripts = listOf(script("0xaaaa", hex)))
            return engine().computeStatus(state, "0xaaaa").isSynced
        }

        assertFalse(syncedAt(989), "eleven behind is still syncing")
        assertTrue(syncedAt(990), "ten behind is the lower edge")
        assertTrue(syncedAt(1_000))
        assertTrue(syncedAt(1_010), "ten ahead is the upper edge")
        assertFalse(syncedAt(1_011), "eleven ahead is out of the window")
    }

    @Test
    fun `nothing is synced against a zero tip`() {
        val state = ChainSyncState(tipNumber = 0L, scripts = listOf(script("0xaaaa", "0x0")))

        val snapshot = engine().computeStatus(state, "0xaaaa")

        assertFalse(snapshot.isSynced, "a tip of 0 means the node has not caught up at all")
        assertEquals(0.0, snapshot.progress)
    }

    @Test
    fun `progress is zero before the poll loop has recorded a sample`() {
        val state = ChainSyncState(tipNumber = 1_000L, scripts = listOf(script("0xaaaa", "0x1f4")))

        // The percentage comes off the poller's sample window, not off the
        // script block: with no samples yet it is 0 even though the script has
        // reached block 500.
        assertEquals(0.0, engine().computeStatus(state, "0xaaaa").progress)
    }

    // --- startBlockFor ---

    @Test
    fun `startBlockFor matches toFromBlock for every mode on both networks`() {
        val engine = engine()
        val tips = listOf(0L, 18_500_000L)
        val custom = 12_345L

        for (network in NetworkType.entries) {
            for (mode in SyncMode.entries) {
                for (tip in tips) {
                    assertEquals(
                        mode.toFromBlock(custom, tip, network),
                        engine.startBlockFor(mode, network, tip, custom),
                        "$mode on $network at tip $tip",
                    )
                }
            }
        }
    }

    @Test
    fun `startBlockFor pins the well known values`() {
        val engine = engine()

        // NEW_WALLET starts at the live tip when there is one.
        assertEquals(
            "18500000",
            engine.startBlockFor(SyncMode.NEW_WALLET, NetworkType.MAINNET, 18_500_000L, null),
        )
        // RECENT is 200k blocks back from it.
        assertEquals(
            "18300000",
            engine.startBlockFor(SyncMode.RECENT, NetworkType.MAINNET, 18_500_000L, null),
        )
        // On testnet with no tip yet the checkpoint is 0, so RECENT clamps.
        assertEquals(
            "0",
            engine.startBlockFor(SyncMode.RECENT, NetworkType.TESTNET, 0L, null),
        )
        assertEquals(
            "0",
            engine.startBlockFor(SyncMode.FULL_HISTORY, NetworkType.MAINNET, 18_500_000L, null),
        )
        assertEquals(
            "12345",
            engine.startBlockFor(SyncMode.CUSTOM, NetworkType.MAINNET, 18_500_000L, 12_345L),
        )
        // A CUSTOM mode with no height recorded degrades to genesis.
        assertEquals(
            "0",
            engine.startBlockFor(SyncMode.CUSTOM, NetworkType.MAINNET, 18_500_000L, null),
        )
    }
}
