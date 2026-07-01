package com.rjnr.pocketnode.ui.screens.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The send-error mapper must surface the REAL cause. The Alex report was a
 * local verification failure (an unresolvable input from a stale pending tx)
 * shown as "check your network" — misleading, since network state was
 * irrelevant. These assert the actionable, non-network-blaming copy.
 */
class SendErrorMessageTest {

    @Test
    fun `unknown input points at the blocking pending tx, not the network`() {
        val msg = mapSendErrorMessage(
            "Broadcast rejected: verification failed: Transaction(Resolve(Unknown([OutPoint(0xabc..0)])))"
        )
        assertTrue(msg.contains("previous transaction", ignoreCase = true))
        assertFalse(msg.contains("network connection", ignoreCase = true))
    }

    @Test
    fun `dead input tells the user coins were already spent`() {
        val msg = mapSendErrorMessage("Broadcast rejected: verification failed: Dead(OutPoint(0xabc..0))")
        assertTrue(msg.contains("already spent", ignoreCase = true))
        assertFalse(msg.contains("network connection", ignoreCase = true))
    }

    @Test
    fun `light client not ready asks to wait, not to check network`() {
        val msg = mapSendErrorMessage("Broadcast rejected: light client not ready (storage not initialized)")
        assertTrue(msg.contains("starting up", ignoreCase = true))
        assertFalse(msg.contains("network connection", ignoreCase = true))
    }

    @Test
    fun `null-returned send no longer blames the network`() {
        val msg = mapSendErrorMessage("Send failed - native returned null")
        assertFalse(
            "must not tell the user to check their network for a local failure",
            msg.contains("network connection", ignoreCase = true),
        )
        assertTrue(msg.contains("reopen", ignoreCase = true) || msg.contains("try again", ignoreCase = true))
    }

    @Test
    fun `existing build errors still map correctly`() {
        assertEquals(
            "Insufficient balance for this transaction.",
            mapSendErrorMessage("Insufficient balance: have X, need Y"),
        )
        assertTrue(mapSendErrorMessage("Dust change refused: ...").contains("61 CKB"))
        assertTrue(mapSendErrorMessage("Transfer amount must be at least 61 CKB (minimum cell capacity)").contains("Minimum"))
    }

    @Test
    fun `null message falls back without crashing`() {
        assertTrue(mapSendErrorMessage(null).isNotBlank())
    }
}
