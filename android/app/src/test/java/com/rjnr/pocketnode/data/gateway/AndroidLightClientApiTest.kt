package com.rjnr.pocketnode.data.gateway

import com.nervosnetwork.ckblightclient.LightClientNative
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android binding must be a pure passthrough: every argument reaches the
 * JNI surface unchanged and every return value comes back unchanged, `null`
 * included.
 *
 * That is the whole point of the seam. The app is written against the JNI
 * contract (null on any failure), so if this adapter ever normalised a value,
 * clamped a limit, reordered arguments or swallowed a null, shared code would
 * see a different node from the one Android has always seen.
 *
 * The JNI object itself cannot be mocked - `external` methods have no bytecode
 * for MockK to instrument, and touching them on the JVM throws
 * `UnsatisfiedLinkError` - so the adapter takes [NativeLightClient] and this
 * test fakes that. `JniLightClient` behind it is 24 one-line forwards whose
 * signatures the compiler checks against `LightClientNative`.
 */
class AndroidLightClientApiTest {

    private val native = mockk<NativeLightClient>(relaxed = false)
    private val api = AndroidLightClientApi(native)

    /**
     * One row per String-returning function: how to stub the JNI call, how to
     * invoke it through the interface, and how to verify the passthrough.
     */
    private data class StringCase(
        val name: String,
        val stub: (NativeLightClient, String?) -> Unit,
        val call: (LightClientApi) -> String?,
        val verifyArgs: (NativeLightClient) -> Unit,
    )

