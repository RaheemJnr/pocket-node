package com.rjnr.pocketnode.data.gateway.models

import com.rjnr.pocketnode.core.format.formatFixedPoint
import com.rjnr.pocketnode.core.format.shannonsToCkbString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 1 CKB = 100,000,000 shannons, so a shannon amount is a decimal scaled by 10^-8. */
private const val SHANNON_SCALE_DIGITS = 8

@Serializable
data class Script(
    @SerialName("code_hash") val codeHash: String,
    @SerialName("hash_type") val hashType: String,
    val args: String
) {
    companion object {
        const val SECP256K1_CODE_HASH =
            "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8"
    }
}

@Serializable
data class OutPoint(
    @SerialName("tx_hash") val txHash: String,
    val index: String
)

@Serializable
data class CellDep(
    @SerialName("out_point") val outPoint: OutPoint,
    @SerialName("dep_type") val depType: String
) {
    companion object {
        val SECP256K1_TESTNET = CellDep(
            outPoint = OutPoint(
                txHash = "0xf8de3bb47d055cdf460d93a2a6e1b05f7432f9777c8c474abf4eec1d4aee5d37",
                index = "0x0"
            ),
            depType = "dep_group"
        )

        val SECP256K1_MAINNET = CellDep(
            outPoint = OutPoint(
                txHash = "0x71a7ba8fc96349fea0ed3a5c47992e3b4084b031a42264a018e0072e8172e46c",
                index = "0x0"
            ),
            depType = "dep_group"
        )
    }
}

@Serializable
data class CellInput(
    val since: String = "0x0",
    @SerialName("previous_output") val previousOutput: OutPoint
)

@Serializable
data class CellOutput(
    val capacity: String,
    val lock: Script,
    val type: Script? = null
)

@Serializable
data class Transaction(
    val version: String = "0x0",
    @SerialName("cell_deps") val cellDeps: List<CellDep>,
    @SerialName("header_deps") val headerDeps: List<String> = emptyList(),
    @SerialName("inputs") val cellInputs: List<CellInput>,
    @SerialName("outputs") val cellOutputs: List<CellOutput>,
    @SerialName("outputs_data") val outputsData: List<String>,
    val witnesses: List<String>
)

@Serializable
data class Cell(
    @SerialName("out_point")
    val outPoint: OutPoint,
    val capacity: String,
    @SerialName("block_number")
    val blockNumber: String,
    val lock: Script,
    val type: Script? = null,
    val data: String = "0x"
) {
    // Malformed node data degrades to 0 instead of crashing balance
    // flow collectors with NumberFormatException (#321).
    fun capacityAsLong(): Long = capacity.removePrefix("0x").toLongOrNull(16) ?: 0L
}

