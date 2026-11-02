package com.rjnr.pocketnode.data.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.rjnr.pocketnode.data.gateway.models.*

/** Membership of raw transaction fields only; no witness authentication or unspentness. */
@Serializable
data class DaoRecoveryIdentity(
    @SerialName("genesis_hash") val genesisHash: String,
    @SerialName("store_generation") val storeGeneration: String,
    @SerialName("run_nonce") val runNonce: String,
)
/** Ephemeral context. Never persist tokens as valid authority. */
@ConsistentCopyVisibility
data class DaoRecoveryTicket internal constructor(val requestId: String, val transactionHash: String, val identity: DaoRecoveryIdentity)
@Serializable
data class DaoRecoveryRawTransaction(
    val version: String,
    @SerialName("cell_deps") val cellDeps: List<CellDep>,
    @SerialName("header_deps") val headerDeps: List<String>,
    val inputs: List<DaoRecoveryInput>,
    val outputs: List<DaoRecoveryOutput>,
    @SerialName("outputs_data") val outputsData: List<String>,
)
@Serializable
data class DaoRecoveryInput(val since: String, @SerialName("previous_output") val previousOutput: OutPoint)
@Serializable
data class DaoRecoveryOutput(val capacity: String, val lock: Script, val type: Script?)
@Serializable
data class DaoRecoveryHeader(
    val version: String,
    @SerialName("compact_target") val compactTarget: String,
    val timestamp: String, val number: String, val epoch: String,
    @SerialName("parent_hash") val parentHash: String,
    @SerialName("transactions_root") val transactionsRoot: String,
    @SerialName("proposals_hash") val proposalsHash: String,
    @SerialName("extra_hash") val extraHash: String,
    val dao: String, val nonce: String, val hash: String,
)
@Serializable
data class DaoRecoveryReceipt(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("proof_kind") val proofKind: String,
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("attempt_id") val attemptId: String,
    val identity: DaoRecoveryIdentity, val revision: String,
    @SerialName("chain_anchor") val chainAnchor: DaoRecoveryHeader,
    @SerialName("transaction_hash") val transactionHash: String,
    @SerialName("raw_transaction") val rawTransaction: DaoRecoveryRawTransaction,
    val header: DaoRecoveryHeader,
    @SerialName("transaction_index") val transactionIndex: Int?,
)
enum class DaoRecoveryStatus { QUEUED, FETCHING, VERIFIED, UNAVAILABLE, EXPIRED, CANCELLED }
data class DaoRecoveryPoll(val status: DaoRecoveryStatus, val receipt: DaoRecoveryReceipt?)
data class DaoRecoveryValidation(val valid: Boolean, val receipt: DaoRecoveryReceipt)
data class DaoRecoveryCancellation(val requestId: String)
sealed class DaoRecoveryResult<out T> {
    data class Success<T>(val value: T) : DaoRecoveryResult<T>()
    data class Failure(val code: String) : DaoRecoveryResult<Nothing>()
}
private class RecoveryProtocolFailure(val reason: String) : IllegalArgumentException()

/**
 * Typed decoder for the dedicated bridge. A successful poll is a snapshot, not an
 * action grant: validate again at every adoption/publication/action boundary.
 * All malformed, foreign, null and unknown responses remain failures.
 */
class DaoRecoveryClient(private val api: LightClientApi, genesisHash: String) {
    private val genesis = genesisHash.lowercase().let { if (it.startsWith("0x")) it else "0x$it" }
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

