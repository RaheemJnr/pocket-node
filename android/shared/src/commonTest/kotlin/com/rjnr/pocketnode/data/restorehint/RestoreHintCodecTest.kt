package com.rjnr.pocketnode.data.restorehint

import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #559: restore hint format, authentication and verification.
 *
 * The phrase is the public BIP-39 test vector, never a real wallet.
 */
class RestoreHintCodecTest {

    private val abandonWords = List(11) { "abandon" } + "about"
    private val otherWords = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    private fun payload(network: NetworkType = NetworkType.TESTNET, kind: String = RestoreHintKind.MNEMONIC) =
        RestoreHintPayload(
            network = network.name,
            createdAtMs = 1_760_000_000_000L,
            tipHeight = 19_000_000L,
            tipHash = "0x" + "11".repeat(32),
            kind = kind,
            accounts = listOf(
                RestoreHintAccount(index = 0, coverageStart = 18_000_000L, firstActivity = 18_120_000L, syncMode = "CUSTOM"),
                RestoreHintAccount(index = 1, coverageStart = 18_500_000L, firstActivity = null, syncMode = "RECENT"),
            ),
            discovery = RestoreHintDiscovery(found = listOf(1, 3), highestScanned = 10),
        )

    private fun seed() = RestoreHintSecret.fromMnemonic(abandonWords)

    // -- Known-answer vector (pinned for iOS interop) --

    private val katPayloadJson =
        """{"v":1,"network":"TESTNET","createdAtMs":1760000000000,"tipHeight":19000000,""" +
            """"tipHash":"0x1111111111111111111111111111111111111111111111111111111111111111",""" +
            """"kind":"mnemonic","accounts":[{"index":0,"coverageStart":18000000,"firstActivity":18120000,""" +
            """"syncMode":"CUSTOM"},{"index":1,"coverageStart":18500000,"firstActivity":null,"syncMode":"RECENT"}],""" +
            """"discovery":{"found":[1,3],"highestScanned":10}}"""

    @Test
    fun canonicalEncodingIsPinned() {
        assertEquals(katPayloadJson, RestoreHintCodec.encodePayload(payload()).decodeToString())
    }

    @Test
    fun knownAnswerAuthKeyAndMac() {
        val secret = seed()
        assertEquals(
            "5737d9898272e45e12e2a24e16356be52c50087807cf18f9ca1b89519a282b88",
            RestoreHintCodec.authKey(secret, NetworkType.TESTNET).toHexStringNoPrefix(),
        )
        assertEquals(
            "53a69a5572012030254563a7272ade108afd4521a5a50e42c631f7de572e2e2a",
            RestoreHintCodec.mac(secret, NetworkType.TESTNET, katPayloadJson.encodeToByteArray()).toHexStringNoPrefix(),
        )
    }

    @Test
    fun knownAnswerRawKeyAuthKey() {
        val secret = RestoreHintSecret.fromPrivateKey(ByteArray(32) { 1 })
        assertEquals(
            "c10df958fa2749846afea5ab20ce2be570bd9f73977ba088be3090f1376ca455",
            RestoreHintCodec.authKey(secret, NetworkType.MAINNET).toHexStringNoPrefix(),
        )
    }

    @Test
    fun sealedFileCarriesTheKnownAnswerMac() {
        val file = RestoreHintCodec.seal(payload(), seed())
        val envelope = Json.parseToJsonElement(file).jsonObject
        assertEquals(RestoreHintFormat.FORMAT, envelope["format"]!!.jsonPrimitive.content)
        assertEquals("1", envelope["v"]!!.jsonPrimitive.content)
        assertEquals(katPayloadJson, Base64.Default.decode(envelope["payload"]!!.jsonPrimitive.content).decodeToString())
        assertEquals(
            "53a69a5572012030254563a7272ade108afd4521a5a50e42c631f7de572e2e2a",
            envelope["mac"]!!.jsonPrimitive.content,
        )
    }

