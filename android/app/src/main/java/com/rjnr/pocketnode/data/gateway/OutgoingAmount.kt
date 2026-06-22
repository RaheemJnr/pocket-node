package com.rjnr.pocketnode.data.gateway

/** One output of an outgoing tx, classified for the net-debit computation. */
data class OutgoingOutput(
    val capacityShannons: Long,
    /** Locked to the sender's own address. */
    val isOurs: Boolean,
    /** Carries a type script (e.g. a Nervos DAO cell) — not plain change. */
    val isTyped: Boolean,
)

/**
 * Net capacity leaving the wallet for an outgoing tx's pending activity row.
 *
 * Mirrors the confirmed-row formula (Σ our inputs − Σ our outputs): the only
 * outputs that come back to spendable balance are our own PLAIN-change
 * outputs, so those are the only ones subtracted. A typed self-output (a DAO
 * deposit cell) is capacity leaving spendable, so it is NOT treated as change.
 *
 * The previous code stored min(all outputs), which returned the change output
 * whenever change < amount sent — the "-17,950.29 for a 150,000 send" bug.
 */
fun computeOutgoingShannons(
    inputCapacities: List<Long>,
    outputs: List<OutgoingOutput>,
): Long {
    val inputs = inputCapacities.sum()
    val change = outputs.filter { it.isOurs && !it.isTyped }.sumOf { it.capacityShannons }
    return (inputs - change).coerceAtLeast(0L)
}

/**
 * Output-only outgoing amount: the sum of outputs NOT locked to us (the
 * recipient amount). Used where input capacities are not available —
 * `sendTransaction`'s standalone insert (DAO unlock, failed-send retry).
 * Excludes the fee (which lives in the inputs), so it can read marginally
 * lower than [computeOutgoingShannons], but it never reports the change
 * output as the amount sent — the bug this replaces.
 */
fun recipientOutgoingShannons(outputs: List<OutgoingOutput>): Long =
    outputs.filter { !it.isOurs }.sumOf { it.capacityShannons }
