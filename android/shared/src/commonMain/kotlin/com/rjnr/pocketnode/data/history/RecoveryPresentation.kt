package com.rjnr.pocketnode.data.history

import com.rjnr.pocketnode.core.format.formatCkbTrimmed
import com.rjnr.pocketnode.data.storage.DaoHistoryArchiveRow
import com.rjnr.pocketnode.data.storage.HistoryArchiveRow
import com.rjnr.pocketnode.data.storage.HistoryRecoveryJob
import kotlin.time.Instant

/** Display strings only, deliberately incapable of supplying receipts or transaction actions. */
data class RecoveryHint(val identity: String, val details: String)

data class RecoveryPresentation(
    val isRevalidating: Boolean = false,
    val transactions: List<RecoveryHint> = emptyList(),
    val daoHints: List<RecoveryHint> = emptyList(),
) {
    val isVisible: Boolean get() = isRevalidating || transactions.isNotEmpty() || daoHints.isNotEmpty()
    val title: String get() = if (isRevalidating) "One-time security revalidation" else "Historical records awaiting verification"
    val explanation: String get() = if (isRevalidating)
        "A security update requires a one-time scan of this wallet's history. Retained records are awaiting verification. Your balance is available only from current verified data."
    else "These retained records are still awaiting verification. They do not establish a balance or confirmations."
    val transactionHeading: String get() = "Unverified history (up to 100 retained records)"
    val daoHeading: String get() = "Read-only DAO history (up to 20 retained records)"
    val daoExplanation: String get() = "Historical DAO hints are unverified and cannot authorize withdrawals or establish a balance."

    companion object {
        fun from(job: HistoryRecoveryJob?, archive: List<HistoryArchiveRow>, hints: List<DaoHistoryArchiveRow>) = RecoveryPresentation(
            isRevalidating = job != null && job.status != "COMPLETE",
            transactions = archive.map { row -> RecoveryHint(row.txHash, listOf(
                "Historical hints, unverified",
                direction(row.directionHint),
                row.balanceChangeHint?.let(::hexAmount)?.let { "Amount: $it CKB" } ?: "Amount unknown",
                row.timestampHint?.takeIf { it > 0 }?.let { "Date: ${Instant.fromEpochMilliseconds(it)}" } ?: "Date unknown",
                row.blockNumberHint?.removePrefix("0x")?.toLongOrNull(16)?.let { "Block: $it" } ?: "Block unknown",
                row.feeHint?.let(::hexAmount)?.let { "Fee: $it CKB" } ?: "Fee unknown",
            ).joinToString(" · ")) },
            daoHints = hints.map { row -> RecoveryHint("${row.txHash}:${row.outputIndex}", listOf(
                "Historical hints, unverified",
                row.depositBlockHint?.let { "Deposit block: $it" } ?: "Deposit block unknown",
                row.withdrawBlockHint?.let { "Withdrawal block: $it" } ?: "Withdrawal block unknown",
                row.capacityHint?.let { "Capacity: ${formatCkbTrimmed(it)} CKB" } ?: "Capacity unknown",
                row.statusHint?.let { "Old status: $it" } ?: "Old status unknown",
            ).joinToString(" · ")) },
        )

        private fun direction(value: String?): String = when (value) {
            "in" -> "Received"
            "out" -> "Sent"
            "dao_deposit" -> "DAO deposit"
            "dao_withdraw" -> "DAO withdrawal"
            "dao_unlock" -> "DAO unlock"
            else -> "Direction unknown"
        }

        private fun hexAmount(value: String): String? = value.removePrefix("0x").toLongOrNull(16)?.let(::formatCkbTrimmed)
    }
}
