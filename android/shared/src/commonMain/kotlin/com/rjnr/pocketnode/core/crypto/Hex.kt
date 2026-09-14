package com.rjnr.pocketnode.core.crypto

/**
 * Hex codec for the shared KMP module.
 *
 * Replaces `org.nervos.ckb.utils.Numeric.toHexString` / `hexStringToByteArray`
 * (#454). It matches the SDK byte for byte on every *well-formed* input, and
 * keeps the two shapes the app actually feeds it:
 *
 *  - the `0x` / `0X` prefix is optional on decode and only stripped when the
 *    string is longer than one character;
 *  - an odd-length string is decoded as if it had a leading `0` nibble.
 *
 * It deliberately does NOT match the SDK on malformed input. `Numeric` decodes
 * a non-hex character to `-1` (it leans on `java.lang.Character.digit`) and
 * returns silently corrupted bytes, so a malformed code hash or args would
 * encode into a valid-looking but wrong CKB address. This decoder throws
 * instead — the private helper it replaced in `AddressUtils` threw too.
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

/**
 * Decodes hex, with or without a `0x` prefix and with odd length tolerated.
 *
 * @throws IllegalArgumentException if any character is not a hex digit.
 */
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
        out[0] = hexDigit(cleaned, 0).toByte()
        i = 1
    } else {
        out = ByteArray(len / 2)
        i = 0
    }
    while (i < len) {
        out[(i + 1) / 2] = ((hexDigit(cleaned, i) shl 4) + hexDigit(cleaned, i + 1)).toByte()
        i += 2
    }
    return out
}

/**
 * The nibble at [index], or [IllegalArgumentException].
 *
 * The message carries the index only. This decoder sits on the private-key read
 * path (`data.privateKeyHex.hexToByteArray()`), so the offending character must
 * not reach a log line.
 */
private fun hexDigit(s: String, index: Int): Int {
    return when (val c = s[index]) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex character at index $index")
    }
}
