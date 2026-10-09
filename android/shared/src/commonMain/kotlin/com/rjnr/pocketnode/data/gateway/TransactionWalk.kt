package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.JniTxWithCell

/**
 * Shared, unit-testable core of the light-client paginated read paths.
 *
 * The #386/#388 bug class — verified independently by a Nervos tester
 * (yanli): "the actual trigger is the fixed first-page limit of 100
 * cells/transactions; a 200-cell mock reproduces it deterministically" — was
 * computing balance / spent-set / transaction net from a SINGLE page. A
 * transaction whose cell interactions straddle the 100-item boundary was then
 * scored from partial data: wrong amount, sometimes wrong direction.
 *
 * The fix is the discipline these helpers encode: walk every page to
 * completion FIRST ([walkAllPages]), then compute over the complete set
 * ([netShannonsByTx], [spentOutpointsOf]). Kept free of JNI/Room so the walk
 * and the computations can be tested with a mock page source (TransactionWalkTest)
 * rather than only through the ~20-dependency GatewayRepository.
 */

/** Outcome of a paginated walk. [hitCap] is true when the runaway guard bounded it. */
data class WalkResult<T>(
    val items: List<T>,
    val pagesWalked: Int,
    val hitCap: Boolean,
)

/**
 * Walk a cursor-paginated light-client read to completion. [fetchPage] returns
 * one decoded page for the given cursor (null on JNI failure). Stops on: null
 * page, empty page, a short page (`size < pageLimit` ⇒ last page), an empty
 * cursor, or the [maxPages] runaway guard.
 */
suspend fun <T> walkAllPages(
    pageLimit: Int,
    maxPages: Int,
    fetchPage: suspend (cursor: String?) -> JniPagination<T>?,
): WalkResult<T> {
    val all = mutableListOf<T>()
    var cursor: String? = null
    var pages = 0
    while (pages < maxPages) {
        val page = fetchPage(cursor) ?: break
        all.addAll(page.objects)
        pages++
        if (page.objects.isEmpty() || page.objects.size < pageLimit || page.lastCursor.isNullOrEmpty()) break
        cursor = page.lastCursor
    }
    return WalkResult(all, pages, pages >= maxPages)
}

/**
 * Net shannon flow per transaction hash over a COMPLETE interaction set:
 * Σ(our output capacities) − Σ(our input capacities). Positive = received,
 * negative = sent. Correct only when [interactions] is the full walk — the
 * whole point of the #388 fix.
 */
fun netShannonsByTx(interactions: List<JniTxWithCell>): Map<String, Long> {
    val sums = HashMap<String, Long>()
    interactions.forEach { i ->
        val cap = i.ioCapacity.removePrefix("0x").toLongOrNull(16) ?: 0L
        val delta = if (i.ioType == "output") cap else -cap
        sums[i.transaction.hash] = (sums[i.transaction.hash] ?: 0L) + delta
    }
    return sums
}

/** Every spent outpoint referenced by the inputs across a complete interaction set. */
fun spentOutpointsOf(interactions: List<JniTxWithCell>): Set<String> = buildSet {
    interactions.forEach { txWithCell ->
        txWithCell.transaction.inputs.forEach { input ->
            add("${input.previousOutput.txHash}:${input.previousOutput.index}")
        }
    }
}
