package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.core.crypto.Blake2b
import com.rjnr.pocketnode.core.crypto.Secp256k1Signer
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.data.gateway.models.Cell
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.WalletDerivation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Signer seam must not change a single byte of what goes on the wire.
 *
 * Two kinds of test live here, and the second is the one that has teeth. The
 * equivalence tests compare the raw-key entry points against the [Signer] ones,
 * which is cheap coverage but self-referential: the key overloads delegate
 * through [PrivateKeySigner], so both sides would move together if the layout
 * changed. [theSignedWitnessIsTheCkbWitnessArgsLayoutOverTheCkbSigningMessage]
 * rebuilds the expected witness independently of the builder and pins it.
 *
 * The key is a throwaway test vector, not anyone's wallet.
 */
class SignerEquivalenceTest {

    private val builder = TransactionBuilder(NetworkValidator())

    @Test
    fun buildTransferThroughSignerIsByteIdenticalToRawKey() {
        val viaKey = builder.buildTransfer(
            fromAddress = FROM_ADDRESS,
            toAddress = TO_ADDRESS,
            amountShannons = 100_00000000L,
            availableCells = CELLS,
            privateKey = TEST_KEY.copyOf(),
            network = NetworkType.TESTNET,
        )
        val viaSigner = builder.buildTransfer(
            fromAddress = FROM_ADDRESS,
            toAddress = TO_ADDRESS,
            amountShannons = 100_00000000L,
            availableCells = CELLS,
            signer = PrivateKeySigner(TEST_KEY.copyOf()),
            network = NetworkType.TESTNET,
        )

        assertEquals(viaKey, viaSigner)
        assertEquals(viaKey.witnesses, viaSigner.witnesses)
        assertEquals(builder.computeTxHash(viaKey), builder.computeTxHash(viaSigner))
        // Guard against an "equal because both are empty" pass.
        assertTrue(viaSigner.witnesses.first().length > 2)
    }

    @Test
    fun buildMultiTransferThroughSignerIsByteIdenticalToRawKey() {
        val recipients = listOf(
            RecipientOutput(TO_ADDRESS, 100_00000000L),
            RecipientOutput(FROM_ADDRESS, 70_00000000L),
        )
        val viaKey = builder.buildMultiTransfer(
            fromAddress = FROM_ADDRESS,
            recipients = recipients,
            availableCells = CELLS,
            privateKey = TEST_KEY.copyOf(),
            network = NetworkType.TESTNET,
        )
        val viaSigner = builder.buildMultiTransfer(
            fromAddress = FROM_ADDRESS,
            recipients = recipients,
            availableCells = CELLS,
            signer = PrivateKeySigner(TEST_KEY.copyOf()),
            network = NetworkType.TESTNET,
        )

        assertEquals(viaKey, viaSigner)
        assertEquals(builder.computeTxHash(viaKey), builder.computeTxHash(viaSigner))
    }

    /**
     * The equivalence tests above compare the seam against itself: the raw-key
     * overloads delegate through [PrivateKeySigner], so they would still pass
     * if the witness layout changed on both sides at once.
     *
     * This one does not. It rebuilds the witness from first principles —
     * the CKB signing message over the raw tx hash, a raw
     * [Secp256k1Signer.signRecoverable], and the WitnessArgs bytes spelled out
     * here rather than taken from the builder — and demands the shipped witness
     * match it byte for byte.
     */
    @Test
    fun theSignedWitnessIsTheCkbWitnessArgsLayoutOverTheCkbSigningMessage() {
        val tx = builder.buildTransfer(
            fromAddress = FROM_ADDRESS,
            toAddress = TO_ADDRESS,
            amountShannons = 100_00000000L,
            availableCells = CELLS,
            signer = PrivateKeySigner(TEST_KEY.copyOf()),
            network = NetworkType.TESTNET,
        )
        // Single input, so the signing message carries no trailing zero-length
        // witnesses. computeTxHash covers the RAW transaction, which excludes
        // witnesses, so signing cannot change it.
        assertEquals(1, tx.cellInputs.size)

        val emptyWitnessArgs = (WITNESS_ARGS_PREFIX + "00".repeat(65)).hexToByteArray()
        assertEquals(WITNESS_ARGS_SIZE, emptyWitnessArgs.size)

        val message = Blake2b()
            .update(builder.computeTxHash(tx).hexToByteArray())
            .update(uint64Le(WITNESS_ARGS_SIZE.toLong()))
            .update(emptyWitnessArgs)
            .doFinal()
        val signature = Secp256k1Signer.signRecoverable(message, TEST_KEY.copyOf())
        assertEquals(65, signature.size)

        assertEquals(
            "0x" + WITNESS_ARGS_PREFIX + signature.toHexStringNoPrefix(),
            tx.witnesses.single(),
        )
    }

