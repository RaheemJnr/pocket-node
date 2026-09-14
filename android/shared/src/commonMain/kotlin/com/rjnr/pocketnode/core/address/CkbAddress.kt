package com.rjnr.pocketnode.core.address

import com.rjnr.pocketnode.core.crypto.hexToByteArray

/** A CKB address decoded back into its hrp and the lock script it carries. */
class DecodedAddress(
    /** `ckb` for mainnet, `ckt` for testnet. */
    val hrp: String,
    val codeHash: ByteArray,
    /** Packed hash type: see [CkbAddress.HASH_TYPE_DATA] and friends. */
    val hashType: Byte,
    val args: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedAddress) return false
        return hrp == other.hrp &&
            codeHash.contentEquals(other.codeHash) &&
            hashType == other.hashType &&
            args.contentEquals(other.args)
    }

    override fun hashCode(): Int {
        var result = hrp.hashCode()
        result = 31 * result + codeHash.contentHashCode()
        result = 31 * result + hashType.toInt()
        result = 31 * result + args.contentHashCode()
        return result
    }
}

/**
 * CKB address codec (RFC 0021) for the shared KMP module.
 *
 * Replaces `org.nervos.ckb.utils.address.Address` (#454). [encodeFull] always
 * produces the current full format — payload header `0x00`, bech32m — and
 * [decode] additionally accepts every legacy format the SDK accepts, so an
 * address pasted from an older wallet still resolves:
 *
 *  | header | encoding | format                                        |
 *  |--------|----------|-----------------------------------------------|
 *  | `0x00` | bech32m  | full (current)                                |
 *  | `0x01` | bech32   | short, by code-hash index (deprecated)        |
 *  | `0x02` | bech32   | full with hash type `data` (deprecated)       |
 *  | `0x04` | bech32   | full with hash type `type` (deprecated)       |
 */
object CkbAddress {

    const val HRP_MAINNET: String = "ckb"
    const val HRP_TESTNET: String = "ckt"

    // Packed hash-type bytes. Note the gap: `data2` is 0x04, not 0x03.
    const val HASH_TYPE_DATA: Byte = 0x00
    const val HASH_TYPE_TYPE: Byte = 0x01
    const val HASH_TYPE_DATA1: Byte = 0x02
    const val HASH_TYPE_DATA2: Byte = 0x04

    private const val FORMAT_FULL_BECH32M: Byte = 0x00
    private const val FORMAT_SHORT: Byte = 0x01
    private const val FORMAT_FULL_DATA: Byte = 0x02
    private const val FORMAT_FULL_TYPE: Byte = 0x04

    // Code hashes the short format refers to by index.
    private val SECP256K1_BLAKE160_SIGHASH_ALL_CODE_HASH =
        "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8".hexToByteArray()
    private val SECP256K1_BLAKE160_MULTISIG_ALL_CODE_HASH_LEGACY =
        "0x5c5069eb0857efc65e1bca0c07df34c31663b3622fd3876c876320fc9634e2a8".hexToByteArray()
    private val ANY_CAN_PAY_CODE_HASH_MAINNET =
        "0xd369597ff47f29fbc0d47d2e3775370d1250b85140c670e4718af712983a2354".hexToByteArray()
    private val ANY_CAN_PAY_CODE_HASH_TESTNET =
        "0x3419a1c09eb2567f6552ee7a8ecffd64155cffe0f1796e6e61ec088d740c1356".hexToByteArray()

    /** Encodes the current full format (header `0x00`, bech32m). */
    fun encodeFull(hrp: String, codeHash: ByteArray, hashType: Byte, args: ByteArray): String {
        require(codeHash.size == 32) { "codeHash must be 32 bytes, got ${codeHash.size}" }
        val payload = ByteArray(1 + codeHash.size + 1 + args.size)
        payload[0] = FORMAT_FULL_BECH32M
        codeHash.copyInto(payload, 1)
        payload[1 + codeHash.size] = hashType
        args.copyInto(payload, 1 + codeHash.size + 1)
        val data = Bech32m.convertBits(payload, 8, 5, pad = true)
        return Bech32m.encode(Bech32Encoding.BECH32M, hrp, data)
    }

