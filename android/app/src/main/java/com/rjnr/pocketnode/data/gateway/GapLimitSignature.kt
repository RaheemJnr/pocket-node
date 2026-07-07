package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.Script

/**
 * #382 gap-limit signature: an outgoing transaction whose change went to no
 * script we know. Seeds imported from Neuron (or any standard BIP44 wallet)
 * spread funds across m/44'/309'/0'/{0|1}/{i}; Pocket Node derives only
 * /N'/0/0, so change constructed by the other wallet lands on sibling
 * addresses we never derived and the send reads as fund loss.
 *
 * Flags a transaction when ALL of:
 *  - it is outgoing (negative net change against our known scripts),
 *  - no output lock args match any script we track (nothing came back),
 *  - it has at least two outputs (a single output with nothing back is the
 *    legitimate send-max shape, not a missing change leg),
 *  - at least one output is a plain untyped secp256k1 lock — the only shape a
 *    change cell can take, on either the receiving or change chain.
 */
fun isUnknownChangeSignature(
    netChangeShannons: Long,
    outputs: List<CellOutput>,
    knownLockArgs: Set<String>,
): Boolean {
    if (netChangeShannons >= 0) return false
    if (outputs.size < 2) return false

    val known = knownLockArgs.map { it.lowercase() }.toSet()
    if (outputs.any { it.lock.args.lowercase() in known }) return false

    return outputs.any { it.type == null && it.lock.codeHash == Script.SECP256K1_CODE_HASH }
}
