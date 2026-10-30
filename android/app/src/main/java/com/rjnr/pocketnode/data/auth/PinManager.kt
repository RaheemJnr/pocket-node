package com.rjnr.pocketnode.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rjnr.pocketnode.core.crypto.Argon2id
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
 * on a normal signed upgrade would grant a fresh guessing budget, so version
 * bookkeeping never clears it. When
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

    private val walletStorage = com.rjnr.pocketnode.data.wallet.WalletStorageBoundary(context)

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

    /**
     * Argon2id cost. Defaults follow the OWASP ASVS 4.0.3 baseline; tests lower
     * them to avoid 64 MB / 300 ms per verify.
     */
    private var argon2Params = Argon2id.Params(
        iterations = PinPolicy.ARGON2_ITERATIONS,
        memoryKib = PinPolicy.ARGON2_MEMORY_KIB,
        parallelism = PinPolicy.ARGON2_PARALLELISM,
        tagLength = PinPolicy.HASH_OUTPUT_BYTES,
    )

    /**
     * Rebuilt whenever [argon2Params] changes: `PinPolicy.argon2Params` has an
     * `internal` setter, which this module cannot reach. The policy holds no
     * state of its own beyond those parameters (everything else lives in
     * [store]), so replacing the instance is equivalent to mutating it.
     */
    private var policy = newPolicy()

    private fun newPolicy() = PinPolicy(
        store = store,
        entropy = SecureRandomEntropySource,
        // Read through the property, not captured by value: tests replace
        // `timeProvider` after construction.
        clock = { timeProvider() },
        logger = logger,
        argon2Params = argon2Params,
    )

    private fun setArgon2Params(params: Argon2id.Params) {
        argon2Params = params
        policy = newPolicy()
    }

    @VisibleForTesting
    internal var argon2Iterations: Int
        get() = argon2Params.iterations
        set(value) = setArgon2Params(argon2Params.copy(iterations = value))

    @VisibleForTesting
    internal var argon2MemoryKb: Int
        get() = argon2Params.memoryKib
        set(value) = setArgon2Params(argon2Params.copy(memoryKib = value))

    @VisibleForTesting
    internal var argon2Parallelism: Int
        get() = argon2Params.parallelism
        set(value) = setArgon2Params(argon2Params.copy(parallelism = value))

    /** Setup is create-only, including concurrent requests and both public input forms. */
    fun setPin(pin: String) = walletStorage.readOrWriteFiles {
        check(!uncertainChange && !policy.hasPin()) { "A PIN already exists. Setup cannot replace it." }
        // Deliberately wider than the policy's own check, which accepts only
        // ASCII 0-9 bytes: `Char.isDigit()` also passes Arabic-Indic and other
        // Unicode digits. The PIN keypad emits ASCII only, so in practice the
        // two agree; a non-ASCII digit arriving from anywhere else is rejected
        // by the policy a moment later rather than silently stored.
        require(pin.length == PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must be exactly $PIN_LENGTH digits"
        }
        policy.setPin(pin.toByteArray(Charsets.UTF_8))
    }

    fun setPinFromChars(pin: CharArray) = walletStorage.readOrWriteFiles {
        check(!uncertainChange && !policy.hasPin()) { "A PIN already exists. Setup cannot replace it." }
        require(pin.size == PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must be exactly $PIN_LENGTH digits"
        }
        policy.setPin(charsToUtf8Bytes(pin))
    }

    internal class ChangeAuthorization internal constructor(
        internal val owner: PinManager,
        internal val auth: AuthManager,
        internal val generation: Long,
        internal val hash: String,
        internal val salt: String?,
        internal val version: Int?,
    )

    internal enum class ChangeResult { CHANGED, REJECTED, FAILED_RESTORED, FAILED_UNCERTAIN }

    // A failed write plus failed restoration has no trustworthy in-process credential outcome.
    @Volatile private var uncertainChange = false

    internal fun hasUnconfirmedChange(): Boolean = uncertainChange

    /** Fresh PIN proof can establish a PIN session after a biometric dashboard unlock. */
    internal fun authorizeChange(pin: CharArray, auth: AuthManager, generation: Long): ChangeAuthorization? {
        val verified = walletStorage.readOrWriteFiles {
            if (uncertainChange || auth.sessionGeneration() != generation) return@readOrWriteFiles null
            val bytes = charsToUtf8Bytes(pin)
            val verified = try { policy.verify(bytes) } finally { bytes.fill(0) }
            if (!verified) return@readOrWriteFiles null
            ChangeAuthorization(this, auth, generation, store.getPinHash()!!,
                prefs.getString(KEY_SALT, null), store.getKdfVersion())
        } ?: return null
        // Recheck the verifier and reset fence at publication, after releasing the verification
        // lock. A reset/replacement/revocation winning this handoff cannot publish the old PIN.
        val liveGeneration = auth.setSessionPinAndGetGenerationIfCurrent(pin, generation) {
            !uncertainChange && !policy.isLockedOut() && sameVerifier(verified)
        } ?: return null
        return ChangeAuthorization(this, auth, liveGeneration, verified.hash, verified.salt, verified.version)
    }

    /** Stage without mutating storage; the short session claim is the authorization commit point. */
    internal fun changePin(authorization: ChangeAuthorization, pin: CharArray): ChangeResult {
        require(pin.size == PIN_LENGTH && pin.all { it in '0'..'9' }) { "PIN must be exactly six digits" }
        if (!walletStorage.readOrWriteFiles { validChange(authorization) }) return ChangeResult.REJECTED
        val salt = SecureRandomEntropySource.nextBytes(SALT_SIZE)
        val bytes = charsToUtf8Bytes(pin)
        val hash = try {
            val output = Argon2id.hash(bytes, salt, argon2Params)
            try { output.toHexStringNoPrefix() } finally { output.fill(0) }
        } finally { bytes.fill(0) }
        return try {
            walletStorage.readOrWriteFiles {
                if (!validChange(authorization)) return@readOrWriteFiles ChangeResult.REJECTED
                val before = prefs.all.filterKeys { it in PIN_STATE_KEYS }
                // Lock order is files -> session. Revocation must remain responsive during commit.
                if (!authorization.auth.claimPinChange(authorization.generation)) return@readOrWriteFiles ChangeResult.REJECTED
                val saved = runCatching {
                    val editor = prefs.edit()
                    PrefsEditor(editor).apply {
                        pinHash(hash); salt(salt); kdfVersion(PinPolicy.KDF_VERSION_ARGON2ID)
                        clearFailureState()
                    }
                    editor.commit()
                }.getOrDefault(false)
                if (saved) ChangeResult.CHANGED else {
                    // SharedPreferences may publish in-memory values even when commit returns false.
                    val restored = runCatching {
                        val editor = prefs.edit()
                        PIN_STATE_KEYS.forEach { editor.remove(it) }
                        before.forEach { (key, value) ->
                            when (value) {
                                is String -> editor.putString(key, value)
                                is Int -> editor.putInt(key, value)
                                is Long -> editor.putLong(key, value)
                            }
                        }
                        editor.commit()
                    }.getOrDefault(false)
                    uncertainChange = !restored
                    if (restored) ChangeResult.FAILED_RESTORED else ChangeResult.FAILED_UNCERTAIN
                }
            }
        } finally { salt.fill(0) }
    }

    private fun validChange(authorization: ChangeAuthorization): Boolean =
        !uncertainChange && authorization.owner === this &&
            authorization.auth.isSessionCurrent(authorization.generation) && !policy.isLockedOut() && sameVerifier(authorization)

    private fun sameVerifier(authorization: ChangeAuthorization): Boolean =
        authorization.hash == store.getPinHash() && authorization.salt == prefs.getString(KEY_SALT, null) &&
            authorization.version == store.getKdfVersion()

    // Verification may migrate a legacy hash, so it shares the replacement/reset writer fence.
    fun verifyPin(pin: String): Boolean {
        val chars = pin.toCharArray()
        return try { verifyPinFromChars(chars) } finally { chars.fill('\u0000') }
    }

    fun verifyPinFromChars(pin: CharArray): Boolean = walletStorage.readOrWriteFiles {
        if (uncertainChange) return@readOrWriteFiles false
        val bytes = charsToUtf8Bytes(pin)
        try { policy.verify(bytes) } finally { bytes.fill(0) }
    }

    fun hasPin(): Boolean = uncertainChange || policy.hasPin()

    /** Reset has already verified every secret store empty; persist removal before process restart. */
    internal fun removePinDurablyForReset() = walletStorage.files {
        check(prefs.edit().remove(KEY_PIN_HASH).remove(KEY_SALT).remove(KEY_KDF_VERSION)
            .remove(KEY_FAILED_ATTEMPTS).remove(KEY_LAST_FAILED_AT).remove(KEY_LOCKOUT_UNTIL).commit()) {
            "PIN reset could not be persisted."
        }
    }

    fun removePin(force: Boolean = false) = walletStorage.readOrWriteFiles {
        if (!force && backupChecker?.invoke() == true) {
            throw IllegalStateException(
                "Cannot remove PIN while encrypted backup files exist. " +
                "Use force=true to delete backups and remove PIN."
            )
        }
        policy.removePin()
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

        override fun update(block: PinStore.Editor.() -> Unit) = edit(commit = false, block)

        override fun updateDurable(block: PinStore.Editor.() -> Unit) = edit(commit = true, block)

        /** @param commit `true` to `commit()` (synchronous) rather than `apply()`. */
        private fun edit(commit: Boolean, block: PinStore.Editor.() -> Unit) {
            val editor = prefs.edit()
            PrefsEditor(editor).block()
            if (commit) editor.commit() else editor.apply()
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
        private val PIN_STATE_KEYS = setOf(KEY_PIN_HASH, KEY_SALT, KEY_KDF_VERSION,
            KEY_FAILED_ATTEMPTS, KEY_LAST_FAILED_AT, KEY_LOCKOUT_UNTIL)

    }
}
