package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.core.address.CkbAddress
import com.rjnr.pocketnode.core.crypto.toHexString
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.transaction.RecipientOutput
import com.rjnr.pocketnode.data.wallet.AddressUtils

/** Format recognition is not proof that the recipient can unlock a script. */
enum class DestinationKind { RECOGNIZED, KNOWN_INVALID, CUSTOM, INVALID_FORMAT, WRONG_NETWORK }

data class DestinationAssessment(val kind: DestinationKind, val message: String) {
    val canSend: Boolean get() = kind == DestinationKind.RECOGNIZED || kind == DestinationKind.CUSTOM
    val isCustom: Boolean get() = kind == DestinationKind.CUSTOM
}

/** Send policy only. The generic RFC 0021 codec deliberately accepts arbitrary scripts. */
object DestinationPolicy {
    const val CUSTOM_GUIDANCE = "Custom lock script. Verify the recipient supports this script before sending."
    private const val MULTISIG = "0x5c5069eb0857efc65e1bca0c07df34c31663b3622fd3876c876320fc9634e2a8"
    private const val ACP_MAINNET = "0xd369597ff47f29fbc0d47d2e3775370d1250b85140c670e4718af712983a2354"
    private const val ACP_TESTNET = "0x3419a1c09eb2567f6552ee7a8ecffd64155cffe0f1796e6e61ec088d740c1356"

    // RFC 0024, RFC 0026 and ckb-system-scripts: sighash args=20,
    // legacy multisig args=20 or 28 (optional since), ACP args=20..22.
    fun assess(address: String, network: NetworkType): DestinationAssessment {
        val decoded = try { CkbAddress.decode(address) } catch (_: Exception) {
            return DestinationAssessment(DestinationKind.INVALID_FORMAT, "Invalid address format")
        }
        val actualNetwork = if (decoded.hrp == CkbAddress.HRP_MAINNET) NetworkType.MAINNET else NetworkType.TESTNET
        if (actualNetwork != network) return DestinationAssessment(DestinationKind.WRONG_NETWORK,
            "This is a ${actualNetwork.name.lowercase()} address on ${network.name.lowercase()}")
        val hash = decoded.codeHash.toHexString()
        val lengths = when (hash) {
            Script.SECP256K1_CODE_HASH -> setOf(20)
            MULTISIG -> setOf(20, 28)
            ACP_MAINNET, ACP_TESTNET -> setOf(20, 21, 22)
            else -> return DestinationAssessment(DestinationKind.CUSTOM, CUSTOM_GUIDANCE)
        }
        if (decoded.hashType != CkbAddress.HASH_TYPE_TYPE || decoded.args.size !in lengths) {
            return DestinationAssessment(DestinationKind.KNOWN_INVALID,
                "Invalid built-in lock parameters. Ask the recipient for a correct address.")
        }
        if ((hash == ACP_MAINNET && network != NetworkType.MAINNET) ||
            (hash == ACP_TESTNET && network != NetworkType.TESTNET)) {
            return DestinationAssessment(DestinationKind.KNOWN_INVALID,
                "This built-in lock belongs to another network. Ask the recipient for a correct address.")
        }
        return DestinationAssessment(DestinationKind.RECOGNIZED, "Recognized address format")
    }

    /** Plain empty-data/no-type output storage, with the existing 61 CKB application floor. */
    fun minimumCapacity(address: String): Long {
        // ByteArray.size is Int-bounded; promotion before arithmetic keeps even
        // the maximum representable args size comfortably below Long overflow.
        val argsBytes = CkbAddress.decode(address).args.size.toLong()
        return maxOf(MIN_CELL_SHANNONS, (8L + 32L + 1L + argsBytes) * 100_000_000L)
    }

    internal fun requireRecipient(recipient: RecipientOutput, network: NetworkType? = null): Script {
        val assessment = assess(recipient.address, network ?: AddressUtils.getNetwork(recipient.address) ?: NetworkType.TESTNET)
        require(assessment.canSend) { assessment.message }
        val minimum = minimumCapacity(recipient.address)
        require(recipient.amountShannons >= minimum) {
            "This recipient requires at least ${minimum / 100_000_000L} CKB to store the output."
        }
        return requireNotNull(AddressUtils.parseAddress(recipient.address))
    }
}
