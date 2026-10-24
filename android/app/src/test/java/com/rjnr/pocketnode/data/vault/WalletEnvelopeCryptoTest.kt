package com.rjnr.pocketnode.data.vault

import org.junit.Assert.*
import org.junit.Test

class WalletEnvelopeCryptoTest {
    private val crypto = WalletEnvelopeCrypto()
    private val passphrase = "  sixteen words 🔐 ไทย  ".toCharArray()
    private val plaintext = byteArrayOf(0, 1, -1, 42, 0, 65)

    // These tests catch loss of exact bytes, ignored authentication, metadata substitution,
    // stale generation AAD, weak input validation, and reuse of random encryption material.
    @Test fun productionKdfRoundTripPreservesExactBytesAndCallerBuffers() {
        val originalPassphrase = passphrase.copyOf()
        val envelope = create()
        assertArrayEquals(plaintext, crypto.decrypt(envelope, "wallet-1", passphrase))
        val key = crypto.unwrapDataKey(envelope, "wallet-1", passphrase)
        try { assertArrayEquals(plaintext, crypto.decryptWithDataKey(envelope, "wallet-1", key)) }
        finally { key.fill(0) }
        assertArrayEquals(originalPassphrase, passphrase)
        assertArrayEquals(byteArrayOf(0, 1, -1, 42, 0, 65), plaintext)
    }

    @Test fun wrongOrNormalizedPassphraseCannotUnlock() {
        val envelope = create()
        rejected { crypto.decrypt(envelope, "wallet-1", "different password long".toCharArray()) }
        rejected { crypto.decrypt(envelope, "wallet-1", String(passphrase).trim().toCharArray()) }
        val unicode = crypto.create("u", 1, plaintext, "abcdefghijklmn e\u0301".toCharArray())
        rejected { crypto.decrypt(unicode, "u", "abcdefghijklmn é".toCharArray()) }
    }

    @Test fun tamperedPayloadAndMetadataAreRejected() {
        val envelope = create()
        val ciphertext = envelope.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        rejected { crypto.decrypt(envelope.copy(ciphertext = ciphertext), "wallet-1", passphrase) }
        rejected { crypto.decrypt(envelope, "another-wallet", passphrase) }
        rejected { crypto.decrypt(envelope.copy(walletId = "other"), "other", passphrase) }
        rejected { crypto.decrypt(envelope.copy(version = 2), "wallet-1", passphrase) }
        rejected { crypto.decrypt(envelope.copy(generation = 2), "wallet-1", passphrase) }
        rejected { crypto.decrypt(envelope.copy(nonce = ByteArray(12)), "wallet-1", passphrase) }
    }

    @Test fun wrapsCannotBeSubstitutedEvenForSameWalletAndGeneration() {
        val first = create()
        val second = create()
        rejected { crypto.unwrapDataKey(first.copy(passphraseWrap = second.passphraseWrap), "wallet-1", passphrase) }
    }

