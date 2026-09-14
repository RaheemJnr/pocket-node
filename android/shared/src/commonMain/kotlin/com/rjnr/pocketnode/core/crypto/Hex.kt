package com.rjnr.pocketnode.core.crypto

/**
 * Hex codec for the shared KMP module.
 *
 * Byte-for-byte compatible with `org.nervos.ckb.utils.Numeric.toHexString` /
 * `Numeric.hexStringToByteArray`, which this replaces (#454). The quirks below
 * are the SDK's and are reproduced deliberately so the swap is behaviour
 * preserving on every input the app already feeds through it:
 *
 *  - the `0x` / `0X` prefix is optional on decode and only stripped when the
 *    string is longer than one character;
 *  - an odd-length string is decoded as if it had a leading `0` nibble;
 *  - a character that is not a hex digit decodes as `-1` (like
 *    `java.lang.Character.digit`) rather than throwing, so malformed input
 *    yields the same garbage bytes it does today instead of a new exception
 *    type escaping from the transaction-serialisation path.
 */
private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex without the `0x` prefix. */
fun ByteArray.toHexStringNoPrefix(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4])
        out.append(HEX_DIGITS[v and 0x0F])
    }
    return out.toString()
}

/** Lowercase hex with the `0x` prefix — matches `Numeric.toHexString(byte[])`. */
fun ByteArray.toHexString(): String = "0x" + toHexStringNoPrefix()

/** Matches `Numeric.hexStringToByteArray` — see the file KDoc for the quirks. */
fun String.hexToByteArray(): ByteArray {
    val cleaned = if (length > 1 && this[0] == '0' && (this[1] == 'x' || this[1] == 'X')) {
        substring(2)
    } else {
        this
    }
    val len = cleaned.length
    if (len == 0) return ByteArray(0)

    val out: ByteArray
    var i: Int
    if (len % 2 != 0) {
        out = ByteArray(len / 2 + 1)
        out[0] = hexDigit(cleaned[0]).toByte()
        i = 1
    } else {
        out = ByteArray(len / 2)
        i = 0
    }
    while (i < len) {
        out[(i + 1) / 2] = ((hexDigit(cleaned[i]) shl 4) + hexDigit(cleaned[i + 1])).toByte()
        i += 2
    }
    return out
}

/** `java.lang.Character.digit(c, 16)` for ASCII: `-1` when [c] is not a hex digit. */
private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
