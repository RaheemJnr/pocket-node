package com.rjnr.pocketnode.core.address

import com.rjnr.pocketnode.core.crypto.toHexString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
