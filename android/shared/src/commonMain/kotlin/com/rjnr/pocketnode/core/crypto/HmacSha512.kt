package com.rjnr.pocketnode.core.crypto

import org.kotlincrypto.macs.hmac.sha2.HmacSHA512

/**
 * HMAC-SHA-512 (RFC 2104) for the shared KMP module.
 *
 * `cash.z.ecc.android:kotlin-bip39` and BouncyCastle are both JVM-only, so the
 * BIP-39 seed path needed a multiplatform MAC (#507). KotlinCrypto's
 * `hmac-sha2` is the same family as the BLAKE2b already used for CKB hashing.
 */
object HmacSha512 {

    /** Output length of HMAC-SHA-512, in bytes. */
    const val MAC_LENGTH: Int = 64

    /** The MAC of [data] under [key]. */
    fun mac(key: ByteArray, data: ByteArray): ByteArray = HmacSHA512(key).doFinal(data)

    /**
     * A reusable instance pinned to one key.
     *
     * PBKDF2 runs thousands of MACs under a single key, and re-deriving the
     * ipad/opad blocks for each one roughly doubles the work. [Keyed] pays that
     * setup cost once. Internal because it hands out a stateful object: only
     * [Pbkdf2] should be holding one, and never across threads.
     */
    internal fun keyed(key: ByteArray): Keyed = Keyed(key)

    internal class Keyed(key: ByteArray) {
        private val mac = HmacSHA512(key)

        /** The MAC of [data]. Resets afterwards, so the instance is reusable. */
        fun mac(data: ByteArray): ByteArray {
            mac.update(data)
            return mac.doFinal()
        }
    }
}
