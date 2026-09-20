package com.rjnr.pocketnode.data.auth

import com.rjnr.pocketnode.core.crypto.Argon2id
import com.rjnr.pocketnode.core.crypto.Blake2b
import com.rjnr.pocketnode.core.crypto.constantTimeEquals
import com.rjnr.pocketnode.core.crypto.EntropySource
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.core.log.Logger

/**
 * The app-PIN hashing, verification and lockout schedule, shared by Android and iOS.
 *
 * The PIN hash is Argon2id-derived (since v1.7.0 / KDF v2). Legacy hashes from
 * v1.6.x and earlier used a single Blake2b-256 pass over `salt || pin`; that path
 * is preserved for verification and silently re-hashed to Argon2id on the first
 * successful entry.
 *
 * The failure counter is cumulative across sessions and resets on a successful
 * verification or an explicit [setPin]. Lockout duration escalates with attempt
 * count (see [lockoutDurationFor]) up to a permanent lockout at 10+ failures. A
 * lockout expires on its own, but the counter does not: the next failure keeps
 * escalating from where the last one left off.
 *
 * Everything platform-specific is injected: [store] persists the six fields,
 * [entropy] supplies the salt, [clock] is epoch-millis, [logger] is the shared
 * logging seam. That keeps this class free of `android.*`, `java.*` and Hilt, so
 * the iOS build applies a byte-identical schedule and derives byte-identical
 * hashes from the same PIN.
 *
 * @param argon2Params the Argon2id cost. Defaults follow the OWASP ASVS 4.0.3
 *   baseline; it is a `var` so tests can lower it rather than pay 64 MB / ~300 ms
 *   per verify.
 */
