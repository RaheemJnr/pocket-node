package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Known answers for the `Numeric`-compatible hex codec (#454). */
class HexTest {

    @Test
    fun encodesLowercaseWithPrefix() {
        assertEquals("0x", byteArrayOf().toHexString())
        assertEquals("0x00", byteArrayOf(0).toHexString())
        assertEquals("0xff", byteArrayOf(-1).toHexString())
        assertEquals("0xdeadbeef", byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()).toHexString())
    }

    @Test
    fun encodesWithoutPrefix() {
        assertEquals("", byteArrayOf().toHexStringNoPrefix())
        assertEquals("0a0b", byteArrayOf(0x0a, 0x0b).toHexStringNoPrefix())
    }

    @Test
    fun decodesWithAndWithoutPrefix() {
        val expected = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertContentEquals(expected, "0xdeadbeef".hexToByteArray())
        assertContentEquals(expected, "deadbeef".hexToByteArray())
        assertContentEquals(expected, "0XDEADBEEF".hexToByteArray())
        assertContentEquals(expected, "DEADBEEF".hexToByteArray())
    }

    @Test
    fun decodesEmptyAndOddLengthLikeTheSdk() {
        assertContentEquals(byteArrayOf(), "".hexToByteArray())
        assertContentEquals(byteArrayOf(), "0x".hexToByteArray())
        // Odd length is read as if a leading zero nibble were present.
        assertContentEquals(byteArrayOf(0x0a), "a".hexToByteArray())
        assertContentEquals(byteArrayOf(0x01, 0x23), "123".hexToByteArray())
        // "0" alone is shorter than the prefix check, so it stays a payload nibble.
        assertContentEquals(byteArrayOf(0x00), "0".hexToByteArray())
    }

    @Test
    fun rejectsNonHexCharacters() {
        // The SDK's Numeric decoded these to -1 nibbles and returned silently
        // corrupted bytes; AddressUtils.encode would then emit a valid-looking
        // but wrong address. Throw instead.
        val bad = listOf(
            "0xzz",
            "deadbeeg",
            "de ad",
            "0x00112233445566778899aabbccddeeff0011223344556677889900112233445g",
            "0x-1",
            "0xde\u00ADad",
        )
        for (case in bad) {
            assertFailsWith<IllegalArgumentException>("expected rejection of '$case'") {
                case.hexToByteArray()
            }
        }
    }

    @Test
    fun rejectionMessageDoesNotEchoTheInput() {
        // hexToByteArray runs on privateKeyHex; the message must not carry key
        // characters into a log line.
        val message = assertFailsWith<IllegalArgumentException> { "0xdeadbeeZ".hexToByteArray() }.message
        assertEquals("Invalid hex character at index 7", message)
    }

    @Test
    fun roundTrips() {
        val cases = listOf(
            ByteArray(0),
            ByteArray(1) { 0 },
            ByteArray(32) { it.toByte() },
            ByteArray(65) { (255 - it).toByte() },
        )
        for (case in cases) {
            assertContentEquals(case, case.toHexString().hexToByteArray())
            assertContentEquals(case, case.toHexStringNoPrefix().hexToByteArray())
        }
    }
}
