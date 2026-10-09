package com.rjnr.pocketnode.data.restorehint

import com.rjnr.pocketnode.core.crypto.Bip39
import com.rjnr.pocketnode.core.crypto.constantTimeEquals
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import kotlinx.serialization.json.Json
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import kotlin.io.encoding.Base64

/**
 * The secret a restore hint is authenticated with (#559).
 *
 * For a mnemonic wallet it is the 64-byte BIP-39 seed (so a passphrase, if the
 * wallet used one, is part of it); for a raw-key wallet the 32-byte private
 * key. Holds a private copy; call [wipe] once done.
 */
class RestoreHintSecret private constructor(
    /** [RestoreHintKind.MNEMONIC] or [RestoreHintKind.RAW_KEY]. */
    val kind: String,
    private val bytes: ByteArray,
) {
    private var wiped = false

    /** @throws IllegalStateException after [wipe]: a zeroed key must never MAC anything. */
    internal fun key(): ByteArray {
        check(!wiped) { "restore hint secret was wiped" }
        return bytes
    }

    /**
     * A copy of the BIP-39 seed, for deriving the sub-account candidates a
     * hint seeds; null for a raw-key secret. The caller wipes the copy.
     *
     * @throws IllegalStateException after [wipe].
     */
    fun copySeed(): ByteArray? {
        check(!wiped) { "restore hint secret was wiped" }
        return if (kind == RestoreHintKind.MNEMONIC) bytes.copyOf() else null
    }

    /** Overwrites the held secret. The instance is unusable afterwards. */
    fun wipe() {
        bytes.fill(0)
        wiped = true
    }

    companion object {
        /** BIP-39 seed length. */
        private const val SEED_LENGTH = 64

        fun fromMnemonic(words: List<String>, passphrase: String = ""): RestoreHintSecret =
            RestoreHintSecret(RestoreHintKind.MNEMONIC, Bip39.toSeed(words, passphrase))

        fun fromSeed(seed: ByteArray): RestoreHintSecret {
            require(seed.size == SEED_LENGTH) { "seed must be $SEED_LENGTH bytes" }
            return RestoreHintSecret(RestoreHintKind.MNEMONIC, seed.copyOf())
        }

        fun fromPrivateKey(privateKey: ByteArray): RestoreHintSecret {
            require(privateKey.size == 32) { "private key must be 32 bytes" }
            return RestoreHintSecret(RestoreHintKind.RAW_KEY, privateKey.copyOf())
        }
    }
}

/** Outcome of [RestoreHintCodec.open]. */
sealed interface RestoreHintOpenResult {
    data class Valid(val payload: RestoreHintPayload) : RestoreHintOpenResult
    data class Rejected(val error: RestoreHintError) : RestoreHintOpenResult
}

/**
 * Builds and verifies restore hint files (#559). Pure, no I/O.
 *
 * Authentication:
 * ```
 * authKey = HMAC-SHA256(key = secret, msg = "pocket-node/restore-hint/v1/<NETWORK>")
 * mac     = HMAC-SHA256(key = authKey, msg = payloadBytes)
 * ```
 * where `secret` is the BIP-39 seed (mnemonic wallets) or the private key
 * (raw-key wallets) and `<NETWORK>` is `MAINNET` or `TESTNET`. Binding the
 * network into the key means a file can never be replayed across networks.
 *
 * There is no encryption. The payload holds no keys, addresses, script args,
 * amounts or tx hashes, only coarse heights (see [RestoreHintPayload] and
 * [RestoreHintFormat.HEIGHT_GRANULARITY]), indices, mode names and the tip it
 * was made at, so the codec protects integrity and origin, not secrecy; the
 * file is still the user's private data. A file is all-or-nothing: any failure
 * rejects it with a typed [RestoreHintError] and none of it is used.
 *
 * The `mac` field must be exactly 64 lowercase hex characters; anything else
 * is MALFORMED, so every implementation accepts the same set of files.
 */
object RestoreHintCodec {

    /** Larger than any real hint by two orders of magnitude; refuse anything bigger unread. */
    const val MAX_FILE_CHARS: Int = 64 * 1024

    private val MAC_HEX = Regex("^[0-9a-f]{64}$")
    private const val MAX_ACCOUNTS = 1_000

    /** Canonical encoder: declaration order, defaults and nulls written out, no whitespace. */
    private val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    /** Forward-compatible decoder: a later minor addition must not break an older reader. */
    private val readerJson = Json { ignoreUnknownKeys = true }

    fun authLabel(network: NetworkType): String = "pocket-node/restore-hint/v1/${network.name}"

    /** The per-network MAC key. Callers wipe the result. */
    fun authKey(secret: RestoreHintSecret, network: NetworkType): ByteArray =
        HmacSHA256(secret.key()).doFinal(authLabel(network).encodeToByteArray())

