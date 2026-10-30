package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.data.gateway.models.CellDep
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.transaction.PreparedTransfer
import com.rjnr.pocketnode.data.transaction.RecipientOutput
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.wallet.AddressUtils

/** A payment approved by the user. Copies own the ordered recipients across suspension points. */
class ReviewedTransfer @Throws(Throwable::class) constructor(
    val walletId: String,
    val fromAddress: String,
    val network: NetworkType,
    recipients: List<RecipientOutput>,
    val feeShannons: Long,
    val generation: Long,
    val isBulk: Boolean = false,
) {
    val sourceScript: Script = requireNotNull(AddressUtils.parseAddress(fromAddress)) { "Invalid sender address" }
    private val outputs = recipients.map { it.copy() }
    private val scripts = outputs.map { DestinationPolicy.requireRecipient(it, network) }
    /** One immutable classification per ordered recipient, captured with the approved outputs. */
    private val assessments = outputs.map { DestinationPolicy.assess(it.address, network) }
    val destinationAssessments: List<DestinationAssessment> get() = assessments.toList()
    val recipients: List<RecipientOutput> get() = outputs.toList()
    val amountShannons: Long = outputs.fold(0L) { total, output ->
        require(output.amountShannons >= TransactionBuilder.MIN_CELL_CAPACITY)
        require(total <= Long.MAX_VALUE - output.amountShannons)
        total + output.amountShannons
    }
    val totalShannons: Long

    init {
        require(outputs.isNotEmpty() && feeShannons >= 0 && amountShannons <= Long.MAX_VALUE - feeShannons)
        totalShannons = amountShannons + feeShannons
    }

    /** Runs under the send mutex, on the exact unsigned bytes immediately before signing. */
    fun validatePrepared(prepared: PreparedTransfer) {
        val tx = prepared.transaction
        fun requireReviewed(ok: Boolean) = require(ok) { "Transaction changed. Review the payment again." }
        requireReviewed(tx.cellOutputs.size in outputs.size..outputs.size + 1)
        requireReviewed(tx.outputsData.size == tx.cellOutputs.size && tx.outputsData.all { it == "0x" })
        outputs.forEachIndexed { index, expected ->
            val actual = tx.cellOutputs[index]
            requireReviewed(actual.lock == scripts[index] && actual.type == null &&
                actual.capacity.removePrefix("0x").toLongOrNull(16) == expected.amountShannons)
        }
        tx.cellOutputs.drop(outputs.size).forEach { change ->
            requireReviewed(change.lock == sourceScript && change.type == null &&
                (change.capacity.removePrefix("0x").toLongOrNull(16) ?: 0) >= TransactionBuilder.MIN_CELL_CAPACITY)
        }
        requireReviewed(tx.cellInputs.isNotEmpty() && tx.cellInputs.map { it.previousOutput }.distinct().size == tx.cellInputs.size)
        var inputTotal = 0L
        tx.cellInputs.forEach { input ->
            val cell = prepared.selectedCells.singleOrNull { it.outPoint == input.previousOutput }
            requireReviewed(cell != null && cell.isPlainCapacity(sourceScript) && input.since == "0x0")
            val amount = cell!!.capacity.removePrefix("0x").toLongOrNull(16) ?: error("Invalid input capacity")
            requireReviewed(amount > 0 && inputTotal <= Long.MAX_VALUE - amount)
            inputTotal += amount
        }
        var outputTotal = 0L
        tx.cellOutputs.forEach { output ->
            val amount = output.capacity.removePrefix("0x").toLongOrNull(16) ?: error("Invalid output capacity")
            requireReviewed(amount > 0 && outputTotal <= Long.MAX_VALUE - amount)
            outputTotal += amount
        }
        requireReviewed(inputTotal >= outputTotal && inputTotal - outputTotal == feeShannons)
        requireReviewed(tx.cellDeps == listOf(if (network == NetworkType.MAINNET) CellDep.SECP256K1_MAINNET else CellDep.SECP256K1_TESTNET))
        requireReviewed(tx.version == "0x0" && tx.headerDeps.isEmpty())
    }
}
