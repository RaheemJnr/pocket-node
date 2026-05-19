package com.rjnr.pocketnode.data.auth

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class AuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @VisibleForTesting
    internal var testPrefs: SharedPreferences? = null

    private var sessionPin: CharArray? = null

    fun setSessionPin(pin: CharArray) {
        clearSession()
        sessionPin = pin.copyOf()
    }

    fun getSessionPin(): CharArray? = sessionPin?.copyOf()

    fun hasSessionPin(): Boolean = sessionPin != null

    fun clearSession() {
        sessionPin?.let { java.util.Arrays.fill(it, '\u0000') }
        sessionPin = null
    }

    private val prefs: SharedPreferences
        get() = testPrefs ?: defaultPrefs

    private val defaultPrefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @VisibleForTesting
    internal var testBiometricManager: BiometricManager? = null

    private val biometricMgr: BiometricManager
        get() = testBiometricManager ?: BiometricManager.from(context)

    enum class BiometricStatus {
        AVAILABLE,
        NO_HARDWARE,
        NOT_ENROLLED,
        UNAVAILABLE
    }

    fun isBiometricAvailable(): BiometricStatus {
        return when (biometricMgr.canAuthenticate(Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            else -> BiometricStatus.UNAVAILABLE
        }
    }

    fun isBiometricEnrolled(): Boolean =
        isBiometricAvailable() == BiometricStatus.AVAILABLE

    fun isBiometricEnabled(): Boolean =
        prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun isAuthBeforeSendEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTH_BEFORE_SEND, false)

    fun setAuthBeforeSendEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTH_BEFORE_SEND, enabled).apply()
    }

    fun hasDeviceCredential(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            biometricMgr.canAuthenticate(Authenticators.DEVICE_CREDENTIAL) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } else {
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguard.isDeviceSecure
        }
    }

    fun getAllowedAuthenticators(): Int {
        return if (isBiometricEnrolled() && isBiometricEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            } else {
                Authenticators.BIOMETRIC_STRONG
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_STRONG
        }
    }

    /**
     * Outcome of a BiometricPrompt CryptoObject-bound authentication.
     *
     * `Success` carries the same [Cipher] the caller passed in, now unlocked
     * by the Android Keystore. The caller must run `cipher.doFinal(...)`
     * before any process boundary — Keystore will not honor it for a fresh
     * `doFinal` later because V2 keys are configured with
     * `setUserAuthenticationParameters(0, ...)` (per-operation auth).
     *
     * `Cancelled` is returned on user-cancel and negative-button press. The
     * caller is responsible for routing to a fallback (PIN, navigation).
     *
     * `Error` carries the BiometricPrompt error code and message. The auth
     * was rejected by the framework — distinguished from cancel so the UI
     * can show a transient error (e.g. "Too many attempts, try again later").
     */
    sealed class CipherAuthResult {
        data class Success(val cipher: Cipher) : CipherAuthResult()
        object Cancelled : CipherAuthResult()
        data class Error(val errorCode: Int, val errString: CharSequence) : CipherAuthResult()
    }

    /**
     * Prompt the user via BiometricPrompt and return the same [cipher] back
     * once Keystore has authorized it. Used by the V2 (auth-bound) wallet
     * key path: the caller obtains an uninitialized-for-`doFinal` Cipher
     * via `KeystoreEncryptionManager.newEncryptCipherV2` /
     * `newDecryptCipherV2`, runs it through this helper, and only then
     * passes it to `encryptWithCipher` / `decryptWithCipher`.
     *
     * The prompt always uses [getAllowedAuthenticators] so users without
     * biometrics can fall back to the device PIN/pattern set in Android
     * Settings. The negative-button text is only shown for biometric-only
     * prompts; [Authenticators.DEVICE_CREDENTIAL] disallows it.
     *
     * Suspending: the coroutine is resumed when the framework callback
     * fires. Cancelling the coroutine cancels the in-flight prompt.
     */
    suspend fun authenticateForCipher(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeButtonText: String = "Cancel"
    ): CipherAuthResult = suspendCancellableCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authedCipher = result.cryptoObject?.cipher
                    if (authedCipher != null) {
                        cont.resume(CipherAuthResult.Success(authedCipher))
                    } else {
                        // Defensive: framework returned success without a
                        // CryptoObject. Should not happen when we pass one
                        // in, but treat it as an error rather than silently
                        // forging a Success with the un-authenticated cipher
                        // (which would fail later at doFinal).
                        cont.resume(
                            CipherAuthResult.Error(
                                BiometricPrompt.ERROR_VENDOR,
                                "Authenticator returned no CryptoObject"
                            )
                        )
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val outcome = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> CipherAuthResult.Cancelled
                        else -> CipherAuthResult.Error(errorCode, errString)
                    }
                    cont.resume(outcome)
                }
            }
        )

        val allowed = getAllowedAuthenticators()
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
        // DEVICE_CREDENTIAL cannot coexist with a negative button — the
        // framework throws IllegalArgumentException. The fallback button is
        // only meaningful for biometric-only prompts.
        if ((allowed and Authenticators.DEVICE_CREDENTIAL) == 0) {
            builder.setNegativeButtonText(negativeButtonText)
        }

        try {
            prompt.authenticate(builder.build(), BiometricPrompt.CryptoObject(cipher))
        } catch (e: IllegalStateException) {
            // The framework rejects authenticate() (e.g. fragment already
            // destroyed). Surface as Error so the caller sees a deterministic
            // failure rather than a hung coroutine.
            cont.resume(CipherAuthResult.Error(BiometricPrompt.ERROR_VENDOR, e.message ?: "prompt failed"))
        }

        cont.invokeOnCancellation {
            try { prompt.cancelAuthentication() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val PREFS_NAME = "ckb_auth_settings"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_AUTH_BEFORE_SEND = "auth_before_send"
    }
}