    private val stringCases: List<StringCase> = listOf(
        StringCase(
            "getTipHeader",
            { n, v -> every { n.nativeGetTipHeader() } returns v },
            { it.getTipHeader() },
            { verify(exactly = 1) { it.nativeGetTipHeader() } },
        ),
        StringCase(
            "getGenesisBlock",
            { n, v -> every { n.nativeGetGenesisBlock() } returns v },
            { it.getGenesisBlock() },
            { verify(exactly = 1) { it.nativeGetGenesisBlock() } },
        ),
        StringCase(
            "getHeader",
            { n, v -> every { n.nativeGetHeader(any()) } returns v },
            { it.getHeader("0xhash") },
            { verify(exactly = 1) { it.nativeGetHeader("0xhash") } },
        ),
        StringCase(
            "fetchHeader",
            { n, v -> every { n.nativeFetchHeader(any()) } returns v },
            { it.fetchHeader("0xhash") },
            { verify(exactly = 1) { it.nativeFetchHeader("0xhash") } },
        ),
        StringCase(
            "getHeaderByNumber",
            { n, v -> every { n.nativeGetHeaderByNumber(any()) } returns v },
            { it.getHeaderByNumber("0x5fa5") },
            { verify(exactly = 1) { it.nativeGetHeaderByNumber("0x5fa5") } },
        ),
        StringCase(
            "getScripts",
            { n, v -> every { n.nativeGetScripts() } returns v },
            { it.getScripts() },
            { verify(exactly = 1) { it.nativeGetScripts() } },
        ),
        StringCase(
            "getCells",
            { n, v -> every { n.nativeGetCells(any(), any(), any(), any()) } returns v },
            { it.getCells("""{"script_type":"lock"}""", "asc", 150, "0xcursor") },
            {
                verify(exactly = 1) {
                    it.nativeGetCells("""{"script_type":"lock"}""", "asc", 150, "0xcursor")
                }
            },
        ),
        StringCase(
            "getTransactions",
            { n, v -> every { n.nativeGetTransactions(any(), any(), any(), any()) } returns v },
            { it.getTransactions("""{"script_type":"type"}""", "desc", 1000, null) },
            {
                verify(exactly = 1) {
                    it.nativeGetTransactions("""{"script_type":"type"}""", "desc", 1000, null)
                }
            },
        ),
        StringCase(
            "getCellsCapacity",
            { n, v -> every { n.nativeGetCellsCapacity(any()) } returns v },
            { it.getCellsCapacity("""{"k":1}""") },
            { verify(exactly = 1) { it.nativeGetCellsCapacity("""{"k":1}""") } },
        ),
        StringCase(
            "sendTransaction",
            { n, v -> every { n.nativeSendTransaction(any()) } returns v },
            { it.sendTransaction("""{"tx":1}""") },
            { verify(exactly = 1) { it.nativeSendTransaction("""{"tx":1}""") } },
        ),
        StringCase(
            "getTransaction",
            { n, v -> every { n.nativeGetTransaction(any()) } returns v },
            { it.getTransaction("0xtx") },
            { verify(exactly = 1) { it.nativeGetTransaction("0xtx") } },
        ),
        StringCase(
            "fetchTransaction",
            { n, v -> every { n.nativeFetchTransaction(any()) } returns v },
            { it.fetchTransaction("0xtx") },
            { verify(exactly = 1) { it.nativeFetchTransaction("0xtx") } },
        ),
        StringCase(
            "estimateCycles",
            { n, v -> every { n.nativeEstimateCycles(any()) } returns v },
            { it.estimateCycles("""{"tx":1}""") },
            { verify(exactly = 1) { it.nativeEstimateCycles("""{"tx":1}""") } },
        ),
        StringCase(
            "localNodeInfo",
            { n, v -> every { n.nativeLocalNodeInfo() } returns v },
            { it.localNodeInfo() },
            { verify(exactly = 1) { it.nativeLocalNodeInfo() } },
        ),
        StringCase(
            "getPeers",
            { n, v -> every { n.nativeGetPeers() } returns v },
            { it.getPeers() },
            { verify(exactly = 1) { it.nativeGetPeers() } },
        ),
        StringCase(
            "callRpc",
            { n, v -> every { n.callRpc(any()) } returns v },
            { it.callRpc("get_tip_header") },
            { verify(exactly = 1) { it.callRpc("get_tip_header") } },
        ),
        StringCase(
            "extractDaoFields",
            { n, v -> every { n.nativeExtractDaoFields(any()) } returns v },
            { it.extractDaoFields("0xdao") },
            { verify(exactly = 1) { it.nativeExtractDaoFields("0xdao") } },
        ),
        StringCase(
            "calculateUnlockEpoch",
            { n, v -> every { n.nativeCalculateUnlockEpoch(any(), any()) } returns v },
            { it.calculateUnlockEpoch("0xdep", "0xwit") },
            { verify(exactly = 1) { it.nativeCalculateUnlockEpoch("0xdep", "0xwit") } },
        ),
    )

    @Test
    fun `every string query returns the JNI value unchanged and passes its arguments through`() {
        for (case in stringCases) {
            val native = mockk<NativeLightClient>()
            val api = AndroidLightClientApi(native)

            val payload = """{"marker":"${case.name}"}"""
            case.stub(native, payload)

            assertEquals(case.name, payload, case.call(api))
            case.verifyArgs(native)
        }
    }

    @Test
    fun `every string query propagates a JNI null instead of substituting a value`() {
        for (case in stringCases) {
            val native = mockk<NativeLightClient>()
            val api = AndroidLightClientApi(native)

            case.stub(native, null)

            assertNull(case.name, case.call(api))
        }
    }

    @Test
    fun `the adapter covers every function on the shared interface`() {
        // Guards against a function being added to LightClientApi and quietly
        // going untested here. 18 string queries plus init, start, stop,
        // status, isInitialized, setScripts and calculateMaxWithdraw.
        val declared = LightClientApi::class.java.declaredMethods.map { it.name }.toSet()
        val covered = stringCases.map { it.name }.toSet() + setOf(
            "init", "start", "stop", "status", "isInitialized",
            "setScripts", "calculateMaxWithdraw",
        )
        assertEquals(emptySet<String>(), declared - covered)
    }

    @Test
    fun `init forwards the config path and reports the JNI result`() {
        every { native.nativeInit(any(), any()) } returns true

        assertTrue(api.init("/data/testnet.toml", "/data/testnet", null))

        verify(exactly = 1) { native.nativeInit("/data/testnet.toml", any()) }
    }