    fun request(transactionHash: String): DaoRecoveryResult<DaoRecoveryTicket> = protect {
        val hash = normalizeHash(transactionHash)
        val result = response(api.requestDaoRecovery(hash), "request")
        val request = json.decodeFromJsonElement<RequestResult>(result)
        requireProtocol(token(request.requestId, "q") && request.transactionHash == hash)
        checkIdentity(request.identity)
        DaoRecoveryTicket(request.requestId, hash, request.identity)
    }
    fun poll(ticket: DaoRecoveryTicket): DaoRecoveryResult<DaoRecoveryPoll> = protect {
        checkTicket(ticket)
        val result = json.decodeFromJsonElement<PollResult>(response(api.pollDaoRecovery(ticket.requestId), "poll"))
        requireProtocol(result.requestId == ticket.requestId)
        val status = DaoRecoveryStatus.entries.firstOrNull { it.name.lowercase() == result.status }
            ?: throw RecoveryProtocolFailure("invalid_response")
        if (status == DaoRecoveryStatus.VERIFIED) {
            checkReceipt(result.receipt ?: throw RecoveryProtocolFailure("invalid_response"), ticket)
        } else requireProtocol(result.receipt == null)
        DaoRecoveryPoll(status, result.receipt)
    }
    fun validate(ticket: DaoRecoveryTicket, receiptId: String): DaoRecoveryResult<DaoRecoveryValidation> = protect {
        checkTicket(ticket)
        requireProtocol(token(receiptId, "r"))
        val result = json.decodeFromJsonElement<ValidationResult>(response(api.validateDaoRecovery(ticket.requestId, receiptId), "validate"))
        requireProtocol(result.requestId == ticket.requestId && result.valid)
        checkReceipt(result.receipt, ticket)
        requireProtocol(result.receipt.receiptId == receiptId)
        DaoRecoveryValidation(true, result.receipt)
    }
    fun cancel(ticket: DaoRecoveryTicket): DaoRecoveryResult<DaoRecoveryCancellation> = protect {
        checkTicket(ticket)
        val result = json.decodeFromJsonElement<CancellationResult>(response(api.cancelDaoRecovery(ticket.requestId), "cancel"))
        requireProtocol(result.requestId == ticket.requestId && result.status == "cancelled")
        DaoRecoveryCancellation(result.requestId)
    }
    private fun response(raw: String?, operation: String): JsonObject {
        if (raw == null) throw RecoveryProtocolFailure("transport_unavailable")
        rejectAmbiguousJson(raw)
        val envelope = json.parseToJsonElement(raw).jsonObject
        requireProtocol(envelope.keys == setOf("schema_version", "operation", "result") ||
            envelope.keys == setOf("schema_version", "operation", "error"))
        requireProtocol(envelope["schema_version"] == JsonPrimitive(1) && envelope["operation"] == JsonPrimitive(operation))
        envelope["error"]?.let {
            val error = json.decodeFromJsonElement<ErrorResult>(it)
            val known = setOf("unavailable", "capacity", "unknown_request", "expired", "sequence_exhausted", "storage", "invalid_receipt", "invalid_hash", "invalid_token")
            throw RecoveryProtocolFailure(if (error.code in known) error.code else "invalid_response")
        }
        val result = envelope.getValue("result").jsonObject
        // Tree decoding can coerce quoted Boolean/Int values. Check authority
        // scalars against exact JSON primitives before decoding typed models.
        if (operation == "validate") requireProtocol(result["valid"] == JsonPrimitive(true))
        if (operation == "poll" || operation == "validate") {
            val receipt = result["receipt"]
            if (receipt != null && receipt != JsonNull) {
                requireProtocol(receipt.jsonObject["schema_version"] == JsonPrimitive(1))
            }
        }
        return result
    }
    // Json's object decoder overwrites duplicate keys. Reject them, including
    // escaped spellings of the same key, before any authority fields are read.
    private fun rejectAmbiguousJson(raw: String) {
        requireProtocol(raw.length <= 4 * 1024 * 1024)
        val objects = mutableListOf<MutableSet<String>>()
        var depth = 0
        var index = 0
        while (index < raw.length) {
            when (raw[index]) {
                '{' -> { objects.add(mutableSetOf()); depth++; requireProtocol(depth <= 64) }
                '[' -> { depth++; requireProtocol(depth <= 64) }
                '}' -> { requireProtocol(objects.isNotEmpty()); objects.removeAt(objects.lastIndex); depth-- }
                ']' -> depth--
                '"' -> {
                    val start = index++
                    while (index < raw.length && raw[index] != '"') {
                        if (raw[index] == '\\') index++
                        index++
                    }
                    requireProtocol(index < raw.length)
                    val end = index + 1
                    var next = end
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (next < raw.length && raw[next] == ':') {
                        val key = json.decodeFromString<String>(raw.substring(start, end))
                        requireProtocol(objects.isNotEmpty() && objects.last().add(key))
                    }
                }
            }
            index++
        }
    }
    private fun checkIdentity(identity: DaoRecoveryIdentity) {
        requireProtocol(identity.genesisHash == genesis && hash(identity.genesisHash) && identity.storeGeneration.isNotBlank() &&
            identity.storeGeneration.length <= 512 && bytes(identity.runNonce, 16))
    }
    private fun checkTicket(ticket: DaoRecoveryTicket) {
        checkIdentity(ticket.identity)
        requireProtocol(hash(ticket.transactionHash) && token(ticket.requestId, "q"))
    }
    private fun checkReceipt(receipt: DaoRecoveryReceipt, ticket: DaoRecoveryTicket) {
        requireProtocol(receipt.schemaVersion == 1 && receipt.proofKind == "raw_transaction_membership" &&
            receipt.requestId == ticket.requestId && receipt.identity == ticket.identity &&
            receipt.transactionHash == ticket.transactionHash && token(receipt.receiptId,"r") && token(receipt.attemptId,"a") &&
            receipt.revision.matches(Regex("0|[1-9][0-9]*")) && receipt.revision.toULongOrNull() != null && receipt.transactionIndex == null)
        checkHeader(receipt.header); checkHeader(receipt.chainAnchor)
        val tx = receipt.rawTransaction
        requireProtocol(quantity(tx.version,8) && tx.outputs.size == tx.outputsData.size)
        tx.headerDeps.forEach { requireProtocol(hash(it)) }
        tx.cellDeps.forEach { requireProtocol(outPoint(it.outPoint) && it.depType in setOf("code","dep_group")) }
        tx.inputs.forEach { requireProtocol(quantity(it.since,16) && outPoint(it.previousOutput)) }
        tx.outputs.forEach { requireProtocol(quantity(it.capacity,16) && script(it.lock) && (it.type == null || script(it.type))) }
        tx.outputsData.forEach { requireProtocol(bytes(it)) }
    }
    private fun checkHeader(header: DaoRecoveryHeader) {
        requireProtocol(quantity(header.version,8) && quantity(header.compactTarget,8) && quantity(header.timestamp,16) &&
            quantity(header.number,16) && quantity(header.epoch,16) && quantity(header.nonce,32) &&
            listOf(header.parentHash,header.transactionsRoot,header.proposalsHash,header.extraHash,header.dao,header.hash).all(::hash))
    }
    private fun outPoint(outPoint: OutPoint): Boolean = hash(outPoint.txHash) && quantity(outPoint.index,8)
    private fun script(script: Script): Boolean = hash(script.codeHash) && script.hashType in setOf("data","type","data1","data2") && bytes(script.args)
    private inline fun <T> protect(block: () -> T): DaoRecoveryResult<T> = try {
        DaoRecoveryResult.Success(block())
    } catch (error: RecoveryProtocolFailure) { DaoRecoveryResult.Failure(error.reason)
    } catch (_: Exception) { DaoRecoveryResult.Failure("invalid_response") }

