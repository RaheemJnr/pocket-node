package com.rjnr.pocketnode.data.vault

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encrypted values only. Persist through [WalletEnvelopeCodec] to enforce input bounds. */
@Serializable
data class WalletEnvelope(
    val version: Int,
    val walletId: String,
    val generation: Long,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val passphraseWrap: PassphraseKeyWrap,
    val deviceWrap: DeviceKeyWrap? = null,
) {
    override fun toString(): String = "WalletEnvelope([redacted])"

    fun validate() {
        requireEnvelope(version == VERSION && generation >= 0)
        requireText(walletId)
        requireEnvelope(ciphertext.size in 16..MAX_PLAINTEXT_BYTES + 16 && nonce.size == 12)
        requireEnvelope(passphraseWrap.salt.size == 32)
        requireEnvelope(passphraseWrap.nonce.size == 12 && passphraseWrap.wrappedKey.size == 48)
        // V1 has one supported KDF profile. Never accept weaker or attacker-sized parameters.
        requireEnvelope(passphraseWrap.kdf == WalletKdfParameters())
        deviceWrap?.let {
            requireText(it.alias)
            requireEnvelope(it.wrappedKey.size == 48 && it.nonce.size == 12)
        }
    }

    companion object {
        const val VERSION = 1
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
    }
}

@Serializable
data class WalletKdfParameters(
    val memoryKib: Int = 65536,
    val iterations: Int = 3,
    val parallelism: Int = 4,
)

@Serializable
data class PassphraseKeyWrap(
    val salt: ByteArray,
    val kdf: WalletKdfParameters = WalletKdfParameters(),
    val wrappedKey: ByteArray,
    val nonce: ByteArray,
) {
    override fun toString(): String = "PassphraseKeyWrap([redacted])"
}

@Serializable
data class DeviceKeyWrap(val alias: String, val wrappedKey: ByteArray, val nonce: ByteArray) {
    override fun toString(): String = "DeviceKeyWrap([redacted])"
}

class WalletEnvelopeFormatException : IllegalArgumentException("Invalid wallet envelope")
class WalletEnvelopeAuthenticationException : GeneralSecurityException("Wallet envelope authentication failed")

internal fun requireEnvelope(condition: Boolean) {
    if (!condition) throw WalletEnvelopeFormatException()
}

internal fun requireText(value: String) {
    requireEnvelope(value.isNotEmpty() && value.length <= 256)
    requireEnvelope(WalletPassphrasePolicy.wellFormed(value.toCharArray()))
    requireEnvelope(value.toByteArray(Charsets.UTF_8).size <= 256)
}

/** Size is checked before JSON parsing, and all field bounds before cryptographic processing. */
object WalletEnvelopeCodec {
    const val MAX_SERIALIZED_BYTES = 512 * 1024
    private val json = Json { encodeDefaults = true }

    fun encode(envelope: WalletEnvelope): ByteArray {
        envelope.validate()
        return json.encodeToString(envelope).toByteArray(Charsets.UTF_8).also {
            requireEnvelope(it.size <= MAX_SERIALIZED_BYTES)
        }
    }

    fun decode(bytes: ByteArray): WalletEnvelope {
        requireEnvelope(bytes.size in 1..MAX_SERIALIZED_BYTES)
        return try {
            json.decodeFromString<WalletEnvelope>(bytes.toString(Charsets.UTF_8)).also { it.validate() }
        } catch (_: Exception) {
            // Parser exceptions can echo input. Do not retain them as a cause.
            throw WalletEnvelopeFormatException()
        }
    }
}

/**
 * Deterministic, length-prefixed AAD shared with future Android device wrapping.
 * Wraps authenticate the payload digest, preventing a substituted wrap from releasing a DEK.
 * Payload metadata deliberately excludes wraps so a convenience wrap can be added independently.
 */
object WalletEnvelopeMetadata {
    fun payload(walletId: String, version: Int, generation: Long): ByteArray =
        metadata(walletId, version, generation, "payload") {}

    fun passphrase(envelope: WalletEnvelope): ByteArray {
        envelope.validate()
        return metadata(envelope.walletId, envelope.version, envelope.generation, "passphrase-wrap") {
            write(payloadDigest(envelope))
            write(envelope.passphraseWrap.salt)
            writeInt(envelope.passphraseWrap.kdf.memoryKib)
            writeInt(envelope.passphraseWrap.kdf.iterations)
            writeInt(envelope.passphraseWrap.kdf.parallelism)
        }
    }

    fun device(envelope: WalletEnvelope, alias: String): ByteArray {
        envelope.validate()
        requireText(alias)
        return metadata(envelope.walletId, envelope.version, envelope.generation, "device-wrap") {
            write(payloadDigest(envelope))
            writeText(alias)
        }
    }

    private fun payloadDigest(envelope: WalletEnvelope): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(envelope.nonce)
            digest(envelope.ciphertext)
        }

    private fun metadata(
        walletId: String,
        version: Int,
        generation: Long,
        purpose: String,
        extra: DataOutputStream.() -> Unit,
    ): ByteArray {
        requireText(walletId)
        requireEnvelope(version == WalletEnvelope.VERSION && generation >= 0)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeText("pocketnode.wallet-envelope")
            it.writeInt(version)
            it.writeText(walletId)
            it.writeLong(generation)
            it.writeText(purpose)
            it.extra()
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
