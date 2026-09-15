package com.rjnr.pocketnode.core.crypto

/**
 * A source of cryptographically secure random bytes.
 *
 * `commonMain` has no `SecureRandom`, and `kotlin.random.Random` is a seeded
 * PRNG that must never produce key material. So the platform supplies the
 * randomness instead: Android passes a `java.security.SecureRandom`-backed
 * implementation, iOS a `SecRandomCopyBytes` one.
 *
 * An implementation MUST return exactly `n` bytes and MUST fail loudly rather
 * than return short, predictable or zero-filled output. A silent fallback here
 * is a wallet that hands out guessable mnemonics.
 */
interface EntropySource {
    /** Exactly [n] fresh random bytes. */
    fun nextBytes(n: Int): ByteArray
}
