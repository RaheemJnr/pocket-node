package com.rjnr.pocketnode.core.format

import kotlin.math.abs
import kotlin.math.round

/**
 * Multiplatform replacements for the JVM-only decimal formatting the moved CKB models and
 * the transaction builder used to rely on (`String.format("%.2f", …)`, `java.math.BigDecimal`).
 * `java.util.Formatter` and `BigDecimal` do not exist in `commonMain` (#455).
 */

/**
 * Renders [value] with exactly [decimals] fraction digits, rounding half away from zero —
 * the behaviour of `String.format("%.${decimals}f", value)` (HALF_UP on the magnitude).
 * The sign is kept for negative inputs even when the rounded magnitude is zero, again
 * matching `java.util.Formatter`.
 */
fun formatDecimal(value: Double, decimals: Int): String {
    require(decimals >= 0) { "decimals must be >= 0" }
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = round(abs(value) * scale).toLong()
    val whole = scaled / scale
    val fraction = scaled % scale

    val sb = StringBuilder()
    if (value < 0) sb.append('-')
    sb.append(whole)
    if (decimals > 0) {
        sb.append('.')
        val digits = fraction.toString()
        repeat(decimals - digits.length) { sb.append('0') }
        sb.append(digits)
    }
    return sb.toString()
}

/**
 * Exact shannons -> CKB decimal string with trailing fraction zeros stripped, i.e. the
 * `BigDecimal(shannons).divide(BigDecimal(100_000_000)).stripTrailingZeros().toPlainString()`
 * this replaces. Integer arithmetic only, so there is no rounding to reason about.
 */
fun shannonsToCkbString(shannons: Long): String {
    val negative = shannons < 0
    val magnitude = if (negative) -shannons else shannons
    val whole = magnitude / 100_000_000L
    val fraction = (magnitude % 100_000_000L).toString().padStart(8, '0').trimEnd('0')
    val sign = if (negative) "-" else ""
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}
