package com.rjnr.pocketnode.core.crypto

import fr.acinq.secp256k1.Secp256k1

/**
 * BIP-32 hierarchical deterministic private-key derivation for the shared KMP
 * module, plus the BIP-44 path Pocket Node uses for CKB (#508).
 *
 * This replaces the private helpers inside the app's `MnemonicManager`, which
 * ran on BouncyCastle's `HMac(SHA512Digest())` and `java.math.BigInteger` —
 * neither reachable from `commonMain`, and the reason iOS could not derive the
 * same keys. The three pieces it needs are all multiplatform now: HMAC-SHA-512
 * from [HmacSha512] (#507), the compressed public key from [Secp256k1Signer],
 * and the scalar addition from libsecp256k1 via secp256k1-kmp.
 *
 * The arithmetic is the one place worth spelling out. BIP-32's child key is
 * `(IL + k_par) mod n`, which the old code wrote out with `BigInteger`. Here it
 * is `Secp256k1.privKeyTweakAdd(k_par, IL)`: libsecp256k1 computes exactly that
 * sum in constant time and refuses the two results BIP-32 also forbids, namely
 * a tweak at or above the curve order and a child key of zero. The `IL >= n`
 * case is screened here first, by an unsigned byte compare against [CURVE_ORDER],
 * so that each rejection can carry the same message the old code used and
 * callers see identical failures. Proved byte-identical to the old
 * implementation over 2,000 random seeds by `Bip32DifferentialTest`.
 *
 * One deliberate difference: if the master key itself came out at or above the
 * curve order, or at zero, this throws where the old code silently reduced mod
 * n. BIP-32 calls such a seed invalid, so throwing is the spec behaviour, and
 * the odds of reaching it are about 2^-127.
 *
 * Private keys are secret material. Every intermediate key and chain code this
 * object allocates is zeroed once it is no longer needed, and nothing is
 * retained in a field.
 */
object Bip32 {

    /** Length of a private key and of a chain code, in bytes. */
    private const val KEY_SIZE = 32

    /** BIP-32 marks a hardened index by setting the high bit. */
    private const val HARDENED_OFFSET = 0x80000000L

    /** `HMAC-SHA512(key = "Bitcoin seed", data = seed)` produces the master key. */
    private const val MASTER_KEY_SALT = "Bitcoin seed"

    /** Order of the secp256k1 group, big-endian. `IL` must be strictly below it. */
    private val CURVE_ORDER =
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141".hexToByteArray()

    /** CKB's registered SLIP-44 coin type. */
    private const val CKB_COIN_TYPE = 309

    /** BIP-44's purpose level. */
    private const val BIP44_PURPOSE = 44

    /**
     * An extended private key: the 32-byte key and its 32-byte chain code.
     *
     * Both arrays are held by reference, not copied. Callers that keep one
     * around own the zeroing of it.
     */
    class ExtendedPrivateKey(val key: ByteArray, val chainCode: ByteArray) {
        init {
            require(key.size == KEY_SIZE) { "Private key must be $KEY_SIZE bytes, got ${key.size}" }
            require(chainCode.size == KEY_SIZE) {
                "Chain code must be $KEY_SIZE bytes, got ${chainCode.size}"
            }
        }
    }

    /**
     * The master extended private key `m` for a BIP-39 [seed].
     *
     * Pocket Node only ever derives from a 64-byte BIP-39 seed, and the old
     * `MnemonicManager.derivePrivateKey` said so with this exact message. Keep
     * the guard: a shorter seed here means a caller lost entropy somewhere.
     *
     * @throws IllegalArgumentException if [seed] is not 64 bytes.
     */
    fun masterKey(seed: ByteArray): ExtendedPrivateKey {
        require(seed.size == 64) { "Seed must be 64 bytes" }
        return masterKeyOfAnyLength(seed)
    }

    /**
     * [masterKey] without the 64-byte guard.
     *
     * BIP-32 itself allows a 128..512 bit seed, and its official test vector 1
     * uses a 128-bit one. Nothing in the app calls this; it exists so
     * `Bip32Test` can check the published vectors against the real code path
     * rather than against a re-derived variant of it.
     */
    internal fun masterKeyOfAnyLength(seed: ByteArray): ExtendedPrivateKey {
        require(seed.size in 16..64) { "Seed must be 16..64 bytes, got ${seed.size}" }

        val i = HmacSha512.mac(MASTER_KEY_SALT.encodeToByteArray(), seed)
        val master = ExtendedPrivateKey(
            key = i.copyOfRange(0, KEY_SIZE),
            chainCode = i.copyOfRange(KEY_SIZE, HmacSha512.MAC_LENGTH),
        )
        i.fill(0)
        return master
    }

    /**
     * The hardened child at [index] — the `i'` level of a path.
     *
     * Data hashed is `0x00 || k_par || (index + 2^31)` big-endian.
     *
     * @param index the unhardened index, `0..2^31-1`.
     */
    fun deriveHardened(parent: ExtendedPrivateKey, index: Int): ExtendedPrivateKey {
        require(index >= 0) { "Child index must be non-negative, got $index" }

        val data = ByteArray(1 + KEY_SIZE + 4)
        data[0] = 0x00
        parent.key.copyInto(data, 1)
        putBe32(data, 1 + KEY_SIZE, index.toLong() + HARDENED_OFFSET)
        return childKey(parent, data)
    }

