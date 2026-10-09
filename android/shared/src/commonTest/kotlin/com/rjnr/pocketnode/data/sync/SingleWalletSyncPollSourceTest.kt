package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.LightClientFixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The iOS poll driver: read the chain state, derive one wallet's numbers, answer. */
class SingleWalletSyncPollSourceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The lock registered in [LightClientFixtures.GET_SCRIPTS]. */
    private val registeredArgs = "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1"
    private val address = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsq"

    private val fake = FakeLightClientApi()

    private fun engine() = SyncEngine(fake, FakeSyncPreferences(), json, NoopLogger)

    private fun source(wallet: ActiveWallet?) =
        SingleWalletSyncPollSource(engine()) { wallet }

    @Test
    fun `no wallet means nothing to poll`() = runTest {
        val source = source(null)

        assertFalse(source.hasWalletInfo())

        val result = source.getAccountStatus()
        assertTrue(result.isFailure)
        assertEquals("No wallet", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a wallet means there is something to poll`() {
        assertTrue(source(ActiveWallet(address, registeredArgs)).hasWalletInfo())
    }

    @Test
    fun `isRegistered is true when the wallets script is in the list`() = runTest {
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueue("getScripts", LightClientFixtures.GET_SCRIPTS)

        val status = source(ActiveWallet(address, registeredArgs)).getAccountStatus().getOrThrow()

        assertTrue(status.isRegistered)
        assertEquals(address, status.address)
        assertEquals(LightClientFixtures.TIP_HEADER_NUMBER.toString(), status.tipNumber)
        // 0x5fa5, the block that script has scanned to; nowhere near the tip.
        assertEquals("24485", status.syncedToBlock)
        assertFalse(status.isSynced)
    }

    @Test
    fun `isRegistered is false when the wallets script is not in the list`() = runTest {
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueue("getScripts", LightClientFixtures.GET_SCRIPTS)

        val status = source(ActiveWallet(address, "0xdeadbeef")).getAccountStatus().getOrThrow()

        assertFalse(status.isRegistered)
        // Not registered, so this wallet has scanned nothing, even though
        // another script in the list has.
        assertEquals("0", status.syncedToBlock)
        assertFalse(status.isSynced)
    }

    @Test
    fun `a script at the tip reports synced`() = runTest {
        val tip = LightClientFixtures.TIP_HEADER_NUMBER
        fake.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        fake.enqueue(
            "getScripts",
            """[{"script":{"args":"$registeredArgs",""" +
                """"code_hash":"0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",""" +
                """"hash_type":"type"},"script_type":"lock","block_number":"0x${tip.toString(16)}"}]""",
        )

        val status = source(ActiveWallet(address, registeredArgs)).getAccountStatus().getOrThrow()

        assertTrue(status.isRegistered)
        assertTrue(status.isSynced)
        assertEquals(tip.toString(), status.syncedToBlock)
    }
}
