package com.rjnr.pocketnode.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rjnr.pocketnode.core.crypto.EntropySource
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android adapter for [PinPolicy].
 *
 * The hashing, verification and lockout schedule itself lives in the shared KMP
 * module (#510) so iOS applies an identical one. What stays here is everything
 * platform-bound: the EncryptedSharedPreferences the state is persisted in, the
 * StrongBox fallback when that keystore is unavailable, the backup-file guard on
 * [removePin], the `String`/`CharArray` entry points the UI calls, and the
 * `SecureRandom` the salt comes from.
 *
 * The prefs keys, the stored values and the public API are unchanged from the
 * pre-extraction version, so an upgrading install verifies its existing PIN and
 * keeps its existing lockout state.
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
    /**
     * Unused since the legacy Blake2b PIN path moved into [PinPolicy], which
     * calls the shared `core.crypto.Blake2b` directly. Kept so the constructor
     * signature (and the `AppModule` provider that calls it) is untouched.
     */
    @Suppress("unused") private val blake2b: Blake2b,
    private val logger: Logger,
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
            logger.w(TAG, "StrongBox-backed pin prefs failed, trying without StrongBox", e)
            try {
                createEncryptedPrefs(useStrongBox = false)
            } catch (e2: Exception) {
                logger.e(TAG, "Pin prefs completely unreadable", e2)
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

    private val store = PrefsPinStore()

    private val policy = PinPolicy(
        store = store,
        entropy = SecureRandomEntropySource,
        // Read through the property, not captured by value: tests replace
        // `timeProvider` after construction.
        clock = { timeProvider() },
        logger = logger,
    )

    /**
     * Argon2id parameters. Defaults follow OWASP ASVS 4.0.3 baseline.
     * Tests override these to avoid 64 MB / 300 ms per verify.
     */
    @VisibleForTesting
    internal var argon2Iterations: Int
        get() = policy.argon2Params.iterations
        set(value) {
            policy.argon2Params = policy.argon2Params.copy(iterations = value)
        }

    @VisibleForTesting
    internal var argon2MemoryKb: Int
        get() = policy.argon2Params.memoryKib
        set(value) {
            policy.argon2Params = policy.argon2Params.copy(memoryKib = value)
        }

    @VisibleForTesting
    internal var argon2Parallelism: Int
        get() = policy.argon2Params.parallelism
        set(value) {
            policy.argon2Params = policy.argon2Params.copy(parallelism = value)
        }

    fun setPin(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must be exactly $PIN_LENGTH digits"
        }
        policy.setPin(pin.toByteArray(Charsets.UTF_8))
    }

    fun setPinFromChars(pin: CharArray) {
        require(pin.size == PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must be exactly $PIN_LENGTH digits"
        }
        policy.setPin(charsToUtf8Bytes(pin))
    }

    fun verifyPin(pin: String): Boolean = policy.verify(pin.toByteArray(Charsets.UTF_8))

    fun verifyPinFromChars(pin: CharArray): Boolean = policy.verify(charsToUtf8Bytes(pin))

    fun hasPin(): Boolean = policy.hasPin()

    fun removePin(force: Boolean = false) {
        if (!force && backupChecker?.invoke() == true) {
            throw IllegalStateException(
                "Cannot remove PIN while encrypted backup files exist. " +
                "Use force=true to delete backups and remove PIN."
            )
        }
        policy.removePin()
    }

    /**
     * #370: clear the failed-attempt counter and any lockout, keeping the PIN
     * itself. Called once after an overwrite install / version upgrade so a
     * user who fumbled their PIN before upgrading is not still staring at "out
     * of attempts" on the freshly upgraded build. Committed synchronously
     * because the caller runs it during cold start, before the PIN gate reads
     * the lockout state, so it goes straight to the store rather than through
     * [PinPolicy.resetFailedAttempts] (which writes with `apply()`). The fields
     * cleared are the same ones, via `clearFailureState`.
     */
    fun resetFailedAttempts() {
        store.update(durable = true) { clearFailureState() }
    }

    fun getRemainingAttempts(): Int = policy.getRemainingAttempts()

    fun isLockedOut(): Boolean = policy.isLockedOut()

    fun getLockoutRemainingMs(): Long = policy.getLockoutRemainingMs()

    /** True if the wallet has hit the permanent-lockout threshold (10+ failures). */
    fun isPermanentlyLocked(): Boolean = policy.isPermanentlyLocked()

    private fun charsToUtf8Bytes(pin: CharArray): ByteArray {
        // Avoid going through String to keep the PIN out of the string intern pool.
        return String(pin).toByteArray(Charsets.UTF_8)
    }

    /**
     * [PinStore] over this manager's EncryptedSharedPreferences.
     *
     * Reads `prefs` on every call rather than caching it, because `testPrefs` is
     * assigned after construction and `encryptedPrefs` is deliberately lazy (it
     * touches the keystore).
     */
    private inner class PrefsPinStore : PinStore {

        override fun getPinHash(): String? = prefs.getString(KEY_PIN_HASH, null)

        override fun getSalt(): ByteArray? =
            prefs.getString(KEY_SALT, null)?.hexToByteArray()

        override fun getKdfVersion(): Int? =
            if (prefs.contains(KEY_KDF_VERSION)) prefs.getInt(KEY_KDF_VERSION, 0) else null

        override fun getFailedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

        override fun getLastFailedAt(): Long? =
            if (prefs.contains(KEY_LAST_FAILED_AT)) prefs.getLong(KEY_LAST_FAILED_AT, 0L) else null

        override fun getLockoutUntil(): Long? =
            if (prefs.contains(KEY_LOCKOUT_UNTIL)) prefs.getLong(KEY_LOCKOUT_UNTIL, 0L) else null

        override fun update(block: PinStore.Editor.() -> Unit) = update(durable = false, block)

        /** @param durable `true` to `commit()` instead of `apply()`. */
        fun update(durable: Boolean, block: PinStore.Editor.() -> Unit) {
            val editor = prefs.edit()
            PrefsEditor(editor).block()
            if (durable) editor.commit() else editor.apply()
        }
    }

    private class PrefsEditor(private val editor: SharedPreferences.Editor) : PinStore.Editor {
        override fun pinHash(v: String?) {
            if (v == null) editor.remove(KEY_PIN_HASH) else editor.putString(KEY_PIN_HASH, v)
        }

        override fun salt(v: ByteArray?) {
            if (v == null) {
                editor.remove(KEY_SALT)
            } else {
                editor.putString(KEY_SALT, v.toHexStringNoPrefix())
            }
        }

        override fun kdfVersion(v: Int?) {
            if (v == null) editor.remove(KEY_KDF_VERSION) else editor.putInt(KEY_KDF_VERSION, v)
        }

        override fun failedAttempts(v: Int?) {
            if (v == null) {
                editor.remove(KEY_FAILED_ATTEMPTS)
            } else {
                editor.putInt(KEY_FAILED_ATTEMPTS, v)
            }
        }

        override fun lastFailedAt(v: Long?) {
            if (v == null) {
                editor.remove(KEY_LAST_FAILED_AT)
            } else {
                editor.putLong(KEY_LAST_FAILED_AT, v)
            }
        }

        override fun lockoutUntil(v: Long?) {
            if (v == null) {
                editor.remove(KEY_LOCKOUT_UNTIL)
            } else {
                editor.putLong(KEY_LOCKOUT_UNTIL, v)
            }
        }
    }

    /** `commonMain` has no `SecureRandom`, so the salt entropy comes from here. */
    private object SecureRandomEntropySource : EntropySource {
        private val random = SecureRandom()

        override fun nextBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)
    }

    companion object {
        private const val TAG = "PinManager"
        internal const val PREFS_NAME = "ckb_pin_prefs"
        internal const val PIN_LENGTH = PinPolicy.PIN_LENGTH
        internal const val MAX_ATTEMPTS = PinPolicy.MAX_ATTEMPTS
        internal const val MAX_ATTEMPTS_BEFORE_PERMANENT = PinPolicy.MAX_ATTEMPTS_BEFORE_PERMANENT
        internal const val LOCKOUT_DURATION_MS = PinPolicy.LOCKOUT_DURATION_MS // first lockout (attempts=5)
        internal const val LOCKOUT_DECAY_MS = PinPolicy.LOCKOUT_DECAY_MS // 24 h
        internal const val SALT_SIZE = PinPolicy.SALT_SIZE
        internal const val HASH_OUTPUT_BYTES = PinPolicy.HASH_OUTPUT_BYTES

        internal const val KDF_VERSION_LEGACY_BLAKE2B = PinPolicy.KDF_VERSION_LEGACY_BLAKE2B
        internal const val KDF_VERSION_ARGON2ID = PinPolicy.KDF_VERSION_ARGON2ID

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
