package com.rjnr.pocketnode.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM wrapper around the Android Keystore-backed wallet encryption key.
 *
 * ## API surface
 *
 * Callers must build a `Cipher` (via [newEncryptCipher] or [newDecryptCipher])
 * and then invoke [encryptWithCipher] / [decryptWithCipher]. The separation
 * exists because the Keystore is moving to per-operation user-authentication
 * binding (see #213). A future change will require callers to thread the
 * Cipher through `BiometricPrompt.CryptoObject(cipher)` before they call
 * `doFinal`; isolating cipher-acquisition from cipher-use makes that change
 * mechanical.
 *
 * For the moment the spec on the underlying [SecretKey] is unchanged — the
 * Cipher is usable without a separate auth step. The audit-binding flags
 * land in the follow-up sub-PR.
 */
@Singleton
class KeystoreEncryptionManager @Inject constructor() {

    private var testKey: SecretKey? = null

    private val secretKey: SecretKey
        get() = testKey ?: getOrCreateKeystoreKey()

    /** Returns a fresh `Cipher` in ENCRYPT_MODE, ready for [encryptWithCipher]. */
    fun newEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher
    }

    /** Returns a fresh `Cipher` in DECRYPT_MODE for the given IV, ready for [decryptWithCipher]. */
    fun newDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    /**
     * Encrypt `plaintext` using a Cipher obtained from [newEncryptCipher].
     * Returns (ciphertext, iv).
     */
    fun encryptWithCipher(cipher: Cipher, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val ciphertext = cipher.doFinal(plaintext)
        return Pair(ciphertext, cipher.iv)
    }

    /** Decrypt `ciphertext` using a Cipher obtained from [newDecryptCipher]. */
    fun decryptWithCipher(cipher: Cipher, ciphertext: ByteArray): ByteArray {
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        keyStore.getEntry(KEY_ALIAS, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return keyGen.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pocket_node_key_material"
        private const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256

        @VisibleForTesting
        fun createForTest(): KeystoreEncryptionManager {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(KEY_SIZE_BITS)
            return KeystoreEncryptionManager().apply {
                testKey = keyGen.generateKey()
            }
        }
    }
}
