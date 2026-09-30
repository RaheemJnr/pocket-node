package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transfer between a wallet's own addresses always pays the fee, so its net
 * change is negative, never zero: `GatewayRepository.getTransactions` used to
 * read that as a plain "Sent". `isSelfTransferSignature` is the piece that
 * tells the two apart: negative net change where every output is a plain
 * secp256k1-blake160 cell whose args are in the caller's known-args set is a
 * self transfer, not an outgoing payment.
 *
 * The set passed in must be scoped to the CURRENT wallet only (its own main
 * address plus its own HD/derived and sub-account candidates), not the
 * broader all-wallets `knownLockArgs` set [isUnknownChangeSignature] uses:
 * otherwise a send to a different wallet in the same app would misclassify
 * as a self transfer.
 *
 * DAO priority (a DAO output or header-dep count overrides this call
 * entirely) and the "exact-zero net still self" / "positive net is 'in'"
 * branches live in the `direction`/`finalDirection` `when` in
 * `GatewayRepository.getTransactions` itself, which this function does not
 * touch; that wiring needs a full GatewayRepository (~20 dependencies) to
 * exercise and is covered by manual smoke, same as `applyBalancedFilter`
 * (see GatewayRepositoryBalancedTest).
 */
class SelfTransferSignatureTest {

    private val ckb = 100_000_000L

    private val mainArgs = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val derivedArgs = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val foreignArgs = "0xcccccccccccccccccccccccccccccccccccccccc"
    private val otherWalletArgs = "0xdddddddddddddddddddddddddddddddddddddddd"

    private val multisigCodeHash = "0x5c5069eb0857efc65e1bca0c07df34c31663b3622fd3876c876320fc9634e2a8"

    private fun secpOutput(capacityCkb: Long, args: String) = CellOutput(
        capacity = "0x${(capacityCkb * ckb).toString(16)}",
        lock = Script(Script.SECP256K1_CODE_HASH, "type", args),
        type = null
    )

    private fun output(capacityCkb: Long, codeHash: String, hashType: String, args: String) = CellOutput(
        capacity = "0x${(capacityCkb * ckb).toString(16)}",
        lock = Script(codeHash, hashType, args),
        type = null
    )

    @Test
    fun `send to own main address is a self transfer`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(100, mainArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertTrue(flagged)
    }

