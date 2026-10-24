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

/** Success must follow a fresh CryptoObject authentication, never a UI boolean. */
interface VaultDeviceAuthentication {
    fun unavailableReason(): String?
    suspend fun wrap(activity: FragmentActivity, envelope: WalletEnvelope, dataKey: ByteArray): DeviceVaultResult<DeviceKeyWrap>
    suspend fun unwrap(activity: FragmentActivity, envelope: WalletEnvelope, title: String, subtitle: String): DeviceVaultResult<ByteArray>
}

internal interface VaultDeviceCipherStore {
    fun unavailableReason(): String?
    fun encryptionCipher(alias: String): Cipher
    fun decryptionCipher(alias: String, nonce: ByteArray): Cipher?
}

internal fun interface VaultCipherAuthenticator {
    suspend fun authenticate(activity: FragmentActivity, cipher: Cipher, title: String, subtitle: String): AuthManager.CipherAuthResult
}

/** Android convenience wrapping. The independent passphrase envelope must already be committed. */
class DeviceVaultKeyStore internal constructor(
    private val keys: VaultDeviceCipherStore,
    private val authentication: VaultCipherAuthenticator,
) : VaultDeviceAuthentication {
    constructor(auth: AuthManager) : this(
        AndroidVaultDeviceCipherStore(auth),
        VaultCipherAuthenticator { activity, cipher, title, subtitle -> auth.authenticateForCipher(activity, cipher, title, subtitle) },
    )

    override fun unavailableReason(): String? = keys.unavailableReason()

    override suspend fun wrap(activity: FragmentActivity, envelope: WalletEnvelope, dataKey: ByteArray): DeviceVaultResult<DeviceKeyWrap> {
        val owned = dataKey.copyOf()
        try {
            unavailableReason()?.let { return DeviceVaultResult.Unavailable(it) }
            envelope.validate()
            requireEnvelope(owned.size == 32)
            val id = MessageDigest.getInstance("SHA-256").digest(envelope.walletId.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            val alias = "pocket_node_vault_${id}_${envelope.generation}_${UUID.randomUUID()}"
            val cipher = keys.encryptionCipher(alias)
            cipher.updateAAD(WalletEnvelopeMetadata.device(envelope, alias))
            return when (val result = authentication.authenticate(activity, cipher, "Enable device authentication", "Your wallet passphrase remains the recovery fallback")) {
                is AuthManager.CipherAuthResult.Success -> {
                    if (result.cipher !== cipher) return DeviceVaultResult.Unavailable("crypto-authentication-failed")
                    DeviceVaultResult.Success(DeviceKeyWrap(alias, cipher.doFinal(owned), cipher.iv))
                }
                AuthManager.CipherAuthResult.Cancelled -> DeviceVaultResult.Cancelled
                is AuthManager.CipherAuthResult.Error -> DeviceVaultResult.Unavailable("device-authentication-failed")
            }
        } catch (error: CancellationException) { throw error }
          catch (_: Exception) { return DeviceVaultResult.Unavailable("device-wrapping-unavailable") }
        finally { owned.fill(0) }
    }

    override suspend fun unwrap(activity: FragmentActivity, envelope: WalletEnvelope, title: String, subtitle: String): DeviceVaultResult<ByteArray> {
        try {
            envelope.validate()
            val wrap = envelope.deviceWrap ?: return DeviceVaultResult.Unavailable("missing-device-wrap")
            unavailableReason()?.let { return DeviceVaultResult.Unavailable(it) }
            // Read-only lookup. Never create a key in the decryption path.
            val cipher = keys.decryptionCipher(wrap.alias, wrap.nonce) ?: return DeviceVaultResult.Unavailable("missing-device-key")
            cipher.updateAAD(WalletEnvelopeMetadata.device(envelope, wrap.alias))
            return when (val result = authentication.authenticate(activity, cipher, title, subtitle)) {
                is AuthManager.CipherAuthResult.Success -> {
                    if (result.cipher !== cipher) return DeviceVaultResult.Unavailable("crypto-authentication-failed")
                    val key = cipher.doFinal(wrap.wrappedKey)
                    if (key.size != 32) { key.fill(0); return DeviceVaultResult.Unavailable("invalid-device-wrap") }
                    DeviceVaultResult.Success(key)
                }
                AuthManager.CipherAuthResult.Cancelled -> DeviceVaultResult.Cancelled
                is AuthManager.CipherAuthResult.Error -> DeviceVaultResult.Unavailable("device-authentication-failed")
            }
        } catch (error: CancellationException) { throw error }
          catch (_: KeyPermanentlyInvalidatedException) { return DeviceVaultResult.Unavailable("invalidated-device-key") }
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
