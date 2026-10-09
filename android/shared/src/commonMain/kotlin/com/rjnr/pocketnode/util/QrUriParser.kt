package com.rjnr.pocketnode.util

private val CKB_ADDRESS_REGEX = Regex("ck[bt]1[a-z0-9]+", RegexOption.IGNORE_CASE)

/**
 * Extracts a CKB address from a raw scanned QR value.
 * Handles: plain ckb1/ckt1 addresses, joyid:// URIs, HTTPS URLs with embedded address.
 * Matches case-insensitively since BIP-173 recommends all-uppercase for QR encoding,
 * and returns the address lowercased. Mixed case is invalid bech32, so a mixed-case
 * match is rejected rather than lowercased into validity.
 * Returns null if no CKB address pattern found, or the match is mixed case.
 */
fun extractCkbAddress(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val match = CKB_ADDRESS_REGEX.find(raw.trim())?.value ?: return null
    val hasLower = match.any { it in 'a'..'z' }
    val hasUpper = match.any { it in 'A'..'Z' }
    if (hasLower && hasUpper) return null
    return match.lowercase()
}
