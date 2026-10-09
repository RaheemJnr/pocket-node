package com.rjnr.pocketnode.data.gateway

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A null read must say WHY: still syncing vs a transient hiccup. Neither may
 * blame the network (the Alex-report mistake).
 *
 * The third assertion this class used to carry — that the not-ready wording
 * routes to the "still starting up" user copy — needs `mapSendErrorMessage`,
 * which is Compose-side app code, so it stays behind in the app module as
 * `ReadPathErrorRoutingTest`.
 */
class ReadPathErrorTest {

    @Test
    fun `not ready names the wait and never the network`() {
        val msg = readPathNullMessage("read cells", lightClientReady = false)
        assertTrue(msg.contains("light client not ready", ignoreCase = true))
        assertFalse(msg.contains("network connection", ignoreCase = true))
    }

    @Test
    fun `ready but null names the operation and calls it transient`() {
        val msg = readPathNullMessage("read balance", lightClientReady = true)
        assertTrue(msg.contains("read balance", ignoreCase = true))
        assertTrue(msg.contains("transient", ignoreCase = true))
        assertFalse(msg.contains("network connection", ignoreCase = true))
    }
}
