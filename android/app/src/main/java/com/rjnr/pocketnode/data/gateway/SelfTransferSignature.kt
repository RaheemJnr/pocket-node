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

/** [pendingTransferDisplay]'s result: what a pending activity row should show. */
data class PendingTransferDisplay(val direction: String, val amountShannons: Long)

/**
 * What a freshly built plain transfer's pending activity row should show,
 * for the same reason [selfTransferDisplayAmount] exists on the confirmed
 * side (#538 review): `buildReserveAndSend`'s own net-debit computation only
 * treats an output as change when it is locked to `fromAddress` exactly, so a
 * self-send to a DERIVED candidate address (a different string, still this
 * wallet's own script) reads its own output as a real recipient and shows the
 * full amount sent plus the fee, not just the fee, until the confirmed row
 * (already fixed) takes over.
 *
 * Reuses [isSelfTransferSignature] against [selfWalletLockArgs] (the same set
 * the confirmed row will use once indexed), so the pending row agrees with
 * what it eventually becomes. Returns null when the built transaction is not
 * a self transfer, so the caller keeps its own direction/amount unchanged.
 */
fun pendingTransferDisplay(
    outputs: List<CellOutput>,
    selfWalletLockArgs: Set<String>,
    plannedFeeShannons: Long?,
    outgoingAmountShannons: Long,
): PendingTransferDisplay? {
    // netChangeShannons is only used for its sign by isSelfTransferSignature;
    // a plain transfer always pays a fee, so it is always negative here.
    if (!isSelfTransferSignature(netChangeShannons = -1L, outputs = outputs, knownLockArgs = selfWalletLockArgs)) {
        return null
    }
    return PendingTransferDisplay("self", plannedFeeShannons ?: outgoingAmountShannons)
}

/**
 * Classify a transaction that might be a candidate-funded gap-limit sweep
 * (#538 review): sweepGapLimitFunds spends FOUND derived candidates back to
 * the main address, so the info.script-scoped JNI walk only ever sees the
 * main-script output (the candidate-script inputs never match the query) and
 * its net reads positive: without this check it would show as "Received",
 * hiding the fee actually paid.
 *
 * A foreign input's owning lock script is not knowable here without an extra
 * JNI lookup per input (see sweepGapLimitFundsInner/WalletPreferences for the
 * cheaper alternative actually used: the sweep's own tx hash is recorded
 * locally at send time), so [isKnownSweepTxHash] is that purely local signal,
 * never derived from chain data. Returns null when it is false, so the
 * caller keeps its own classification (a genuine receive from a foreign
 * input is never marked and is always left as "in").
 */
fun sweepRowDisplay(
    isKnownSweepTxHash: Boolean,
    recordedFeeShannons: Long?,
    netChangeAmount: Long,
): PendingTransferDisplay? {
    if (!isKnownSweepTxHash) return null
    return PendingTransferDisplay("self", recordedFeeShannons ?: netChangeAmount)
}

/**
 * The balanceChange sendTransaction's own pending-row insert should show
 * (#538 review, retry-path follow-up).
 *
 * sendTransaction's insert (used directly by sweepGapLimitFunds, and by
 * retryBroadcast for any re-sent transaction) computes a recipient amount:
 * the sum of outputs NOT locked to this wallet. That reads 0 whenever every
 * output IS locked to this wallet, such as a gap-limit sweep (spends
 * candidate cells back to the main address) or a retried self-transfer: the
 * pending row would otherwise show "Sent 0 CKB" (or whatever wrong amount)
 * until the confirmed row, already fixed elsewhere, takes over.
 * [pendingDirection] "self" says this is one of those cases and prefers
 * [pendingFeeShannons]; any other direction is untouched.
 */
fun sendTransactionPendingAmount(
    pendingDirection: String,
    pendingFeeShannons: Long?,
    recipientAmountShannons: Long,
): Long = if (pendingDirection == "self") {
    pendingFeeShannons ?: recipientAmountShannons
} else {
    recipientAmountShannons
}

/**
 * What retryBroadcast should pass through to sendTransaction for the row it
 * is re-sending (#538 review, retry-path follow-up).
 *
 * retryBroadcast deletes the cached `transactions` row before re-sending, so
 * without this, a "self" row (a self-transfer to a derived candidate, or a
 * gap-limit sweep) would regress back to sendTransaction's own default "out"
 * + wrong amount for as long as the retry is pending, undoing the fix this
 * whole PR makes. [cachedDirection]/[cachedFeeShannons] are read from that
 * row BEFORE it is deleted. Returns null for any other direction (or no
 * cached row at all), so the caller falls back to sendTransaction's default.
 * The fee is deliberately left nullable here (unlike [PendingTransferDisplay]'s
 * amount): "unknown" must stay distinguishable from a genuine zero so
 * sendTransactionPendingAmount can fall back to the recipient amount instead.
 */
fun retryPendingOverride(cachedDirection: String?, cachedFeeShannons: Long?): Pair<String, Long?>? =
    if (cachedDirection == "self") "self" to cachedFeeShannons else null