class PinPolicy(
    private val store: PinStore,
    private val entropy: EntropySource,
    private val clock: () -> Long,
    private val logger: Logger,
    argon2Params: Argon2id.Params = Argon2id.Params(
        iterations = ARGON2_ITERATIONS,
        memoryKib = ARGON2_MEMORY_KIB,
        parallelism = ARGON2_PARALLELISM,
        tagLength = HASH_OUTPUT_BYTES,
    ),
) {

    var argon2Params: Argon2id.Params = argon2Params
        internal set

    /**
     * Stores a fresh PIN and clears every trace of the previous failure state.
     *
     * @param pinBytes the PIN as UTF-8 digits. Not retained and not copied.
     */
    fun setPin(pinBytes: ByteArray) {
        require(pinBytes.size == PIN_LENGTH && pinBytes.all { it in ASCII_ZERO..ASCII_NINE }) {
            "PIN must be exactly $PIN_LENGTH digits"
        }
        val hash = hashPinArgon2id(pinBytes)
        store.update {
            pinHash(hash)
            kdfVersion(KDF_VERSION_ARGON2ID)
            clearFailureState()
        }
    }

    /**
     * True if [pinBytes] matches the stored PIN.
     *
     * Returns false while locked out, before any PIN is set, and on an unknown
     * KDF version. A match resets the failure counter; a mismatch records a
     * failure and may start the next lockout.
     */
    fun verify(pinBytes: ByteArray): Boolean {
        if (isLockedOut()) return false
        if (!hasPin()) return false

        val storedHash = store.getPinHash() ?: return false
        // A store written before the version key existed is Blake2b by definition.
        val kdfVersion = store.getKdfVersion() ?: KDF_VERSION_LEGACY_BLAKE2B

        val matches = when (kdfVersion) {
            KDF_VERSION_ARGON2ID -> hashesMatch(hashPinArgon2id(pinBytes), storedHash)
            KDF_VERSION_LEGACY_BLAKE2B -> hashesMatch(hashPinBlake2b(pinBytes), storedHash)
            else -> {
                logger.e(TAG, "Unknown KDF version $kdfVersion, refusing to verify")
                return false
            }
        }

        return if (matches) {
            if (kdfVersion == KDF_VERSION_LEGACY_BLAKE2B) {
                // Silent migration: re-derive the same plaintext PIN under
                // Argon2id and overwrite the stored hash. The next verify
                // will use Argon2id. Failure here is non-fatal, we still
                // accepted the PIN, the migration retries on the next entry.
                runCatching {
                    val newHash = hashPinArgon2id(pinBytes)
                    store.update {
                        pinHash(newHash)
                        kdfVersion(KDF_VERSION_ARGON2ID)
                    }
                    logger.i(TAG, "Migrated PIN hash to Argon2id")
                }.onFailure {
                    logger.w(TAG, "PIN Argon2id migration write failed (will retry next entry)", it)
                }
            }
            onSuccessfulPin()
            true
        } else {
            recordFailedAttempt()
            false
        }
    }

    fun hasPin(): Boolean = store.getPinHash() != null

    /**
     * Clears the PIN, its salt, its KDF version and all failure state.
     *
     * Every field is removed rather than zeroed, including the attempt counter:
     * "no PIN configured" and "a PIN with zero failures against it" must not
     * look the same in storage. `clearFailureState`, used by the success and
     * `setPin` paths, writes 0 instead because the PIN is still there.
     *
     * Platform guards (Android refuses while encrypted backup files exist)
     * belong in the adapter, not here.
     */
    fun removePin() {
        store.update {
            pinHash(null)
            kdfVersion(null)
            salt(null)
            failedAttempts(null)
            lastFailedAt(null)
            lockoutUntil(null)
        }
    }

    /**
     * #370: clears the failed-attempt counter and any lockout, keeping the PIN.
     * Called once after an overwrite install / version upgrade so a user who
     * fumbled their PIN before upgrading is not still staring at "out of
     * attempts" on the freshly upgraded build.
     *
     * @param durable `true` when the caller cannot continue until the reset has
     *   reached storage, as the cold-start path cannot: the PIN gate reads the
     *   lockout state immediately afterwards.
     */
    fun resetFailedAttempts(durable: Boolean = false) {
        if (durable) {
            store.updateDurable { clearFailureState() }
        } else {
            store.update { clearFailureState() }
        }
    }

    fun getRemainingAttempts(): Int =
        (MAX_ATTEMPTS - store.getFailedAttempts()).coerceAtLeast(0)

    fun isLockedOut(): Boolean {
        val lockoutUntil = store.getLockoutUntil() ?: return false
        if (lockoutUntil == 0L) return false
        // Lockout expires naturally; the counter stays put so subsequent
        // failures continue escalating. The counter only resets on a
        // successful verify or on setPin.
        return clock() < lockoutUntil
    }

    fun getLockoutRemainingMs(): Long {
        val lockoutUntil = store.getLockoutUntil() ?: return 0L
        if (lockoutUntil == 0L) return 0L
        return (lockoutUntil - clock()).coerceAtLeast(0L)
    }

    /** True if the wallet has hit the permanent-lockout threshold (10+ failures). */
    fun isPermanentlyLocked(): Boolean =
        store.getFailedAttempts() >= MAX_ATTEMPTS_BEFORE_PERMANENT

    private fun recordFailedAttempt() {
        val attempts = store.getFailedAttempts() + 1
        val now = clock()
        val lockoutMs = lockoutDurationFor(attempts)
        store.update {
            failedAttempts(attempts)
            lastFailedAt(now)
            if (lockoutMs > 0) {
                lockoutUntil(if (lockoutMs == Long.MAX_VALUE) Long.MAX_VALUE else now + lockoutMs)
            }
        }
    }

    private fun onSuccessfulPin() {
        // A correct PIN resets the counter to max, matching platform
        // convention (Android lockscreen does the same). The previous 24h
        // decay window kept the counter after success to slow an attacker
        // grinding between owner unlocks, but in practice it left the OWNER
        // staring at "out of attempts" + the recovery dialog on every unlock
        // for a day after fumbling their PIN (device-test report, 2026-07).
        // Escalating lockouts (30s at 5 failures -> permanent at 10) still
        // bound brute force between successes.
        //
        // A storage failure here must not turn a correct PIN into a rejected
        // one: the user would be locked out of their own wallet by a full disk.
        // The counter simply stays where it was and the next success retries.
        runCatching { store.update { clearFailureState() } }.onFailure {
            logger.w(TAG, "Failed to reset the PIN attempt counter after a correct PIN", it)
        }
    }

    /**
     * Compares two hex hashes without leaking, through timing, how many leading
     * characters matched. `String.equals` stops at the first difference, which
     * an attacker who can also write the stored hash could grind against.
     */
    private fun hashesMatch(computed: String, stored: String): Boolean =
        constantTimeEquals(computed.encodeToByteArray(), stored.encodeToByteArray())

    private fun hashPinArgon2id(pinBytes: ByteArray): String {
        val salt = getOrCreateSalt()
        val output = Argon2id.hash(
            password = pinBytes,
            salt = salt,
            params = argon2Params,
        )
        // Only what this function allocated. `pinBytes` belongs to the caller,
        // which still needs it (the migration path hashes twice).
        return output.toHexStringNoPrefix().also { output.fill(0) }
    }

    private fun hashPinBlake2b(pinBytes: ByteArray): String {
        val salt = getOrCreateSalt()
        // Streamed rather than hashing `salt + pinBytes`, which would leave a
        // second copy of the PIN on the heap. BLAKE2b's incremental update is
        // bit-identical to the one-shot digest of the concatenation, so hashes
        // written by v1.6.x still verify.
        val digest = Blake2b().update(salt).update(pinBytes).doFinal()
        return digest.toHexStringNoPrefix().also { digest.fill(0) }
    }

    private fun getOrCreateSalt(): ByteArray {
        store.getSalt()?.let { return it }
        val fresh = entropy.nextBytes(SALT_SIZE)
        store.update { salt(fresh) }
        return fresh
    }

    companion object {
        private const val TAG = "PinPolicy"

        private const val ASCII_ZERO: Byte = 48 // '0'
        private const val ASCII_NINE: Byte = 57 // '9'

        const val PIN_LENGTH = 6
        const val MAX_ATTEMPTS = 5
        const val MAX_ATTEMPTS_BEFORE_PERMANENT = 10

        /** First lockout, at [MAX_ATTEMPTS] failures. */
        const val LOCKOUT_DURATION_MS = 30_000L

        /**
         * Retained for callers that still reference the old decay window. A
         * successful verify now resets the counter outright, so nothing in the
         * policy reads this.
         */
        const val LOCKOUT_DECAY_MS = 24 * 60 * 60 * 1000L

        const val SALT_SIZE = 32
        const val HASH_OUTPUT_BYTES = 32

        const val KDF_VERSION_LEGACY_BLAKE2B = 1
        const val KDF_VERSION_ARGON2ID = 2

        /** OWASP ASVS 4.0.3 baseline: t=3, m=64 MiB, p=4. */
        const val ARGON2_ITERATIONS = 3
        const val ARGON2_MEMORY_KIB = 64 * 1024
        const val ARGON2_PARALLELISM = 4

        /**
         * How long a lockout lasts after [attempts] cumulative failures, `0` for
         * no lockout and [Long.MAX_VALUE] for a permanent one.
         */
        fun lockoutDurationFor(attempts: Int): Long = when {
            attempts < MAX_ATTEMPTS -> 0L
            attempts == 5 -> LOCKOUT_DURATION_MS   // 30 s
            attempts == 6 -> 60_000L               // 1 min
            attempts == 7 -> 300_000L              // 5 min
            attempts == 8 -> 1_800_000L            // 30 min
            attempts == 9 -> 3_600_000L            // 1 h
            else -> Long.MAX_VALUE                 // permanent
        }
    }
}