    fun mac(secret: RestoreHintSecret, network: NetworkType, payloadBytes: ByteArray): ByteArray {
        val key = authKey(secret, network)
        return try {
            HmacSHA256(key).doFinal(payloadBytes)
        } finally {
            key.fill(0)
        }
    }

    /** Canonical UTF-8 JSON of [payload], the bytes the MAC covers. */
    fun encodePayload(payload: RestoreHintPayload): ByteArray =
        canonicalJson.encodeToString(RestoreHintPayload.serializer(), payload).encodeToByteArray()

    /** The complete file text for [payload], authenticated under [secret]. */
    fun seal(payload: RestoreHintPayload, secret: RestoreHintSecret): String {
        require(payload.v == RestoreHintFormat.VERSION) { "unsupported payload version ${payload.v}" }
        require(payload.kind == secret.kind) { "payload kind ${payload.kind} does not match the secret" }
        val network = NetworkType.entries.firstOrNull { it.name == payload.network }
            ?: throw IllegalArgumentException("unknown network ${payload.network}")
        val bytes = encodePayload(payload)
        val envelope = RestoreHintEnvelope(
            format = RestoreHintFormat.FORMAT,
            v = RestoreHintFormat.VERSION,
            payload = Base64.Default.encode(bytes),
            mac = mac(secret, network, bytes).toHexStringNoPrefix(),
        )
        return canonicalJson.encodeToString(RestoreHintEnvelope.serializer(), envelope)
    }

    /**
     * Parses and verifies [fileText] against [secret] for [expectedNetwork].
     *
     * The MAC is checked before the payload is parsed, so nothing an outsider
     * wrote is ever interpreted. A MAC that verifies only under the other
     * network's key is reported as [RestoreHintError.WRONG_NETWORK].
     */
    fun open(
        fileText: String,
        secret: RestoreHintSecret,
        expectedNetwork: NetworkType,
    ): RestoreHintOpenResult {
        if (fileText.length > MAX_FILE_CHARS) return rejected(RestoreHintError.MALFORMED)
        val envelope = try {
            readerJson.decodeFromString(RestoreHintEnvelope.serializer(), fileText)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException.
            return rejected(RestoreHintError.MALFORMED)
        }
        if (envelope.format != RestoreHintFormat.FORMAT) return rejected(RestoreHintError.MALFORMED)
        if (envelope.v != RestoreHintFormat.VERSION) return rejected(RestoreHintError.UNSUPPORTED_VERSION)

        if (!MAC_HEX.matches(envelope.mac)) return rejected(RestoreHintError.MALFORMED)
        val payloadBytes: ByteArray
        val givenMac: ByteArray
        try {
            payloadBytes = Base64.Default.decode(envelope.payload)
            givenMac = envelope.mac.hexToByteArray()
        } catch (e: IllegalArgumentException) {
            return rejected(RestoreHintError.MALFORMED)
        }

        if (!constantTimeEquals(mac(secret, expectedNetwork, payloadBytes), givenMac)) {
            val other = NetworkType.entries.first { it != expectedNetwork }
            return if (constantTimeEquals(mac(secret, other, payloadBytes), givenMac)) {
                rejected(RestoreHintError.WRONG_NETWORK)
            } else {
                rejected(RestoreHintError.BAD_MAC)
            }
        }

        // Authentic from here on. It must still be self-consistent.
        val payload = try {
            readerJson.decodeFromString(RestoreHintPayload.serializer(), payloadBytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            return rejected(RestoreHintError.MALFORMED)
        }
        if (payload.v != RestoreHintFormat.VERSION) return rejected(RestoreHintError.UNSUPPORTED_VERSION)
        if (payload.network != expectedNetwork.name) return rejected(RestoreHintError.MALFORMED)
        if (payload.kind != secret.kind) return rejected(RestoreHintError.MALFORMED)
        if (!isWellFormed(payload)) return rejected(RestoreHintError.MALFORMED)
        return RestoreHintOpenResult.Valid(payload)
    }

    private fun isWellFormed(payload: RestoreHintPayload): Boolean =
        payload.tipHeight >= 0 &&
            payload.accounts.isNotEmpty() &&
            payload.accounts.size <= MAX_ACCOUNTS &&
            payload.accounts.all { a ->
                a.index >= 0 && a.coverageStart >= 0 && (a.firstActivity == null || a.firstActivity >= 0)
            } &&
            payload.discovery.found.all { it >= 0 } &&
            payload.discovery.found.size <= MAX_ACCOUNTS &&
            payload.discovery.highestScanned >= 0

    private fun rejected(error: RestoreHintError) = RestoreHintOpenResult.Rejected(error)
}
