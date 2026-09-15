package com.rjnr.pocketnode.core.crypto

/**
 * PBKDF2 (RFC 8018 section 5.2) with HMAC-SHA-512 as the pseudorandom function.
 *
 * Exists for BIP-39 seed derivation, which is a fixed 2048-round, 64-byte
 * PBKDF2-HMAC-SHA512 (#507). It is NOT a general password hash: 2048 rounds is
 * what BIP-39 specifies, not what a PIN or passphrase KDF should use. The PIN
 * path stays on Argon2id.
 */
object Pbkdf2 {

    /**
     * Derives [keyLengthBytes] bytes from [password] and [salt].
     *
     * @param iterations round count, must be positive.
     * @param keyLengthBytes output length, must be positive.
     */
    fun hmacSha512(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(keyLengthBytes > 0) { "keyLengthBytes must be positive" }

        val hLen = HmacSha512.MAC_LENGTH
        val blocks = (keyLengthBytes + hLen - 1) / hLen
        val prf = HmacSha512.keyed(password)
        val out = ByteArray(keyLengthBytes)

        // Block i is F(P, S, c, i) = U_1 xor U_2 xor ... xor U_c, where
        // U_1 = PRF(P, S || INT_32_BE(i)) and U_j = PRF(P, U_j-1).
        val saltedIndex = ByteArray(salt.size + 4)
        salt.copyInto(saltedIndex)
        for (block in 1..blocks) {
            saltedIndex[salt.size] = (block ushr 24).toByte()
            saltedIndex[salt.size + 1] = (block ushr 16).toByte()
            saltedIndex[salt.size + 2] = (block ushr 8).toByte()
            saltedIndex[salt.size + 3] = block.toByte()

            var u = prf.mac(saltedIndex)
            val accumulator = u.copyOf()
            for (round in 2..iterations) {
                u = prf.mac(u)
                for (i in accumulator.indices) {
                    accumulator[i] = (accumulator[i].toInt() xor u[i].toInt()).toByte()
                }
            }

            // The final block is truncated when keyLengthBytes is not a multiple of hLen.
            val offset = (block - 1) * hLen
            accumulator.copyInto(
                destination = out,
                destinationOffset = offset,
                startIndex = 0,
                endIndex = minOf(hLen, keyLengthBytes - offset),
            )
        }
        return out
    }
}