    @Test
    fun privateKeySignerReportsTheLockArgsItSignsFor() {
        val signer = PrivateKeySigner(TEST_KEY.copyOf())
        val expected = WalletDerivation.lockScript(WalletDerivation.publicKey(TEST_KEY)).args
        assertEquals(expected, signer.lockArgs())
        assertEquals(FROM_SCRIPT.args, signer.lockArgs())
    }

    @Test
    fun closeZeroesTheKeyAndTheSignerThenRefusesToSign() {
        val key = TEST_KEY.copyOf()
        val signer = PrivateKeySigner(key)
        assertEquals(65, signer.signRecoverable(MESSAGE).size)

        signer.close()

        assertTrue(key.all { it == 0.toByte() }, "close() must zero-fill the key")
        // The zero scalar is not a valid secp256k1 private key, so the library's
        // key check rejects it. A closed signer therefore fails loudly rather
        // than emitting a signature over a degenerate key.
        assertFailsWith<IllegalArgumentException> { signer.signRecoverable(MESSAGE) }
    }

    private companion object {
        /**
         * The all-`0x01` secp256k1 scalar: a public test vector, not a wallet
         * key. Nothing is ever funded at the address it derives.
         */
        val TEST_KEY = ByteArray(32) { 1 }

        val MESSAGE = ByteArray(32) { (it + 7).toByte() }

        /**
         * A `WitnessArgs` carrying a 65-byte lock and no input_type or
         * output_type, as CKB molecule lays it out, spelled out here rather
         * than taken from the builder so this test is an independent oracle.
         *
         * Table header is `full_size` plus one offset per field (4 + 3 * 4 =
         * 16). `lock` is a Bytes: a 4-byte length then 65 bytes, so 69. Both
         * absent options serialize to nothing, which is why the last two
         * offsets equal `full_size`. Total 16 + 69 = 85.
         */
        const val WITNESS_ARGS_SIZE = 85

        /** [WITNESS_ARGS_SIZE] bytes minus the 65-byte signature: header + lock length. */
        const val WITNESS_ARGS_PREFIX =
            "55000000" + // full_size = 85
                "10000000" + // offset[lock] = 16
                "55000000" + // offset[input_type] = 85 (absent)
                "55000000" + // offset[output_type] = 85 (absent)
                "41000000"   // lock Bytes length = 65

        /** Little-endian `uint64`, the width CKB prefixes each witness with when signing. */
        fun uint64Le(value: Long): ByteArray =
            ByteArray(8) { ((value shr (it * 8)) and 0xFF).toByte() }

        val FROM_SCRIPT: Script =
            WalletDerivation.lockScript(WalletDerivation.publicKey(TEST_KEY))

        val TO_SCRIPT = Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = "0x" + "bb".repeat(20),
        )

        val FROM_ADDRESS: String = AddressUtils.encode(FROM_SCRIPT, NetworkType.TESTNET)
        val TO_ADDRESS: String = AddressUtils.encode(TO_SCRIPT, NetworkType.TESTNET)

        val CELLS: List<Cell> = listOf(
            Cell(
                outPoint = OutPoint(txHash = "0x" + "11".repeat(32), index = "0x0"),
                capacity = "0x${400_00000000L.toString(16)}",
                blockNumber = "0x100",
                lock = FROM_SCRIPT,
                type = null,
                data = "0x",
            ),
            Cell(
                outPoint = OutPoint(txHash = "0x" + "22".repeat(32), index = "0x1"),
                capacity = "0x${300_00000000L.toString(16)}",
                blockNumber = "0x101",
                lock = FROM_SCRIPT,
                type = null,
                data = "0x",
            ),
        )
    }
}