    // -- Round trip and rejections --

    @Test
    fun roundTripWithTheSamePhraseVerifies() {
        val file = RestoreHintCodec.seal(payload(), seed())
        val result = RestoreHintCodec.open(file, seed(), NetworkType.TESTNET)
        assertEquals(RestoreHintOpenResult.Valid(payload()), result)
    }

    @Test
    fun roundTripWithARawKeyVerifies() {
        val key = ByteArray(32) { (it + 7).toByte() }
        val p = payload(kind = RestoreHintKind.RAW_KEY).copy(
            accounts = listOf(RestoreHintAccount(0, 100L, 200L, "RECENT")),
            discovery = RestoreHintDiscovery(),
        )
        val file = RestoreHintCodec.seal(p, RestoreHintSecret.fromPrivateKey(key))
        assertEquals(
            RestoreHintOpenResult.Valid(p),
            RestoreHintCodec.open(file, RestoreHintSecret.fromPrivateKey(key), NetworkType.TESTNET),
        )
        // A different key is a bad MAC.
        val other = ByteArray(32) { 9 }
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.BAD_MAC),
            RestoreHintCodec.open(file, RestoreHintSecret.fromPrivateKey(other), NetworkType.TESTNET),
        )
    }

    @Test
    fun aDifferentPhraseIsABadMac() {
        val file = RestoreHintCodec.seal(payload(), seed())
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.BAD_MAC),
            RestoreHintCodec.open(file, RestoreHintSecret.fromMnemonic(otherWords), NetworkType.TESTNET),
        )
    }

    @Test
    fun aPassphraseChangesTheKey() {
        val file = RestoreHintCodec.seal(payload(), seed())
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.BAD_MAC),
            RestoreHintCodec.open(file, RestoreHintSecret.fromMnemonic(abandonWords, "TREZOR"), NetworkType.TESTNET),
        )
    }

    @Test
    fun everySingleEditedPayloadByteIsABadMac() {
        val file = RestoreHintCodec.seal(payload(), seed())
        val envelope = Json.decodeFromString(RestoreHintEnvelope.serializer(), file)
        val bytes = Base64.Default.decode(envelope.payload)
        for (i in bytes.indices) {
            val edited = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val tampered = Json.encodeToString(
                RestoreHintEnvelope.serializer(),
                envelope.copy(payload = Base64.Default.encode(edited)),
            )
            val result = RestoreHintCodec.open(tampered, seed(), NetworkType.TESTNET)
            assertEquals(RestoreHintOpenResult.Rejected(RestoreHintError.BAD_MAC), result, "byte $i")
        }
    }

    @Test
    fun anEditedMacIsABadMac() {
        val file = RestoreHintCodec.seal(payload(), seed())
        val envelope = Json.decodeFromString(RestoreHintEnvelope.serializer(), file)
        val flipped = envelope.mac.dropLast(1) + (if (envelope.mac.last() == '0') '1' else '0')
        val tampered = Json.encodeToString(RestoreHintEnvelope.serializer(), envelope.copy(mac = flipped))
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.BAD_MAC),
            RestoreHintCodec.open(tampered, seed(), NetworkType.TESTNET),
        )
    }

    @Test
    fun aFileForTheOtherNetworkIsWrongNetwork() {
        val file = RestoreHintCodec.seal(payload(network = NetworkType.MAINNET), seed())
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.WRONG_NETWORK),
            RestoreHintCodec.open(file, seed(), NetworkType.TESTNET),
        )
        // And it is still valid where it belongs.
        assertIs<RestoreHintOpenResult.Valid>(RestoreHintCodec.open(file, seed(), NetworkType.MAINNET))
    }

    @Test
    fun anUnknownEnvelopeVersionIsUnsupported() {
        val file = RestoreHintCodec.seal(payload(), seed())
        val envelope = Json.decodeFromString(RestoreHintEnvelope.serializer(), file)
        val v2 = Json.encodeToString(RestoreHintEnvelope.serializer(), envelope.copy(v = 2))
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.UNSUPPORTED_VERSION),
            RestoreHintCodec.open(v2, seed(), NetworkType.TESTNET),
        )
    }

    @Test
    fun anAuthenticPayloadWithAnUnknownVersionIsUnsupported() {
        // MAC'd correctly, so the version check is what rejects it.
        val bytes = katPayloadJson.replaceFirst("\"v\":1", "\"v\":2").encodeToByteArray()
        val file = Json.encodeToString(
            RestoreHintEnvelope.serializer(),
            RestoreHintEnvelope(
                format = RestoreHintFormat.FORMAT,
                v = 1,
                payload = Base64.Default.encode(bytes),
                mac = RestoreHintCodec.mac(seed(), NetworkType.TESTNET, bytes).toHexStringNoPrefix(),
            ),
        )
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.UNSUPPORTED_VERSION),
            RestoreHintCodec.open(file, seed(), NetworkType.TESTNET),
        )
    }

    @Test
    fun anAuthenticPayloadNamingTheOtherNetworkIsMalformed() {
        // MAC'd under the TESTNET key but claiming MAINNET: inconsistent.
        val bytes = katPayloadJson.replace("TESTNET", "MAINNET").encodeToByteArray()
        val file = sealRaw(bytes)
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.MALFORMED),
            RestoreHintCodec.open(file, seed(), NetworkType.TESTNET),
        )
    }

    @Test
    fun anAuthenticPayloadWithNegativeHeightsIsMalformed() {
        val bytes = katPayloadJson.replace("\"coverageStart\":18000000", "\"coverageStart\":-5").encodeToByteArray()
        assertEquals(
            RestoreHintOpenResult.Rejected(RestoreHintError.MALFORMED),
            RestoreHintCodec.open(sealRaw(bytes), seed(), NetworkType.TESTNET),
        )
    }

    @Test
    fun unknownPayloadFieldsAreIgnoredForForwardCompatibility() {
        val bytes = katPayloadJson.replace("\"discovery\":", "\"futureField\":42,\"discovery\":").encodeToByteArray()
        assertEquals(
            RestoreHintOpenResult.Valid(payload()),
            RestoreHintCodec.open(sealRaw(bytes), seed(), NetworkType.TESTNET),
        )
    }

    @Test
    fun malformedInputsAreMalformed() {
        val good = Json.decodeFromString(RestoreHintEnvelope.serializer(), RestoreHintCodec.seal(payload(), seed()))
        val cases = listOf(
            "",
            "not json",
            "[]",
            "{}",
            """{"format":"something-else","v":1,"payload":"${good.payload}","mac":"${good.mac}"}""",
            """{"format":"${RestoreHintFormat.FORMAT}","v":1,"payload":"%%%","mac":"${good.mac}"}""",
            """{"format":"${RestoreHintFormat.FORMAT}","v":1,"payload":"${good.payload}","mac":"zz"}""",
            """{"format":"${RestoreHintFormat.FORMAT}","v":1,"payload":"${good.payload}","mac":"abcd"}""",
            "x".repeat(RestoreHintCodec.MAX_FILE_CHARS + 1),
        )
        for (case in cases) {
            assertEquals(
                RestoreHintOpenResult.Rejected(RestoreHintError.MALFORMED),
                RestoreHintCodec.open(case, seed(), NetworkType.TESTNET),
                "input: ${case.take(40)}",
            )
        }
    }

    @Test
    fun macMustBeExactly64LowercaseHexChars() {
        val good = Json.decodeFromString(RestoreHintEnvelope.serializer(), RestoreHintCodec.seal(payload(), seed()))
        // Each of these decodes to the right 32 bytes, so before the strict
        // check they verified; iOS must accept exactly the same set of files.
        val variants = listOf(good.mac.uppercase(), "0x" + good.mac, good.mac + " ", " " + good.mac)
        for (mac in variants) {
            val file = Json.encodeToString(RestoreHintEnvelope.serializer(), good.copy(mac = mac))
            assertEquals(
                RestoreHintOpenResult.Rejected(RestoreHintError.MALFORMED),
                RestoreHintCodec.open(file, seed(), NetworkType.TESTNET),
                "mac: '$mac'",
            )
        }
        assertIs<RestoreHintOpenResult.Valid>(
            RestoreHintCodec.open(Json.encodeToString(RestoreHintEnvelope.serializer(), good), seed(), NetworkType.TESTNET)
        )
    }

    @Test
    fun aWipedSecretRefusesToBeUsed() {
        val secret = seed()
        secret.wipe()
        assertFailsWith<IllegalStateException> { RestoreHintCodec.authKey(secret, NetworkType.TESTNET) }
        assertFailsWith<IllegalStateException> { secret.copySeed() }
        assertFailsWith<IllegalStateException> { RestoreHintCodec.seal(payload(), secret) }
    }

    @Test
    fun heightsCoarsenDownToTheGranularity() {
        assertEquals(10_000L, RestoreHintFormat.HEIGHT_GRANULARITY)
        assertEquals(18_120_000L, RestoreHintFormat.coarsen(18_123_456L))
        assertEquals(18_120_000L, RestoreHintFormat.coarsen(18_120_000L))
        assertEquals(0L, RestoreHintFormat.coarsen(9_999L))
        assertEquals(0L, RestoreHintFormat.coarsen(0L))
        assertEquals(0L, RestoreHintFormat.coarsen(-5L))
        for (h in listOf(1L, 9_999L, 10_001L, 18_123_456L, Long.MAX_VALUE)) {
            val c = RestoreHintFormat.coarsen(h)
            assertTrue(c <= h && h - c < RestoreHintFormat.HEIGHT_GRANULARITY && c % RestoreHintFormat.HEIGHT_GRANULARITY == 0L)
        }
    }

    @Test
    fun sealRefusesAKindTheSecretDoesNotMatch() {
        assertFailsWith<IllegalArgumentException> {
            RestoreHintCodec.seal(payload(kind = RestoreHintKind.RAW_KEY), seed())
        }
    }

    // -- Privacy: the payload carries no identifying data --

    @Test
    fun payloadHasNoAddressScriptAmountOrKeyFields() {
        val allowed = setOf(
            "v", "network", "createdAtMs", "tipHeight", "tipHash", "kind", "accounts", "discovery",
            "index", "coverageStart", "firstActivity", "syncMode", "found", "highestScanned",
        )
        val keys = mutableSetOf<String>()
        collectKeys(Json.parseToJsonElement(RestoreHintCodec.encodePayload(payload()).decodeToString()), keys)
        assertEquals(allowed, keys)
        val forbidden = listOf("address", "script", "args", "amount", "capacity", "balance", "key", "mnemonic", "seed", "txhash", "hash160")
        for (k in keys) {
            for (f in forbidden) {
                assertTrue(!k.lowercase().contains(f), "payload key '$k' looks identifying")
            }
        }
    }

    private fun collectKeys(element: JsonElement, out: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.forEach { (k, v) -> out += k; collectKeys(v, out) }
            is JsonArray -> element.forEach { collectKeys(it, out) }
            else -> Unit
        }
    }

    private fun sealRaw(bytes: ByteArray): String = Json.encodeToString(
        RestoreHintEnvelope.serializer(),
        RestoreHintEnvelope(
            format = RestoreHintFormat.FORMAT,
            v = 1,
            payload = Base64.Default.encode(bytes),
            mac = RestoreHintCodec.mac(seed(), NetworkType.TESTNET, bytes).toHexStringNoPrefix(),
        ),
    )
}
