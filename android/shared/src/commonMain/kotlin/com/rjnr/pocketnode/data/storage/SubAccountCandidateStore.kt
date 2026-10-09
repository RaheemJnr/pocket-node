package com.rjnr.pocketnode.data.storage

/**
 * A derivable-but-not-yet-restored slot, as the sync code reads it.
 *
 * Mirrors the Android app's `SubAccountCandidateEntity` minus `createdAt`,
 * which no sync path reads. The lifecycle states are the same four strings the
 * entity declares, repeated here so shared code can compare against them
 * without reaching into the app module.
 */
data class SubAccountCandidateRecord(
    val parentWalletId: String,
    /** Full BIP44 path this slot derives at — the candidate's identity. */
    val derivationPath: String,
    val accountIndex: Int,
    val scriptArgs: String,
    val state: String = STATE_PENDING,
    /**
     * Lowest block this candidate's script was ever registered to scan from
     * (0 = never registered).
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

/**
 * The `sub_account_candidates` reads and writes the registration path performs,
 * narrowed to the two it needs.
 *
 * Android binds it to `SubAccountCandidateDao` through
 * `RoomSubAccountCandidateStore`.
 */
interface SubAccountCandidateStore {

    /** Every candidate recorded for [parentId], lowest account index first. */
    suspend fun getForParent(parentId: String): List<SubAccountCandidateRecord>

    /**
     * Record the scan start for a registration, keeping the LOWEST block ever
     * used (deepest coverage wins; 0 means never registered). Implementations
     * apply the keep-min themselves.
     */
    suspend fun updateRegisteredFrom(
        parentId: String,
        derivationPath: String,
        fromBlock: Long,
    )

    /**
     * Lock-script args of every candidate on record, across all parents and
     * all lifecycle states. The gap-limit signature check treats them
     * as scripts we know, so a change output landing on one is not a missing
     * change leg.
     */
    suspend fun allScriptArgs(): List<String>
}