    /** Decodes any of the four formats above; throws [AddressFormatException] otherwise. */
    fun decode(address: String): DecodedAddress {
        val bech32 = Bech32m.decode(address)
        if (bech32.hrp != HRP_MAINNET && bech32.hrp != HRP_TESTNET) {
            throw AddressFormatException("Invalid hrp")
        }
        val payload = Bech32m.convertBits(bech32.data, 5, 8, pad = false)
        if (payload.isEmpty()) throw AddressFormatException("Empty payload")

        return when (payload[0]) {
            FORMAT_FULL_BECH32M -> {
                if (bech32.encoding != Bech32Encoding.BECH32M) {
                    throw AddressFormatException("Payload header 0x00 must have encoding bech32m")
                }
                decodeFullBech32m(payload, bech32.hrp)
            }

            FORMAT_SHORT -> {
                if (bech32.encoding != Bech32Encoding.BECH32) {
                    throw AddressFormatException("Payload header 0x01 must have encoding bech32")
                }
                decodeShort(payload, bech32.hrp)
            }

            FORMAT_FULL_DATA, FORMAT_FULL_TYPE -> {
                if (bech32.encoding != Bech32Encoding.BECH32) {
                    throw AddressFormatException("Payload header 0x02 or 0x04 must have encoding bech32")
                }
                decodeFullBech32(payload, bech32.hrp)
            }

            else -> throw AddressFormatException("Unknown format type")
        }
    }

    private fun decodeFullBech32m(payload: ByteArray, hrp: String): DecodedAddress {
        if (payload.size < 34) throw AddressFormatException("Invalid payload length ${payload.size}")
        val hashType = payload[33]
        if (hashType != HASH_TYPE_DATA && hashType != HASH_TYPE_TYPE &&
            hashType != HASH_TYPE_DATA1 && hashType != HASH_TYPE_DATA2
        ) {
            throw AddressFormatException("Unknown script hash type")
        }
        return DecodedAddress(
            hrp = hrp,
            codeHash = payload.copyOfRange(1, 33),
            hashType = hashType,
            args = payload.copyOfRange(34, payload.size),
        )
    }

    private fun decodeFullBech32(payload: ByteArray, hrp: String): DecodedAddress {
        if (payload.size < 33) throw AddressFormatException("Invalid payload length ${payload.size}")
        val hashType = if (payload[0] == FORMAT_FULL_TYPE) HASH_TYPE_TYPE else HASH_TYPE_DATA
        return DecodedAddress(
            hrp = hrp,
            codeHash = payload.copyOfRange(1, 33),
            hashType = hashType,
            args = payload.copyOfRange(33, payload.size),
        )
    }

    private fun decodeShort(payload: ByteArray, hrp: String): DecodedAddress {
        if (payload.size < 2) throw AddressFormatException("Invalid payload length ${payload.size}")
        val args = payload.copyOfRange(2, payload.size)
        val codeHash = when (payload[1]) {
            0x00.toByte() -> {
                if (args.size != 20) throw AddressFormatException("Invalid args length ${args.size}")
                SECP256K1_BLAKE160_SIGHASH_ALL_CODE_HASH
            }

            0x01.toByte() -> {
                if (args.size != 20) throw AddressFormatException("Invalid args length ${args.size}")
                SECP256K1_BLAKE160_MULTISIG_ALL_CODE_HASH_LEGACY
            }

            0x02.toByte() -> {
                if (args.size < 20 || args.size > 22) {
                    throw AddressFormatException("Invalid args length ${args.size}")
                }
                if (hrp == HRP_MAINNET) ANY_CAN_PAY_CODE_HASH_MAINNET else ANY_CAN_PAY_CODE_HASH_TESTNET
            }

            else -> throw AddressFormatException("Unknown code hash index")
        }
        // The short format only ever named `type`-hash-type scripts.
        return DecodedAddress(hrp, codeHash.copyOf(), HASH_TYPE_TYPE, args)
    }
}