@Serializable
data class TransactionRecord(
    @SerialName("tx_hash") val txHash: String,
    @SerialName("block_number") val blockNumber: String,
    @SerialName("block_hash") val blockHash: String,
    val timestamp: Long,
    @SerialName("balance_change") val balanceChange: String,
    val direction: String,
    // Legacy fee field: a hex shannon string that has only ever been populated
    // on DAO pending rows and is "0x0" everywhere else. Kept because the CSV
    // exporter reads it. [feeShannons] below is the authoritative value.
    val fee: String,
    val confirmations: Int,
    // Raw hex timestamp from CKB block header (e.g. "0x18c8d0a7a00"), null if not yet fetched
    @SerialName("block_timestamp_hex") val blockTimestampHex: String? = null,
    // True if the transaction interacts with a DAO type script cell
    @SerialName("is_dao_related") val isDaoRelated: Boolean = false,
    // True if this tx was sent as one batch of a bulk airdrop. Set at display
    // time from a persisted set of bulk tx hashes (not serialized or synced).
    @SerialName("is_bulk") val isBulk: Boolean = false,
    // "PENDING", "CONFIRMED", "FAILED" — defaults to CONFIRMED so historical
    // rows that never had an explicit status (pre-#115) render as confirmed.
    @SerialName("status") val status: String = "CONFIRMED",
    // Network fee in shannons: Σ(inputs) − Σ(outputs) (#497). Null means
    // "not known yet", NOT "zero" — the light client only resolves input
    // capacities for cells it has indexed, so a tx whose inputs predate the
    // sync window cannot be scored and the detail sheet says "Pending"
    // instead of showing a wrong number. Incoming transactions leave this
    // null because the sender paid the fee, not us.
    @SerialName("fee_shannons") val feeShannons: Long? = null
) {
    /**
     * Get balance change as CKB amount (from shannons)
     */
    fun balanceChangeAsCkb(): Double = balanceChangeShannons() / 100_000_000.0

    /** The raw signed shannon amount. Amounts are formatted from this, never from a Double. */
    private fun balanceChangeShannons(): Long =
        balanceChange.removePrefix("0x").toLongOrNull(16) ?: 0L

    /**
     * Get formatted amount string with sign
     */
    fun formattedAmount(): String {
        val shannons = balanceChangeShannons()
        // Thresholds are the exact shannon equivalents of the 1 CKB / 0.0001 CKB cutoffs.
        val formattedValue = when {
            shannons >= 100_000_000L -> formatFixedPoint(shannons, SHANNON_SCALE_DIGITS, 2)
            shannons >= 10_000L -> formatFixedPoint(shannons, SHANNON_SCALE_DIGITS, 4)
            else -> formatFixedPoint(shannons, SHANNON_SCALE_DIGITS, 8)
        }
        return when (direction) {
            "in", "dao_unlock" -> "+$formattedValue CKB"
            "out", "dao_deposit" -> "-$formattedValue CKB"
            "dao_withdraw" -> "$formattedValue CKB"
            "self" -> "$formattedValue CKB"
            else -> "$formattedValue CKB"
        }
    }

    /**
     * Get compact confirmation string (e.g., "7.4K" for 7438)
     */
    fun compactConfirmations(): String {
        return when {
            confirmations >= 1_000_000 -> formatFixedPoint(confirmations.toLong(), 6, 1) + "M"
            confirmations >= 1_000 -> formatFixedPoint(confirmations.toLong(), 3, 1) + "K"
            else -> confirmations.toString()
        }
    }

    /**
     * Whether a network-fee row belongs on this transaction's detail sheet.
     *
     * Every direction we can originate — "out", "self", and the three DAO
     * ops — pays a fee. A plain "in" was paid for by the sender, so showing
     * a fee row there would be wrong at any value.
     */
    fun paysNetworkFee(): Boolean = direction != "in"

    /**
     * The network fee as a CKB string (8 decimals, trailing zeros trimmed),
     * or null when [feeShannons] is not known yet. Callers render null as
     * "Pending" rather than hiding the row — see [paysNetworkFee].
     */
    fun formattedFee(): String? = feeShannons?.let { "${shannonsToCkbString(it)} CKB" }

    fun isIncoming(): Boolean = direction == "in"
    fun isOutgoing(): Boolean = direction == "out"
    fun isSelfTransfer(): Boolean = direction == "self"

    fun isDaoDeposit(): Boolean = direction == "dao_deposit"
    fun isDaoWithdraw(): Boolean = direction == "dao_withdraw"
    fun isDaoUnlock(): Boolean = direction == "dao_unlock"
    fun isDaoTransaction(): Boolean = isDaoRelated || direction.startsWith("dao_")

    /**
     * Check if transaction is confirmed
     */
    fun isConfirmed(): Boolean = confirmations > 0

    /**
     * Check if transaction is pending (not yet confirmed)
     */
    fun isPending(): Boolean = confirmations == 0

    /**
     * Get relative time string (e.g., "2 hours ago", "Yesterday").
     *
     * [nowMillis] is passed in rather than read from a platform clock: shared code takes the
     * current time from its caller instead of adding an expect/actual seam for it (D1).
     */
    fun getRelativeTimeString(nowMillis: Long): String {
        // If no timestamp but has confirmations, show as confirmed without time
        if (timestamp == 0L) {
            return if (confirmations > 0) "Confirmed" else "Pending"
        }

        val diff = nowMillis - timestamp

        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours hr ago"
            days == 1L -> "Yesterday"
            days < 7 -> "$days days ago"
            days < 30 -> "${days / 7} weeks ago"
            days < 365 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }

    /**
     * Get short transaction hash for display
     */
    fun shortTxHash(): String {
        return if (txHash.length > 20) {
            "${txHash.take(10)}...${txHash.takeLast(6)}"
        } else {
            txHash
        }
    }
    fun shorterTxHash(): String {
        return if (txHash.length > 10) {
            "${txHash.take(4)}...${txHash.takeLast(4)}"
        } else {
            txHash
        }
    }
}

enum class NetworkType(val hrp: String) {
    TESTNET("ckt"),
    MAINNET("ckb")
}

val NetworkType.displayName: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }
