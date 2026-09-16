package com.rjnr.pocketnode.util

/**
 * Sanitizes a CKB amount string entered by the user.
 * - Returns "" for empty input (valid mid-entry state)
 * - Truncates to 8 decimal places (shannon precision: 1 CKB = 100,000,000 shannons)
 * - Returns null for clearly invalid input (letters, multiple dots)
 *
 * `isDigit()` here also accepts Arabic-Indic, Devanagari and fullwidth digits,
 * which no amount parser downstream can read. It is left as it is because
 * Android has shipped this behaviour since v1.x and narrowing it is a change
 * to a live input field rather than a bug fix;
 * [com.rjnr.pocketnode.core.format.ckbToShannons] is ASCII-only and refuses
 * them, so nothing can reach a transaction. Worth converging in its own
 * change.
 */
fun sanitizeAmount(input: String): String? {
    if (input.isEmpty()) return ""
    // Reject multiple decimal points or non-numeric chars
    if (input.count { it == '.' } > 1) return null
    if (!input.all { it.isDigit() || it == '.' }) return null
    // Truncate decimals beyond 8 places
    val dotIndex = input.indexOf('.')
    return if (dotIndex != -1 && input.length - dotIndex - 1 > 8) {
        input.substring(0, dotIndex + 9)
    } else {
        input
    }
}
