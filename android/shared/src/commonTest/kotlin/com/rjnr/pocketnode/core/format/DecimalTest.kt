package com.rjnr.pocketnode.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The expected strings here are the output of the JVM `String.format("%.Nf", shannons / 1e8)`
 * this code replaced, captured from a JDK 22 run before the swap (#455). They are also the
 * mathematically correct HALF_UP rounding of the exact shannon value, which is the point:
 * the integer path owes no allegiance to double representation, so it gives the same answer
 * on Kotlin/Native, where `kotlin.math.round` breaks ties the other way.
 */
class DecimalTest {

    // --- the four rounding cases the Double path got wrong ---

    @Test
    fun roundsHalfUpAtTwoDecimals() {
        // 61.005 CKB
        assertEquals("61.01", formatFixedPoint(6_100_500_000L, 8, 2))
        // 12.345 CKB
        assertEquals("12.35", formatFixedPoint(1_234_500_000L, 8, 2))
    }

    @Test
    fun roundsHalfUpAtOneDecimal() {
        assertEquals("1.1", formatFixedPoint(1050L, 3, 1))
        assertEquals("1.3", formatFixedPoint(1250L, 3, 1))
    }

    @Test
    fun roundsAwayFromZeroOnTiesNotToEven() {
        // Ties-to-even (Math.rint, the JVM behaviour of kotlin.math.round) would give
        // "0.2" for both; HALF_UP gives 0.3 for the 0.25 tie.
        assertEquals("0.3", formatFixedPoint(250L, 3, 1))   // 0.25: HALF_EVEN would give 0.2
        assertEquals("0.5", formatFixedPoint(4500L, 4, 1))  // 0.45: HALF_EVEN would give 0.4
        assertEquals("0.2", formatFixedPoint(249L, 3, 1))   // below the tie, rounds down
    }

    // --- zero, smallest unit, sign ---

    @Test
    fun formatsZero() {
        assertEquals("0.00000000", formatFixedPoint(0L, 8, 8))
        assertEquals("0.00", formatFixedPoint(0L, 8, 2))
        assertEquals("0", formatFixedPoint(0L, 8, 0))
    }

    @Test
    fun formatsOneShannon() {
        assertEquals("0.00000001", formatFixedPoint(1L, 8, 8))
        // Rounded away entirely at coarser precision, but still a zero, not an error.
        assertEquals("0.00", formatFixedPoint(1L, 8, 2))
    }

    @Test
    fun formatsNegativeAmounts() {
        assertEquals("-61.01", formatFixedPoint(-6_100_500_000L, 8, 2))
        assertEquals("-0.00000001", formatFixedPoint(-1L, 8, 8))
        // Sign survives a magnitude that rounds to zero, as %f does.
        assertEquals("-0.00", formatFixedPoint(-1L, 8, 2))
    }

    @Test
    fun carriesRoundingAcrossTheDecimalPoint() {
        // 0.999 -> 1.00, i.e. the increment grows the integer part.
        assertEquals("1.00", formatFixedPoint(99_500_000L, 8, 2))
        assertEquals("10.0", formatFixedPoint(9_950L, 3, 1))
    }

    // --- Long extremes: the magnitude must never be negated ---

    @Test
    fun formatsLongMaxValue() {
        assertEquals("92233720368.54775807", formatFixedPoint(Long.MAX_VALUE, 8, 8))
        assertEquals("92233720368.55", formatFixedPoint(Long.MAX_VALUE, 8, 2))
    }

    @Test
    fun formatsLongMinValue() {
        assertEquals("-92233720368.54775808", formatFixedPoint(Long.MIN_VALUE, 8, 8))
        assertEquals("-92233720368.55", formatFixedPoint(Long.MIN_VALUE, 8, 2))
    }

    // --- shannonsToCkbString ---

    @Test
    fun stripsTrailingZerosForDustChangeMessages() {
        assertEquals("0", shannonsToCkbString(0L))
        assertEquals("60", shannonsToCkbString(6_000_000_000L))
        assertEquals("61.00000001", shannonsToCkbString(6_100_000_001L))
        assertEquals("0.00000001", shannonsToCkbString(1L))
        assertEquals("0.5", shannonsToCkbString(50_000_000L))
        assertEquals("-92233720368.54775808", shannonsToCkbString(Long.MIN_VALUE))
    }
}
