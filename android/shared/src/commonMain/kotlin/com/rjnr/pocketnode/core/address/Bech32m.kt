package com.rjnr.pocketnode.core.address

/** Thrown for any malformed CKB address or bech32 string. */
class AddressFormatException(message: String) : IllegalArgumentException(message)

/** Which checksum constant a bech32 string uses: BIP-173 or BIP-350. */
enum class Bech32Encoding { BECH32, BECH32M }

/** A decoded bech32 string: its encoding, its hrp, and its payload as 5-bit groups. */
class Bech32Data(
    val encoding: Bech32Encoding,
    val hrp: String,
    /** Payload in 5-bit groups, checksum already stripped. */
    val data: ByteArray,
)

/**
 * bech32 (BIP-173) and bech32m (BIP-350) codec for the shared KMP module.
 *
 * Replaces `org.nervos.ckb.utils.address.Bech32` (#454); the algorithm is the
 * same bitcoinj-derived one, so encodings and rejections match the SDK.
 */
object Bech32m {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3

    /**
     * Encodes [values] (5-bit groups) under [hrp], appending the [encoding] checksum.
     *
     * Validates its inputs to the same standard [decode] does: an out-of-range
     * group or an hrp character outside printable ASCII would otherwise produce
     * a string that does not round-trip, and for an address that means a
     * well-formed-looking payment destination nobody can spend from.
     */
    fun encode(encoding: Bech32Encoding, hrp: String, values: ByteArray): String {
        if (hrp.isEmpty()) throw AddressFormatException("Human-readable part is too short")
        if (hrp.length > 83) throw AddressFormatException("Human-readable part is too long")
        for (i in hrp.indices) {
            val c = hrp[i]
            // Same charset rule as decode(). '1' stays legal: the separator is
            // the LAST '1', and the data charset excludes it, so an hrp
            // containing one still round-trips (BIP-173).
            if (c.code < 33 || c.code > 126) {
                throw AddressFormatException("Invalid character '$c' at $i")
            }
        }
        for (i in values.indices) {
            val v = values[i].toInt()
            if (v < 0 || v > 31) throw AddressFormatException("Data value '$v' at $i is not 5 bits")
        }
        val lowerHrp = hrp.lowercase()
        val checksum = createChecksum(encoding, lowerHrp, values)
        val out = StringBuilder(lowerHrp.length + 1 + values.size + checksum.size)
        out.append(lowerHrp)
        out.append('1')
        for (b in values) out.append(CHARSET[b.toInt()])
        for (b in checksum) out.append(CHARSET[b.toInt()])
        return out.toString()
    }

    /** Decodes [str], rejecting mixed case, non-charset characters and a bad checksum. */
    fun decode(str: String): Bech32Data {
        var lower = false
        var upper = false
        if (str.length < 8) throw AddressFormatException("Input too short: ${str.length}")
        for (i in str.indices) {
            val c = str[i]
            if (c.code < 33 || c.code > 126) throw AddressFormatException("Invalid character '$c' at $i")
            if (c in 'a'..'z') {
                if (upper) throw AddressFormatException("Invalid character '$c' at $i")
                lower = true
            }
            if (c in 'A'..'Z') {
                if (lower) throw AddressFormatException("Invalid character '$c' at $i")
                upper = true
            }
        }
        val pos = str.lastIndexOf('1')
        if (pos < 1) throw AddressFormatException("Missing human-readable part")
        val dataPartLength = str.length - 1 - pos
        if (dataPartLength < 6) throw AddressFormatException("Data part too short: $dataPartLength")

        val values = ByteArray(dataPartLength)
        for (i in 0 until dataPartLength) {
            val c = str[i + pos + 1]
            val rev = charsetRev(c)
            if (rev < 0) throw AddressFormatException("Invalid character '$c' at ${i + pos + 1}")
            values[i] = rev.toByte()
        }
        val hrp = str.substring(0, pos).lowercase()
        val encoding = verifyChecksum(hrp, values) ?: throw AddressFormatException("Invalid checksum")
        return Bech32Data(encoding, hrp, values.copyOfRange(0, values.size - 6))
    }

    /**
     * Regroups [input] from [fromBits]-wide to [toBits]-wide groups, zero-padding
     * the tail when [pad] is set and otherwise rejecting a non-zero remainder.
     */
    fun convertBits(input: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>(input.size * fromBits / toBits + 2)
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        for (element in input) {
            val value = element.toInt() and 0xFF
            if ((value ushr fromBits) != 0) {
                throw AddressFormatException("Input value '$value' exceeds '$fromBits' bit size")
            }
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) out.add(((acc shl (toBits - bits)) and maxv).toByte())
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw AddressFormatException("Could not convert bits, invalid padding")
        }
        return out.toByteArray()
    }

    private fun charsetRev(c: Char): Int = CHARSET.indexOf(c.lowercaseChar())

    private fun polymod(values: ByteArray): Int {
        var c = 1
        for (vI in values) {
            val c0 = (c ushr 25) and 0xFF
            c = ((c and 0x1ffffff) shl 5) xor (vI.toInt() and 0xFF)
            if ((c0 and 1) != 0) c = c xor 0x3b6a57b2
            if ((c0 and 2) != 0) c = c xor 0x26508e6d
            if ((c0 and 4) != 0) c = c xor 0x1ea119fa
            if ((c0 and 8) != 0) c = c xor 0x3d4233dd
            if ((c0 and 16) != 0) c = c xor 0x2a1462b3
        }
        return c
    }

    private fun expandHrp(hrp: String): ByteArray {
        val hrpLength = hrp.length
        val ret = ByteArray(hrpLength * 2 + 1)
        for (i in 0 until hrpLength) {
            val c = hrp[i].code and 0x7f
            ret[i] = ((c ushr 5) and 0x07).toByte()
            ret[i + hrpLength + 1] = (c and 0x1f).toByte()
        }
        ret[hrpLength] = 0
        return ret
    }

    private fun verifyChecksum(hrp: String, values: ByteArray): Bech32Encoding? {
        val combined = expandHrp(hrp) + values
        return when (polymod(combined)) {
            BECH32_CONST -> Bech32Encoding.BECH32
            BECH32M_CONST -> Bech32Encoding.BECH32M
            else -> null
        }
    }

    private fun createChecksum(encoding: Bech32Encoding, hrp: String, values: ByteArray): ByteArray {
        val hrpExpanded = expandHrp(hrp)
        val enc = ByteArray(hrpExpanded.size + values.size + 6)
        hrpExpanded.copyInto(enc, 0)
        values.copyInto(enc, hrpExpanded.size)
        val const = if (encoding == Bech32Encoding.BECH32) BECH32_CONST else BECH32M_CONST
        val mod = polymod(enc) xor const
        return ByteArray(6) { i -> ((mod ushr (5 * (5 - i))) and 31).toByte() }
    }
}
