package com.rjnr.pocketnode.core.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [constantTimeEquals] cannot be shown to be constant-time by a unit test, so
 * these only pin down that it is a correct equality: the timing property is a
 * property of the implementation (no early return inside the loop) and is
 * guarded by review.
 */
class ConstantTimeTest {

    @Test
    fun equalContentsMatch() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun emptyArraysMatch() {
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun aDifferenceAnywhereFails() {
        val a = ByteArray(32) { it.toByte() }
        for (i in a.indices) {
            val b = a.copyOf()
            b[i] = (b[i] + 1).toByte()
            assertFalse(constantTimeEquals(a, b), "index $i")
        }
    }

    @Test
    fun differentLengthsFail() {
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(ByteArray(0), byteArrayOf(0)))
    }

    @Test
    fun signBitsAreCompared() {
        // A naive `a[i] - b[i]` accumulator or an Int widening that sign-extends
        // inconsistently would call these equal.
        assertFalse(constantTimeEquals(byteArrayOf(-1), byteArrayOf(1)))
        assertFalse(constantTimeEquals(byteArrayOf(-128), byteArrayOf(0)))
        assertTrue(constantTimeEquals(byteArrayOf(-1, -128), byteArrayOf(-1, -128)))
    }
}
