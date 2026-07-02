package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.ui.screens.send.mapSendErrorMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A null read must say WHY: still syncing vs a transient hiccup. Neither may
 * blame the network (the Alex-report mistake). The not-ready branch must also
 * carry the token `mapSendErrorMessage` routes to the "still starting up" copy.
 */
class ReadPathErrorTest {

    @Test
    fun `not ready names the wait, never the network`() {
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

    @Test
    fun `not ready message routes to the starting-up user copy`() {
        val raw = readPathNullMessage("read cells", lightClientReady = false)
        assertTrue(mapSendErrorMessage(raw).contains("starting up", ignoreCase = true))
    }
}
