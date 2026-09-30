package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
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

/**
 * Sub-account candidate script args usable for the self-transfer scope:
 * every candidate for the active wallet except ones in RESTORED state (#538
 * review).
 *
 * createSubAccount() promotes a candidate to its own separate WalletEntity
 * (its own walletId, its own address, its own row in the wallet switcher) and
 * marks the candidate row RESTORED rather than deleting it. Left unfiltered,
 * that row would keep naming the restored child's script as "ours" forever,
 * so a transfer from the parent wallet to that now-distinct child would
 * misclassify as a self transfer instead of a real transfer between two
 * wallets. GatewayRepository.getTransactions also subtracts every other
 * WalletEntity's own address from the self-transfer set as defence in depth,
 * in case a candidate row's state is ever stale.
 */
fun activeSelfTransferCandidateArgs(candidates: List<SubAccountCandidateEntity>): List<String> =
    candidates.filter { it.state != SubAccountCandidateEntity.STATE_RESTORED }.map { it.scriptArgs }

/**
 * The amount to display on a self-transfer row (#538 review).
 *
 * [netChangeAmount] (`abs(netChangeShannons)`) is wrong whenever a non-change
 * output went to another script this wallet also owns, such as a derived
 * candidate address: the JNI walk that produced it was queried with only the
 * wallet's main script, so it never saw that output and undercounts by its
 * capacity (a 100 CKB send to a derived address showed as ~100 CKB "self",
 * not the fee it actually cost). [feeShannons] is computed from the FULL
 * declared transaction (every input and output, not the walk), so it is the
 * honest number here and is preferred whenever it is known.
 *
 * [netChangeAmount] is only used as a fallback when [feeShannons] is null,
 * and is only a SAFE fallback (see [isSelfTransferFallbackSafe]) when every
 * output lands on the wallet's own main script: in that one case nothing is
 * missing from the walk, so it already equals the fee exactly. When that is
 * not the case there is no honest number to fall back to, so the same value
 * is still returned (never worse than the pre-#538 behavior) but the caller
 * should log the uncertainty rather than trust it silently.
 */
fun selfTransferDisplayAmount(feeShannons: Long?, netChangeAmount: Long): Long =
    feeShannons ?: netChangeAmount

/**
 * Whether the [selfTransferDisplayAmount] fallback (used when `feeShannons`
 * is null) is backed by every output landing on the wallet's own main
 * script, [mainScriptArgs]. False does not mean the returned amount is
 * definitely wrong, only that it cannot be vouched for the way it can when
 * this is true.
 */
fun isSelfTransferFallbackSafe(outputs: List<CellOutput>, mainScriptArgs: String): Boolean =
    outputs.all { it.lock.args.equals(mainScriptArgs, ignoreCase = true) }
