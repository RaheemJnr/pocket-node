package com.rjnr.pocketnode.core.crypto

import fr.acinq.secp256k1.Secp256k1

/**
 * secp256k1 signing for the shared KMP module, on libsecp256k1 via secp256k1-kmp.
 *
 * Replaces `org.nervos.ckb.crypto.secp256k1.{ECKeyPair, Sign}` (BouncyCastle),
 * which is JVM-only (#454). The output is byte-for-byte what the SDK produced:
 *
 *  - deterministic nonce per RFC 6979 (HMAC-SHA256), so `r` is reproducible;
 *  - `s` normalised to the low half of the curve order;
 *  - 65-byte recoverable form laid out as `r[32] || s[32] || recId[1]`, where
 *    `recId` is the bare recovery id `0..3` — no 27 or 31 offset. That is what
 *    `Sign.SignatureData.getSignature()` writes and what the CKB
 *    secp256k1_blake160_sighash_all lock script expects in the WitnessArgs lock
 *    field. Proved against the SDK by Secp256k1DifferentialTest.
 */
object Secp256k1Signer {

    private const val PRIVATE_KEY_SIZE = 32
    private const val MESSAGE_SIZE = 32

    /** The 33-byte compressed public key for [privateKey]. */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "Private key must be $PRIVATE_KEY_SIZE bytes, got ${privateKey.size}"
        }
        return Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(privateKey))
    }

    /**
     * Signs an already-hashed 32-byte [message32] and returns `r || s || recId`
     * (65 bytes). [message32] is signed as-is — this function does no hashing.
     */
    fun signRecoverable(message32: ByteArray, privateKey: ByteArray): ByteArray {
        require(message32.size == MESSAGE_SIZE) {
            "Message must be $MESSAGE_SIZE bytes, got ${message32.size}"
        }
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "Private key must be $PRIVATE_KEY_SIZE bytes, got ${privateKey.size}"
        }
        val compact = Secp256k1.sign(message32, privateKey)
        val expected = publicKey(privateKey)
        var recId = -1
        for (candidate in 0..3) {
            val recovered = try {
                Secp256k1.pubKeyCompress(Secp256k1.ecdsaRecover(compact, message32, candidate))
            } catch (e: Exception) {
                null
            }
            if (recovered != null && recovered.contentEquals(expected)) {
                recId = candidate
                break
            }
        }
        check(recId >= 0) { "Could not construct a recoverable key. This should never happen." }

        val signature = ByteArray(65)
        compact.copyInto(signature, 0, 0, 64)
        signature[64] = recId.toByte()
        return signature
    }

    /**
     * Verifies [signature] — 64-byte `r || s`, or the 65-byte recoverable form
     * whose trailing recovery id is ignored — over [message32] for [publicKey].
     */
    fun verify(signature: ByteArray, message32: ByteArray, publicKey: ByteArray): Boolean {
        val compact = when (signature.size) {
            64 -> signature
            65 -> signature.copyOfRange(0, 64)
            else -> return false
        }
        return try {
            Secp256k1.verify(compact, message32, publicKey)
        } catch (e: Exception) {
            false
        }
    }
}
