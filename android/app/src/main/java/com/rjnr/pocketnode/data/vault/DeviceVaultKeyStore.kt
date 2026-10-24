package com.rjnr.pocketnode.data.vault

import androidx.fragment.app.FragmentActivity
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.rjnr.pocketnode.data.auth.AuthManager
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException

sealed interface DeviceVaultResult<out T> {
    class Success<T>(val value: T) : DeviceVaultResult<T> {
        override fun toString() = "DeviceVaultResult.Success([redacted])"
    }
    data object Cancelled : DeviceVaultResult<Nothing>
    data class Unavailable(val reason: String) : DeviceVaultResult<Nothing>
}

enum class VaultDevicePromptMode { BIOMETRIC_ONLY, CREDENTIAL_ONLY }

/** Success must follow a fresh CryptoObject authentication, never a UI boolean. */
interface VaultDeviceAuthentication {
    fun unavailableReason(): String?
    fun availablePromptModes(): Set<VaultDevicePromptMode> = setOf(VaultDevicePromptMode.BIOMETRIC_ONLY)
    suspend fun wrap(activity: FragmentActivity, envelope: WalletEnvelope, dataKey: ByteArray, mode: VaultDevicePromptMode = VaultDevicePromptMode.BIOMETRIC_ONLY): DeviceVaultResult<DeviceKeyWrap>
    suspend fun unwrap(activity: FragmentActivity, envelope: WalletEnvelope, title: String, subtitle: String, mode: VaultDevicePromptMode = VaultDevicePromptMode.BIOMETRIC_ONLY): DeviceVaultResult<ByteArray>
}

internal interface VaultDeviceCipherStore {
    fun unavailableReason(): String?
    fun availablePromptModes(): Set<VaultDevicePromptMode> = setOf(VaultDevicePromptMode.BIOMETRIC_ONLY)
    fun encryptionCipher(alias: String): Cipher
    fun decryptionCipher(alias: String, nonce: ByteArray): Cipher?
}

internal fun interface VaultCipherAuthenticator {
    suspend fun authenticate(activity: FragmentActivity, cipher: Cipher, title: String, subtitle: String, mode: VaultDevicePromptMode): AuthManager.CipherAuthResult
}

