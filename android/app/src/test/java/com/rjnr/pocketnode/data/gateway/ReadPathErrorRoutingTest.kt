package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.ui.screens.send.mapSendErrorMessage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the read-path null contract that spans both modules:
 * `readPathNullMessage` now lives in the shared core, `mapSendErrorMessage` is
 * Compose-side app code, and the not-ready branch has to carry the exact token
 * that routes to the "still starting up" user copy. The wording assertions on
 * the message itself live in the shared `ReadPathErrorTest`.
 */
class ReadPathErrorRoutingTest {

    @Test
    fun `not ready message routes to the starting-up user copy`() {
        val raw = readPathNullMessage("read cells", lightClientReady = false)
        assertTrue(mapSendErrorMessage(raw).contains("starting up", ignoreCase = true))
    }
}