    @Test fun freshEnvelopesUseIndependentRandomMaterial() {
        val first = create()
        val second = create()
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertFalse(first.passphraseWrap.salt.contentEquals(second.passphraseWrap.salt))
        assertFalse(first.passphraseWrap.nonce.contentEquals(second.passphraseWrap.nonce))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test fun rotationAuthenticatesNewGenerationAndDiscardsStaleDeviceWrap() {
        val original = create().copy(deviceWrap = DeviceKeyWrap("alias", ByteArray(48), ByteArray(12)))
        val newPassphrase = "new exact long passphrase".toCharArray()
        val rotated = crypto.rewrap(original, "wallet-1", passphrase, newPassphrase, 2)
        assertNull(rotated.deviceWrap)
        assertEquals(2L, rotated.generation)
        assertFalse(original.nonce.contentEquals(rotated.nonce))
        assertArrayEquals(plaintext, crypto.decrypt(rotated, "wallet-1", newPassphrase))
        rejected { crypto.decrypt(rotated, "wallet-1", passphrase) }
        rejected { crypto.decrypt(rotated.copy(generation = 1), "wallet-1", newPassphrase) }
        assertArrayEquals(plaintext, crypto.decrypt(original, "wallet-1", passphrase))
        rejected { crypto.rewrap(original, "wallet-1", passphrase, newPassphrase, 1) }
    }

    @Test fun malformedLengthsAndKdfAreRejectedBeforeDerivation() {
        val envelope = create()
        val wrap = envelope.passphraseWrap
        for (bad in listOf(
            envelope.copy(ciphertext = ByteArray(15)),
            envelope.copy(ciphertext = ByteArray(WalletEnvelope.MAX_PLAINTEXT_BYTES + 17)),
            envelope.copy(nonce = ByteArray(11)),
            envelope.copy(generation = -1),
            envelope.copy(passphraseWrap = wrap.copy(salt = ByteArray(15))),
            envelope.copy(passphraseWrap = wrap.copy(nonce = ByteArray(13))),
            envelope.copy(passphraseWrap = wrap.copy(wrappedKey = ByteArray(47))),
            envelope.copy(passphraseWrap = wrap.copy(kdf = WalletKdfParameters(memoryKib = Int.MAX_VALUE))),
            envelope.copy(passphraseWrap = wrap.copy(kdf = WalletKdfParameters(memoryKib = 32))),
            envelope.copy(passphraseWrap = wrap.copy(kdf = WalletKdfParameters(iterations = 0))),
            envelope.copy(passphraseWrap = wrap.copy(kdf = WalletKdfParameters(parallelism = 255))),
            envelope.copy(deviceWrap = DeviceKeyWrap("", ByteArray(48), ByteArray(12)))
        )) rejected { crypto.decrypt(bad, "wallet-1", passphrase) }
        rejected { crypto.decryptWithDataKey(envelope, "wallet-1", ByteArray(31)) }
        rejected { crypto.create("w", 0, ByteArray(WalletEnvelope.MAX_PLAINTEXT_BYTES + 1), passphrase) }
        rejected { crypto.create("", 0, plaintext, passphrase) }
        rejected { crypto.create("w", 0, plaintext, "short".toCharArray()) }
    }

    @Test fun boundedSerializationRoundTripsAndRejectsMalformedInput() {
        val envelope = create().copy(deviceWrap = DeviceKeyWrap("alias", ByteArray(48), ByteArray(12)))
        val bytes = WalletEnvelopeCodec.encode(envelope)
        val decoded = WalletEnvelopeCodec.decode(bytes)
        assertArrayEquals(plaintext, crypto.decrypt(decoded, "wallet-1", passphrase))
        assertEquals("alias", decoded.deviceWrap!!.alias)
        rejected { WalletEnvelopeCodec.decode(bytes.copyOf(bytes.size / 2)) }
        rejected { WalletEnvelopeCodec.decode(ByteArray(WalletEnvelopeCodec.MAX_SERIALIZED_BYTES + 1)) }
        rejected { WalletEnvelopeCodec.encode(envelope.copy(version = 99)) }
        rejected { WalletEnvelopeCodec.decode("{}".toByteArray()) }
    }

    @Test fun diagnosticsDoNotExposeEnvelopeContentsOrIdentifiers() {
        val envelope = create().copy(deviceWrap = DeviceKeyWrap("private-alias", ByteArray(48), ByteArray(12)))
        assertFalse(envelope.toString().contains("wallet-1"))
        assertFalse(envelope.deviceWrap.toString().contains("private-alias"))
        rejected { crypto.create("secret-wallet", 1, plaintext, "secret".toCharArray()) }.also {
            assertFalse(it.toString().contains("secret"))
            assertNull(it.cause)
        }
    }

    @Test fun metadataIsDeterministicAndSeparatesPurposesAndAliases() {
        val envelope = create()
        val first = WalletEnvelopeMetadata.device(envelope, "a")
        assertArrayEquals(first, WalletEnvelopeMetadata.device(envelope, "a"))
        assertFalse(first.contentEquals(WalletEnvelopeMetadata.device(envelope, "b")))
        assertFalse(first.contentEquals(WalletEnvelopeMetadata.passphrase(envelope)))
        assertFalse(first.contentEquals(WalletEnvelopeMetadata.device(envelope.copy(generation = 2), "a")))
    }

    private fun create() = crypto.create("wallet-1", 1, plaintext, passphrase)
    private fun rejected(block: () -> Any?): Exception {
        try { block(); fail("Expected rejection") } catch (e: Exception) { return e }
        throw AssertionError("Expected rejection")
    }
}