    /**
     * The normal (non-hardened) child at [index].
     *
     * Data hashed is `serP(point(k_par)) || index` big-endian, where `serP` is
     * the 33-byte compressed public key.
     *
     * @param index the child index, `0..2^31-1`.
     */
    fun deriveNormal(parent: ExtendedPrivateKey, index: Int): ExtendedPrivateKey {
        require(index >= 0) { "Child index must be non-negative, got $index" }

        val compressedPublicKey = Secp256k1Signer.publicKey(parent.key) // 33 bytes
        val data = ByteArray(compressedPublicKey.size + 4)
        compressedPublicKey.copyInto(data, 0)
        putBe32(data, compressedPublicKey.size, index.toLong())
        return childKey(parent, data)
    }

    /**
     * Derives the 32-byte private key at [path] from [seed].
     *
     * [path] is the usual textual form: `m` on its own for the master key, then
     * `/`-separated indices, each optionally suffixed with `'`, `h` or `H` to
     * mark it hardened — for example `m/44'/309'/0'/0/0`. A leading `m` is
     * required; public (`M`) derivation is not supported.
     *
     * Every extended key along the way is zeroed before returning, including
     * the last one; the returned array is a copy.
     *
     * @throws IllegalArgumentException if [seed] is not 64 bytes, if [path] is
     *   malformed, or if a level derives an invalid key.
     */
    fun derivePath(seed: ByteArray, path: String): ByteArray {
        val levels = parsePath(path)

        var current = masterKey(seed)
        try {
            for (level in levels) {
                val next = if (level.hardened) {
                    deriveHardened(current, level.index)
                } else {
                    deriveNormal(current, level.index)
                }
                wipe(current)
                current = next
            }
            return current.key.copyOf()
        } finally {
            wipe(current)
        }
    }

    /**
     * Derives the CKB private key at `m/44'/309'/[accountIndex]'/[chainIndex]/[addressIndex]`.
     *
     * [chainIndex] is BIP-44's change level: 0 is the receiving chain, 1 the
     * change chain. Everything shipped before #382 Tier 2 used chain 0 only,
     * so the defaults keep those call sites byte-identical.
     */
    fun deriveCkbPrivateKey(
        seed: ByteArray,
        accountIndex: Int = 0,
        chainIndex: Int = 0,
        addressIndex: Int = 0,
    ): ByteArray {
        var current = masterKey(seed)
        try {
            for (index in intArrayOf(BIP44_PURPOSE, CKB_COIN_TYPE, accountIndex)) {
                val next = deriveHardened(current, index)
                wipe(current)
                current = next
            }
            for (index in intArrayOf(chainIndex, addressIndex)) {
                val next = deriveNormal(current, index)
                wipe(current)
                current = next
            }
            return current.key.copyOf()
        } finally {
            wipe(current)
        }
    }

    // -- internals --

    /**
     * `I = HMAC-SHA512(c_par, data)`, child key `= (IL + k_par) mod n`,
     * child chain code `= IR`.
     */
    private fun childKey(parent: ExtendedPrivateKey, data: ByteArray): ExtendedPrivateKey {
        val i = HmacSha512.mac(parent.chainCode, data)
        data.fill(0) // holds k_par on the hardened path

        val il = i.copyOfRange(0, KEY_SIZE)
        val childChainCode = i.copyOfRange(KEY_SIZE, HmacSha512.MAC_LENGTH)
        i.fill(0)

        try {
            require(isBelowCurveOrder(il)) { "Invalid derived key (>= curve order)" }

            // libsecp256k1 computes (k_par + IL) mod n and rejects a zero result,
            // which is the other case BIP-32 declares invalid.
            val childKey = try {
                Secp256k1.privKeyTweakAdd(parent.key, il)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid derived key (zero)", e)
            }
            return ExtendedPrivateKey(childKey, childChainCode)
        } finally {
            il.fill(0)
        }
    }

    /** True when the 32-byte big-endian [value] is strictly below the group order. */
    private fun isBelowCurveOrder(value: ByteArray): Boolean {
        for (index in 0 until KEY_SIZE) {
            val a = value[index].toInt() and 0xFF
            val b = CURVE_ORDER[index].toInt() and 0xFF
            if (a != b) return a < b
        }
        return false // exactly n, which is not a valid scalar either
    }

    private fun putBe32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value shr 24 and 0xFF).toByte()
        buffer[offset + 1] = (value shr 16 and 0xFF).toByte()
        buffer[offset + 2] = (value shr 8 and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun wipe(extendedKey: ExtendedPrivateKey) {
        extendedKey.key.fill(0)
        extendedKey.chainCode.fill(0)
    }

    private class PathLevel(val index: Int, val hardened: Boolean)

    private fun parsePath(path: String): List<PathLevel> {
        val parts = path.trim().split('/')
        require(parts.isNotEmpty() && parts[0] == "m") {
            "Derivation path must start with \"m\", got \"$path\""
        }

        return parts.drop(1).map { part ->
            require(part.isNotEmpty()) { "Empty level in derivation path \"$path\"" }
            val hardened = part.endsWith("'") || part.endsWith("h") || part.endsWith("H")
            val digits = if (hardened) part.dropLast(1) else part
            val index = digits.toIntOrNull()
            require(index != null && index >= 0 && digits.all { it in '0'..'9' }) {
                "Invalid level \"$part\" in derivation path \"$path\""
            }
            PathLevel(index, hardened)
        }
    }
}
