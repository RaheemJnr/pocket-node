package com.rjnr.pocketnode.core.crypto

import org.kotlincrypto.hash.blake2.BLAKE2b

/**
 * BLAKE2b-256 with CKB's `"ckb-default-hash"` personalization and no key or salt.
 *
 * Replaces `org.nervos.ckb.crypto.Blake2b` (BouncyCastle `Blake2bDigest`), which
 * is JVM-only, with KotlinCrypto's multiplatform BLAKE2b (#454). The public
 * shape — [update] / [doFinal] / [digest] — mirrors the SDK class so call sites
 * move over unchanged.
 */
class Blake2b {
    private val digest = BLAKE2b(DIGEST_LENGTH * 8, CKB_HASH_PERSONALIZATION)

    fun update(input: ByteArray): Blake2b {
        digest.update(input)
        return this
    }

    /** Finalises and resets, like `Blake2bDigest.doFinal`. */
    fun doFinal(): ByteArray = digest.digest()

    companion object {
        /** The 16 personalization bytes CKB mixes into the BLAKE2b parameter block. */
        val CKB_HASH_PERSONALIZATION: ByteArray = "ckb-default-hash".encodeToByteArray()
        const val DIGEST_LENGTH: Int = 32

        fun digest(input: ByteArray): ByteArray = Blake2b().update(input).doFinal()
    }
}
