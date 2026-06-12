package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.Script
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #332 follow-up: PARTIAL setScripts with a block number BEHIND the script's
 * current position rewinds the light client's filter scan (Rust
 * `update_filter_scripts` has no monotonic guard and clears the matched-block
 * queue) — a multi-hour re-scan on long-history wallets. The seam now clamps
 * rewinds unless the caller explicitly opts in (rescue rescan,
 * find-older-deposits).
 */
class PartialClampTest {

    private fun script(args: String) = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = args,
    )

    private fun status(args: String, block: Long) = JniScriptStatus(
        script = script(args),
        scriptType = "lock",
        blockNumber = "0x${block.toString(16)}",
    )

    @Test
    fun `rewind below current position is clamped to current`() {
        val (clamped, count) = clampPartialRewinds(
            requested = listOf(status("0xaa", 5_000_000L)),
            currentBlockByArgs = mapOf("0xaa" to 18_000_000L),
        )
        assertEquals("0x${18_000_000L.toString(16)}", clamped.single().blockNumber)
        assertEquals(1, count)
    }

    @Test
    fun `forward or equal block passes through untouched`() {
        val (clamped, count) = clampPartialRewinds(
            requested = listOf(status("0xaa", 18_000_000L), status("0xbb", 19_000_000L)),
            currentBlockByArgs = mapOf("0xaa" to 18_000_000L, "0xbb" to 18_000_000L),
        )
        assertEquals("0x${18_000_000L.toString(16)}", clamped[0].blockNumber)
        assertEquals("0x${19_000_000L.toString(16)}", clamped[1].blockNumber)
        assertEquals(0, count)
    }

    @Test
    fun `unknown script args pass through - nothing to clamp against`() {
        val (clamped, count) = clampPartialRewinds(
            requested = listOf(status("0xnew", 1_000L)),
            currentBlockByArgs = mapOf("0xaa" to 18_000_000L),
        )
        assertEquals("0x${1_000L.toString(16)}", clamped.single().blockNumber)
        assertEquals(0, count)
    }

    @Test
    fun `malformed requested block passes through unchanged`() {
        val bad = JniScriptStatus(script = script("0xaa"), scriptType = "lock", blockNumber = "0xZZ")
        val (clamped, count) = clampPartialRewinds(
            requested = listOf(bad),
            currentBlockByArgs = mapOf("0xaa" to 18_000_000L),
        )
        assertEquals("0xZZ", clamped.single().blockNumber)
        assertEquals(0, count)
    }
}
