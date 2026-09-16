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

/**
 * A wallet balance as the home screen shows it: exactly two fraction digits and
 * grouped thousands, e.g. `1_234_560_000_000` -> `"12,345.60"`.
 *
 * The same output as the Android home card's
 * `String.format(Locale.US, "%,.2f CKB", balanceCkb)`, minus its trip through a
 * `Double`. 21 billion CKB is 2.1e18 shannons, comfortably inside `Long` but
 * well past the 2^53 where a `Double` stops representing shannons exactly, so
 * the balance is formatted from the integer all the way down.
 *
 * [groupSeparator] and [decimalSeparator] are parameters rather than a locale
 * lookup: `commonMain` has no locale API, and the wallet renders one canonical
 * form today. A platform that wants its own can pass them.
 */
fun formatCkbBalance(
    shannons: Long,
    groupSeparator: String = ",",
    decimalSeparator: String = ".",
): String = grouped(
    formatFixedPoint(shannons, scaleDigits = 8, decimals = 2),
    groupSeparator,
    decimalSeparator,
)

/**
 * An amount as the send review sheet shows it: grouped thousands and between
 * two and eight fraction digits, e.g. `6_100_000_000` -> `"61.00"` and
 * `1_000` -> `"0.00001"`.
 *
 * The Android `SendReviewSheet.formatReviewCkb`, integer-exact and shared.
 * Two digits minimum so a whole-number amount reads as money rather than as a
 * count; eight maximum so a fee of a thousand shannons is still visible rather
 * than rounded to `0.00`. Trailing zeros past the second digit are dropped,
 * which is why a fee and a round amount can show different widths on the same
 * sheet: each is shown to the precision it actually has.
 */
fun formatCkbAmount(
    shannons: Long,
    groupSeparator: String = ",",
    decimalSeparator: String = ".",
): String {
    val full = formatFixedPoint(shannons, scaleDigits = 8, decimals = 8)
    val whole = full.substringBefore('.')
    // Never below two digits, so "61" is "61.00" rather than "61".
    val fraction = full.substringAfter('.').trimEnd('0').padEnd(2, '0')
    return grouped("$whole.$fraction", groupSeparator, decimalSeparator)
}

/**
 * [shannons] as CKB with exactly [decimals] fraction digits and no grouping,
 * i.e. `String.format("%.${decimals}f", shannons / 1e8)` without the `Double`.
 *
 * The send form's estimated-fee line uses six.
 */
fun formatCkbFixed(shannons: Long, decimals: Int): String =
    formatFixedPoint(shannons, scaleDigits = 8, decimals = decimals)

/**
 * A CKB amount as the user typed it, in shannons, or null if it is not a CKB
 * amount at all.
 *
 * The money path's only string-to-integer step, so it never goes near a
 * floating-point type: the digits are concatenated and parsed once as a
 * `Long`. `0.1 + 0.2` is the reason, and so is the fact that a `Double`
 * stops representing shannons exactly somewhere around 90 million CKB (#321).
 *
 * Truncates rather than rounds past the eighth decimal, matching Android's
 * `BigDecimal(amount).setScale(8, RoundingMode.DOWN)`: a user who typed nine
 * decimals cannot be charged for a shannon they did not type. An amount too
 * large for a `Long` answers null rather than wrapping, the same outcome
 * `longValueExact` produces by throwing.
 *
 * Accepts only ASCII `0`-`9` and a single `.`, so no sign, no exponent and no
 * grouping. `Char.isDigit` is deliberately not used: it also answers true for
 * Arabic-Indic, Devanagari and fullwidth digits, which `toLongOrNull` would
 * then refuse anyway, but the refusal should come from the rule rather than
 * from an accident of the parser.
 * [com.rjnr.pocketnode.util.sanitizeAmount] has already held the field to that
 * shape; this is the second, independent check.
 */
fun ckbToShannons(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.count { it == '.' } > 1) return null
    if (!trimmed.all { it in '0'..'9' || it == '.' }) return null

    val whole = trimmed.substringBefore('.')
    val rawFraction = if (trimmed.contains('.')) trimmed.substringAfter('.') else ""
    // "." alone, and "", neither is a number.
    if (whole.isEmpty() && rawFraction.isEmpty()) return null

    val fraction = rawFraction.take(8).padEnd(8, '0')
    // Leading zeros are harmless to `toLongOrNull`, and overflow answers null.
    return (whole + fraction).toLongOrNull()
}

/** Groups the whole part of an already-formatted fixed-point string. */
private fun grouped(fixed: String, groupSeparator: String, decimalSeparator: String): String {
    val negative = fixed.startsWith("-")
    val unsigned = if (negative) fixed.substring(1) else fixed
    val whole = unsigned.substringBefore('.')
    val fraction = unsigned.substringAfter('.', missingDelimiterValue = "")
    val group = buildString {
        whole.forEachIndexed { index, digit ->
            if (index > 0 && (whole.length - index) % 3 == 0) append(groupSeparator)
            append(digit)
        }
    }
    return (if (negative) "-" else "") + group +
        if (fraction.isEmpty()) "" else decimalSeparator + fraction
}

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
