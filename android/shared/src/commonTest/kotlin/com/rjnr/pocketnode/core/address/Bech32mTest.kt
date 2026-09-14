package com.rjnr.pocketnode.core.address

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** BIP-173 / BIP-350 vectors for the generic bech32 codec (#454). */
class Bech32mTest {

    @Test
    fun encodesAndDecodesBip173Vectors() {
        val vectors = listOf(
            "a12uel5l",
            "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
            "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
        )
        for (vector in vectors) {
            val decoded = Bech32m.decode(vector)
            assertEquals(Bech32Encoding.BECH32, decoded.encoding)
            assertEquals(vector, Bech32m.encode(decoded.encoding, decoded.hrp, decoded.data))
        }
    }

    @Test
    fun encodesAndDecodesBip350Vectors() {
        val vectors = listOf(
            "a1lqfn3a",
            "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx",
            "?1v759aa",
        )
        for (vector in vectors) {
            val decoded = Bech32m.decode(vector)
            assertEquals(Bech32Encoding.BECH32M, decoded.encoding)
            assertEquals(vector, Bech32m.encode(decoded.encoding, decoded.hrp, decoded.data))
        }
    }

    @Test
    fun rejectsInvalidStrings() {
        // Too short, no separator, empty hrp, bad checksum, out-of-charset character.
        assertFailsWith<AddressFormatException> { Bech32m.decode("a12uel5") }
        assertFailsWith<AddressFormatException> { Bech32m.decode("qpzry9x8gf2tvdw0") }
        assertFailsWith<AddressFormatException> { Bech32m.decode("1qzzfhee") }
        assertFailsWith<AddressFormatException> { Bech32m.decode("a12uel5m") }
        assertFailsWith<AddressFormatException> { Bech32m.decode("a12uebl5l") }
    }

    @Test
    fun rejectsMixedCase() {
        assertFailsWith<AddressFormatException> { Bech32m.decode("A12Uel5l") }
    }

    @Test
    fun convertBitsRoundTripsWithPadding() {
        val input = ByteArray(33) { it.toByte() }
        val five = Bech32m.convertBits(input, 8, 5, pad = true)
        assertContentEquals(input, Bech32m.convertBits(five, 5, 8, pad = false))
    }

    @Test
    fun convertBitsRejectsNonZeroPadding() {
        val five = Bech32m.convertBits(ByteArray(1) { 1 }, 8, 5, pad = true)
        // Corrupt the padding nibble so the unpadded reverse conversion must reject it.
        five[five.size - 1] = (five[five.size - 1].toInt() or 1).toByte()
        assertFailsWith<AddressFormatException> { Bech32m.convertBits(five, 5, 8, pad = false) }
    }
}