    @Serializable private data class RequestResult(@SerialName("request_id") val requestId: String, @SerialName("transaction_hash") val transactionHash: String, val identity: DaoRecoveryIdentity)
    @Serializable private data class PollResult(@SerialName("request_id") val requestId: String, val status: String, val receipt: DaoRecoveryReceipt?)
    @Serializable private data class ValidationResult(@SerialName("request_id") val requestId: String, val valid: Boolean, val receipt: DaoRecoveryReceipt)
    @Serializable private data class CancellationResult(@SerialName("request_id") val requestId: String, val status: String)
    @Serializable private data class ErrorResult(val code: String)
    companion object {
        private fun requireProtocol(condition: Boolean) { if (!condition) throw RecoveryProtocolFailure("invalid_response") }
        private fun normalizeHash(value: String): String {
            val normalized = if (value.startsWith("0x")) value.lowercase() else "0x${value.lowercase()}"
            requireProtocol(hash(normalized)); return normalized
        }
        private fun hash(value: String): Boolean = bytes(value,32)
        private fun bytes(value: String, size: Int? = null): Boolean = value.startsWith("0x") && value.length % 2 == 0 &&
            (size == null || value.length == 2 + size * 2) && value.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
        private fun quantity(value: String, maxDigits: Int): Boolean = value.matches(Regex("0x(0|[1-9a-f][0-9a-f]{0,${maxDigits-1}})"))
        private fun token(value: String, kind: String): Boolean = value.length <= 2048 && value.startsWith("dr1.$kind.") &&
            value.removePrefix("dr1.$kind.").let { it.isNotEmpty() && it.length % 2 == 0 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}
