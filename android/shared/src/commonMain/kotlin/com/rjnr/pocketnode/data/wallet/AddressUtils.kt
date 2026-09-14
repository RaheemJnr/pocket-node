package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.address.CkbAddress
import com.rjnr.pocketnode.core.address.DecodedAddress
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.crypto.toHexString
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script

/**
 * Address utilities built on the shared multiplatform CKB address codec (#454).
 */
object AddressUtils {

    /**
     * Parses a CKB address string into a Script object.
     * Returns null if the address is invalid.
     */
    fun parseAddress(address: String): Script? {
        return try {
            decode(address)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encode a Script to a CKB address string.
     */
    fun encode(script: Script, network: NetworkType): String {
        val hrp = when (network) {
            NetworkType.TESTNET -> CkbAddress.HRP_TESTNET
            NetworkType.MAINNET -> CkbAddress.HRP_MAINNET
        }

        val hashType = when (script.hashType) {
            "type" -> CkbAddress.HASH_TYPE_TYPE
            "data" -> CkbAddress.HASH_TYPE_DATA
            "data1" -> CkbAddress.HASH_TYPE_DATA1
            "data2" -> CkbAddress.HASH_TYPE_DATA2
            else -> CkbAddress.HASH_TYPE_TYPE
        }

        return CkbAddress.encodeFull(
            hrp = hrp,
            codeHash = script.codeHash.hexToByteArray(),
            hashType = hashType,
            args = script.args.hexToByteArray()
        )
    }

    /**
     * Decode a CKB address to extract the Script.
     */
    fun decode(address: String): Script {
        return CkbAddress.decode(address).toScript()
    }

    /**
     * Validate if an address string is valid.
     */
    fun isValid(address: String): Boolean {
        return try {
            CkbAddress.decode(address)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the network type from an address.
     */
    fun getNetwork(address: String): NetworkType? {
        return try {
            when (CkbAddress.decode(address).hrp) {
                CkbAddress.HRP_TESTNET -> NetworkType.TESTNET
                CkbAddress.HRP_MAINNET -> NetworkType.MAINNET
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun DecodedAddress.toScript(): Script = Script(
        codeHash = codeHash.toHexString(),
        hashType = when (hashType) {
            CkbAddress.HASH_TYPE_TYPE -> "type"
            CkbAddress.HASH_TYPE_DATA -> "data"
            CkbAddress.HASH_TYPE_DATA1 -> "data1"
            CkbAddress.HASH_TYPE_DATA2 -> "data2"
            else -> throw IllegalArgumentException("Unknown script hash type $hashType")
        },
        args = args.toHexString()
    )
}
