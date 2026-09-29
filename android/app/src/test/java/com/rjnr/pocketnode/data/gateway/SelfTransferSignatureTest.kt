package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.Script
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
}
