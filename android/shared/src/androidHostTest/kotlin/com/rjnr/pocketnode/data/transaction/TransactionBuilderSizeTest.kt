package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
import org.junit.Assert.*
import org.junit.Test

class TransactionBuilderSizeTest {
    private val builder = TransactionBuilder(NetworkValidator())
    private val network = NetworkType.TESTNET
    private val lock = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20))
    private val from = AddressUtils.encode(lock, network)
    private val key = ByteArray(32) { 0x11 }
    private val cap = 570_000
    private val ckb = 100_000_000L
    private fun cells() = listOf(Cell(OutPoint("0x" + "11".repeat(32), "0x0"),
        "0x${(2_000_000L * ckb).toString(16)}", "0x1", lock, data = "0x"))
    private fun ordinary() = builder.prepareMultiTransfer(from,
        listOf(RecipientOutput(from, 100 * ckb)), cells(), network).transaction
    private fun signed(tx: Transaction) = builder.signTransaction(tx, key, tx.cellInputs.size)
    private fun sized(target: Int): Transaction {
        val tx = ordinary()
        val padding = target - builder.serializedSizeWithWitnesses(signed(tx))
        return tx.copy(outputsData = listOf("0x" + "ab".repeat(padding), "0x"))
    }
    private fun assertTooLarge(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("Oversized transaction must be refused", failure)
        assertTrue("Expected size rejection, got $failure", failure!!.message.orEmpty().contains("too large"))
    }
    @Test fun `signed transaction accepts just under and exactly at wallet byte cap`() {
        for (bytes in listOf(cap - 1, cap)) {
            assertEquals(bytes, builder.serializedSizeWithWitnesses(signed(sized(bytes))))
        }
    }
    @Test fun `signed transaction refuses one byte over wallet byte cap`() {
        assertTooLarge { signed(sized(cap + 1)) }
    }
    @Test fun `actual witness input type payload counts toward signed byte cap`() {
        val tx = ordinary()
        val empty = builder.signTransaction(tx, key, 1, ByteArray(0))
        val overhead = builder.serializedSizeWithWitnesses(empty) + 4
        val accepted = builder.signTransaction(tx, key, 1, ByteArray(cap - overhead))
        assertEquals(cap, builder.serializedSizeWithWitnesses(accepted))
        assertTooLarge { builder.signTransaction(tx, key, 1, ByteArray(cap - overhead + 1)) }
    }
    private fun recipients(count: Int, argsBytes: Int) = List(count) {
        RecipientOutput(AddressUtils.encode(lock.copy(codeHash = "0x" + "42".repeat(32), args = "0x" + "bb".repeat(argsBytes)), network),
            (argsBytes + 100L) * ckb)
    }
    @Test fun `normal bulk and max reject oversized custom output bytes before signing`() {
        for (count in listOf(1, 2)) {
            assertTooLarge { builder.prepareMultiTransfer(from, recipients(count, cap / count), cells(), network) }
        }
        val address = recipients(1, cap).single().address
        val amount = builder.calculateMaxSendable(cells(), address)
        assertTooLarge { builder.prepareMultiTransfer(from, listOf(RecipientOutput(address, amount)), cells(), network) }
    }
    @Test fun `ordinary normal bulk and max remain signable`() {
        for (outputs in listOf(recipients(1, 20), recipients(2, 20),
            listOf(RecipientOutput(from, builder.calculateMaxSendable(cells(), from))))) {
            val tx = builder.buildMultiTransfer(from, outputs, cells(), key, network)
            assertTrue(builder.serializedSizeWithWitnesses(tx) < cap)
        }
    }
    @Test fun `normal bulk and max preparation enforce exact signed byte boundaries`() {
        for (mode in listOf("normal", "bulk", "max")) {
            val baseOutputs = recipients(if (mode == "bulk") 2 else 1, 20)
            fun outputs(argsBytes: Int): List<RecipientOutput> {
                val grown = recipients(1, argsBytes).single()
                return if (mode == "max") listOf(grown.copy(amountShannons =
                    builder.calculateMaxSendable(cells(), grown.address)))
                else listOf(grown) + baseOutputs.drop(1)
            }
            val base = builder.prepareMultiTransfer(from, outputs(20), cells(), network)
            val baseSize = builder.serializedSizeWithWitnesses(signed(base.transaction))
            for (target in listOf(cap - 1, cap, cap + 1)) {
                val requested = outputs(20 + target - baseSize)
                if (target > cap) {
                    assertTooLarge { builder.prepareMultiTransfer(from, requested, cells(), network) }
                } else {
                    val tx = builder.buildMultiTransfer(from, requested, cells(), key, network)
                    assertEquals("$mode signed in-block bytes", target, builder.serializedSizeWithWitnesses(tx))
                }
            }
        }
    }
    @Test fun `final signer witness payload is measured after signing`() {
        val tx = ordinary()
        // The expected 65-byte signature fits. The supplied result does not.
        val signer = object : Signer {
            override fun signRecoverable(messageHash: ByteArray) = ByteArray(cap)
        }
        assertTooLarge { builder.signTransaction(tx, signer, 1) }
    }
    @Test fun `wire size includes molecule wrappers witness bytes and block offset`() {
        // Raw: 77 + 48 inputs + 206 outputs + 20 data = 351.
        // Witness dynvec: 4 total + 4 offset + 4 length + 85 WitnessArgs = 97.
        // Transaction table 12 and block offset 4 -> 464.
        assertEquals(464, builder.serializedSizeWithWitnesses(signed(ordinary())))
    }
    @Test fun `sweep counts a signature for every lock group`() {
        val inputs = List(4400) { SweepInput(OutPoint("0x" + "11".repeat(32), "0x${it.toString(16)}"),
            100 * ckb, "0x${it.toString(16).padStart(40, '0')}") }
        assertTooLarge { builder.buildSweep(inputs, lock, network).getOrThrow() }
    }
}
