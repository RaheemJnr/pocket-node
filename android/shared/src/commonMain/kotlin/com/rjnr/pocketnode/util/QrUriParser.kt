package com.rjnr.pocketnode.util

private val CKB_ADDRESS_REGEX = Regex("ck[bt]1[a-z0-9]+")

/**
 * Extracts a CKB address from a raw scanned QR value.
 * Handles: plain ckb1/ckt1 addresses, joyid:// URIs, HTTPS URLs with embedded address.
 * Returns null if no CKB address pattern found.
 */
fun extractCkbAddress(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return CKB_ADDRESS_REGEX.find(raw.trim())?.value
}
