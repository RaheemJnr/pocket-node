package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.CellOutput

/**
 * A transaction is a self transfer when the wallet paid the fee (net change is
 * negative — at least one input was ours) and every output lands on a script
 * we track (main address, HD/derived candidates, sub-account candidates —
 * the same `knownLockArgs` set [isUnknownChangeSignature] uses). Nothing left
 * for anyone else means the funds never left the wallet.
 *
 * An empty output list (nothing to check) is not a self transfer — it falls
 * back to "out", the safe default. Same for any output landing on a script we
 * don't recognize: it keeps the "Sent" label rather than risk hiding a real
 * outgoing payment as a transfer to self.
 */
fun isSelfTransferSignature(
    netChangeShannons: Long,
    outputs: List<CellOutput>,
    knownLockArgs: Set<String>,
): Boolean {
    if (netChangeShannons >= 0) return false
    if (outputs.isEmpty()) return false

    val known = knownLockArgs.map { it.lowercase() }.toSet()
    return outputs.all { it.lock.args.lowercase() in known }
}