/** Android convenience wrapping. The independent passphrase envelope must already be committed. */
class DeviceVaultKeyStore internal constructor(
    private val keys: VaultDeviceCipherStore,
    private val authentication: VaultCipherAuthenticator,
) : VaultDeviceAuthentication {
    constructor(auth: AuthManager) : this(
        AndroidVaultDeviceCipherStore(auth),
        VaultCipherAuthenticator { activity, cipher, title, subtitle, mode ->
            auth.authenticateForCipher(activity, cipher, title, subtitle, promptMode = when (mode) {
                VaultDevicePromptMode.BIOMETRIC_ONLY -> AuthManager.CipherPromptMode.BIOMETRIC_ONLY
                VaultDevicePromptMode.CREDENTIAL_ONLY -> AuthManager.CipherPromptMode.CREDENTIAL_ONLY
            })
        },
    )

    override fun unavailableReason(): String? = keys.unavailableReason()
    override fun availablePromptModes(): Set<VaultDevicePromptMode> = keys.availablePromptModes()

    private fun supports(mode: VaultDevicePromptMode): Boolean =
        mode in availablePromptModes() && (mode != VaultDevicePromptMode.CREDENTIAL_ONLY || Build.VERSION.SDK_INT >= 30)

    override suspend fun wrap(activity: FragmentActivity, envelope: WalletEnvelope, dataKey: ByteArray, mode: VaultDevicePromptMode): DeviceVaultResult<DeviceKeyWrap> {
        val owned = dataKey.copyOf()
        try {
            unavailableReason()?.let { return DeviceVaultResult.Unavailable(it) }
            if (!supports(mode)) return DeviceVaultResult.Unavailable("unsupported-device-authentication")
            envelope.validate()
            requireEnvelope(owned.size == 32)
            val id = MessageDigest.getInstance("SHA-256").digest(envelope.walletId.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            val alias = "pocket_node_vault_${id}_${envelope.generation}_${UUID.randomUUID()}"
            val cipher = keys.encryptionCipher(alias)
            return when (val result = authentication.authenticate(activity, cipher, "Enable device authentication", "Your wallet passphrase remains the recovery fallback", mode)) {
                is AuthManager.CipherAuthResult.Success -> {
                    if (result.cipher !== cipher) return DeviceVaultResult.Unavailable("crypto-authentication-failed")
                    // AndroidKeyStore may cache an auth error if AAD is sent before the per-use prompt.
                    cipher.updateAAD(WalletEnvelopeMetadata.device(envelope, alias))
                    DeviceVaultResult.Success(DeviceKeyWrap(alias, cipher.doFinal(owned), cipher.iv))
                }
                AuthManager.CipherAuthResult.Cancelled -> DeviceVaultResult.Cancelled
                is AuthManager.CipherAuthResult.Error -> DeviceVaultResult.Unavailable("device-authentication-failed")
            }
        } catch (error: CancellationException) { throw error }
          catch (_: Exception) { return DeviceVaultResult.Unavailable("device-wrapping-unavailable") }
        finally { owned.fill(0) }
    }

    override suspend fun unwrap(activity: FragmentActivity, envelope: WalletEnvelope, title: String, subtitle: String, mode: VaultDevicePromptMode): DeviceVaultResult<ByteArray> {
        try {
            envelope.validate()
            val wrap = envelope.deviceWrap ?: return DeviceVaultResult.Unavailable("missing-device-wrap")
            unavailableReason()?.let { return DeviceVaultResult.Unavailable(it) }
            if (!supports(mode)) return DeviceVaultResult.Unavailable("unsupported-device-authentication")
            // Read-only lookup. Never create a key in the decryption path.
            val cipher = keys.decryptionCipher(wrap.alias, wrap.nonce) ?: return DeviceVaultResult.Unavailable("missing-device-key")
            return when (val result = authentication.authenticate(activity, cipher, title, subtitle, mode)) {
                is AuthManager.CipherAuthResult.Success -> {
                    if (result.cipher !== cipher) return DeviceVaultResult.Unavailable("crypto-authentication-failed")
                    cipher.updateAAD(WalletEnvelopeMetadata.device(envelope, wrap.alias))
                    val key = cipher.doFinal(wrap.wrappedKey)
                    if (key.size != 32) { key.fill(0); return DeviceVaultResult.Unavailable("invalid-device-wrap") }
                    DeviceVaultResult.Success(key)
                }
                AuthManager.CipherAuthResult.Cancelled -> DeviceVaultResult.Cancelled
                is AuthManager.CipherAuthResult.Error -> DeviceVaultResult.Unavailable("device-authentication-failed")
            }
        } catch (error: CancellationException) { throw error }
          catch (_: KeyPermanentlyInvalidatedException) { return DeviceVaultResult.Unavailable("invalidated-device-key") }
          catch (_: javax.crypto.AEADBadTagException) { return DeviceVaultResult.Unavailable("device-wrap-integrity") }
          catch (_: WalletEnvelopeFormatException) { return DeviceVaultResult.Unavailable("malformed-device-envelope") }
          catch (_: Exception) { return DeviceVaultResult.Unavailable("device-unwrapping-unavailable") }
    }
}

private class AndroidVaultDeviceCipherStore(private val auth: AuthManager) : VaultDeviceCipherStore {
    override fun unavailableReason(): String? = when {
        !auth.hasDeviceCredential() -> "no-device-lock"
        Build.VERSION.SDK_INT < 30 &&
            (!auth.isBiometricEnabled() || !auth.isBiometricEnrolled()) -> "unsupported-device-authentication"
        else -> null
    }

    override fun availablePromptModes(): Set<VaultDevicePromptMode> {
        if (!auth.hasDeviceCredential()) return emptySet()
        return buildSet {
            if (auth.isBiometricEnabled() && auth.isBiometricEnrolled()) add(VaultDevicePromptMode.BIOMETRIC_ONLY)
            if (Build.VERSION.SDK_INT >= 30) add(VaultDevicePromptMode.CREDENTIAL_ONLY)
        }
    }

    override fun encryptionCipher(alias: String): Cipher {
        check(unavailableReason() == null)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!store.containsAlias(alias))
        val builder = KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            var types = KeyProperties.AUTH_DEVICE_CREDENTIAL
            if (auth.isBiometricEnabled() && auth.isBiometricEnrolled()) types = types or KeyProperties.AUTH_BIOMETRIC_STRONG
            builder.setUserAuthenticationParameters(0, types)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(builder.build())
        val key = generator.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun decryptionCipher(alias: String, nonce: ByteArray): Cipher? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(alias, null) as? SecretKey ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        }
    }
}

/** Stable device adapter result codes, never exception text or UI strings, classify recovery choices. */
internal fun deviceFailureKind(reason: String): VaultFailureKind = when (reason) {
    "missing-device-key" -> VaultFailureKind.MISSING_DEVICE_KEY
    "invalidated-device-key" -> VaultFailureKind.INVALIDATED_DEVICE_KEY
    "missing-device-wrap" -> VaultFailureKind.NO_DEVICE_WRAP
    "no-device-lock" -> VaultFailureKind.NO_DEVICE_LOCK
    "unsupported-device-authentication" -> VaultFailureKind.UNSUPPORTED_DEVICE_AUTH
    "device-wrap-integrity", "invalid-device-wrap", "crypto-authentication-failed" -> VaultFailureKind.PAYLOAD_INTEGRITY
    "malformed-device-envelope" -> VaultFailureKind.MALFORMED_OR_UNSUPPORTED
    else -> VaultFailureKind.TEMPORARY_DEVICE_FAILURE
}
