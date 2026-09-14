package com.rjnr.pocketnode.core.address

import com.rjnr.pocketnode.core.crypto.toHexString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.nervos.ckb.Network
import org.nervos.ckb.type.Script
import org.nervos.ckb.utils.Numeric
import org.nervos.ckb.utils.address.Address

/**
 * Differential tests: the shared CKB address codec against the Java SDK it
 * replaced (#454).
 *
 * The SDK is a TEST-ONLY dependency of this source set. A disagreement here is
 * an address-derivation bug, not a test bug: do not relax an assertion to make
 * it pass.
 */
class AddressDifferentialTest {

    private companion object {
        const val SEED = 20260914L
        const val ITERATIONS = 10_000
    }

    private val secp256k1CodeHash =
        Numeric.hexStringToByteArray("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8")

    private fun hrpOf(network: Network) =
        if (network == Network.MAINNET) CkbAddress.HRP_MAINNET else CkbAddress.HRP_TESTNET

    @Test
    fun encodeFullMatchesTheSdkOnBothNetworks() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val args = random.nextBytes(20)
            for (network in listOf(Network.MAINNET, Network.TESTNET)) {
                val sdk = Address(Script(secp256k1CodeHash, args, Script.HashType.TYPE), network).encode()
                assertEquals(
                    sdk,
                    CkbAddress.encodeFull(
                        hrp = hrpOf(network),
                        codeHash = secp256k1CodeHash,
                        hashType = CkbAddress.HASH_TYPE_TYPE,
                        args = args,
                    ),
                    "full address mismatch at iteration $i for args ${Numeric.toHexString(args)} on $network",
                )
            }
        }
    }

    @Test
    fun encodeFullMatchesTheSdkForEveryHashType() {
        val random = Random(SEED)
        val hashTypes = listOf(
            Script.HashType.DATA to CkbAddress.HASH_TYPE_DATA,
            Script.HashType.TYPE to CkbAddress.HASH_TYPE_TYPE,
            Script.HashType.DATA1 to CkbAddress.HASH_TYPE_DATA1,
            Script.HashType.DATA2 to CkbAddress.HASH_TYPE_DATA2,
        )
        repeat(1_000) { i ->
            val codeHash = random.nextBytes(32)
            // Exercise a non-20-byte args length too — full addresses allow any.
            val args = random.nextBytes(if (i % 3 == 0) 32 else 20)
            for ((sdkHashType, ourHashType) in hashTypes) {
                val sdk = Address(Script(codeHash, args, sdkHashType), Network.MAINNET).encode()
                assertEquals(
                    sdk,
                    CkbAddress.encodeFull(CkbAddress.HRP_MAINNET, codeHash, ourHashType, args),
                    "full address mismatch at iteration $i for $sdkHashType",
                )
            }
        }
    }

    @Test
    fun decodeRecoversTheSameScriptFromSdkFullAddresses() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val args = random.nextBytes(20)
            for (network in listOf(Network.MAINNET, Network.TESTNET)) {
                val encoded = Address(Script(secp256k1CodeHash, args, Script.HashType.TYPE), network).encode()
                val decoded = CkbAddress.decode(encoded)
                assertEquals(hrpOf(network), decoded.hrp, "hrp mismatch at iteration $i")
                assertEquals(
                    Numeric.toHexString(secp256k1CodeHash),
                    decoded.codeHash.toHexString(),
                    "code hash mismatch at iteration $i",
                )
                assertEquals(CkbAddress.HASH_TYPE_TYPE, decoded.hashType, "hash type mismatch at iteration $i")
                assertEquals(
                    Numeric.toHexString(args),
                    decoded.args.toHexString(),
                    "args mismatch at iteration $i",
                )
            }
        }
    }

    @Test
    fun decodeRecoversTheSameScriptFromSdkShortAddresses() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val args = random.nextBytes(20)
            for (network in listOf(Network.MAINNET, Network.TESTNET)) {
                @Suppress("DEPRECATION")
                val encoded =
                    Address(Script(secp256k1CodeHash, args, Script.HashType.TYPE), network).encodeShort()
                val sdkScript = Address.decode(encoded).script
                val decoded = CkbAddress.decode(encoded)
                assertEquals(hrpOf(network), decoded.hrp, "hrp mismatch at iteration $i")
                assertEquals(
                    Numeric.toHexString(sdkScript.codeHash),
                    decoded.codeHash.toHexString(),
                    "short address code hash mismatch at iteration $i",
                )
                assertEquals(CkbAddress.HASH_TYPE_TYPE, decoded.hashType, "hash type mismatch at iteration $i")
                assertEquals(
                    Numeric.toHexString(sdkScript.args),
                    decoded.args.toHexString(),
                    "short address args mismatch at iteration $i",
                )
            }
        }
    }

    @Test
    fun decodeRecoversTheSameScriptFromSdkDeprecatedFullAddresses() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val codeHash = random.nextBytes(32)
            val args = random.nextBytes(20)
            val cases = listOf(
                Script.HashType.TYPE to CkbAddress.HASH_TYPE_TYPE,
                Script.HashType.DATA to CkbAddress.HASH_TYPE_DATA,
            )
            for ((sdkHashType, ourHashType) in cases) {
                @Suppress("DEPRECATION")
                val encoded =
                    Address(Script(codeHash, args, sdkHashType), Network.MAINNET).encodeFullBech32()
                val decoded = CkbAddress.decode(encoded)
                assertEquals(
                    Numeric.toHexString(codeHash),
                    decoded.codeHash.toHexString(),
                    "deprecated full code hash mismatch at iteration $i",
                )
                assertEquals(ourHashType, decoded.hashType, "deprecated full hash type mismatch at iteration $i")
                assertEquals(
                    Numeric.toHexString(args),
                    decoded.args.toHexString(),
                    "deprecated full args mismatch at iteration $i",
                )
            }
        }
    }

    /** Re-encodes an arbitrary payload so the checksum is valid and only the payload is wrong. */
    private fun addressOf(
        payload: ByteArray,
        encoding: Bech32Encoding = Bech32Encoding.BECH32M,
        hrp: String = CkbAddress.HRP_MAINNET,
    ): String = Bech32m.encode(encoding, hrp, Bech32m.convertBits(payload, 8, 5, pad = true))

    @Test
    fun structuralNegativesAreRejectedByBothDecoders() {
        val codeHash = secp256k1CodeHash
        val args = Numeric.hexStringToByteArray("0xb39bbc0b3673c7d36450bc14cfcdad2d559c6c64")
        val cases = linkedMapOf(
            "unknown format byte 0x03" to
                addressOf(byteArrayOf(0x03) + codeHash + byteArrayOf(0x01) + args),
            "unknown format byte 0x05" to
                addressOf(byteArrayOf(0x05) + codeHash + byteArrayOf(0x01) + args),
            "unknown hash type 0x03" to
                addressOf(byteArrayOf(0x00) + codeHash + byteArrayOf(0x03) + args),
            "unknown hash type 0xff" to
                addressOf(byteArrayOf(0x00) + codeHash + byteArrayOf(0xFF.toByte()) + args),
            "short secp256k1 args 19 bytes" to
                addressOf(byteArrayOf(0x01, 0x00) + ByteArray(19) { 0x11 }, Bech32Encoding.BECH32),
            "short secp256k1 args 21 bytes" to
                addressOf(byteArrayOf(0x01, 0x00) + ByteArray(21) { 0x11 }, Bech32Encoding.BECH32),
            "short multisig args 19 bytes" to
                addressOf(byteArrayOf(0x01, 0x01) + ByteArray(19) { 0x11 }, Bech32Encoding.BECH32),
            "short acp args 23 bytes" to
                addressOf(byteArrayOf(0x01, 0x02) + ByteArray(23) { 0x11 }, Bech32Encoding.BECH32),
            "unknown short code hash index 0x03" to
                addressOf(byteArrayOf(0x01, 0x03) + args, Bech32Encoding.BECH32),
            "full payload in bech32 not bech32m" to
                addressOf(byteArrayOf(0x00) + codeHash + byteArrayOf(0x01) + args, Bech32Encoding.BECH32),
            "deprecated full payload in bech32m not bech32" to
                addressOf(byteArrayOf(0x04) + codeHash + args, Bech32Encoding.BECH32M),
            "foreign hrp" to
                addressOf(byteArrayOf(0x00) + codeHash + byteArrayOf(0x01) + args, hrp = "bc"),
        )
        for ((label, address) in cases) {
            assertEquals(
                false,
                runCatching { Address.decode(address) }.isSuccess,
                "the SDK accepted '$label'; this fixture no longer tests what it claims",
            )
            assertEquals(
                false,
                runCatching { CkbAddress.decode(address) }.isSuccess,
                "we accepted '$label'",
            )
        }
    }

    @Test
    fun truncatedPayloadsAreRejectedByBoth_butOnlyWeRejectThemOnPurpose() {
        // Both decoders reject these, so the outcome is parity and asserted as
        // such. HOW they reject is not, and it is why our decoder length-checks
        // explicitly instead of copying the SDK's control flow: `Address` reads
        // the code hash with `Arrays.copyOfRange(payload, 1, 33)`, which
        // zero-fills past the end of a short payload rather than failing. What
        // actually stops a truncated address there is the unchecked `payload[33]`
        // one line later, throwing ArrayIndexOutOfBoundsException — an accident
        // of the reading order, not a rule, and not the AddressFormatException a
        // caller would catch. Verified: all five inputs below come back
        // AIOOBE/IllegalArgumentException from the SDK.
        val codeHash = secp256k1CodeHash
        val truncated = listOf(
            "code hash cut to 20 bytes" to
                addressOf(byteArrayOf(0x00) + codeHash.copyOfRange(0, 20)),
            "no hash type byte" to addressOf(byteArrayOf(0x00) + codeHash),
            "header only" to addressOf(byteArrayOf(0x00)),
            "deprecated full, code hash cut to 20 bytes" to
                addressOf(byteArrayOf(0x04) + codeHash.copyOfRange(0, 20), Bech32Encoding.BECH32),
            "short address header only" to
                addressOf(byteArrayOf(0x01), Bech32Encoding.BECH32),
        )
        for ((label, address) in truncated) {
            assertEquals(
                false,
                runCatching { Address.decode(address) }.isSuccess,
                "the SDK accepted truncated payload '$label'",
            )
            val ours = runCatching { CkbAddress.decode(address) }
            assertEquals(false, ours.isSuccess, "we accepted truncated payload '$label'")
            assertTrue(
                ours.exceptionOrNull() is AddressFormatException,
                "'$label' must fail as AddressFormatException, got " +
                    "${ours.exceptionOrNull()?.let { it::class.simpleName }}",
            )
        }
    }

    @Test
    fun bothDecodersAcceptAnAllUppercaseAddressAndRejectMixedCase() {
        val args = Numeric.hexStringToByteArray("0xb39bbc0b3673c7d36450bc14cfcdad2d559c6c64")
        val valid = Address(Script(secp256k1CodeHash, args, Script.HashType.TYPE), Network.MAINNET).encode()

        val upper = valid.uppercase()
        assertEquals(
            Numeric.toHexString(Address.decode(upper).script.args),
            CkbAddress.decode(upper).args.toHexString(),
            "uppercase address must decode to the same args in both",
        )

        val mixed = "CKB1" + valid.substring(4)
        assertEquals(
            runCatching { Address.decode(mixed) }.isSuccess,
            runCatching { CkbAddress.decode(mixed) }.isSuccess,
            "mixed case acceptance must match",
        )
        assertEquals(false, runCatching { CkbAddress.decode(mixed) }.isSuccess, "we accepted mixed case")
    }

    @Test
    fun decodeRejectsWhateverTheSdkRejects() {
        val random = Random(SEED)
        repeat(1_000) { i ->
            val args = random.nextBytes(20)
            val valid =
                Address(Script(secp256k1CodeHash, args, Script.HashType.TYPE), Network.MAINNET).encode()
            // Flip one payload character; both codecs must reject the result.
            val index = 5 + random.nextInt(valid.length - 6)
            val replacement = if (valid[index] == 'q') 'p' else 'q'
            val corrupted = valid.substring(0, index) + replacement + valid.substring(index + 1)

            val sdkAccepted = runCatching { Address.decode(corrupted) }.isSuccess
            val oursAccepted = runCatching { CkbAddress.decode(corrupted) }.isSuccess
            assertEquals(sdkAccepted, oursAccepted, "acceptance mismatch at iteration $i for '$corrupted'")
        }
    }
}
