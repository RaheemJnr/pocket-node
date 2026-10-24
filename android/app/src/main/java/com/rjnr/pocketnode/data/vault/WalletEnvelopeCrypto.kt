package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.core.crypto.Argon2id
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * Owns temporary DEKs, derived keys and rotation plaintext; clears them in finally blocks.
 * Input buffers belong to callers, as do returned plaintext and unwrapped keys: callers must clear
 * them. JVM/provider key copies cannot be guaranteed erased. No plaintext Strings are constructed.
 * Envelopes are mutable-array DTOs; callers must not mutate them during an operation.
 */
class WalletEnvelopeCrypto(private val random: SecureRandom) {
    @Inject constructor() : this(SecureRandom())

    fun create(walletId: String, generation: Long, plaintext: ByteArray, passphrase: CharArray): WalletEnvelope {
        requireEnvelope(plaintext.size <= WalletEnvelope.MAX_PLAINTEXT_BYTES)
        requireEnvelope(WalletPassphrasePolicy.validate(passphrase))
        val aad = WalletEnvelopeMetadata.payload(walletId, WalletEnvelope.VERSION, generation)
        val dataKey = randomBytes(32)
        try {
            val nonce = randomBytes(12)
            val ciphertext = crypt(Cipher.ENCRYPT_MODE, dataKey, nonce, aad, plaintext)
            val shell = WalletEnvelope(
                version = WalletEnvelope.VERSION,
                walletId = walletId,
                generation = generation,
                ciphertext = ciphertext,
                nonce = nonce,
                passphraseWrap = PassphraseKeyWrap(randomBytes(32), wrappedKey = ByteArray(48), nonce = randomBytes(12)),
            )
            return wrap(shell, dataKey, passphrase)
        } finally { dataKey.fill(0) }
    }

    fun decrypt(envelope: WalletEnvelope, walletId: String, passphrase: CharArray): ByteArray {
        val key = unwrapDataKey(envelope, walletId, passphrase)
        try { return decryptWithDataKey(envelope, walletId, key) }
        finally { key.fill(0) }
    }

    /** Caller owns and must clear the returned key. */
    fun unwrapDataKey(envelope: WalletEnvelope, walletId: String, passphrase: CharArray): ByteArray {
        validateIdentity(envelope, walletId)
        val wrappingKey = derive(passphrase, envelope.passphraseWrap)
        try {
            return crypt(Cipher.DECRYPT_MODE, wrappingKey, envelope.passphraseWrap.nonce,
                WalletEnvelopeMetadata.passphrase(envelope), envelope.passphraseWrap.wrappedKey)
        } finally { wrappingKey.fill(0) }
    }

    fun decryptWithDataKey(envelope: WalletEnvelope, walletId: String, dataKey: ByteArray): ByteArray {
        validateIdentity(envelope, walletId)
        requireEnvelope(dataKey.size == 32)
        return crypt(Cipher.DECRYPT_MODE, dataKey, envelope.nonce,
            WalletEnvelopeMetadata.payload(walletId, envelope.version, envelope.generation), envelope.ciphertext)
    }

    /** New generation requires new payload AAD and invalidates the old device wrap. */
    fun rewrap(
        envelope: WalletEnvelope,
        walletId: String,
        oldPassphrase: CharArray,
        newPassphrase: CharArray,
        generation: Long,
    ): WalletEnvelope {
        validateIdentity(envelope, walletId)
        requireEnvelope(generation > envelope.generation)
        requireEnvelope(WalletPassphrasePolicy.validate(newPassphrase))
        val key = unwrapDataKey(envelope, walletId, oldPassphrase)
        try {
            val plaintext = decryptWithDataKey(envelope, walletId, key)
            try {
                val nonce = randomBytes(12)
                val ciphertext = crypt(Cipher.ENCRYPT_MODE, key, nonce,
                    WalletEnvelopeMetadata.payload(walletId, envelope.version, generation), plaintext)
                val next = envelope.copy(
                    generation = generation, ciphertext = ciphertext, nonce = nonce, deviceWrap = null,
                    passphraseWrap = PassphraseKeyWrap(randomBytes(32), wrappedKey = ByteArray(48), nonce = randomBytes(12)),
                )
                return wrap(next, key, newPassphrase)
            } finally { plaintext.fill(0) }
        } finally { key.fill(0) }
    }

    private fun wrap(envelope: WalletEnvelope, dataKey: ByteArray, passphrase: CharArray): WalletEnvelope {
        val wrappingKey = derive(passphrase, envelope.passphraseWrap)
        try {
            val wrapped = crypt(Cipher.ENCRYPT_MODE, wrappingKey, envelope.passphraseWrap.nonce,
                WalletEnvelopeMetadata.passphrase(envelope), dataKey)
            return envelope.copy(passphraseWrap = envelope.passphraseWrap.copy(wrappedKey = wrapped))
        } finally { wrappingKey.fill(0) }
    }

    private fun derive(passphrase: CharArray, wrap: PassphraseKeyWrap): ByteArray {
        requireEnvelope(WalletPassphrasePolicy.validate(passphrase))
        // Explicit buffer avoids an immutable plaintext String and permits clearing on failure.
        val buffer = ByteBuffer.allocate(passphrase.size * 3)
        try {
            val encoder = Charsets.UTF_8.newEncoder()
            val result = encoder.encode(CharBuffer.wrap(passphrase), buffer, true)
            requireEnvelope(result.isUnderflow && encoder.flush(buffer).isUnderflow)
            val bytes = buffer.array().copyOf(buffer.position())
            try {
                return Argon2id.hash(bytes, wrap.salt, Argon2id.Params(
                    iterations = wrap.kdf.iterations, memoryKib = wrap.kdf.memoryKib,
                    parallelism = wrap.kdf.parallelism, tagLength = 32,
                ))
            } finally { bytes.fill(0) }
        } finally { buffer.array().fill(0) }
    }

    private fun validateIdentity(envelope: WalletEnvelope, walletId: String) {
        envelope.validate()
        requireEnvelope(envelope.walletId == walletId)
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(input)
            }
        } catch (_: GeneralSecurityException) {
            // Provider messages and causes are deliberately excluded from diagnostics.
            throw WalletEnvelopeAuthenticationException()
        }
    }
}