    @Test
    fun `send to a derived address in the known set is a self transfer`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(100, derivedArgs)),
            knownLockArgs = setOf(mainArgs, derivedArgs),
        )
        assertTrue(flagged)
    }

    @Test
    fun `change plus a known-address output is still a self transfer`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(60, mainArgs), secpOutput(40, derivedArgs)),
            knownLockArgs = setOf(mainArgs, derivedArgs),
        )
        assertTrue(flagged)
    }

    @Test
    fun `any foreign output keeps it a plain send`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(60, mainArgs), secpOutput(40, foreignArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `all-foreign outputs is a plain send`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -1_000 * ckb,
            outputs = listOf(secpOutput(999, foreignArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `positive net change is never a self transfer`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = 500 * ckb,
            outputs = listOf(secpOutput(500, mainArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `zero net change is never a self transfer here (the caller keeps its own zero branch)`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = 0L,
            outputs = listOf(secpOutput(100, mainArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `no outputs falls back to a plain send`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = emptyList(),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `known-args match is case-insensitive`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(100, mainArgs.uppercase().replaceFirst("0X", "0x"))),
            knownLockArgs = setOf(mainArgs),
        )
        assertTrue(flagged)
    }

    @Test
    fun `narrowed known-args set (fallback to only myScript) still classifies a same-address self transfer`() {
        // #382-style fallback: if the full known-scripts set failed to build,
        // getTransactions falls back to setOf(myScript.args) alone. A transfer
        // to that same address is still correctly flagged; only a transfer to
        // a derived address would be missed in that narrower-set case.
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(100, mainArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertTrue(flagged)
    }

    @Test
    fun `send to a different wallet in the same app is a plain send, not a self transfer`() {
        // The caller must scope its known-args set to the CURRENT wallet only.
        // otherWalletArgs stands in for a second wallet's address that a
        // broader, all-wallets set would have included; here it is correctly
        // left out, so the send to it reads as "out", not "self".
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(100, otherWalletArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `same args under a multisig lock is not ours, even though the args match`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(output(100, multisigCodeHash, "type", mainArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `same args and code hash but a different hash type is not ours`() {
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(output(100, Script.SECP256K1_CODE_HASH, "data", mainArgs)),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `a typed output on our own lock is not a self transfer`() {
        // #538 review: an xUDT, Spore, .bit or any other token/NFT minted
        // onto our own lock still moved a distinct on-chain asset; labelling
        // that "Self Transfer" (a CKB-only phrase) would misrepresent it.
        val typedOutput = CellOutput(
            capacity = "0x${(100 * ckb).toString(16)}",
            lock = Script(Script.SECP256K1_CODE_HASH, "type", mainArgs),
            type = Script(
                codeHash = "0x50bd8d6680b8b9cf98b73f3c08faf8b2a21914311954118ad6609be6e78a1b95",
                hashType = "type",
                args = "0x",
            ),
        )
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(typedOutput),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `a typed output alongside a plain self output is still not a self transfer`() {
        val typedOutput = CellOutput(
            capacity = "0x${(100 * ckb).toString(16)}",
            lock = Script(Script.SECP256K1_CODE_HASH, "type", mainArgs),
            type = Script(
                codeHash = "0x50bd8d6680b8b9cf98b73f3c08faf8b2a21914311954118ad6609be6e78a1b95",
                hashType = "type",
                args = "0x",
            ),
        )
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(60, mainArgs), typedOutput),
            knownLockArgs = setOf(mainArgs),
        )
        assertFalse(flagged)
    }

    // --- selfTransferDisplayAmount / isSelfTransferFallbackSafe (#538 review) ---

    @Test
    fun `self-send to a derived candidate with change to main shows exactly the fee`() {
        // The JNI walk was queried with only the main script, so netChangeAmount
        // (abs of Σ-outputs-to-main minus Σ-inputs-from-main) never saw the 100
        // CKB leg that went to the derived candidate: it reads as ~100 CKB, not
        // the fee. feeShannons, computed from the full declared transaction, is
        // not fooled by that and is what must be shown.
        val fee = 1_000L
        val wronglyUndercountedNet = 100 * ckb + fee // what abs(net) reads as
        val displayed = selfTransferDisplayAmount(feeShannons = fee, netChangeAmount = wronglyUndercountedNet)
        assertEquals(fee, displayed)
    }

    @Test
    fun `unknown fee falls back to net change`() {
        val netChangeAmount = 1_000L
        val displayed = selfTransferDisplayAmount(feeShannons = null, netChangeAmount = netChangeAmount)
        assertEquals(netChangeAmount, displayed)
    }

    @Test
    fun `fallback is safe when every output is on the main script`() {
        val safe = isSelfTransferFallbackSafe(
            outputs = listOf(secpOutput(100, mainArgs)),
            mainScriptArgs = mainArgs,
        )
        assertTrue(safe)
    }

    @Test
    fun `fallback is not safe when an output went to a derived candidate`() {
        val safe = isSelfTransferFallbackSafe(
            outputs = listOf(secpOutput(60, mainArgs), secpOutput(40, derivedArgs)),
            mainScriptArgs = mainArgs,
        )
        assertFalse(safe)
    }

    // --- activeSelfTransferCandidateArgs (#538 review) ---

    private fun candidate(scriptArgs: String, state: String, accountIndex: Int = 1) = SubAccountCandidateEntity(
        parentWalletId = "parent-wallet",
        derivationPath = "m/44'/309'/$accountIndex'/0/0",
        accountIndex = accountIndex,
        scriptArgs = scriptArgs,
        state = state,
        createdAt = 0L,
    )

    @Test
    fun `RESTORED candidates are excluded from the self-transfer scope`() {
        val args = activeSelfTransferCandidateArgs(
            listOf(
                candidate(derivedArgs, SubAccountCandidateEntity.STATE_PENDING),
                candidate(foreignArgs, SubAccountCandidateEntity.STATE_RESTORED),
            )
        )
        assertEquals(listOf(derivedArgs), args)
    }

    @Test
    fun `non-RESTORED states are still included`() {
        val args = activeSelfTransferCandidateArgs(
            listOf(
                candidate(derivedArgs, SubAccountCandidateEntity.STATE_PENDING),
                candidate(foreignArgs, SubAccountCandidateEntity.STATE_FOUND),
                candidate(otherWalletArgs, SubAccountCandidateEntity.STATE_EMPTY),
            )
        )
        assertEquals(setOf(derivedArgs, foreignArgs, otherWalletArgs), args.toSet())
    }

    @Test
    fun `parent to restored sub-account stays Sent`() {
        // createSubAccount() promotes a candidate to its own separate
        // WalletEntity and marks the candidate row RESTORED rather than
        // deleting it. A send from the parent wallet to that now-distinct
        // child must classify as "out", not "self".
        val restoredChildArgs = derivedArgs
        val knownArgs = buildSet {
            add(mainArgs)
            addAll(
                activeSelfTransferCandidateArgs(
                    listOf(candidate(restoredChildArgs, SubAccountCandidateEntity.STATE_RESTORED))
                )
            )
        }
        val flagged = isSelfTransferSignature(
            netChangeShannons = -100_000L,
            outputs = listOf(secpOutput(100, restoredChildArgs)),
            knownLockArgs = knownArgs,
        )
        assertFalse(flagged)
    }

    // --- selfRowDirectionAndAmount (#538 review, safety fallback) ---

    @Test
    fun `known fee wins even when the fallback would not have been safe`() {
        val fee = 1_000L
        val (dir, amt) = selfRowDirectionAndAmount(
            feeShannons = fee,
            outputs = listOf(secpOutput(60, mainArgs), secpOutput(40, derivedArgs)),
            mainScriptArgs = mainArgs,
            netChangeAmount = 100 * ckb + fee,
        )
        assertEquals("self", dir)
        assertEquals(fee, amt)
    }

    @Test
    fun `unknown fee with a safe fallback still reads self`() {
        val netChangeAmount = 1_000L
        val (dir, amt) = selfRowDirectionAndAmount(
            feeShannons = null,
            outputs = listOf(secpOutput(100, mainArgs)),
            mainScriptArgs = mainArgs,
            netChangeAmount = netChangeAmount,
        )
        assertEquals("self", dir)
        assertEquals(netChangeAmount, amt)
    }

    @Test
    fun `unknown fee with an unsafe fallback demotes to out`() {
        // No honest number to show: netChangeAmount is missing whatever went
        // to the derived candidate, so showing it under "Self Transfer" would
        // hide that gap. Demoted to "out" instead (never worse than the
        // pre-#538 behavior, and never mislabeled as safely self-contained).
        val wronglyPositiveNet = 100 * ckb
        val (dir, amt) = selfRowDirectionAndAmount(
            feeShannons = null,
            outputs = listOf(secpOutput(60, mainArgs), secpOutput(40, derivedArgs)),
            mainScriptArgs = mainArgs,
            netChangeAmount = wronglyPositiveNet,
        )
        assertEquals("out", dir)
        assertEquals(wronglyPositiveNet, amt)
    }

    // --- pendingTransferDisplay (#538 review) ---

    @Test
    fun `a freshly built self-send's pending row reads self with the fee`() {
        // buildReserveAndSend's own net-debit computation only treats an
        // output as change when it is locked to fromAddress exactly, so a
        // send to a derived candidate (change back to main) would otherwise
        // report the full 100 CKB plus the fee as the pending amount.
        val fee = 1_000L
        val outputs = listOf(secpOutput(60, derivedArgs), secpOutput(40, mainArgs))
        val wronglyInflatedOutgoingAmount = 100 * ckb + fee

        val display = pendingTransferDisplay(
            outputs = outputs,
            selfWalletLockArgs = setOf(mainArgs, derivedArgs),
            plannedFeeShannons = fee,
            outgoingAmountShannons = wronglyInflatedOutgoingAmount,
            mainScriptArgs = mainArgs,
        )

        assertEquals(PendingTransferDisplay("self", fee), display)
    }

    @Test
    fun `pending display falls back to the outgoing amount when the fee is unknown but the fallback is safe`() {
        val outgoingAmount = 500L
        val display = pendingTransferDisplay(
            outputs = listOf(secpOutput(100, mainArgs)),
            selfWalletLockArgs = setOf(mainArgs),
            plannedFeeShannons = null,
            outgoingAmountShannons = outgoingAmount,
            mainScriptArgs = mainArgs,
        )
        assertEquals(PendingTransferDisplay("self", outgoingAmount), display)
    }

    @Test
    fun `pending display is null when the fee is unknown and the fallback is not safe`() {
        // #538 review: a derived-candidate output means outgoingAmount could
        // be wrong, and with no fee to fall back on there is no honest number
        // to show under "Self Transfer": null lets buildReserveAndSend's own
        // "out" + outgoingAmount stand instead.
        val display = pendingTransferDisplay(
            outputs = listOf(secpOutput(60, derivedArgs), secpOutput(40, mainArgs)),
            selfWalletLockArgs = setOf(mainArgs, derivedArgs),
            plannedFeeShannons = null,
            outgoingAmountShannons = 100 * ckb,
            mainScriptArgs = mainArgs,
        )
        assertEquals(null, display)
    }

    @Test
    fun `pending display is null (unchanged) for a real send to someone else`() {
        val display = pendingTransferDisplay(
            outputs = listOf(secpOutput(100, foreignArgs)),
            selfWalletLockArgs = setOf(mainArgs),
            plannedFeeShannons = 1_000L,
            outgoingAmountShannons = 100 * ckb + 1_000L,
            mainScriptArgs = mainArgs,
        )
        assertEquals(null, display)
    }

    // --- sweepRowDisplay (#538 review) ---

    @Test
    fun `a sweep from a candidate to main is self with the fee`() {
        val fee = 1_000L
        val display = sweepRowDisplay(
            isKnownSweepTxHash = true,
            outputsAreSelf = true,
            recordedFeeShannons = fee,
        )
        assertEquals(PendingTransferDisplay("self", fee), display)
    }

    @Test
    fun `a sweep with no recorded fee is not overridden (stays in)`() {
        // #538 review: no abs(net) fallback for a sweep (its walk-based net
        // is the FULL swept balance, not a partial value the way a plain
        // self-send's undercounted net is), so an unresolved fee means no
        // override at all, not a wrong amount under "self".
        val display = sweepRowDisplay(
            isKnownSweepTxHash = true,
            outputsAreSelf = true,
            recordedFeeShannons = null,
        )
        assertEquals(null, display)
    }

    @Test
    fun `a genuine receive from a foreign input stays in`() {
        // Not marked as a sweep, so the caller must keep its own
        // classification (a positive net change read as "in").
        val display = sweepRowDisplay(
            isKnownSweepTxHash = false,
            outputsAreSelf = false,
            recordedFeeShannons = null,
        )
        assertEquals(null, display)
    }

    @Test
    fun `a marker without the outputs also passing isSelfTransferSignature is not trusted`() {
        // #538 review, defence in depth: even a wallet-scoped marker saying
        // "yes" is not enough alone if the transaction's own outputs do not
        // independently agree.
        val display = sweepRowDisplay(
            isKnownSweepTxHash = true,
            outputsAreSelf = false,
            recordedFeeShannons = 1_000L,
        )
        assertEquals(null, display)
    }

    // --- sendTransactionPendingAmount (#538 review) ---

    @Test
    fun `a sweep's pending row shows the fee, not the recipient amount of zero`() {
        // Every output of a sweep is locked to the main script, so
        // sendTransaction's own recipientOutgoingShannons (outputs NOT ours)
        // reads 0: without this, the pending row would say "Sent 0 CKB".
        val fee = 1_000L
        val amount = sendTransactionPendingAmount(
            pendingDirection = "self",
            pendingFeeShannons = fee,
            recipientAmountShannons = 0L,
        )
        assertEquals(fee, amount)
    }

    @Test
    fun `pending amount for self with an unknown fee falls back to the recipient amount`() {
        val recipientAmount = 500L
        val amount = sendTransactionPendingAmount(
            pendingDirection = "self",
            pendingFeeShannons = null,
            recipientAmountShannons = recipientAmount,
        )
        assertEquals(recipientAmount, amount)
    }

    @Test
    fun `pending amount for a plain out send is unaffected`() {
        val recipientAmount = 12_345L
        val amount = sendTransactionPendingAmount(
            pendingDirection = "out",
            pendingFeeShannons = 1_000L,
            recipientAmountShannons = recipientAmount,
        )
        assertEquals(recipientAmount, amount)
    }

    // --- retryPendingOverride (#538 review, retry-path follow-up) ---

    @Test
    fun `a retried sweep still shows the fee`() {
        // retryBroadcast reads the cached row (direction "self", the fee the
        // sweep was originally sent with) before deleting it, and this is
        // what tells sendTransaction to show that fee again instead of
        // regressing to its own default.
        val fee = 1_000L
        val override = retryPendingOverride(cachedDirection = "self", cachedFeeShannons = fee)
        assertEquals("self" to fee, override)
    }

    @Test
    fun `a retried self row with an unresolved cached fee is not overridden`() {
        // #538 review, safety fallback: sendTransaction cannot tell whether
        // its own recipient-amount fallback would be safe the way
        // selfRowDirectionAndAmount can, so an unknown fee here is never
        // trusted to override "out" with a "self" label.
        val override = retryPendingOverride(cachedDirection = "self", cachedFeeShannons = null)
        assertEquals(null, override)
    }

    @Test
    fun `a retried plain send is not overridden`() {
        val override = retryPendingOverride(cachedDirection = "out", cachedFeeShannons = 1_000L)
        assertEquals(null, override)
    }

    @Test
    fun `no cached row at all is not overridden`() {
        val override = retryPendingOverride(cachedDirection = null, cachedFeeShannons = null)
        assertEquals(null, override)
    }
}
