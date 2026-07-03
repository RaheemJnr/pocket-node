package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity

/**
 * A derivable-but-not-yet-restored sub-account slot recorded at parent
 * mnemonic import (#82 / #371 phase 1). Holds ONLY the public lock-script
 * args for m/44'/309'/{accountIndex}'/0/0 — never key material; keys are
 * re-derived from the parent mnemonic if the user restores the slot.
 *
 * Lifecycle (phase 2 consumes these):
 *  PENDING  -> registered for sync, activity unknown
 *  FOUND    -> on-chain activity seen; offer one-tap restore
 *  RESTORED -> user restored it (wallet row exists now)
 *  EMPTY    -> sync completed with no activity; candidate retired
 */
@Entity(
    tableName = "sub_account_candidates",
    primaryKeys = ["parentWalletId", "accountIndex"],
)
data class SubAccountCandidateEntity(
    val parentWalletId: String,
    val accountIndex: Int,
    val scriptArgs: String,
    val state: String = STATE_PENDING,
    val createdAt: Long,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_FOUND = "FOUND"
        const val STATE_RESTORED = "RESTORED"
        const val STATE_EMPTY = "EMPTY"
    }
}
