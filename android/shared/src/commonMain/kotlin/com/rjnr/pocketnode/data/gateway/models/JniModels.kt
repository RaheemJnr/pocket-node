package com.rjnr.pocketnode.data.gateway.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JniPagination<T>(
    @SerialName("objects") val objects: List<T>,
    @SerialName("last_cursor") val lastCursor: String?
)

@Serializable
data class JniCellsCapacity(
    val capacity: String,
    @SerialName("block_number") val blockNumber: String,
    @SerialName("block_hash") val blockHash: String
)

@Serializable
data class JniTransactionView(
    val hash: String,
    val version: String,
    @SerialName("cell_deps") val cellDeps: List<CellDep>,
    @SerialName("header_deps") val headerDeps: List<String>,
    val inputs: List<CellInput>,
    val outputs: List<CellOutput>,
    @SerialName("outputs_data") val outputsData: List<String>,
    val witnesses: List<String>
)

@Serializable
data class JniTxWithCell(
    val transaction: JniTransactionView,
    @SerialName("block_number") val blockNumber: String,
    @SerialName("tx_index") val txIndex: String,
    @SerialName("io_index") val ioIndex: String,
    @SerialName("io_type") val ioType: String, // "input" or "output"
    @SerialName("io_capacity") val ioCapacity: String
)

@Serializable
data class JniTxStatus(
    val status: String,
    @SerialName("block_hash") val blockHash: String? = null
)

@Serializable
data class JniTransactionWithStatus(
    val transaction: JniTransactionView? = null,
    val cycles: String? = null,
    @SerialName("tx_status") val txStatus: JniTxStatus
)

@Serializable
data class JniHeaderView(
    val hash: String,
    val number: String,
    val epoch: String,
    val timestamp: String,
    @SerialName("parent_hash") val parentHash: String,
    @SerialName("transactions_root") val transactionsRoot: String,
    @SerialName("proposals_hash") val proposalsHash: String,
    @SerialName("extra_hash") val extraHash: String,
    val dao: String,
    val nonce: String
)

@Serializable
data class JniFetchHeaderResponse(
    val status: String,
    val data: JniHeaderView? = null
)

@Serializable
data class JniFetchTransactionResponse(
    val status: String,
    val data: JniTransactionWithStatus? = null,
    val timestamp: String? = null,
    @SerialName("first_sent") val firstSent: String? = null
)

@Serializable
data class JniScriptStatus(
    val script: Script,
    @SerialName("script_type") val scriptType: String = "lock",
    @SerialName("block_number") val blockNumber: String
)

@Serializable
data class JniSearchKey(
    val script: Script,
    @SerialName("script_type") val scriptType: String = "lock",
    @SerialName("filter") val filter: JniSearchKeyFilter? = null,
    @SerialName("with_data") val withData: Boolean = true
)

@Serializable
data class JniSearchKeyFilter(
    val script: Script? = null,
    @SerialName("script_len_range") val scriptLenRange: List<String>? = null,
    @SerialName("output_data_len_range") val outputDataLenRange: List<String>? = null,
    @SerialName("block_range") val blockRange: List<String>? = null
)

@Serializable
data class JniLocalNode(
    val version: String,
    @SerialName("node_id") val nodeId: String,
    val active: Boolean
)

@Serializable
data class JniRemoteNode(
    val version: String,
    @SerialName("node_id") val nodeId: String,
    @SerialName("connected_duration") val connectedDuration: String
)
