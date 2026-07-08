package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #382 Tier 2 registration policy. Account-axis candidates trickle at most
 * [SyncCoordinator.MAX_CANDIDATE_SCRIPTS_PER_PARENT] per cycle (each batch
 * is a fresh filter rewind, and account slots resolve one wallet at a time).
 * Chain-axis candidates must register as ONE batch: 41 scripts through a
 * 5-slot pipe means ~9 rewinds, each re-scanning the whole window — one
 * batch costs a single scan pass.
 */
class CandidateSelectionTest {

    private fun account(index: Int, state: String = SubAccountCandidateEntity.STATE_PENDING) =
        SubAccountCandidateEntity(
            parentWalletId = "p",
            derivationPath = SubAccountDiscovery.accountPath(index),
            accountIndex = index,
            scriptArgs = "0xa$index",
            state = state,
            createdAt = 1L,
        )

    private fun chain(chainIdx: Int, addrIdx: Int, state: String = SubAccountCandidateEntity.STATE_PENDING) =
        SubAccountCandidateEntity(
            parentWalletId = "p",
            derivationPath = SubAccountDiscovery.chainPath(chainIdx, addrIdx),
            accountIndex = 0,
            scriptArgs = "0xc$chainIdx$addrIdx",
            state = state,
            createdAt = 1L,
        )

    @Test
    fun `account axis is capped, chain axis registers whole`() {
        val pending = (1..10).map { account(it) } +
            (0..20).flatMap { i -> listOf(0, 1).map { c -> chain(c, i) } }
                .filter { it.derivationPath != SubAccountDiscovery.chainPath(0, 0) }
        val selected = selectCandidatesForRegistration(pending, accountAxisCap = 5)
        assertEquals(5, selected.count { it.accountIndex >= 1 })
        assertEquals(41, selected.count { it.accountIndex == 0 })
    }

    @Test
    fun `account axis selects lowest indices first`() {
        val selected = selectCandidatesForRegistration(
            listOf(account(9), account(2), account(7), account(1), account(5), account(3)),
            accountAxisCap = 3,
        )
        assertEquals(listOf(1, 2, 3), selected.map { it.accountIndex })
    }

    @Test
    fun `chain-axis FOUND slots stay registered so their spends keep indexing`() {
        // Dropping a FOUND side script from the filter freezes its view:
        // the sweep that spends its cells is never indexed against it, the
        // spent-set never grows, and the found-funds card shows the swept
        // amount forever (emulator, 2026-07-08). Account-axis FOUND slots
        // are still excluded — the restore flow turns them into wallets
        // that register properly on their own.
        val selected = selectCandidatesForRegistration(
            listOf(
                account(1, SubAccountCandidateEntity.STATE_FOUND),
                account(2, SubAccountCandidateEntity.STATE_EMPTY),
                account(3, SubAccountCandidateEntity.STATE_RESTORED),
                chain(1, 0, SubAccountCandidateEntity.STATE_FOUND),
                chain(1, 2, SubAccountCandidateEntity.STATE_EMPTY),
                chain(1, 1),
            ),
            accountAxisCap = 5,
        )
        assertEquals(2, selected.size)
        assertTrue(selected.any { it.derivationPath == SubAccountDiscovery.chainPath(1, 1) })
        assertTrue(selected.any { it.derivationPath == SubAccountDiscovery.chainPath(1, 0) })
    }

    @Test
    fun `empty input selects nothing`() {
        assertTrue(selectCandidatesForRegistration(emptyList(), accountAxisCap = 5).isEmpty())
    }
}
