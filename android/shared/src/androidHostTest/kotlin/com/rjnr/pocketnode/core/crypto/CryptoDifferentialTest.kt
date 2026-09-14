package com.rjnr.pocketnode.core.crypto

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.nervos.ckb.crypto.secp256k1.ECKeyPair
import org.nervos.ckb.crypto.secp256k1.Sign
import org.nervos.ckb.utils.Numeric
import org.nervos.ckb.crypto.Blake2b as SdkBlake2b

/**
 * Differential tests: every shared crypto primitive against the CKB Java SDK it
 * replaced (#454).
 *
 * The SDK is a TEST-ONLY dependency of this source set — it must never appear on
 * a production classpath again. A disagreement here is a signing bug, not a test
 * bug: do not relax an assertion to make it pass.
 */
class CryptoDifferentialTest {

    private companion object {
        const val SEED = 20260914L
        const val ITERATIONS = 10_000

        /** secp256k1 group order. */
        val CURVE_N: BigInteger = BigInteger(
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16,
        )
    }

    /** A uniformly random private key in [1, n-1]. */
    private fun Random.privateKey(): ByteArray {
        while (true) {
            val candidate = nextBytes(32)
            val value = BigInteger(1, candidate)
            if (value.signum() > 0 && value < CURVE_N) return candidate
        }
    }

    @Test
    fun blake2bMatchesTheSdkOverRandomInput() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val input = random.nextBytes(random.nextInt(0, 301))
            assertEquals(
                Numeric.toHexString(SdkBlake2b.digest(input)),
                Blake2b.digest(input).toHexString(),
                "blake2b mismatch at iteration $i for input ${Numeric.toHexString(input)}",
            )
        }
    }

    @Test
    fun blake2bIncrementalMatchesTheSdkOverRandomInput() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val a = random.nextBytes(random.nextInt(0, 101))
            val b = random.nextBytes(random.nextInt(0, 101))
            val sdk = SdkBlake2b()
            sdk.update(a)
            sdk.update(b)
            assertEquals(
                Numeric.toHexString(sdk.doFinal()),
                Blake2b().update(a).update(b).doFinal().toHexString(),
                "incremental blake2b mismatch at iteration $i",
            )
        }
    }

    @Test
    fun publicKeyMatchesTheSdkOverRandomKeys() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val key = random.privateKey()
            val sdk = ECKeyPair.create(BigInteger(1, key)).getEncodedPublicKey(true)
            assertEquals(
                Numeric.toHexString(sdk),
                Secp256k1Signer.publicKey(key).toHexString(),
                "public key mismatch at iteration $i for key ${Numeric.toHexString(key)}",
            )
        }
    }

    @Test
    fun signRecoverableMatchesTheSdkByteForByte() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val key = random.privateKey()
            val message = random.nextBytes(32)
            val sdk = Sign.signMessage(message, ECKeyPair.create(BigInteger(1, key))).signature
            val ours = Secp256k1Signer.signRecoverable(message, key)
            assertEquals(65, ours.size)
            assertEquals(
                Numeric.toHexString(sdk),
                ours.toHexString(),
                "signature mismatch at iteration $i for key ${Numeric.toHexString(key)} " +
                    "message ${Numeric.toHexString(message)}",
            )
        }
    }

    @Test
    fun verifyAcceptsSdkProducedSignatures() {
        val random = Random(SEED)
        repeat(1_000) { i ->
            val key = random.privateKey()
            val message = random.nextBytes(32)
            val sdk = Sign.signMessage(message, ECKeyPair.create(BigInteger(1, key))).signature
            assertTrue(
                Secp256k1Signer.verify(sdk, message, Secp256k1Signer.publicKey(key)),
                "verify rejected an SDK signature at iteration $i",
            )
        }
    }

    @Test
    fun hexEncodingMatchesTheSdkOverRandomInput() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val input = random.nextBytes(random.nextInt(0, 129))
            assertEquals(
                Numeric.toHexString(input),
                input.toHexString(),
                "hex encode mismatch at iteration $i",
            )
        }
    }

    @Test
    fun hexDecodingMatchesTheSdkOverRandomInput() {
        val random = Random(SEED)
        repeat(ITERATIONS) { i ->
            val input = random.nextBytes(random.nextInt(0, 129))
            // Both spellings the SDK accepts, plus the odd-length and uppercase quirks.
            val prefixed = Numeric.toHexString(input)
            val bare = prefixed.removePrefix("0x")
            val upper = "0X" + bare.uppercase()
            val odd = bare.drop(1)
            for (spelling in listOf(prefixed, bare, upper, odd)) {
                assertEquals(
                    Numeric.toHexString(Numeric.hexStringToByteArray(spelling)),
                    spelling.hexToByteArray().toHexString(),
                    "hex decode mismatch at iteration $i for '$spelling'",
                )
            }
        }
    }
}
