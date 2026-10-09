package com.rjnr.pocketnode.data.restorehint

import com.rjnr.pocketnode.data.gateway.models.SyncMode

/** The start chosen for one account the restored secret can derive. */
data class RestoreHintAccountPlan(
    val index: Int,
    val startBlock: Long,
)

/**
 * What a verified hint asks the restore to do (#559).
 *
 * @property startBlock the restored wallet's start: the lowest per-account
 *   start, because sub-account candidates register from the parent's window.
 * @property sourceCoverageStart the lowest block the old phone itself covered
 *   for those accounts, for the "the old phone synced from block M" copy.
 * @property seedIndices sub-account indices to seed discovery with. Seeding
 *   only registers them as ordinary PENDING candidates; the chain still
 *   decides whether they have activity.
 */
data class RestoreHintPlan(
    val accounts: List<RestoreHintAccountPlan>,
    val startBlock: Long,
    val sourceCoverageStart: Long,
    val seedIndices: List<Int>,
) {
    /**
     * Block 0 is FULL_HISTORY, not CUSTOM 0: outside FULL_HISTORY a start of
     * 0 is replaced by the network checkpoint, which would start the restore
     * LATER than the source covered.
     */
    val syncMode: SyncMode get() = if (startBlock == 0L) SyncMode.FULL_HISTORY else SyncMode.CUSTOM

    val customHeight: Long? get() = if (startBlock == 0L) null else startBlock
}

/**
 * Turns a verified [RestoreHintPayload] into start heights (#559). Pure.
 *
 * The one rule: a hint can only make a restore start EARLIER, never later than
 * the source wallet's own coverage. Per account
 * `start = max(0, min(coverageStart, firstActivity ?: coverageStart) - MARGIN)`.
 */
object RestoreHintPlanner {

    /** Safety margin below the earliest known block, in blocks. */
    const val MARGIN: Long = 1_000L

    /**
     * Highest sub-account index a hint may seed. Each seeded index is one more
     * registered script; real wallets stay far below this.
     */
    const val MAX_SEED_INDEX: Int = 100

    fun accountStart(account: RestoreHintAccount): Long {
        val earliest = minOf(account.coverageStart, account.firstActivity ?: account.coverageStart)
        return (earliest - MARGIN).coerceAtLeast(0L)
    }

    /**
     * The plan for restoring with a secret of [restoringKind], or null when
     * the hint has nothing usable for it. Only accounts the restored secret
     * can derive are used: every account index for a mnemonic, only index 0
     * for a raw key. The main account (index 0) must be present, because the
     * restored wallet's own start cannot be chosen without it.
     */
    fun plan(payload: RestoreHintPayload, restoringKind: String): RestoreHintPlan? {
        if (payload.kind != restoringKind) return null
        val derivable = payload.accounts.filter { isDerivable(it.index, restoringKind) }
        if (derivable.none { it.index == 0 }) return null

        val perIndex = derivable
            .groupBy { it.index }
            .map { (index, entries) -> RestoreHintAccountPlan(index, entries.minOf(::accountStart)) }
            .sortedBy { it.index }

        val seedIndices = if (restoringKind == RestoreHintKind.MNEMONIC) {
            (derivable.map { it.index } + payload.discovery.found)
                .filter { it in 1..MAX_SEED_INDEX }
                .distinct()
                .sorted()
        } else {
            emptyList()
        }

        return RestoreHintPlan(
            accounts = perIndex,
            startBlock = perIndex.minOf { it.startBlock },
            sourceCoverageStart = derivable.minOf { it.coverageStart },
            seedIndices = seedIndices,
        )
    }

    private fun isDerivable(index: Int, kind: String): Boolean = when (kind) {
        RestoreHintKind.RAW_KEY -> index == 0
        // Any non-negative Int is a valid hardened BIP-32 account index (< 2^31).
        RestoreHintKind.MNEMONIC -> index >= 0
        else -> false
    }
}
