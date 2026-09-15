package com.rjnr.pocketnode.core.format

/**
 * Fixed-point decimal formatting for commonMain, where `java.util.Formatter` and
 * `java.math.BigDecimal` do not exist (#455).
 *
 * Everything here works on the exact integer value (shannons, confirmations) and never on a
 * `Double`: `kotlin.math.round` ties differently per platform (JVM rounds half to even via
 * `Math.rint`, Kotlin/Native rounds half away from zero), and pre-multiplying a `Double` by a
 * power of ten reintroduces binary representation error. Integer digits give one answer on
 * every target, and it is the answer `String.format("%.Nf", …)` used to give.
 *
 * Output is locale-independent: the fraction separator is always `.`, never the comma a
 * `Locale.getDefault()` formatter would produce for some users. CKB amounts are rendered
 * next to a "CKB" suffix and must read the same everywhere.
 */

/**
 * Renders [value] scaled by 10^-[scaleDigits] with exactly [decimals] fraction digits,
 * rounding half away from zero (`RoundingMode.HALF_UP`, what `%f` applied).
 *
 * e.g. `formatFixedPoint(6_100_500_000, scaleDigits = 8, decimals = 2)` -> `"61.01"`.
 *
 * Safe for every [Long] including `MIN_VALUE`: the magnitude is taken from `toString()`,
 * so nothing is ever negated.
 */
internal fun formatFixedPoint(value: Long, scaleDigits: Int, decimals: Int): String {
    require(scaleDigits >= 0) { "scaleDigits must be >= 0" }
    require(decimals in 0..scaleDigits) { "decimals must be in 0..$scaleDigits" }

    val magnitude = value.toString().removePrefix("-")
    // One guaranteed integer digit in front of the scaled fraction digits.
    val padded = magnitude.padStart(scaleDigits + 1, '0')
    val fractionStart = padded.length - scaleDigits

    val kept = padded.substring(0, fractionStart + decimals)
    val discarded = padded.substring(fractionStart + decimals)
    // HALF_UP only ever depends on the first discarded digit: below 5 rounds down, 5 and
    // above rounds away from zero (a tie included).
    val rounded = if (discarded.isNotEmpty() && discarded[0] >= '5') incrementDigits(kept) else kept

    val whole = rounded.dropLast(decimals).trimStart('0').ifEmpty { "0" }
    val fraction = rounded.takeLast(decimals)
    val sign = if (value < 0) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.$fraction"
}

/**
 * Public entry point to [shannonsToCkbString] for platform UI outside this module.
 *
 * The send review sheet's fee and the transaction detail sheet's fee are the
 * same number shown twice, and #497 exists so a user can cross-check one
 * against the other — they must not differ by formatting ("1" vs "1.00").
 * Amounts and totals keep their own formatter; only the fee is unified.
 */
fun formatCkbTrimmed(shannons: Long): String = shannonsToCkbString(shannons)

/**
 * Exact shannons -> CKB string with trailing fraction zeros stripped, i.e. the
 * `BigDecimal(shannons).divide(BigDecimal(100_000_000)).stripTrailingZeros().toPlainString()`
 * this replaces.
 */
internal fun shannonsToCkbString(shannons: Long): String =
    formatFixedPoint(shannons, scaleDigits = 8, decimals = 8)
        .trimEnd('0')
        .trimEnd('.')

/** Adds one to a decimal digit string, growing it on overflow ("99" -> "100"). */
private fun incrementDigits(digits: String): String {
    val chars = digits.toCharArray()
    for (i in chars.indices.reversed()) {
        if (chars[i] == '9') {
            chars[i] = '0'
        } else {
            chars[i] = chars[i] + 1
            return chars.concatToString()
        }
    }
    return "1" + chars.concatToString()
}
