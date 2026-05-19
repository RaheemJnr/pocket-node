package com.rjnr.pocketnode.data.crypto

import android.os.Build
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
 * ## Key aliases
 *
 * Two keys coexist while the v1.7.0 user-authentication migration is in flight:
 *
 * - **V1** (`pocket_node_key_material`): unrestricted. Any caller that can
 *   reach the Keystore service can decrypt the wallet blobs. This is what
 *   v1.6.x and earlier shipped with, and what the Keystore audit
 *   ([#187 Finding High 1](https://github.com/RaheemJnr/pocket-node/issues/187))
 *   flagged as inadequate.
 * - **V2** (`pocket_node_key_material_v2`): bound to the user's biometric or
 *   device credential via `setUserAuthenticationRequired(true)`. Every
 *   `Cipher.doFinal` requires a fresh `BiometricPrompt.authenticate(promptInfo,
 *   CryptoObject(cipher))` before the keystore releases the key.
 *
 * ## Current state
 *
 * - Existing call sites (`KeyStoreMigrationHelper`) use the V1 API.
 *   Behavior on v1.7.0 is unchanged from v1.6.x for those callers.
 * - The V2 API is wired but unused. Migration from V1 to V2 happens in the
 *   follow-up sub-PR for #213.
 * - Once migration ships, V1 is deleted and only V2 remains.
 *
 * ## API surface
 *
 * Callers obtain a `Cipher` via [newEncryptCipher] / [newDecryptCipher] (V1)
 * or [newEncryptCipherV2] / [newDecryptCipherV2] (V2). The Cipher returned
 * by the V2 variants is **not yet authenticated** — the caller must pass it
 * through `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))`
 * before invoking [encryptWithCipher] / [decryptWithCipher]. Without that
 * step, `doFinal` throws `UserNotAuthenticatedException`.
 */
@Singleton
class KeystoreEncryptionManager @Inject constructor() {

    private var testKey: SecretKey? = null
    private var testKeyV2: SecretKey? = null

    private val secretKey: SecretKey
        get() = testKey ?: getOrCreateKeystoreKey()

    private val secretKeyV2: SecretKey
        get() = testKeyV2 ?: getOrCreateKeystoreKeyV2()

    // -- V1 (unrestricted) API ---------------------------------------------

    /** Returns a fresh `Cipher` in ENCRYPT_MODE under the V1 (unrestricted) key. */
    fun newEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher
    }

    /** Returns a fresh `Cipher` in DECRYPT_MODE under the V1 (unrestricted) key. */
    fun newDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    // -- V2 (auth-bound) API -----------------------------------------------

    /**
     * Returns a fresh `Cipher` in ENCRYPT_MODE under the V2 (auth-bound) key.
     *
     * The caller must run the returned Cipher through
     * `BiometricPrompt.authenticate(promptInfo, CryptoObject(cipher))` before
     * calling [encryptWithCipher]. Calling `doFinal` on an unauthenticated
     * Cipher throws `UserNotAuthenticatedException`.
     */
    fun newEncryptCipherV2(): Cipher {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeyV2)
        return cipher
    }

    /**
     * Returns a fresh `Cipher` in DECRYPT_MODE under the V2 (auth-bound) key.
     *
     * Same authentication requirement as [newEncryptCipherV2].
     */
    fun newDecryptCipherV2(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKeyV2, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    // -- Cipher use (shared between V1 and V2) -----------------------------

    /**
     * Encrypt `plaintext` using a Cipher obtained from [newEncryptCipher] or
     * [newEncryptCipherV2]. Returns (ciphertext, iv).
     */
    fun encryptWithCipher(cipher: Cipher, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val ciphertext = cipher.doFinal(plaintext)
        return Pair(ciphertext, cipher.iv)
    }

    /**
     * Decrypt `ciphertext` using a Cipher obtained from [newDecryptCipher] or
     * [newDecryptCipherV2].
     */
    fun decryptWithCipher(cipher: Cipher, ciphertext: ByteArray): ByteArray {
        return cipher.doFinal(ciphertext)
    }

    // -- Key acquisition ---------------------------------------------------

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

    private fun getOrCreateKeystoreKeyV2(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        keyStore.getEntry(KEY_ALIAS_V2, null)?.let { entry ->
            return (entry as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val specBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS_V2,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // The OS-enforced auth binding: doFinal will throw
            // UserNotAuthenticatedException until BiometricPrompt unlocks the
            // Cipher via CryptoObject(cipher).
            .setUserAuthenticationRequired(true)
            // Adding or removing a biometric enrollment invalidates the key.
            // Forces re-import after a fingerprint change. This is the right
            // default for a money-storing wallet: a thief who enrolls their
            // own fingerprint cannot read existing data.
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Per-operation auth, no grace window. Each doFinal needs a fresh
            // BiometricPrompt success. AUTH_BIOMETRIC_STRONG plus
            // AUTH_DEVICE_CREDENTIAL lets users without enrolled biometrics
            // fall back to the device PIN/pattern set in Android Settings.
            specBuilder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            // API 26-29 uses the legacy validity-duration knob. -1 means
            // "every operation needs fresh auth" (the closest analogue of
            // setUserAuthenticationParameters(0, ...)).
            @Suppress("DEPRECATION")
            specBuilder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        keyGen.init(specBuilder.build())
        return keyGen.generateKey()
    }

    // -- Migration helpers (used by the upcoming v1.6.x -> v1.7.0 flow) ----

    /** Delete the V1 (unrestricted) Keystore key. Called by the migration after re-encryption finishes. */
    fun deleteV1Key() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    /** True if the V1 (unrestricted) Keystore key is still present. */
    fun hasV1Key(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        return keyStore.containsAlias(KEY_ALIAS)
    }

    /** True if the V2 (auth-bound) Keystore key has been generated. */
    fun hasV2Key(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)
        return keyStore.containsAlias(KEY_ALIAS_V2)
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pocket_node_key_material"
        private const val KEY_ALIAS_V2 = "pocket_node_key_material_v2"
        private const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256

        @VisibleForTesting
        fun createForTest(): KeystoreEncryptionManager {
            val keyGen1 = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }
            val keyGen2 = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }
            return KeystoreEncryptionManager().apply {
                testKey = keyGen1.generateKey()
                testKeyV2 = keyGen2.generateKey()
            }
        }
    }
}
