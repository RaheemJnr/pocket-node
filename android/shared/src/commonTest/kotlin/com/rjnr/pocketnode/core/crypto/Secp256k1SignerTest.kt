package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Known answers for the secp256k1 primitives (#454). */
class Secp256k1SignerTest {

    private val one = "0x0000000000000000000000000000000000000000000000000000000000000001".hexToByteArray()

    @Test
    fun derivesTheGeneratorPointForPrivateKeyOne() {
        assertEquals(
            "0x0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            Secp256k1Signer.publicKey(one).toHexString(),
        )
    }

    @Test
    fun signRecoverableReturns65BytesWithARecoveryIdTail() {
        val message = Blake2b.digest("pocket-node".encodeToByteArray())
        val signature = Secp256k1Signer.signRecoverable(message, one)
        assertEquals(65, signature.size)
        assertTrue(signature[64].toInt() in 0..3, "recovery id must be 0..3, got ${signature[64]}")
    }

    @Test
    fun signRecoverableIsDeterministic() {
        val message = Blake2b.digest("rfc6979".encodeToByteArray())
        assertEquals(
            Secp256k1Signer.signRecoverable(message, one).toHexString(),
            Secp256k1Signer.signRecoverable(message, one).toHexString(),
        )
    }

    @Test
    fun verifyAcceptsItsOwnSignatureAndRejectsAnotherMessage() {
        val message = Blake2b.digest("verify me".encodeToByteArray())
        val other = Blake2b.digest("verify me too".encodeToByteArray())
        val signature = Secp256k1Signer.signRecoverable(message, one)
        val publicKey = Secp256k1Signer.publicKey(one)
        assertTrue(Secp256k1Signer.verify(signature, message, publicKey))
        assertTrue(Secp256k1Signer.verify(signature.copyOfRange(0, 64), message, publicKey))
        assertFalse(Secp256k1Signer.verify(signature, other, publicKey))
    }

    @Test
    fun sIsLowHalfOfTheCurveOrder() {
        // n/2, the canonical-s boundary the CKB lock script assumes.
        val halfOrder = "0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0".hexToByteArray()
        for (i in 1..16) {
            val message = Blake2b.digest(byteArrayOf(i.toByte()))
            val s = Secp256k1Signer.signRecoverable(message, one).copyOfRange(32, 64)
            assertTrue(compareUnsigned(s, halfOrder) <= 0, "s must be canonical (low), got ${s.toHexString()}")
        }
    }

    @Test
    fun acceptsAnUnpaddedShortKeyAsTheLeftPadded32ByteOne() {
        // The Java SDK took keys as BigInteger, so a legacy record stored without
        // leading zero padding decoded to fewer than 32 bytes and still signed.
        // A 31-byte key must behave exactly like the same key zero-padded to 32.
        val padded =
            "0x0011223344556677889900112233445566778899001122334455667788990011".hexToByteArray()
        val short = padded.copyOfRange(1, 32)
        assertEquals(31, short.size)
        assertEquals(32, padded.size)
        assertContentEquals(Secp256k1Signer.publicKey(padded), Secp256k1Signer.publicKey(short))

        val message = Blake2b.digest("short key".encodeToByteArray())
        assertContentEquals(
            Secp256k1Signer.signRecoverable(message, padded),
            Secp256k1Signer.signRecoverable(message, short),
        )
    }

    @Test
    fun acceptsASingleByteKey() {
        val short = byteArrayOf(1)
        assertContentEquals(Secp256k1Signer.publicKey(one), Secp256k1Signer.publicKey(short))
    }

    @Test
    fun rejectsOutOfRangeKeys() {
        val message = Blake2b.digest("nope".encodeToByteArray())
        val n = "0xfffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141".hexToByteArray()
        val bad = mapOf(
            "empty" to ByteArray(0),
            "33 bytes" to ByteArray(33) { 1 },
            "zero" to ByteArray(32),
            "short zero" to ByteArray(4),
            "curve order" to n,
            "above curve order" to ByteArray(32) { 0xFF.toByte() },
        )
        for ((label, key) in bad) {
            assertFailsWith<IllegalArgumentException>("expected rejection of $label") {
                Secp256k1Signer.publicKey(key)
            }
            assertFailsWith<IllegalArgumentException>("expected rejection of $label") {
                Secp256k1Signer.signRecoverable(message, key)
            }
        }
    }

    @Test
    fun rejectsAMessageThatIsNot32Bytes() {
        assertFailsWith<IllegalArgumentException> {
            Secp256k1Signer.signRecoverable(ByteArray(31), one)
        }
        assertFailsWith<IllegalArgumentException> {
            Secp256k1Signer.signRecoverable(ByteArray(33), one)
        }
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }
}
