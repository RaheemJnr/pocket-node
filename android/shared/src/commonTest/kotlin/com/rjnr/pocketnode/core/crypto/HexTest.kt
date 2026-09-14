package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
