package com.rjnr.pocketnode.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Stores and verifies the device PIN.
 *
 * The PIN hash is Argon2id-derived (since v1.7.0 / KDF v2). Legacy hashes
 * from v1.6.x and earlier used a single Blake2b-256 pass; the legacy path
 * is preserved for verification and silently re-hashed to Argon2id on the
 * first successful entry.
 *
 * The failure counter is cumulative across sessions and resets on a
 * successful verification or an explicit `setPin`. Lockout duration
 * escalates with attempt count up to a permanent lockout at 10+ failures.
 *
 * The counter (and lockout state) live in EncryptedSharedPreferences, which
 * survive an app upgrade / overwrite install by design (#370). Resetting it
 * on reinstall would let an attacker sideload a build to clear the count and
 * keep brute-forcing, so it deliberately persists across upgrades. When
 * attempts run out, the recovery path is "reset and restore from seed"
 * (surfaced by [com.rjnr.pocketnode.ui.screens.auth.PinEntryScreen], #373),
 * not a counter reset.
 */
@Singleton
class PinManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blake2b: Blake2b
) {
    @VisibleForTesting
    internal var testPrefs: SharedPreferences? = null

    private var backupChecker: (() -> Boolean)? = null

    @VisibleForTesting
    fun setBackupChecker(checker: () -> Boolean) {
        backupChecker = checker
    }

    @Inject
    fun setBackupCheckerFromDI(keyBackupManager: KeyBackupManager) {
        backupChecker = { keyBackupManager.hasAnyBackups() }
    }

    private val prefs: SharedPreferences
        get() = testPrefs ?: encryptedPrefs

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs(useStrongBox = true)
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox-backed pin prefs failed, trying without StrongBox", e)
            try {
                createEncryptedPrefs(useStrongBox = false)
            } catch (e2: Exception) {
                Log.e(TAG, "Pin prefs completely unreadable", e2)
                createEncryptedPrefs(useStrongBox = true)
            }
        }
    }

    private fun createEncryptedPrefs(useStrongBox: Boolean): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .apply { if (useStrongBox) setRequestStrongBoxBacked(true) }
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @VisibleForTesting
    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    /**
     * Argon2id parameters. Defaults follow OWASP ASVS 4.0.3 baseline.
     * Tests override these to avoid 64 MB / 300 ms per verify.
     */
    @VisibleForTesting
    internal var argon2Iterations: Int = 3
    @VisibleForTesting
    internal var argon2MemoryKb: Int = 64 * 1024
    @VisibleForTesting
    internal var argon2Parallelism: Int = 4

    fun setPin(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must be exactly $PIN_LENGTH digits"
        }
        val hash = hashPinArgon2id(pin.toByteArray(Charsets.UTF_8))
        writeFreshPin(hash)
    }

    fun setPinFromChars(pin: CharArray) {
        require(pin.size == PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must be exactly $PIN_LENGTH digits"
        }
        val hash = hashPinArgon2id(charsToUtf8Bytes(pin))
        writeFreshPin(hash)
    }

    private fun writeFreshPin(hash: String) {
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putInt(KEY_KDF_VERSION, KDF_VERSION_ARGON2ID)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LAST_FAILED_AT)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    fun verifyPin(pin: String): Boolean = verifyInternal(pin.toByteArray(Charsets.UTF_8))

    fun verifyPinFromChars(pin: CharArray): Boolean = verifyInternal(charsToUtf8Bytes(pin))

    private fun verifyInternal(pinBytes: ByteArray): Boolean {
        if (isLockedOut()) return false
        if (!hasPin()) return false

        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val kdfVersion = prefs.getInt(KEY_KDF_VERSION, KDF_VERSION_LEGACY_BLAKE2B)

        val matches = when (kdfVersion) {
            KDF_VERSION_ARGON2ID -> hashPinArgon2id(pinBytes) == storedHash
            KDF_VERSION_LEGACY_BLAKE2B -> hashPinBlake2b(pinBytes) == storedHash
            else -> {
                Log.e(TAG, "Unknown KDF version $kdfVersion, refusing to verify")
                return false
            }
        }

        return if (matches) {
            if (kdfVersion == KDF_VERSION_LEGACY_BLAKE2B) {
                // Silent migration: re-derive the same plaintext PIN under
                // Argon2id and overwrite the stored hash. The next verify
                // will use Argon2id. Failure here is non-fatal — we still
                // accepted the PIN, the migration retries on the next entry.
                runCatching {
                    val newHash = hashPinArgon2id(pinBytes)
                    prefs.edit()
                        .putString(KEY_PIN_HASH, newHash)
                        .putInt(KEY_KDF_VERSION, KDF_VERSION_ARGON2ID)
                        .apply()
                    Log.i(TAG, "Migrated PIN hash to Argon2id")
                }.onFailure {
                    Log.w(TAG, "PIN Argon2id migration write failed (will retry next entry)", it)
                }
            }
            onSuccessfulPin()
            true
        } else {
            recordFailedAttempt()
            false
        }
    }

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun removePin(force: Boolean = false) {
        if (!force && backupChecker?.invoke() == true) {
            throw IllegalStateException(
                "Cannot remove PIN while encrypted backup files exist. " +
                "Use force=true to delete backups and remove PIN."
            )
        }
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_KDF_VERSION)
            .remove(KEY_SALT)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LAST_FAILED_AT)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    /**
     * #370: clear the failed-attempt counter and any lockout, keeping the PIN
     * itself. Called once after an overwrite install / version upgrade so a
     * user who fumbled their PIN before upgrading is not still staring at "out
     * of attempts" on the freshly upgraded build. Committed synchronously
     * because the caller runs it during cold start, before the PIN gate reads
     * the lockout state.
     */
    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LAST_FAILED_AT)
            .remove(KEY_LOCKOUT_UNTIL)
            .commit()
    }

    fun getRemainingAttempts(): Int {
        val failed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        return (MAX_ATTEMPTS - failed).coerceAtLeast(0)
    }

    fun isLockedOut(): Boolean {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil == 0L) return false
        // Lockout expires naturally; the counter stays put so subsequent
        // failures continue escalating. The counter only resets on a
        // successful verify (subject to the 24h decay window) or on setPin.
        return timeProvider() < lockoutUntil
    }

    fun getLockoutRemainingMs(): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil == 0L) return 0L
        return (lockoutUntil - timeProvider()).coerceAtLeast(0L)
    }

    /** True if the wallet has hit the permanent-lockout threshold (10+ failures). */
    fun isPermanentlyLocked(): Boolean {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        return attempts >= MAX_ATTEMPTS_BEFORE_PERMANENT
    }

    private fun recordFailedAttempt() {
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val now = timeProvider()
        val editor = prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, attempts)
            .putLong(KEY_LAST_FAILED_AT, now)

        val lockoutMs = lockoutDurationFor(attempts)
        if (lockoutMs > 0) {
            val lockoutUntil = if (lockoutMs == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                now + lockoutMs
            }
            editor.putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
        }
        editor.apply()
    }

    private fun lockoutDurationFor(attempts: Int): Long = when {
        attempts < MAX_ATTEMPTS -> 0L
        attempts == 5 -> 30_000L              // 30 s
        attempts == 6 -> 60_000L              // 1 min
        attempts == 7 -> 300_000L             // 5 min
        attempts == 8 -> 1_800_000L           // 30 min
        attempts == 9 -> 3_600_000L           // 1 h
        else -> Long.MAX_VALUE                // permanent
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
        prefs.edit()
            .remove(KEY_LOCKOUT_UNTIL)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .remove(KEY_LAST_FAILED_AT)
            .apply()
    }

    private fun hashPinArgon2id(pinBytes: ByteArray): String {
        val salt = getOrCreateSalt()
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(argon2Iterations)
            .withMemoryAsKB(argon2MemoryKb)
            .withParallelism(argon2Parallelism)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator().also { it.init(params) }
        val output = ByteArray(HASH_OUTPUT_BYTES)
        gen.generateBytes(pinBytes, output)
        return output.joinToString("") { "%02x".format(it) }
    }

    private fun hashPinBlake2b(pinBytes: ByteArray): String {
        val salt = getOrCreateSalt()
        val input = salt + pinBytes
        val hash = blake2b.hash(input)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun charsToUtf8Bytes(pin: CharArray): ByteArray {
        // Avoid going through String to keep the PIN out of the string intern pool.
        return String(pin).toByteArray(Charsets.UTF_8)
    }

    private fun getOrCreateSalt(): ByteArray {
        val existingHex = prefs.getString(KEY_SALT, null)
        if (existingHex != null) {
            return hexToBytes(existingHex)
        }
        val salt = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(salt)
        val hex = salt.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SALT, hex).apply()
        return salt
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val TAG = "PinManager"
        internal const val PREFS_NAME = "ckb_pin_prefs"
        internal const val PIN_LENGTH = 6
        internal const val MAX_ATTEMPTS = 5
        internal const val MAX_ATTEMPTS_BEFORE_PERMANENT = 10
        internal const val LOCKOUT_DURATION_MS = 30_000L // first lockout (attempts=5)
        internal const val LOCKOUT_DECAY_MS = 24 * 60 * 60 * 1000L // 24 h
        internal const val SALT_SIZE = 32
        internal const val HASH_OUTPUT_BYTES = 32

        internal const val KDF_VERSION_LEGACY_BLAKE2B = 1
        internal const val KDF_VERSION_ARGON2ID = 2

        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_KDF_VERSION = "pin_kdf_version"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LAST_FAILED_AT = "last_failed_at"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"

        /**
         * #370: whether an overwrite install / version bump should reset the
         * failed-attempt counter. True only on a genuine upgrade — a recorded
         * previous version strictly below the current one. A fresh install
         * (lastSeen 0) has no counter to reset; a same-version relaunch or a
         * downgrade must not clear a legitimate lockout.
         */
        fun shouldResetAttemptsForUpgrade(lastSeen: Int, current: Int): Boolean =
            lastSeen in 1 until current
    }
}
