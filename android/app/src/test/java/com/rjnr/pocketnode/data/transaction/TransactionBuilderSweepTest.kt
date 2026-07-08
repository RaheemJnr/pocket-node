package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.validation.NetworkValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #382 Tier 3: the sweep spends cells locked by MULTIPLE secp256k1 scripts
 * (different derivation paths of one seed) into one output at the main
 * address. The existing signTransaction assumes ONE lock group; the sweep
 * needs per-group signing: each group's first witness carries that group's
 * signature over the SHARED tx hash plus the group's own witnesses.
 *
 * Fee: G full WitnessArgs items (one per group), not one — the transfer
 * formula undercounts a sweep by 85 bytes per extra group.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionBuilderSweepTest {

    private val builder = TransactionBuilder(NetworkValidator())

    private val ckb = 100_000_000L
    private val mainArgs = "0x" + "aa".repeat(20)
    private val sideArgsA = "0x" + "bb".repeat(20)
    private val sideArgsB = "0x" + "cc".repeat(20)
    private val keyA = ByteArray(32) { 0x11 }
    private val keyB = ByteArray(32) { 0x22 }

    private fun mainScript() = Script(Script.SECP256K1_CODE_HASH, "type", mainArgs)

    private fun input(index: Int, capacityCkb: Long, lockArgs: String) = SweepInput(
        outPoint = OutPoint("0x" + "%064x".format(index), "0x0"),
        capacityShannons = capacityCkb * ckb,
        lockArgs = lockArgs,
    )

    // --- fee ---

    @Test
    fun `sweep fee with one group equals the transfer fee`() {
        assertEquals(
            builder.estimateTransferFee(inputCount = 5, outputCount = 1),
            builder.estimateSweepFee(inputCount = 5, groupCount = 1),
        )
    }

    @Test
    fun `each extra group pays one more full witness args`() {
        // A group leader's witness is (4 len + 85 WitnessArgs); a member's is
        // (4 len + 0). Fee rate is 1000/KB so fee == bytes above the floor.
        val base = builder.estimateSweepFee(inputCount = 30, groupCount = 1)
        val threeGroups = builder.estimateSweepFee(inputCount = 30, groupCount = 3)
        assertEquals(base + 2 * 85, threeGroups)
    }

    // --- buildSweep ---

    @Test
    fun `sweep builds one all-in output with fee deducted`() {
        val plan = builder.buildSweep(
            inputs = listOf(
                input(1, 10_000, sideArgsA),
                input(2, 5_000, sideArgsA),
                input(3, 10_000, sideArgsB),
            ),
            toScript = mainScript(),
            network = NetworkType.TESTNET,
        ).getOrThrow()

        assertEquals(25_000 * ckb, plan.totalShannons)
        assertEquals(builder.estimateSweepFee(3, 2), plan.feeShannons)
        val tx = plan.transaction
        assertEquals(3, tx.cellInputs.size)
        assertEquals(1, tx.cellOutputs.size)
        assertEquals(
            25_000 * ckb - plan.feeShannons,
            tx.cellOutputs.single().capacity.removePrefix("0x").toLong(16),
        )
        assertEquals(mainArgs, tx.cellOutputs.single().lock.args)
        assertEquals(listOf(sideArgsA, sideArgsA, sideArgsB), plan.inputLockArgs)
        assertEquals(listOf("0x"), tx.outputsData)
    }

    @Test
    fun `sweep below minimum cell capacity is refused with an actionable message`() {
        val result = builder.buildSweep(
            inputs = listOf(input(1, 60, sideArgsA)),
            toScript = mainScript(),
            network = NetworkType.TESTNET,
        )
        assertTrue(result.isFailure)
        assertTrue(
            "message should mention the 61 CKB minimum, got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("61") == true,
        )
    }

    @Test
    fun `sweep with no inputs is refused`() {
        assertTrue(
            builder.buildSweep(emptyList(), mainScript(), NetworkType.TESTNET).isFailure
        )
    }

    // --- multi-group signing ---

    private fun unsignedTwoGroupTx() = builder.buildSweep(
        inputs = listOf(
            input(1, 10_000, sideArgsA),
            input(2, 5_000, sideArgsA),
            input(3, 10_000, sideArgsB),
        ),
        toScript = mainScript(),
        network = NetworkType.TESTNET,
    ).getOrThrow()

    @Test
    fun `single-group sweep signing is byte-identical to the shipped single-lock signer`() {
        // The strongest anchor available off-chain: collapsing the sweep to
        // one group must reproduce the signer every mainnet send goes
        // through today.
        val plan = builder.buildSweep(
            inputs = listOf(input(1, 10_000, sideArgsA), input(2, 5_000, sideArgsA)),
            toScript = mainScript(),
            network = NetworkType.TESTNET,
        ).getOrThrow()
        val viaSweep = builder.signSweep(
            plan.transaction, plan.inputLockArgs, mapOf(sideArgsA to keyA.copyOf())
        ).getOrThrow()
        val viaLegacy = builder.signTransaction(
            plan.transaction, keyA.copyOf(), inputCount = 2
        )
        assertEquals(viaLegacy.witnesses, viaSweep.witnesses)
    }

    @Test
    fun `each group leader carries a signature and members stay empty`() {
        val plan = unsignedTwoGroupTx()
        val signed = builder.signSweep(
            plan.transaction, plan.inputLockArgs,
            mapOf(sideArgsA to keyA.copyOf(), sideArgsB to keyB.copyOf()),
        ).getOrThrow()

        assertEquals(3, signed.witnesses.size)
        assertTrue("group A leader must be signed", signed.witnesses[0].length > 10)
        assertEquals("group A member must stay empty", "0x", signed.witnesses[1])
        assertTrue("group B leader must be signed", signed.witnesses[2].length > 10)
        assertNotEquals(
            "different keys must produce different signatures",
            signed.witnesses[0], signed.witnesses[2],
        )
    }

    @Test
    fun `sweep signing is deterministic`() {
        val plan = unsignedTwoGroupTx()
        val keys = mapOf(sideArgsA to keyA, sideArgsB to keyB)
        assertEquals(
            builder.signSweep(plan.transaction, plan.inputLockArgs, keys).getOrThrow(),
            builder.signSweep(plan.transaction, plan.inputLockArgs, keys).getOrThrow(),
        )
    }

    @Test
    fun `missing key for a group fails instead of producing a half-signed tx`() {
        val plan = unsignedTwoGroupTx()
        val result = builder.signSweep(
            plan.transaction, plan.inputLockArgs, mapOf(sideArgsA to keyA)
        )
        assertTrue(result.isFailure)
    }
}
