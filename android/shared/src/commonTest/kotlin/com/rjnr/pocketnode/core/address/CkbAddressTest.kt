package com.rjnr.pocketnode.core.address

import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.crypto.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RFC 0021 test vectors, copied from
 * https://github.com/nervosnetwork/rfcs/blob/master/rfcs/0021-ckb-address-format/0021-ckb-address-format.md
 * ("Examples and Demo Code").
 */
class CkbAddressTest {

    private val secp256k1CodeHash =
        "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8"
    private val multisigCodeHash =
        "0x5c5069eb0857efc65e1bca0c07df34c31663b3622fd3876c876320fc9634e2a8"
    private val args = "0xb39bbc0b3673c7d36450bc14cfcdad2d559c6c64"

    private val fullAddress =
        "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqdnnw7qkdnnclfkg59uzn8umtfd2kwxceqxwquc4"
    private val shortAddressSecp256k1 = "ckb1qyqt8xaupvm8837nv3gtc9x0ekkj64vud3jqfwyw5v"
    private val shortAddressMultisig = "ckb1qyq5lv479ewscx3ms620sv34pgeuz6zagaaqklhtgg"
    private val deprecatedFullAddress =
        "ckb1qjda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xw3vumhs9nvu786dj9p0q5elx66t24n3kxgj53qks"

    @Test
    fun encodesTheRfcFullAddress() {
        assertEquals(
            fullAddress,
            CkbAddress.encodeFull(
                hrp = CkbAddress.HRP_MAINNET,
                codeHash = secp256k1CodeHash.hexToByteArray(),
                hashType = CkbAddress.HASH_TYPE_TYPE,
                args = args.hexToByteArray(),
            ),
        )
    }

    @Test
    fun decodesTheRfcFullAddress() {
        val decoded = CkbAddress.decode(fullAddress)
        assertEquals(CkbAddress.HRP_MAINNET, decoded.hrp)
        assertEquals(secp256k1CodeHash, decoded.codeHash.toHexString())
        assertEquals(CkbAddress.HASH_TYPE_TYPE, decoded.hashType)
        assertEquals(args, decoded.args.toHexString())
    }

    @Test
    fun decodesTheRfcShortAddressForCodeHashIndexZero() {
        val decoded = CkbAddress.decode(shortAddressSecp256k1)
        assertEquals(CkbAddress.HRP_MAINNET, decoded.hrp)
        assertEquals(secp256k1CodeHash, decoded.codeHash.toHexString())
        assertEquals(CkbAddress.HASH_TYPE_TYPE, decoded.hashType)
        assertEquals(args, decoded.args.toHexString())
    }

    @Test
    fun decodesTheRfcShortAddressForCodeHashIndexOne() {
        val decoded = CkbAddress.decode(shortAddressMultisig)
        assertEquals(multisigCodeHash, decoded.codeHash.toHexString())
        assertEquals(CkbAddress.HASH_TYPE_TYPE, decoded.hashType)
        assertEquals("0x4fb2be2e5d0c1a3b8694f832350a33c1685d477a", decoded.args.toHexString())
    }

    @Test
    fun decodesTheRfcDeprecatedFullAddress() {
        val decoded = CkbAddress.decode(deprecatedFullAddress)
        assertEquals(secp256k1CodeHash, decoded.codeHash.toHexString())
        // The deprecated full format's 0x04 header means hash type `type`.
        assertEquals(CkbAddress.HASH_TYPE_TYPE, decoded.hashType)
        assertEquals(args, decoded.args.toHexString())
    }

    @Test
    fun roundTripsTestnetAddresses() {
        val encoded = CkbAddress.encodeFull(
            hrp = CkbAddress.HRP_TESTNET,
            codeHash = secp256k1CodeHash.hexToByteArray(),
            hashType = CkbAddress.HASH_TYPE_TYPE,
            args = args.hexToByteArray(),
        )
        kotlin.test.assertTrue(encoded.startsWith("ckt1"), "got $encoded")
        assertEquals(CkbAddress.HRP_TESTNET, CkbAddress.decode(encoded).hrp)
    }

    @Test
    fun rejectsAMutatedChecksum() {
        val last = fullAddress.last()
        val swapped = fullAddress.dropLast(1) + if (last == 'q') 'p' else 'q'
        assertFailsWith<AddressFormatException> { CkbAddress.decode(swapped) }
    }

    @Test
    fun rejectsMixedCase() {
        assertFailsWith<AddressFormatException> {
            CkbAddress.decode(fullAddress.substring(0, 10).uppercase() + fullAddress.substring(10))
        }
    }

    @Test
    fun rejectsAnUnknownHumanReadablePart() {
        // Same payload, re-checksummed under a foreign hrp.
        val data = Bech32m.decode(fullAddress).data
        val foreign = Bech32m.encode(Bech32Encoding.BECH32M, "bc", data)
        assertFailsWith<AddressFormatException> { CkbAddress.decode(foreign) }
    }

