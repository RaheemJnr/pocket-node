package com.rjnr.pocketnode.data.history

import com.rjnr.pocketnode.data.storage.*
import kotlin.test.*

class RecoveryPresentationTest {
    private fun job(status: String = "PENDING") = HistoryRecoveryJob("w", "MAINNET", "internal", 0, 120, 500, 600, null, status)
    private fun archive() = HistoryArchiveRow("w", "MAINNET", "hash", null, null, null, "out", null)

    @Test fun `pending recovery explains one time revalidation without claiming completion from progress`() {
        val model = RecoveryPresentation.from(job(), listOf(archive()), emptyList())
        assertTrue(model.isRevalidating)
        assertEquals("One-time security revalidation", model.title)
        assertTrue(model.explanation.contains("awaiting verification"))
        assertFalse(model.explanation.contains("internal"))
        assertFalse(model.explanation.contains("500"))
    }
    @Test fun `erased historical values remain unknown and never become zero or pending`() {
        val row = RecoveryPresentation.from(job(), listOf(archive()), emptyList()).transactions.single()
        assertEquals("hash", row.identity)
        assertTrue(row.details.contains("Amount unknown"))
        assertTrue(row.details.contains("Date unknown"))
        assertTrue(row.details.contains("Block unknown"))
        assertTrue(row.details.contains("Fee unknown"))
        assertTrue(row.details.contains("unverified"))
        assertFalse(row.details.contains("Pending"))
    }
    @Test fun `completed recovery resumes normally but unreconfirmed archive stays historical`() {
        val model = RecoveryPresentation.from(job("COMPLETE"), listOf(archive()), emptyList())
        assertFalse(model.isRevalidating)
        assertEquals(1, model.transactions.size)
        assertTrue(model.explanation.contains("awaiting verification"))
        assertFalse(RecoveryPresentation.from(job("COMPLETE"), emptyList(), emptyList()).isVisible)
    }
    @Test fun `DAO hints stay separately identified readonly records`() {
        val hint = DaoHistoryArchiveRow("w", "MAINNET", "dao", "0x2", 12, null, null, "DEPOSITED")
        val model = RecoveryPresentation.from(job(), emptyList(), listOf(hint))
        assertTrue(model.transactions.isEmpty())
        assertEquals("dao:0x2", model.daoHints.single().identity)
        assertTrue(model.daoHints.single().details.contains("Capacity unknown"))
        assertTrue(model.daoExplanation.contains("cannot authorize withdrawals"))
    }
    @Test fun `known historical hints are labeled unverified and formatted without zero fallbacks`() {
        val row = archive().copy(blockNumberHint = "0x10", timestampHint = 1700000000000,
            balanceChangeHint = "0x5f5e100", feeHint = "0x186a0")
        val details = RecoveryPresentation.from(job(), listOf(row), emptyList()).transactions.single().details
        assertTrue(details.contains("unverified"))
        assertTrue(details.contains("Amount: 1 CKB"))
        assertTrue(details.contains("Block: 16"))
        assertTrue(details.contains("Date: 2023-11-14"))
        assertTrue(details.contains("Fee: 0.001 CKB"))
        assertTrue(details.contains("Sent"))
    }

}
