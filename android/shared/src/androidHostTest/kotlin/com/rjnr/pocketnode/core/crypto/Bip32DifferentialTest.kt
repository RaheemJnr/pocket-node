package com.rjnr.pocketnode.core.crypto

import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Differential tests: shared [Bip32] against the BIP-32 code it replaced (#508).
 *
 * The oracle in [LegacyBip32] is the pre-change body of
 * `MnemonicManager.derivePrivateKey` and its private helpers, copied across
 * unchanged apart from one substitution: the HMAC-SHA-512 comes from
 * `javax.crypto.Mac` rather than BouncyCastle's `HMac(SHA512Digest())`, so the
 * test needs no new Gradle dependency. Both compute the same RFC 2104 MAC. The
 * `java.math.BigInteger` arithmetic — the part [Bip32] replaced with
 * `Secp256k1.privKeyTweakAdd` — is verbatim.
 *
 * Every wallet Pocket Node has ever created derives its keys through that old
 * code. A disagreement here means existing users would open the app on a
 * different address with an empty balance, so a failure is never a test bug:
 * do not relax an assertion to make it pass.
 *
 * The random inputs are seeded, so a failure reproduces from the printed
 * iteration index. Assertion messages carry the index and nothing else. Seeds
 * and derived keys are key material and must never reach CI logs or JUnit XML.
 */
class Bip32DifferentialTest {

    private companion object {
        const val RANDOM_SEED = 20260915L

        /** Random 64-byte seeds, each derived at one sampled BIP-44 path. */
        const val ITERATIONS = 2_000

        /** BIP-44 account level sampled over `0..ACCOUNT_MAX`, hardened. */
        const val ACCOUNT_MAX = 3

        /** BIP-44 change level: 0 receiving, 1 change. */
        const val CHAIN_MAX = 1

        /** BIP-44 address level sampled over `0..ADDRESS_MAX`. */
        const val ADDRESS_MAX = 20

        /** Minimum samples demanded of each of the 8 account/chain pairs. */
        const val MIN_SAMPLES_PER_ACCOUNT_CHAIN_PAIR = 100
    }

    @Test
    fun matchesLegacyDerivationOverRandomSeedsAndPaths() {
        val random = Random(RANDOM_SEED)
        val pairCounts = mutableMapOf<Pair<Int, Int>, Int>()

        repeat(ITERATIONS) { iteration ->
            val seed = random.nextBytes(64)
            val account = random.nextInt(0, ACCOUNT_MAX + 1)
            val chain = random.nextInt(0, CHAIN_MAX + 1)
            val address = random.nextInt(0, ADDRESS_MAX + 1)
            pairCounts[account to chain] = (pairCounts[account to chain] ?: 0) + 1

            val expected = LegacyBip32.derivePrivateKey(seed, account, chain, address)
            val actual = Bip32.deriveCkbPrivateKey(seed, account, chain, address)

            assertEquals(32, actual.size, "iteration $iteration: derived key is not 32 bytes")
            assertEquals(
                expected.toHexStringNoPrefix(),
                actual.toHexStringNoPrefix(),
                "iteration $iteration (account=$account chain=$chain address=$address): " +
                    "shared Bip32 disagrees with the legacy implementation",
            )
        }

        for (account in 0..ACCOUNT_MAX) {
            for (chain in 0..CHAIN_MAX) {
                val count = pairCounts[account to chain] ?: 0
                assertTrue(
                    count >= MIN_SAMPLES_PER_ACCOUNT_CHAIN_PAIR,
                    "account=$account chain=$chain sampled only $count times, " +
                        "need at least $MIN_SAMPLES_PER_ACCOUNT_CHAIN_PAIR",
                )
            }
        }
    }

    /**
     * The three constants `Bip32Test.ckbPath_pinsShippedKeys` pins, re-derived
     * here through the legacy implementation.
     *
     * That test claims its expectations came from the pre-change Android code.
     * This is where the claim is checked, so the two files cannot drift into
     * agreeing with each other but not with what shipped.
     */
    @Test
    fun pinnedCkbConstantsComeFromTheLegacyImplementation() {
        val seed = (
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
            ).hexToByteArray()

        assertEquals(
            "3085e9a4de8b62c9a89f6f8ac6c087dc2da76591823ffcd5965a74837fcad68c",
            LegacyBip32.derivePrivateKey(seed, 0, 0, 0).toHexStringNoPrefix(),
        )
        assertEquals(
            "6e67f6184d29488f7c2299d1c58c591f6482140100c017371054c170edf32259",
            LegacyBip32.derivePrivateKey(seed, 0, 1, 0).toHexStringNoPrefix(),
        )
        assertEquals(
            "787cea3b7f3be5932e3ec2f8f6374f8d12dc50c5de046d3dc4093466548b7863",
            LegacyBip32.derivePrivateKey(seed, 1, 0, 5).toHexStringNoPrefix(),
        )
    }

    /** A seed of the wrong length must fail identically on both sides. */
    @Test
    fun rejectsSeedsThatAreNot64BytesTheSameWay() {
        listOf(0, 1, 16, 32, 63, 65, 128).forEach { size ->
            val seed = ByteArray(size)

            val legacy = assertFailsWith<IllegalArgumentException> {
                LegacyBip32.derivePrivateKey(seed, 0, 0, 0)
            }
            val shared = assertFailsWith<IllegalArgumentException> {
                Bip32.deriveCkbPrivateKey(seed, 0, 0, 0)
            }

            assertEquals("Seed must be 64 bytes", legacy.message, "legacy, size=$size")
            assertEquals(legacy.message, shared.message, "shared, size=$size")
            assertEquals(legacy::class, shared::class, "exception type, size=$size")
        }
    }

    /**
     * The pre-#508 BIP-32 implementation, lifted from
     * `com.rjnr.pocketnode.data.wallet.MnemonicManager`.
     *
     * Kept verbatim on purpose, including the `BigInteger` arithmetic and the
     * `System.arraycopy` calls that `commonMain` cannot use. Do not tidy it,
     * do not share helpers with [Bip32]: its whole value is being an
     * independent restatement of what shipped.
     */
    private object LegacyBip32 {

        private val SECP256K1_N = BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
        )

        private class ExtendedKey(
            val key: ByteArray,       // 32 bytes - private key
            val chainCode: ByteArray  // 32 bytes
        )

        fun derivePrivateKey(
            seed: ByteArray,
            accountIndex: Int = 0,
            chainIndex: Int = 0,
            addressIndex: Int = 0
        ): ByteArray {
            require(seed.size == 64) { "Seed must be 64 bytes" }

            var key = deriveMasterKey(seed)
            // m/44' -> m/44'/309' -> m/44'/309'/accountIndex'
            key = deriveHardenedChild(key, 44)
            key = deriveHardenedChild(key, 309)
            key = deriveHardenedChild(key, accountIndex)
            // m/44'/309'/accountIndex'/chainIndex -> .../chainIndex/addressIndex
            key = deriveNormalChild(key, chainIndex)
            key = deriveNormalChild(key, addressIndex)

            return key.key
        }

        private fun deriveMasterKey(seed: ByteArray): ExtendedKey {
            val hmacResult = hmacSha512("Bitcoin seed".toByteArray(), seed)
            return ExtendedKey(
                key = hmacResult.copyOfRange(0, 32),
                chainCode = hmacResult.copyOfRange(32, 64)
            )
        }

        /**
         * Hardened child derivation: data = 0x00 || parentKey || (index + 0x80000000) BE
         */
        private fun deriveHardenedChild(parent: ExtendedKey, index: Int): ExtendedKey {
            val data = ByteArray(37)
            data[0] = 0x00
            System.arraycopy(parent.key, 0, data, 1, 32)
            putBE32(data, 33, index.toLong() + 0x80000000L)
            return computeChildKey(parent, data)
        }

        /**
         * Normal child derivation: data = compressedPubKey || index BE
         */
        private fun deriveNormalChild(parent: ExtendedKey, index: Int): ExtendedKey {
            val compressedPubKey = Secp256k1Signer.publicKey(parent.key) // 33 bytes

            val data = ByteArray(37)
            System.arraycopy(compressedPubKey, 0, data, 0, 33)
            putBE32(data, 33, index.toLong())
            return computeChildKey(parent, data)
        }

        private fun computeChildKey(parent: ExtendedKey, data: ByteArray): ExtendedKey {
            val hmacResult = hmacSha512(parent.chainCode, data)

            val il = BigInteger(1, hmacResult.copyOfRange(0, 32))
            require(il < SECP256K1_N) { "Invalid derived key (>= curve order)" }

            val childKey = il.add(BigInteger(1, parent.key)).mod(SECP256K1_N)
            require(childKey != BigInteger.ZERO) { "Invalid derived key (zero)" }

            return ExtendedKey(
                key = childKey.toByteArray32(),
                chainCode = hmacResult.copyOfRange(32, 64)
            )
        }

        /**
         * BouncyCastle's `HMac(SHA512Digest())` in the original; the JDK's own
         * provider here, so this source set needs no extra dependency. Same
         * RFC 2104 construction, same output.
         */
        private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(key, "HmacSHA512"))
            return mac.doFinal(data)
        }

        private fun putBE32(buf: ByteArray, offset: Int, value: Long) {
            buf[offset] = (value shr 24 and 0xFF).toByte()
            buf[offset + 1] = (value shr 16 and 0xFF).toByte()
            buf[offset + 2] = (value shr 8 and 0xFF).toByte()
            buf[offset + 3] = (value and 0xFF).toByte()
        }

        /** Convert BigInteger to exactly 32 bytes, zero-padded. */
        private fun BigInteger.toByteArray32(): ByteArray {
            val bytes = this.toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> error("Key too large: ${bytes.size} bytes")
            }
        }
    }
}