    // --- Structural negatives -------------------------------------------------
    // Every one of these is a well-formed bech32 string carrying a malformed CKB
    // payload, so the checksum passes and only the payload rules can catch it.
    // A decoder that shrugged any of them off would hand back a lock script that
    // is not the one the address names.

    /** Re-encodes an arbitrary payload so the checksum is valid and only the payload is wrong. */
    private fun addressOf(
        payload: ByteArray,
        encoding: Bech32Encoding = Bech32Encoding.BECH32M,
        hrp: String = CkbAddress.HRP_MAINNET,
    ): String = Bech32m.encode(encoding, hrp, Bech32m.convertBits(payload, 8, 5, pad = true))

    private val codeHashBytes get() = secp256k1CodeHash.hexToByteArray()
    private val argsBytes get() = args.hexToByteArray()

    @Test
    fun rejectsATruncatedFullPayload() {
        // Code hash cut short. Zero-filling the rest would silently name a
        // different script.
        assertFailsWith<AddressFormatException> {
            CkbAddress.decode(addressOf(byteArrayOf(0x00) + codeHashBytes.copyOfRange(0, 20)))
        }
        // Full code hash but no hash-type byte and no args.
        assertFailsWith<AddressFormatException> {
            CkbAddress.decode(addressOf(byteArrayOf(0x00) + codeHashBytes))
        }
    }

    @Test
    fun rejectsAnUnknownFormatByte() {
        // 0x03 sits in the gap between the short (0x01) and deprecated-full
        // (0x02 / 0x04) headers.
        assertFailsWith<AddressFormatException> {
            CkbAddress.decode(
                addressOf(byteArrayOf(0x03) + codeHashBytes + byteArrayOf(0x01) + argsBytes)
            )
        }
        assertFailsWith<AddressFormatException> {
            CkbAddress.decode(
                addressOf(byteArrayOf(0x05) + codeHashBytes + byteArrayOf(0x01) + argsBytes)
            )
        }
    }

    @Test
    fun rejectsAnUnknownHashTypeByte() {
        // Packed hash types are 0x00, 0x01, 0x02 and 0x04 — note the gap at 0x03.
        for (unknown in listOf(0x03, 0x05, 0xFF)) {
            assertFailsWith<AddressFormatException>("hash type $unknown must be rejected") {
                CkbAddress.decode(
                    addressOf(
                        byteArrayOf(0x00) + codeHashBytes + byteArrayOf(unknown.toByte()) + argsBytes
                    )
                )
            }
        }
    }

    @Test
    fun rejectsShortAddressesWithTheWrongArgsLength() {
        // secp256k1 (index 0x00) and multisig (0x01) are blake160, exactly 20 bytes.
        for (index in listOf(0x00, 0x01)) {
            for (length in listOf(19, 21)) {
                assertFailsWith<AddressFormatException>("index $index length $length") {
                    CkbAddress.decode(
                        addressOf(
                            byteArrayOf(0x01, index.toByte()) + ByteArray(length) { 0x11 },
                            Bech32Encoding.BECH32,
                        )
                    )
                }
            }
        }
        // anyone-can-pay (0x02) allows 20..22 for the optional minimum fields.
        for (length in listOf(19, 23)) {
            assertFailsWith<AddressFormatException>("acp length $length") {
                CkbAddress.decode(
                    addressOf(
                        byteArrayOf(0x01, 0x02) + ByteArray(length) { 0x11 },
                        Bech32Encoding.BECH32,
                    )
                )
            }
        }
    }

    @Test
    fun rejectsAnUnknownShortCodeHashIndex() {
        assertFailsWith<AddressFormatException> {
            CkbAddress.decode(
                addressOf(byteArrayOf(0x01, 0x03) + argsBytes, Bech32Encoding.BECH32)
            )
        }
    }

    @Test
    fun acceptsAnAllUppercaseAddressAndRejectsMixedCase() {
        // BIP-173 allows an all-uppercase string; only mixing the two is illegal.
        val upper = fullAddress.uppercase()
        assertEquals(CkbAddress.decode(fullAddress).args.toHexString(), CkbAddress.decode(upper).args.toHexString())
        assertEquals(CkbAddress.HRP_MAINNET, CkbAddress.decode(upper).hrp)
        assertFailsWith<AddressFormatException> {
            CkbAddress.decode("CKB1" + fullAddress.substring(4))
        }
    }

    @Test
    fun rejectsAnUppercaseButUnknownHumanReadablePart() {
        val data = Bech32m.decode(fullAddress).data
        assertFailsWith<AddressFormatException> {
            CkbAddress.decode(Bech32m.encode(Bech32Encoding.BECH32M, "CKX", data).uppercase())
        }
    }

    @Test
    fun rejectsAFullPayloadCarriedByTheWrongChecksumConstant() {
        val data = Bech32m.decode(fullAddress).data
        val wrongEncoding = Bech32m.encode(Bech32Encoding.BECH32, CkbAddress.HRP_MAINNET, data)
        assertFailsWith<AddressFormatException> { CkbAddress.decode(wrongEncoding) }
    }
}
