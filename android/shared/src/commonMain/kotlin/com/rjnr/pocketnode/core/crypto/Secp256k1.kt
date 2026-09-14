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
        val key = normalizeKey(privateKey)
        return Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(key))
    }

    /**
     * Signs an already-hashed 32-byte [message32] and returns `r || s || recId`
     * (65 bytes). [message32] is signed as-is — this function does no hashing.
     */
    fun signRecoverable(message32: ByteArray, privateKey: ByteArray): ByteArray {
        require(message32.size == MESSAGE_SIZE) {
            "Message must be $MESSAGE_SIZE bytes, got ${message32.size}"
        }
        val key = normalizeKey(privateKey)
        val compact = Secp256k1.sign(message32, key)
        val expected = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(key))
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

    /**
     * Left-pads [privateKey] to 32 bytes and range-checks it.
     *
     * The CKB Java SDK took keys as `BigInteger`, so a legacy record whose hex
     * was stored without leading zero padding (a key numerically below 2^248,
     * roughly 1 in 256) decoded to fewer than 32 bytes and still signed. Keep
     * accepting those rather than locking their owners out; libsecp256k1 needs
     * a fixed-width scalar, so pad on the left, which is what `BigInteger`
     * did implicitly.
     *
     * @throws IllegalArgumentException if the key is empty, longer than 32
     *   bytes, zero, or not below the curve order.
     */
    private fun normalizeKey(privateKey: ByteArray): ByteArray {
        require(privateKey.isNotEmpty() && privateKey.size <= PRIVATE_KEY_SIZE) {
            "Private key must be 1..$PRIVATE_KEY_SIZE bytes, got ${privateKey.size}"
        }
        val key = if (privateKey.size == PRIVATE_KEY_SIZE) {
            privateKey
        } else {
            ByteArray(PRIVATE_KEY_SIZE).also {
                privateKey.copyInto(it, PRIVATE_KEY_SIZE - privateKey.size)
            }
        }
        // libsecp256k1's own check: rejects zero and anything >= the group order.
        require(Secp256k1.secKeyVerify(key)) { "Private key is not a valid secp256k1 scalar" }
        return key
    }
}
