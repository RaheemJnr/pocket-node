package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.data.transaction.TransactionBuilder

/**
 * The two pieces of send-screen judgement that are not UI: what a raw failure
 * means, and whether an amount is about to empty the wallet.
 *
 * Both were Android-only and both are now shared, because both are decisions
 * rather than decoration. [mapSendErrorMessage] turns a node's own words into
 * something a user can act on, and getting one of its branches wrong tells
 * somebody to reopen the app when they should be waiting for a confirmation.
 * [sweepWarning] decides whether the review sheet demands an acknowledgement
 * before a send that leaves nothing behind. A second, independently written
 * copy of either on iOS is a second thing to get wrong.
 *
 * The Android `SendViewModel` still holds its own copy of
 * [mapSendErrorMessage] for now; converging on this one is a follow-up, and
 * `SendCopyTest` pins the strings so the two cannot drift silently in the
 * meantime.
 */

/**
 * Maps a raw send/broadcast failure to a user-facing message.
 *
 * Ported verbatim from `SendViewModel.mapSendErrorMessage`, order included:
 * the branches are tried most-specific-first, and reordering them changes
 * which message a user sees. The Rust bridge now returns the real rejection
 * reason (through the `"Broadcast rejected: ..."` exception
 * [SendPipeline] raises off `BROADCAST_ERROR_PREFIX`), so the broadcast
 * branches name local verification failures instead of blaming the network.
 *
 * Non-null where Android's takes a nullable: a caller with nothing to map
 * passes its own placeholder, which is what the Android version's
 * `raw ?: "Unknown error"` amounted to.
 */
fun mapSendErrorMessage(message: String): String = when {
    // Cell/UTXO fetch
    message.contains("Failed to get cells", ignoreCase = true) ->
        "Could not fetch your available funds. Please ensure your wallet is synced and try again."
    message.contains("No cells available", ignoreCase = true) ||
        message.contains("Insufficient cells", ignoreCase = true) ->
        "Not enough funds available. Please wait for your wallet to fully sync."

    // Amount/build
    message.contains("Insufficient balance", ignoreCase = true) ->
        "Insufficient balance for this transaction."
    message.contains("minimum", ignoreCase = true) && message.contains("61", ignoreCase = true) ->
        "Minimum transfer amount is 61 CKB due to CKB's cell model."
    message.contains("Dust change refused", ignoreCase = true) ->
        "This exact amount would leave less than 61 CKB of change, which CKB cannot store as a separate output and the protocol would silently absorb into the transaction fee. Try sending a slightly different amount, or send your full balance minus the fee."

    // Broadcast rejected with a real reason (from the bridge). Specific,
    // actionable causes first.
    message.contains("Unknown(", ignoreCase = true) ->
        "This send depends on a previous transaction that hasn't confirmed yet. Wait for it to confirm, or reopen the app and try again."
    message.contains("Dead(", ignoreCase = true) ->
        "Some of the coins for this transaction were already spent. Reopen the app to refresh your balance, then try again."
    message.contains("light client not ready", ignoreCase = true) ->
        "The wallet is still starting up. Please wait a moment and try again."
    message.contains("verification failed", ignoreCase = true) ->
        "The network rejected this transaction. Reopen the app to refresh your wallet, then try again."
    // Any other broadcast rejection: surface a concise reason instead of a
    // network message, since these are usually deterministic and local.
    message.contains("Broadcast rejected", ignoreCase = true) ->
        "The network rejected this transaction. Please reopen the app and try again."
    message.contains("Send failed", ignoreCase = true) ||
        message.contains("broadcast", ignoreCase = true) ->
        "Could not send the transaction. Please reopen the app and try again."

    // Sync
    message.contains("not synced", ignoreCase = true) ||
        message.contains("sync", ignoreCase = true) ->
        "Wallet is still syncing. Please wait for sync to complete before sending."

    // Data/parsing (likely a bug)
    message.contains("json", ignoreCase = true) ||
        message.contains("parse", ignoreCase = true) ||
        message.contains("serial", ignoreCase = true) ||
        message.contains("missing", ignoreCase = true) ->
        "Internal error processing transaction data. Please try again or restart the app."

    else -> "Transaction failed: $message"
}

/**
 * What a send would leave behind, when that is little enough to be worth
 * stopping for (#447).
 *
 * Integer shannons throughout. The displayed balance is rounded to two
 * decimals and must never drive this: a sheet that says "you will have 0.00
 * CKB left" off a rounded subtraction is a sheet that can be wrong by a whole
 * cell.
 */
data class SweepWarning(
    /** Spendable capacity left once the amount and the fee have gone, clamped at zero. */
    val remainingShannons: Long,
    /**
     * True when [remainingShannons] is under one minimal cell, i.e. the wallet
     * genuinely cannot fund another transaction. Between that and the
     * 1%-of-balance line it still can, and the copy softens accordingly.
     */
    val belowMinCell: Boolean,
)

/** A secp256k1-blake160 cell cannot hold less than 61 CKB. */
const val MIN_CELL_SHANNONS: Long = TransactionBuilder.MIN_CELL_CAPACITY

/**
 * How much the wallet should keep back: one minimal cell, or 1% of the
 * balance when that is larger.
 *
 * The percentage catches the "swept a big wallet" case, where 61 CKB left out
 * of 50,000 is still effectively everything gone.
 */
fun sweepThreshold(balanceShannons: Long): Long =
    maxOf(MIN_CELL_SHANNONS, balanceShannons / 100)

/** Spendable capacity after the send, clamped at zero. */
fun sweepRemainingAfter(balanceShannons: Long, totalShannons: Long): Long =
    (balanceShannons - totalShannons).coerceAtLeast(0L)

/**
 * The warning for a send of [amountShannons] paying [feeShannons] out of
 * [balanceShannons], or null when nothing needs saying.
 *
 * A zero balance answers null rather than "you are about to sweep": zero means
 * "not loaded yet" far more often than it means "empty", and warning on it
 * would fire on every send made before the first balance tick.
 */
fun sweepWarning(
    balanceShannons: Long,
    amountShannons: Long,
    feeShannons: Long,
): SweepWarning? {
    if (balanceShannons <= 0L) return null
    val total = amountShannons + feeShannons
    val remaining = sweepRemainingAfter(balanceShannons, total)
    if (remaining >= sweepThreshold(balanceShannons)) return null
    return SweepWarning(
        remainingShannons = remaining,
        belowMinCell = remaining < MIN_CELL_SHANNONS,
    )
}
