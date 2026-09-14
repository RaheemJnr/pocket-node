package com.rjnr.pocketnode.util

import com.rjnr.pocketnode.core.crypto.toHexStringNoPrefix

/** Unprefixed lowercase hex. Delegates to the shared codec so there is one encoder (#454). */
fun ByteArray.toHex(): String = toHexStringNoPrefix()

fun String.hexToBytes(): ByteArray {
    val hex = removePrefix("0x")
    require(hex.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

fun Long.toHex(): String = "0x${toString(16)}"

fun Long.toLittleEndianBytes(size: Int = 8): ByteArray {
    val result = ByteArray(size)
    var value = this
    for (i in 0 until size) {
        result[i] = (value and 0xFF).toByte()
        value = value shr 8
    }
    return result
}

fun Int.toLittleEndianBytes(): ByteArray {
    return byteArrayOf(
        (this and 0xFF).toByte(),
        ((this shr 8) and 0xFF).toByte(),
        ((this shr 16) and 0xFF).toByte(),
        ((this shr 24) and 0xFF).toByte()
    )
}
