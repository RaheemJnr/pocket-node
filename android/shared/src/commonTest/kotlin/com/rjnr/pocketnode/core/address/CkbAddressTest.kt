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

    @Test
    fun rejectsAFullPayloadCarriedByTheWrongChecksumConstant() {
        val data = Bech32m.decode(fullAddress).data
        val wrongEncoding = Bech32m.encode(Bech32Encoding.BECH32, CkbAddress.HRP_MAINNET, data)
        assertFailsWith<AddressFormatException> { CkbAddress.decode(wrongEncoding) }
    }
}
