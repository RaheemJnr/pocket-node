package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.Script

/**
 * A transaction is a self transfer when the wallet paid the fee (net change
 * is negative, meaning at least one input was ours) and every output is a
 * plain secp256k1-blake160 cell (the lock the wallet itself uses) whose args
 * are in [knownLockArgs]. Nothing left for anyone else means the funds never
 * left the wallet.
 *
 * [knownLockArgs] must be scoped to the CURRENT wallet only: its main address
 * plus its own HD/derived and sub-account candidates. It must NOT include
 * other wallets in the app, otherwise a send from wallet A to wallet B would
 * misclassify as a self transfer when wallet A actually lost the full amount.
 * See the caller in GatewayRepository.getTransactions for how that narrower
 * set is built, distinct from the broader `knownLockArgs` [isUnknownChangeSignature]
 * uses (which does intentionally span every wallet).
 *
 * Matching the lock code_hash and hash_type, not just args, matters too: an
 * output with the same args under a different lock (multisig, ACP) is not a
 * cell this wallet's own key controls, even though the args happen to match,
 * so it must not count as ours.
 *
 * An empty output list (nothing to check) is not a self transfer, it falls
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
    return outputs.all {
        it.lock.codeHash == Script.SECP256K1_CODE_HASH &&
            it.lock.hashType == "type" &&
            it.lock.args.lowercase() in known
    }
}
