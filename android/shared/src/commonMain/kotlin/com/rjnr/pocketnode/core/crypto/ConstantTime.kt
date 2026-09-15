package com.rjnr.pocketnode.core.crypto

/**
 * Compares [a] and [b] in time that depends on their length but not on their
 * contents.
 *
 * `ByteArray.contentEquals` and `String.equals` return at the first differing
 * byte, so how long the call takes reveals how many leading bytes were right.
 * That is enough to recover a secret one byte at a time when an attacker can
 * both submit a guess and measure the answer. This one accumulates the
 * differences and only looks at the total at the end.
 *
 * Lengths are not secret here (a hash length is fixed and public), so a length
 * mismatch returns early. Contents never do.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].toInt() xor b[i].toInt())
    }
    return diff == 0
}
