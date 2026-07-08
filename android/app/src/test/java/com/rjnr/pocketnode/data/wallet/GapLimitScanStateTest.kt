package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #382 Tier 2 sub-PR 3: pure decisions driving the Home surface and the
 * explicit scan action.
 *
 * Resolution: what the chain-axis candidate set means for the active wallet.
 *  - no candidates  -> NOT_SCANNED (imported before Tier 2; offer Scan now)
 *  - any PENDING    -> SCANNING (registration/reconcile still working)
 *  - any FOUND      -> FOUND (show found-funds surface)
 *  - all EMPTY      -> CLEAR (scan finished clean; Tier 1 signal can clear)
 *
 * Window: the gap-limit window a new scan should derive. Extends by 20 when
 * the current boundary index shows activity (a used slot at the edge means
 * the wallet may have rotated past it), capped at 60.
 */
class GapLimitScanStateTest {

    private fun chain(chainIdx: Int, addrIdx: Int, state: String) =
        SubAccountCandidateEntity(
            parentWalletId = "p",
            derivationPath = SubAccountDiscovery.chainPath(chainIdx, addrIdx),
            accountIndex = 0,
            scriptArgs = "0x$chainIdx$addrIdx",
            state = state,
            createdAt = 1L,
        )

    private val pending = SubAccountCandidateEntity.STATE_PENDING
    private val found = SubAccountCandidateEntity.STATE_FOUND
    private val empty = SubAccountCandidateEntity.STATE_EMPTY

    // --- resolution ---

    @Test
    fun `no chain candidates means not scanned`() {
        assertEquals(GapLimitResolution.NOT_SCANNED, gapLimitResolution(emptyList()))
    }

    @Test
    fun `any pending means still scanning`() {
        assertEquals(
            GapLimitResolution.SCANNING,
            gapLimitResolution(listOf(chain(0, 1, empty), chain(1, 0, pending), chain(1, 1, found))),
        )
    }

    @Test
    fun `found wins once nothing is pending`() {
        assertEquals(
            GapLimitResolution.FOUND,
            gapLimitResolution(listOf(chain(0, 1, empty), chain(1, 3, found))),
        )
    }

    @Test
    fun `all empty means clear`() {
        assertEquals(
            GapLimitResolution.CLEAR,
            gapLimitResolution(listOf(chain(0, 1, empty), chain(1, 0, empty))),
        )
    }

    // --- window ---

    @Test
    fun `no candidates scans the default window`() {
        assertEquals(SubAccountDiscovery.CHAIN_GAP_WINDOW, nextScanWindow(emptyList()))
    }

    @Test
    fun `quiet boundary keeps the current window`() {
        val candidates = listOf(chain(0, 20, empty), chain(1, 20, empty), chain(1, 5, found))
        assertEquals(20, nextScanWindow(candidates))
    }

    @Test
    fun `active boundary extends by twenty`() {
        val candidates = listOf(chain(0, 20, found), chain(1, 20, empty))
        assertEquals(40, nextScanWindow(candidates))
    }

    @Test
    fun `extension caps at sixty`() {
        val candidates = listOf(chain(0, 60, found), chain(1, 60, found))
        assertEquals(60, nextScanWindow(candidates))
    }

    // --- path parsing (Tier 3 sweep re-derives keys from candidate paths) ---

    @Test
    fun `chain and index parse from a chain-axis path`() {
        assertEquals(1 to 7, chainAndIndexFromPath("m/44'/309'/0'/1/7"))
        assertEquals(0 to 20, chainAndIndexFromPath("m/44'/309'/0'/0/20"))
    }

    @Test
    fun `account-axis and malformed paths refuse to parse`() {
        // account-axis slots are never sweep inputs; a silent wrong parse
        // would derive the wrong key and produce an unverifiable signature.
        org.junit.Assert.assertNull(chainAndIndexFromPath("m/44'/309'/3'/0/0"))
        org.junit.Assert.assertNull(chainAndIndexFromPath("garbage"))
        org.junit.Assert.assertNull(chainAndIndexFromPath("m/44'/309'/0'/x/2"))
    }
}