    @Test
    fun `init failure is reported as false`() {
        every { native.nativeInit(any(), any()) } returns false

        assertFalse(api.init("/data/testnet.toml", "", null))
    }

    @Test
    fun `init wraps the listener so JNI status callbacks reach it verbatim`() {
        val callbackSlot = slot<LightClientNative.StatusCallback>()
        every { native.nativeInit(any(), capture(callbackSlot)) } returns true
        val seen = mutableListOf<Pair<String, String>>()

        api.init("/data/testnet.toml", "/data/testnet", object : LightClientStatusListener {
            override fun onStatusChanged(status: String, data: String) {
                seen += status to data
            }
        })

        callbackSlot.captured.onStatusChange("running", "")
        callbackSlot.captured.onStatusChange("stopped", "bye")

        assertEquals(listOf("running" to "", "stopped" to "bye"), seen)
    }

    @Test
    fun `a null listener does not stop the JNI callback from firing`() {
        val callbackSlot = slot<LightClientNative.StatusCallback>()
        every { native.nativeInit(any(), capture(callbackSlot)) } returns true

        api.init("/data/testnet.toml", "", null)

        // Must not throw: the bridge always gets a callback object.
        callbackSlot.captured.onStatusChange("initialized", "")
    }

    @Test
    fun `start stop and setScripts pass through their arguments and results`() {
        every { native.nativeStart() } returns true
        every { native.nativeStop() } returns false
        every { native.nativeSetScripts(any(), any()) } returns true

        assertTrue(api.start())
        assertFalse(api.stop())
        assertTrue(api.setScripts("[]", 2))

        verify(exactly = 1) { native.nativeStart() }
        verify(exactly = 1) { native.nativeStop() }
        verify(exactly = 1) { native.nativeSetScripts("[]", 2) }
    }

    @Test
    fun `status returns the raw JNI state code`() {
        for (code in listOf(0, 1, 2)) {
            every { native.nativeGetStatus() } returns code
            assertEquals(code, api.status())
        }
    }

    @Test
    fun `isInitialized is false before init and true once init succeeds`() {
        every { native.nativeGetStatus() } returns LightClientNative.STATUS_INIT
        every { native.nativeInit(any(), any()) } returns true

        assertFalse(api.isInitialized())

        api.init("/data/testnet.toml", "", null)

        assertTrue(api.isInitialized())
    }

    @Test
    fun `isInitialized stays false when init fails`() {
        every { native.nativeGetStatus() } returns LightClientNative.STATUS_INIT
        every { native.nativeInit(any(), any()) } returns false

        api.init("/data/testnet.toml", "", null)

        assertFalse(api.isInitialized())
    }

    @Test
    fun `isInitialized reads a non-INIT status as initialized even without an init call`() {
        // A node that is already past INIT: this adapter never ran init, but
        // the state plainly says the client came up.
        every { native.nativeGetStatus() } returns LightClientNative.STATUS_RUNNING

        assertTrue(api.isInitialized())
    }

    @Test
    fun `calculateMaxWithdraw passes its four arguments through and returns the JNI long`() {
        every { native.nativeCalculateMaxWithdraw(any(), any(), any(), any()) } returns 6_100_000_123L

        assertEquals(
            6_100_000_123L,
            api.calculateMaxWithdraw("0xdep", "0xwit", 6_100_000_000L, 6_100_000_000L)
        )

        verify(exactly = 1) {
            native.nativeCalculateMaxWithdraw("0xdep", "0xwit", 6_100_000_000L, 6_100_000_000L)
        }
    }

    @Test
    fun `calculateMaxWithdraw propagates the JNI failure sentinel`() {
        every { native.nativeCalculateMaxWithdraw(any(), any(), any(), any()) } returns -1L

        assertEquals(-1L, api.calculateMaxWithdraw("0x", "0x", 0L, 0L))
    }
}
