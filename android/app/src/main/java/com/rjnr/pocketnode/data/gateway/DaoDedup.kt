package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit

/**
 * #357: drop a deposit's stale DEPOSITED entry once its phase-1 withdraw has
 * committed. A confirmed phase-1 tx consumes the deposit cell and creates a
 * new withdrawing cell, but the light client's get_cells can surface the new
 * withdrawing cell before its spent-outpoint filter drops the old deposit, so
 * both briefly appear — the deposit then rendered a duplicate "Confirming…"
 * card next to the real withdrawing position.
 *
 * Each withdrawing entry carries [DaoDeposit.consumedDepositOutPoints] (the
 * inputs of its phase-1 tx). Any DEPOSITED entry whose outpoint was consumed
 * by a withdrawing entry is the spent original — drop it. Only DEPOSITED
 * entries are dropped, so a withdrawing/unlockable position is never removed.
 */
fun dedupeWithdrawnDeposits(deposits: List<DaoDeposit>): List<DaoDeposit> {
    val consumed = deposits.flatMap { it.consumedDepositOutPoints }.toSet()
    if (consumed.isEmpty()) return deposits
    return deposits.filterNot { it.status == DaoCellStatus.DEPOSITED && it.outPoint in consumed }
}
