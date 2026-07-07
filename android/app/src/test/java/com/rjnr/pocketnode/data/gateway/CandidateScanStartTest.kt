package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #382 (Codex P1 on the sub-PR 3 review): candidate scripts must scan
 * HISTORY. Registration inherited the parent's RESUME height, so a wallet
 * already synced to tip registered its gap-limit candidates at ~tip and the
 * scan could never see the blocks where the Neuron change actually lives.
 *
 * candidateScanStart picks the deepest reasonable start:
 *  - the wallet's mode-derived historical start (what a fresh sync would use)
 *  - the earliest cached transaction's block minus a margin (the sibling
 *    change left DURING those transactions, so history starts there)
 *  - never shallower than the RECENT window (tip - 200k) when tip is known
 */
class CandidateScanStartTest {

    private val tip = 1_000_000L

    @Test
    fun `synced new-wallet mode still scans at least the recent window`() {
        // mode-derived start for NEW_WALLET is the tip — useless for a scan.
        assertEquals(800_000L, candidateScanStart(tip, null, tip))
    }

    @Test
    fun `full history scans from genesis`() {
        assertEquals(0L, candidateScanStart(0L, null, tip))
    }

    @Test
    fun `earliest cached transaction anchors deeper than the floor`() {
        assertEquals(499_000L, candidateScanStart(tip, 500_000L, tip))
    }

    @Test
    fun `anchor near genesis clamps to zero`() {
        assertEquals(0L, candidateScanStart(tip, 500L, tip))
    }

    @Test
    fun `unknown tip falls back to the mode-derived start`() {
        assertEquals(18_300_000L, candidateScanStart(18_300_000L, null, 0L))
    }

    @Test
    fun `unknown tip still honors the transaction anchor`() {
        assertEquals(299_000L, candidateScanStart(18_300_000L, 300_000L, 0L))
    }

    // --- historicalStartBlock: the mode-derived start, resume ignored ---

    @Test
    fun `recent mode derives tip minus window`() {
        assertEquals(
            800_000L,
            historicalStartBlock(SyncMode.RECENT, null, tip, NetworkType.MAINNET),
        )
    }

    @Test
    fun `full history derives zero`() {
        assertEquals(
            0L,
            historicalStartBlock(SyncMode.FULL_HISTORY, null, tip, NetworkType.MAINNET),
        )
    }

    @Test
    fun `future custom height resets to the recent window`() {
        assertEquals(
            800_000L,
            historicalStartBlock(SyncMode.CUSTOM, 2_000_000L, tip, NetworkType.MAINNET),
        )
    }

    @Test
    fun `zero resolution outside full history falls back to checkpoint`() {
        assertEquals(
            18_300_000L,
            historicalStartBlock(SyncMode.CUSTOM, null, 20_000_000L, NetworkType.MAINNET),
        )
    }
}
