package com.rjnr.pocketnode.data.crypto

import kotlinx.serialization.Serializable

/**
 * The plaintext bundle written under the V2 (auth-bound) Keystore key.
 *
 * V1 stored `privateKey` and `mnemonic` as two separate AES-GCM ciphertexts
 * with two separate IVs. V2 stores them as a single JSON blob inside one
 * AES-GCM ciphertext, so each wallet's migration is exactly one
 * BiometricPrompt + one `Cipher.doFinal` per direction. Reduces the prompt
 * count from 2N to N during migration (where N = number of wallets).
 *
 * Lives in the shared module (#511) because iOS onboarding writes and reads the
 * same JSON shape. The Keystore / Secure Enclave binding stays platform side;
 * only the model and its codec are shared.
 */
@Serializable
data class WalletKeyBundle(
    val privateKeyHex: String,
    val mnemonic: String? = null,
)
