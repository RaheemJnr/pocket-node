package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.Script
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #382: seeds imported from Neuron (or any standard BIP44 wallet) spread funds
 * across m/44'/309'/0'/{0|1}/{i}, but Pocket Node derives only /N'/0/0. A send
 * made by the other wallet spends our known cell and pays change to a sibling
 * address we never derived — Activity shows a huge negative amount with nothing
 * coming back, which reads as fund loss.
 *
 * The signature: an outgoing transaction where NO output returns to any script
 * we know, and at least one plain (untyped) secp256k1 output exists that could
 * be that missing change leg. Single-output transactions are excluded — that
 * shape is a legitimate send-max.
 */
class GapLimitSignatureTest {

    private val ckb = 100_000_000L

    private val knownArgs = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val siblingArgs = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val recipientArgs = "0xcccccccccccccccccccccccccccccccccccccccc"

    private val daoTypeScript = Script(
        codeHash = "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e",
        hashType = "type",
        args = "0x"
    )

    private fun secpOutput(capacityCkb: Long, args: String, type: Script? = null) = CellOutput(
        capacity = "0x${(capacityCkb * ckb).toString(16)}",
        lock = Script(Script.SECP256K1_CODE_HASH, "type", args),
        type = type
    )

    @Test
    fun `neuron send with sibling change is flagged`() {
        // Neuron spent our cell: recipient + change both land on scripts we don't know
        val flagged = isUnknownChangeSignature(
            netChangeShannons = -1_000 * ckb,
            outputs = listOf(
                secpOutput(900, recipientArgs),
                secpOutput(99, siblingArgs),
            ),
            knownLockArgs = setOf(knownArgs),
        )
        assertTrue(flagged)
    }

    @Test
    fun `our own send with change back to us is not flagged`() {
        val flagged = isUnknownChangeSignature(
            netChangeShannons = -900 * ckb,
            outputs = listOf(
                secpOutput(900, recipientArgs),
                secpOutput(99, knownArgs),
            ),
            knownLockArgs = setOf(knownArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `send-max single output is not flagged`() {
        // One output and nothing back is the legitimate send-everything shape
        val flagged = isUnknownChangeSignature(
            netChangeShannons = -1_000 * ckb,
            outputs = listOf(secpOutput(999, recipientArgs)),
            knownLockArgs = setOf(knownArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `incoming transaction is not flagged`() {
        val flagged = isUnknownChangeSignature(
            netChangeShannons = 500 * ckb,
            outputs = listOf(
                secpOutput(500, knownArgs),
                secpOutput(100, siblingArgs),
            ),
            knownLockArgs = setOf(knownArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `self consolidation is not flagged`() {
        val flagged = isUnknownChangeSignature(
            netChangeShannons = 0L,
            outputs = listOf(secpOutput(100, knownArgs)),
            knownLockArgs = setOf(knownArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `dao deposit locked to sibling with sibling change is flagged`() {
        // knmo's Nervos Talk case: deposit cell + change both on the change chain
        val flagged = isUnknownChangeSignature(
            netChangeShannons = -10_300 * ckb,
            outputs = listOf(
                secpOutput(10_200, siblingArgs, type = daoTypeScript),
                secpOutput(99, siblingArgs),
            ),
            knownLockArgs = setOf(knownArgs),
        )
        assertTrue(flagged)
    }

    @Test
    fun `dao deposit with change back to us is not flagged`() {
        val flagged = isUnknownChangeSignature(
            netChangeShannons = -10_201 * ckb,
            outputs = listOf(
                secpOutput(10_200, siblingArgs, type = daoTypeScript),
                secpOutput(99, knownArgs),
            ),
            knownLockArgs = setOf(knownArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `unknown outputs that are all typed have no plausible change leg and are not flagged`() {
        // No plain secp output at all — nothing here can be a change cell
        val flagged = isUnknownChangeSignature(
            netChangeShannons = -10_300 * ckb,
            outputs = listOf(
                secpOutput(10_200, siblingArgs, type = daoTypeScript),
                secpOutput(100, recipientArgs, type = daoTypeScript),
            ),
            knownLockArgs = setOf(knownArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `known-args match is case-insensitive`() {
        val flagged = isUnknownChangeSignature(
            netChangeShannons = -900 * ckb,
            outputs = listOf(
                secpOutput(900, recipientArgs),
                secpOutput(99, knownArgs.uppercase().replaceFirst("0X", "0x")),
            ),
            knownLockArgs = setOf(knownArgs),
        )
        assertFalse(flagged)
    }

    @Test
    fun `non-secp unknown lock does not count as the change leg`() {
        // e.g. omnilock/ACP recipient plus a typed leg — still no plain secp change
        val exoticLock = Script(
            codeHash = "0x9b819793a64463aed77c615d6cb226eea5487ccfc0783043a587254cda2b6f26",
            hashType = "type",
            args = recipientArgs
        )
        val flagged = isUnknownChangeSignature(
            netChangeShannons = -900 * ckb,
            outputs = listOf(
                CellOutput("0x${(900 * ckb).toString(16)}", exoticLock, null),
                secpOutput(100, recipientArgs, type = daoTypeScript),
            ),
            knownLockArgs = setOf(knownArgs),
        )
        assertFalse(flagged)
    }
}
