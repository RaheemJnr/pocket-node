package com.rjnr.pocketnode.data.crypto

import com.rjnr.pocketnode.core.crypto.Blake2b as SharedBlake2b

/**
 * Blake2b-256 wrapper over the shared multiplatform implementation.
 * BLAKE2b-256 with CKB personalization (#454).
 */
class Blake2b {

    /**
     * Hash input bytes using Blake2b-256 with CKB personalization.
     */
    fun hash(input: ByteArray): ByteArray {
        return SharedBlake2b.digest(input)
    }

    /**
     * Create a new hasher for incremental hashing.
     */
    fun newHasher(): Blake2bHasher = Blake2bHasher()
}

/**
 * Incremental Blake2b hasher over the shared multiplatform implementation.
 */
class Blake2bHasher {
    private val blake2b = SharedBlake2b()

    fun update(input: ByteArray): Blake2bHasher {
        blake2b.update(input)
        return this
    }

    fun finalize(): ByteArray {
        return blake2b.doFinal()
    }
}
