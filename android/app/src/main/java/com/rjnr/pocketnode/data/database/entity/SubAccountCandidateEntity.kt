package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity

/**
 * A derivable-but-not-yet-restored slot recorded at parent mnemonic import
 * (#82 / #371 phase 1; generalized for #382 Tier 2). Holds ONLY the public
 * lock-script args for the path — never key material; keys are re-derived
 * from the parent mnemonic when needed.
 *
 * Two candidate axes share this table, distinguished by [derivationPath]:
 *  - account axis (#82):  m/44'/309'/{accountIndex}'/0/0 — restorable as a
 *    sub-account wallet; [accountIndex] carries the restore index.
 *  - chain axis (#382):   m/44'/309'/0'/{0|1}/{addressIndex} — gap-limit
 *    slots other BIP44 wallets (Neuron) spread funds across; accountIndex
 *    is 0 and these are NEVER restored as wallets (Tier 3 sweeps instead).
 *
 * Lifecycle (phase 2 consumes these):
 *  PENDING  -> registered for sync, activity unknown
 *  FOUND    -> on-chain activity seen; offer one-tap restore (account axis)
 *              or count toward the gap-limit found-funds surface (chain axis)
 *  RESTORED -> user restored it (wallet row exists now; account axis only)
 *  EMPTY    -> sync completed with no activity; candidate retired
 */
@Entity(
    tableName = "sub_account_candidates",
    primaryKeys = ["parentWalletId", "derivationPath"],
)
data class SubAccountCandidateEntity(
    val parentWalletId: String,
    /** Full BIP44 path this slot derives at — the candidate's identity. */
    val derivationPath: String,
    val accountIndex: Int,
    val scriptArgs: String,
    val state: String = STATE_PENDING,
    val createdAt: Long,
    /**
     * Lowest block this candidate's script was ever registered to scan from
     * (0 = never registered). The reconciler may only declare EMPTY when
     * scanned-to-tip AND the scan actually covered chain from here — a
     * candidate registered at tip has covered nothing, and judging it
     * "no history" retired every candidate seconds after import
     * (device-test 2026-07).
     */
    val registeredFromBlock: Long = 0,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_FOUND = "FOUND"
        const val STATE_RESTORED = "RESTORED"
        const val STATE_EMPTY = "EMPTY"
    }
}
