package com.rjnr.pocketnode.util

import com.rjnr.pocketnode.core.format.ckbToShannons

/**
 * Validates a CKB amount edit without stripping or rewriting any character.
 * Empty input and a lone dot are allowed while typing, but are not payable amounts.
 * All other edits use [ckbToShannons]: ASCII digits, one optional dot, at most
 * eight decimals, and a nonnegative Long shannon value. Excess precision and
 * locale digits/separators are rejected as a whole, preserving the previous edit.
 */
fun sanitizeAmount(input: String): String? =
    if (input.isEmpty() || input == "." || ckbToShannons(input) != null) input else null
