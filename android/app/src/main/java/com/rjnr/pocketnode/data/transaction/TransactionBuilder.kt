package com.rjnr.pocketnode.data.transaction

import android.util.Log
import com.rjnr.pocketnode.data.gateway.DaoConstants
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.util.toHex
import org.nervos.ckb.crypto.Blake2b
import org.nervos.ckb.crypto.secp256k1.ECKeyPair
import org.nervos.ckb.crypto.secp256k1.Sign
import org.nervos.ckb.utils.Numeric
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton
import com.rjnr.pocketnode.util.redactAddress

/** #382 Tier 3: one sweep input — a live untyped cell plus the lock args that identify its signing group. */
data class SweepInput(
    val outPoint: OutPoint,
    val capacityShannons: Long,
    val lockArgs: String,
)

/** #382 Tier 3: an unsigned sweep plus the numbers the confirm dialog shows. */
data class SweepPlan(
    val transaction: Transaction,
    val totalShannons: Long,
    val feeShannons: Long,
    /** Parallel to the transaction's inputs — feeds [TransactionBuilder.signSweep]. */
    val inputLockArgs: List<String>,
)

@Singleton
class TransactionBuilder @Inject constructor(
    private val networkValidator: NetworkValidator
) {

    companion object {
        private const val TAG = "TransactionBuilder"
        const val SECP256K1_CODE_HASH =
            "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8"
        const val MIN_CELL_CAPACITY = 61_00000000L
        const val DEFAULT_FEE = 100_000L // generous upper bound for cell selection
        const val FEE_RATE = 1000L       // shannons per KB (standard minimum relay fee)
        const val MIN_FEE = 1_000L       // floor: 0.00001 CKB
        private const val MAX_TX_SIZE = 596 * 1024 // 596KB CKB protocol limit

        // SECP256K1 cell deps for different networks
        // Testnet (Pudge)
        private const val TESTNET_SECP256K1_TX_HASH =
            "0xf8de3bb47d055cdf460d93a2a6e1b05f7432f9777c8c474abf4eec1d4aee5d37"
        // Mainnet (Mirana)
        private const val MAINNET_SECP256K1_TX_HASH =
            "0x71a7ba8fc96349fea0ed3a5c47992e3b4084b031a42264a018e0072e8172e46c"
    }

    /**
     * Estimate the fee for a simple secp256k1 transfer based on input/output count.
     * Uses the standard CKB fee rate (1000 shannons/KB).
     */
    fun estimateTransferFee(inputCount: Int, outputCount: Int): Long =
        estimateFeeWithGroups(inputCount, outputCount, groupCount = 1)

    /**
     * #382 Tier 3: fee for a sweep spending inputs locked by [groupCount]
     * DIFFERENT secp256k1 scripts. Each lock group's first witness carries a
     * full WitnessArgs (85 bytes); the transfer formula assumes exactly one
     * group and undercounts a sweep by 85 bytes per extra group — enough for
     * a "low fee rate" rejection at the node.
     */
    fun estimateSweepFee(inputCount: Int, groupCount: Int): Long =
        estimateFeeWithGroups(inputCount, outputCount = 1, groupCount = groupCount)

    private fun estimateFeeWithGroups(inputCount: Int, outputCount: Int, groupCount: Int): Long {
        // Molecule accounting, sized to never undershoot the node's measured
        // tx_size. The previous formula undercounted the witnesses dynvec
        // (missing the 4-byte offset per item) and per-output script tables;
        // its flat +100 buffer only absorbed ~25 inputs. A ~100-input send
        // from a fragmented mining wallet serialized to 5300 bytes while the
        // fee paid for 4964 — rejected "low fee rate" on every attempt
        // (Alex, Telegram 2026-07). Overpaying is thousandths of a cent;
        // undershooting bricks the send.
        //
        // raw = table hdr(28) + version(4) + cell_deps fixvec(4+37) +
        //       header_deps(4) = 77
        val rawOverhead = 77
        // inputs fixvec: 4 + n * (since 8 + outpoint 36)
        val inputsSize = 4 + inputCount * 44
        // outputs dynvec: 4 + n * (offset 4 + CellOutput 97)
        //   CellOutput = table hdr 16 + capacity 8 + secp lock script 73
        //   (script = table hdr 16 + codeHash 32 + hashType 1 + args 4+20)
        val outputsSize = 4 + outputCount * (4 + 97)
        // outputs_data dynvec: 4 + n * (offset 4 + empty Bytes 4)
        val outputsDataSize = 4 + outputCount * 8
        // witnesses dynvec: 4 + n*offset(4) + one full item (len 4 +
        // WitnessArgs 85) PER LOCK GROUP + each remaining "0x" item (len 4)
        val witnessSize = 4 + inputCount * 4 + groupCount * (4 + 85) +
            (inputCount - groupCount).coerceAtLeast(0) * 4
        // Transaction wrapper table hdr (12) + in-block serialization offset (4)
        val wrapperSize = 16
        // Margin: molecule drift and future script arg growth. Proportional to
        // input count so big txs keep headroom (2 bytes/input + 200 flat).
        val margin = 200 + inputCount * 2

        val estimatedBytes = rawOverhead + inputsSize + outputsSize +
            outputsDataSize + witnessSize + wrapperSize + margin
        return calculateFeeForSize(estimatedBytes)
    }

    /**
     * Max sendable amount for a "send everything" transfer: the sum of all
     * spendable cell capacities minus the fee for a transaction consuming
     * EVERY cell as an input with a single output and no change. Pure integer
     * math throughout — Double loses shannon precision above ~90M CKB (#321),
     * and a 1-input fee assumption underestimates the fee on fragmented
     * wallets, making the subsequent Max send fail with insufficient funds.
     *
     * Cells with malformed capacity hex are skipped from both the sum and the
     * input count: buildTransfer could not spend them either.
     */
    fun calculateMaxSendable(cells: List<Cell>): Long {
        val capacities = cells.mapNotNull { it.capacity.removePrefix("0x").toLongOrNull(16) }
        if (capacities.isEmpty()) return 0L
        val total = capacities.sum()
        val fee = estimateTransferFee(inputCount = capacities.size, outputCount = 1)
        return (total - fee).coerceAtLeast(0L)
    }

    // ========================================
    // #382 Tier 3: gap-limit sweep
    // ========================================

    /**
     * #382 Tier 3: build the unsigned all-in sweep — every input cell into
     * ONE output at [toScript], fee deducted, NO change output (there is no
     * change: the whole point is consolidation). Refused when the remainder
     * would fall below the 61 CKB minimum cell capacity.
     */
    fun buildSweep(
        inputs: List<SweepInput>,
        toScript: Script,
        network: NetworkType,
    ): Result<SweepPlan> = runCatching {
        require(inputs.isNotEmpty()) { "Nothing to sweep" }

        val total = inputs.sumOf { it.capacityShannons }
        val groupCount = inputs.map { it.lockArgs }.distinct().size
        val fee = estimateSweepFee(inputs.size, groupCount)
        val outputCapacity = total - fee
        if (outputCapacity < MIN_CELL_CAPACITY) {
            throw IllegalStateException(
                "Sweep refused: the funds found (${total / 100_000_000.0} CKB) minus the " +
                    "fee would be below the ${MIN_CELL_CAPACITY / 100_000_000} CKB minimum cell capacity."
            )
        }

        val secpTxHash = if (network == NetworkType.MAINNET) MAINNET_SECP256K1_TX_HASH else TESTNET_SECP256K1_TX_HASH
        val tx = Transaction(
            version = "0x0",
            cellDeps = listOf(
                CellDep(outPoint = OutPoint(txHash = secpTxHash, index = "0x0"), depType = "dep_group")
            ),
            headerDeps = emptyList(),
            cellInputs = inputs.map { CellInput(previousOutput = it.outPoint, since = "0x0") },
            cellOutputs = listOf(
                CellOutput(capacity = "0x${outputCapacity.toString(16)}", lock = toScript, type = null)
            ),
            outputsData = listOf("0x"),
            witnesses = inputs.map { "0x" },
        )

        val estimatedSize = estimateTransactionSize(tx)
        if (estimatedSize > MAX_TX_SIZE) {
            throw IllegalStateException("Sweep too large: $estimatedSize bytes (max $MAX_TX_SIZE)")
        }

        SweepPlan(
            transaction = tx,
            totalShannons = total,
            feeShannons = fee,
            inputLockArgs = inputs.map { it.lockArgs },
        )
    }

    /**
     * #382 Tier 3: sign a sweep whose inputs span MULTIPLE secp256k1 lock
     * groups. Per CKB consensus, inputs are grouped by lock script; each
     * group's FIRST witness carries that group's signature over
     * blake2b(tx_hash || len+WitnessArgs-placeholder || len(+bytes) of each
     * OTHER witness in the SAME group). Other members stay "0x". Collapsing
     * to one group reproduces [signTransaction] byte-for-byte (see
     * TransactionBuilderSweepTest's equivalence anchor).
     *
     * Keys are the caller's responsibility to wipe; this function does not
     * retain them.
     */
    fun signSweep(
        tx: Transaction,
        inputLockArgs: List<String>,
        keysByLockArgs: Map<String, ByteArray>,
    ): Result<Transaction> = runCatching {
        require(inputLockArgs.size == tx.cellInputs.size) {
            "inputLockArgs (${inputLockArgs.size}) must parallel inputs (${tx.cellInputs.size})"
        }
        val groups: Map<String, List<Int>> = inputLockArgs.withIndex()
            .groupBy({ it.value }, { it.index })
        groups.keys.forEach { args ->
            require(keysByLockArgs.containsKey(args)) { "No key provided for lock group $args" }
        }

        val txHash = blake2bHash(serializeRawTransaction(tx))
        val emptyWitnessArgs = serializeWitnessArgs(ByteArray(65), null, null)
        val witnesses = MutableList(tx.cellInputs.size) { "0x" }

        groups.forEach { (args, memberIndices) ->
            val blake2b = Blake2b()
            blake2b.update(txHash)
            blake2b.update(littleEndianLong(emptyWitnessArgs.size.toLong()))
            blake2b.update(emptyWitnessArgs)
            // Other members of THIS group contribute their (empty) witnesses.
            repeat(memberIndices.size - 1) {
                blake2b.update(littleEndianLong(0L))
            }
            val message = blake2b.doFinal()

            val keyPair = ECKeyPair.create(BigInteger(1, keysByLockArgs.getValue(args)))
            val signature = Sign.signMessage(message, keyPair).signature
            val signedWitness = serializeWitnessArgs(signature, null, null)
            witnesses[memberIndices.first()] =
                "0x" + signedWitness.joinToString("") { "%02x".format(it) }
        }

        tx.copy(witnesses = witnesses)
    }

    /**
     * Calculate fee from serialized transaction size using the standard fee rate.
     * fee = ceil(size_bytes * fee_rate / 1000), with a minimum floor.
     */
    private fun calculateFeeForSize(sizeBytes: Int): Long {
        val fee = (sizeBytes.toLong() * FEE_RATE + 999) / 1000
        return fee.coerceAtLeast(MIN_FEE)
    }

    fun buildTransfer(
        fromAddress: String,
        toAddress: String,
        amountShannons: Long,
        availableCells: List<Cell>,
        privateKey: ByteArray,
        network: NetworkType
    ): Transaction {
        Log.d(TAG, "🔨 Building transfer transaction")
        // Sender + recipient + amount form a payment record — redact the
        // addresses and drop the amount from debug logcat (#321).
        Log.d(TAG, "  From: ${fromAddress.redactAddress()}")
        Log.d(TAG, "  To: ${toAddress.redactAddress()}")

        // Validate recipient amount meets minimum cell capacity
        if (amountShannons < MIN_CELL_CAPACITY) {
            throw IllegalArgumentException(
                "Transfer amount must be at least ${MIN_CELL_CAPACITY / 100_000_000} CKB (minimum cell capacity)"
            )
        }

        val senderScript = AddressUtils.parseAddress(fromAddress)
            ?: throw IllegalArgumentException("Invalid sender address")
        val recipientScript = AddressUtils.parseAddress(toAddress)
            ?: throw IllegalArgumentException("Invalid recipient address")

        // Validate addresses match the app-level network config
        networkValidator.validateTransferAddresses(fromAddress, toAddress, network)
            .getOrThrow()

        val isMainnet = network == NetworkType.MAINNET
        val secp256k1TxHash = if (isMainnet) MAINNET_SECP256K1_TX_HASH else TESTNET_SECP256K1_TX_HASH
        Log.d(TAG, "  Network: ${network.name}")
        Log.d(TAG, "  Using SECP256K1 cell dep: $secp256k1TxHash")

        // Select cells with generous fee to ensure we gather enough inputs
        val (selectedCells, totalInput) = selectCells(availableCells, amountShannons + DEFAULT_FEE)

        Log.d(TAG, "  Selected ${selectedCells.size} cells with total: $totalInput shannons")

        if (selectedCells.isEmpty()) {
            throw IllegalStateException("No cells available")
        }

        if (totalInput < amountShannons + MIN_FEE) {
            throw IllegalStateException("Insufficient balance: have $totalInput, need at least ${amountShannons + MIN_FEE}")
        }

        // Compute dynamic fee based on actual tx structure
        val changeWithDefaultFee = totalInput - amountShannons - DEFAULT_FEE
        val initialOutputCount = if (changeWithDefaultFee >= MIN_CELL_CAPACITY) 2 else 1
        var dynamicFee = estimateTransferFee(selectedCells.size, initialOutputCount)

        // Recompute change with the (smaller) dynamic fee
        var change = totalInput - amountShannons - dynamicFee
        val finalOutputCount = if (change >= MIN_CELL_CAPACITY) 2 else 1

        // Re-estimate if output count changed (edge case: lower fee creates a viable change output)
        if (finalOutputCount != initialOutputCount) {
            dynamicFee = estimateTransferFee(selectedCells.size, finalOutputCount)
            change = totalInput - amountShannons - dynamicFee
        }

        Log.d(TAG, "  Dynamic fee: $dynamicFee shannons (${dynamicFee / 100_000_000.0} CKB)")

        val inputs = selectedCells.map { cell ->
            CellInput(
                previousOutput = OutPoint(
                    txHash = cell.outPoint.txHash,
                    index = cell.outPoint.index
                ),
                since = "0x0"
            )
        }

        val outputs = mutableListOf<CellOutput>()
        val outputsData = mutableListOf<String>()

        // Output to recipient
        outputs.add(
            CellOutput(
                capacity = "0x${amountShannons.toString(16)}",
                lock = recipientScript,
                type = null
            )
        )
        outputsData.add("0x")

        // Change output back to sender (using dynamic fee).
        //
        // Three cases. CKB has no "send change to fee" idiom — capacity that
        // isn't in an output is silently consumed as transaction fee, so any
        // non-zero change below MIN_CELL_CAPACITY would be burned (#287).
        when {
            change == 0L -> {
                // Exact fit: input total = amount + fee. No change output needed.
                Log.d(TAG, "  No change output (exact fit; total inputs = amount + fee)")
            }
            change >= MIN_CELL_CAPACITY -> {
                outputs.add(
                    CellOutput(
                        capacity = "0x${change.toString(16)}",
                        lock = senderScript,
                        type = null
                    )
                )
                outputsData.add("0x")
                Log.d(TAG, "  Change output: $change shannons")
            }
            else -> {
                // 0 < change < MIN_CELL_CAPACITY: emitting this as a change
                // cell would violate the protocol minimum; omitting it would
                // silently burn $change shannons (up to ~61 CKB) as fee.
                // Refuse with a clear, actionable error rather than the prior
                // silent burn. Recovery options for the user: send a slightly
                // different amount that allows for a valid change output, OR
                // send the full balance minus fee (which yields an exact fit
                // with no change output at all).
                val changeCkbStr = java.math.BigDecimal(change)
                    .divide(java.math.BigDecimal(100_000_000))
                    .stripTrailingZeros()
                    .toPlainString()
                val minCkb = MIN_CELL_CAPACITY / 100_000_000
                Log.w(TAG, "  Dust change refused: $change shannons ($changeCkbStr CKB) below min $MIN_CELL_CAPACITY")
                throw IllegalStateException(
                    "Dust change refused: this send would leave $changeCkbStr CKB of change " +
                        "below the $minCkb CKB minimum cell capacity, which would be silently absorbed as " +
                        "transaction fee. Try sending a slightly different amount or sending your full balance " +
                        "minus the fee."
                )
            }
        }

        // Use network-appropriate cell dependency
        val cellDeps = listOf(
            CellDep(
                outPoint = OutPoint(
                    txHash = secp256k1TxHash,
                    index = "0x0"
                ),
                depType = "dep_group"
            )
        )

        val unsignedTx = Transaction(
            version = "0x0",
            cellDeps = cellDeps,
            headerDeps = emptyList(),
            cellInputs = inputs,
            cellOutputs = outputs,
            outputsData = outputsData,
            witnesses = inputs.map { "0x" }
        )

        // Validate transaction size before signing
        val estimatedSize = estimateTransactionSize(unsignedTx)
        if (estimatedSize > MAX_TX_SIZE) {
            throw IllegalStateException(
                "Transaction too large: $estimatedSize bytes (max: $MAX_TX_SIZE). " +
                        "Try sending a smaller amount or consolidate cells first."
            )
        }

        Log.d(TAG, "  Signing transaction with ${inputs.size} inputs, ${outputs.size} outputs (est. ${estimatedSize} bytes)")
        return signTransaction(unsignedTx, privateKey, selectedCells.size)
    }

    // ========================================
    // DAO Transaction Builders
    // ========================================

    fun buildDaoDeposit(
        amountShannons: Long,
        availableCells: List<Cell>,
        senderScript: Script,
        privateKey: ByteArray,
        network: NetworkType
    ): Transaction {
        require(amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
            "Minimum DAO deposit is ${DaoConstants.MIN_DEPOSIT_SHANNONS / 100_000_000} CKB"
        }

        val (selectedCells, totalInput) = selectCells(availableCells, amountShannons + DEFAULT_FEE)
        if (totalInput < amountShannons + DEFAULT_FEE) {
            throw Exception("Insufficient balance. Need ${amountShannons + DEFAULT_FEE}, have $totalInput")
        }

        val fee = estimateTransferFee(selectedCells.size, 2) // deposit + change
        val change = totalInput - amountShannons - fee

        val inputs = selectedCells.map { CellInput(previousOutput = it.outPoint) }

        val outputs = mutableListOf(
            CellOutput(
                capacity = "0x${amountShannons.toString(16)}",
                lock = senderScript,
                type = DaoConstants.DAO_TYPE_SCRIPT
            )
        )
        val outputsData = mutableListOf(
            "0x" + DaoConstants.DAO_DEPOSIT_DATA.joinToString("") { "%02x".format(it) }
        )

        // Same dust-change refusal as buildTransfer (#287). DAO deposit must
        // not silently burn change capacity below 61 CKB into the fee.
        when {
            change == 0L -> Unit
            change >= MIN_CELL_CAPACITY -> {
                outputs.add(CellOutput(capacity = "0x${change.toString(16)}", lock = senderScript))
                outputsData.add("0x")
            }
            else -> {
                val changeCkbStr = java.math.BigDecimal(change)
                    .divide(java.math.BigDecimal(100_000_000))
                    .stripTrailingZeros()
                    .toPlainString()
                throw IllegalStateException(
                    "Dust change refused: this DAO deposit would leave $changeCkbStr CKB " +
                        "below the ${MIN_CELL_CAPACITY / 100_000_000} CKB minimum, which would be silently absorbed " +
                        "as transaction fee. Adjust the deposit amount and retry."
                )
            }
        }

        val secp256k1Dep = when (network) {
            NetworkType.TESTNET -> CellDep.SECP256K1_TESTNET
            NetworkType.MAINNET -> CellDep.SECP256K1_MAINNET
        }

        val unsignedTx = Transaction(
            cellDeps = listOf(secp256k1Dep, DaoConstants.daoCellDep(network)),
            headerDeps = emptyList(),
            cellInputs = inputs,
            cellOutputs = outputs,
            outputsData = outputsData,
            witnesses = inputs.map { "0x" }
        )

        return signTransaction(unsignedTx, privateKey, inputs.size)
    }

    fun buildDaoWithdraw(
        depositCell: Cell,
        depositBlockNumber: Long,
        depositBlockHash: String,
        senderScript: Script,
        privateKey: ByteArray,
        network: NetworkType,
        availableCells: List<Cell>
    ): Transaction {
        // Phase 1 (deposit → withdrawing) preserves the deposit cell's capacity
        // exactly. The fee must come from a separate normal cell (CKB RFC 0023).
        // The previous implementation used the deposit cell as the only input,
        // making `inputs == outputs`, so the network rejected the tx and the
        // JNI bridge returned null ("send failed - native returned null", #119).
        val (feeCells, feeTotal) = selectCells(availableCells, DEFAULT_FEE + MIN_CELL_CAPACITY)
        if (feeTotal < DEFAULT_FEE) {
            throw Exception(
                "Insufficient balance to cover withdraw fee. Need ${DEFAULT_FEE} shannons, have $feeTotal"
            )
        }

        // Output mirrors the deposit cell but with block number as data
        val blockNumberBytes = ByteArray(8)
        var num = depositBlockNumber
        for (i in 0 until 8) {
            blockNumberBytes[i] = (num and 0xFF).toByte()
            num = num shr 8
        }
        val blockNumberHex = "0x" + blockNumberBytes.joinToString("") { "%02x".format(it) }

        val inputs = listOf(CellInput(previousOutput = depositCell.outPoint)) +
            feeCells.map { CellInput(previousOutput = it.outPoint) }

        val withdrawingOutput = CellOutput(
            capacity = depositCell.capacity,
            lock = senderScript,
            type = DaoConstants.DAO_TYPE_SCRIPT
        )

        // Change output (if any). feeTotal - DEFAULT_FEE goes back to the user.
        // Refuse dust change rather than silently absorbing it as fee (#287).
        val change = feeTotal - DEFAULT_FEE
        val outputs = mutableListOf(withdrawingOutput)
        val outputsData = mutableListOf(blockNumberHex)
        when {
            change == 0L -> Unit
            change >= MIN_CELL_CAPACITY -> {
                outputs.add(CellOutput(capacity = "0x${change.toString(16)}", lock = senderScript))
                outputsData.add("0x")
            }
            else -> {
                val changeCkbStr = java.math.BigDecimal(change)
                    .divide(java.math.BigDecimal(100_000_000))
                    .stripTrailingZeros()
                    .toPlainString()
                throw IllegalStateException(
                    "Dust change refused: this DAO withdraw would leave $changeCkbStr CKB " +
                        "below the ${MIN_CELL_CAPACITY / 100_000_000} CKB minimum, which would be silently absorbed " +
                        "as transaction fee. Use a wallet cell with a slightly different capacity to cover the fee."
                )
            }
        }

        val secp256k1Dep = when (network) {
            NetworkType.TESTNET -> CellDep.SECP256K1_TESTNET
            NetworkType.MAINNET -> CellDep.SECP256K1_MAINNET
        }

        val unsignedTx = Transaction(
            cellDeps = listOf(secp256k1Dep, DaoConstants.daoCellDep(network)),
            headerDeps = listOf(depositBlockHash),
            cellInputs = inputs,
            cellOutputs = outputs,
            outputsData = outputsData,
            witnesses = inputs.map { "0x" }
        )

        return signTransaction(unsignedTx, privateKey, inputs.size)
    }

    fun buildDaoUnlock(
        withdrawingCell: Cell,
        maxWithdraw: Long,
        sinceValue: String,
        depositBlockHash: String,
        withdrawBlockHash: String,
        senderScript: Script,
        privateKey: ByteArray,
        network: NetworkType
    ): Transaction {
        val fee = DEFAULT_FEE

        val inputs = listOf(
            CellInput(
                since = sinceValue,
                previousOutput = withdrawingCell.outPoint
            )
        )

        val outputs = listOf(
            CellOutput(
                capacity = "0x${(maxWithdraw - fee).toString(16)}",
                lock = senderScript
                // No type script — funds return to normal cell
            )
        )

        val secp256k1Dep = when (network) {
            NetworkType.TESTNET -> CellDep.SECP256K1_TESTNET
            NetworkType.MAINNET -> CellDep.SECP256K1_MAINNET
        }

        // input_type in witness = 8-byte LE index of the deposit header in
        // header_deps (index 0). dao.c reads this from WitnessArgs.input_type
        // (MolReader_WitnessArgs_get_input_type); putting it in output_type
        // fails script verification (#315).
        val depositHeaderIndex = ByteArray(8) // 8 zero bytes = index 0

        val unsignedTx = Transaction(
            cellDeps = listOf(secp256k1Dep, DaoConstants.daoCellDep(network)),
            headerDeps = listOf(depositBlockHash, withdrawBlockHash),
            cellInputs = inputs,
            cellOutputs = outputs,
            outputsData = listOf("0x"),
            witnesses = listOf("0x")
        )

        return signTransaction(unsignedTx, privateKey, 1, witnessInputType = depositHeaderIndex)
    }

    private fun estimateTransactionSize(tx: Transaction): Int {
        return try {
            val rawSize = serializeRawTransaction(tx).size
            // witnesses dynvec: 4 + n*offset(4) + first (len 4 + WitnessArgs 85)
            // + each remaining "0x" (len 4); + Transaction wrapper hdr 12 +
            // in-block offset 4. Same accounting as estimateTransferFee — the
            // old n*69+100 form dropped the per-witness dynvec offset and
            // undershot past ~25 inputs (Alex, Telegram 2026-07).
            val n = tx.cellInputs.size
            val witnessOverhead = 4 + n * 4 + (4 + 85) + (n - 1).coerceAtLeast(0) * 4
            rawSize + witnessOverhead + 16 + 100
        } catch (e: Exception) {
            Log.w(TAG, "Transaction size estimation failed: ${e.message}")
            Int.MAX_VALUE // fail-safe: reject if we can't estimate size
        }
    }

    // internal for CellSelectionOrderTest — selection order is behavioural and
    // deserves direct coverage rather than only through buildTransfer.
    internal fun selectCells(
        cells: List<Cell>,
        requiredCapacity: Long,
        minChange: Long = MIN_CELL_CAPACITY,
    ): Pair<List<Cell>, Long> {
        val sortedCells = cells
            .filter { it.type == null }
            // Malformed capacity hex from the node: skip the cell rather than
            // abort the whole selection — same treatment as
            // calculateMaxSendable (#321).
            .mapNotNull { cell -> parseCapacity(cell.capacity)?.let { cell to it } }
            // Smallest-first (Neuron's cells.ts does the same). Largest-first
            // kept txs small but never spent dust, so a frequently-funded
            // wallet fragmented until a send needed 100+ inputs and hit the
            // fee/size edge (Alex, #395). Smallest-first sweeps dust into
            // every send, bounding fragmentation. Cost: slightly larger txs,
            // now priced correctly by the #395 fee fix.
            .sortedBy { (_, capacity) -> capacity }

        val selected = mutableListOf<Cell>()
        var total = 0L

        for ((cell, capacity) in sortedCells) {
            // Stop once the amount+fee is covered AND the leftover change is
            // viable — either exact (no change output) or at least a whole
            // min-capacity cell. Stopping the instant `total >= required`
            // (the old behaviour) could strand the remainder in the dust zone
            // (0, 61 CKB), which buildTransfer then refuses as dust change —
            // a send that would have succeeded. Smallest-first makes that
            // overshoot common, so selection has to keep going one more cell
            // to escape the zone. Mirrors Neuron's gatherInputs stop rule.
            val change = total - requiredCapacity
            if (total >= requiredCapacity && (change == 0L || change >= minChange)) break
            selected.add(cell)
            total += capacity
        }

        return Pair(selected, total)
    }

    private fun parseCapacity(hex: String): Long? {
        return hex.removePrefix("0x").toLongOrNull(16)
    }

    /**
     * Computes the canonical CKB transaction hash for [tx].
     *
     * Returned as `0x`-prefixed lowercase hex, matching the format the
     * Rust JNI bridge returns from `nativeSendTransaction`. Witnesses are
     * excluded from the hash by definition (CKB tx hash = blake2b of the
     * raw tx serialization, which `serializeRawTransaction` already produces
     * without witness bytes).
     *
     * Phase A (#115) relies on this matching the JNI-returned hash byte-for-byte;
     * the on-device verification gate runs separately.
     */
    fun computeTxHash(tx: Transaction): String {
        val rawTxBytes = serializeRawTransaction(tx)
        return "0x" + blake2bHash(rawTxBytes).toHex()
    }

    // internal (was private): TransactionBuilderSweepTest anchors signSweep's
    // single-group output byte-for-byte against this shipped signer.
    internal fun signTransaction(
        tx: Transaction,
        privateKey: ByteArray,
        inputCount: Int,
        witnessInputType: ByteArray? = null
    ): Transaction {
        // 1. Serialize the raw transaction and compute its hash
        val rawTxBytes = serializeRawTransaction(tx)
        val txHash = blake2bHash(rawTxBytes)

        // 2. Create empty witness args (65 zero bytes for signature placeholder)
        val emptyWitnessArgs = serializeWitnessArgs(ByteArray(65), witnessInputType, null)

        // 3. Build the signing message
        val blake2b = Blake2b()
        blake2b.update(txHash)
        blake2b.update(littleEndianLong(emptyWitnessArgs.size.toLong()))
        blake2b.update(emptyWitnessArgs)

        // For additional witnesses in the same lock group
        for (i in 1 until inputCount) {
            val emptyWitness = byteArrayOf()
            blake2b.update(littleEndianLong(emptyWitness.size.toLong()))
        }

        val message = blake2b.doFinal()

        // 4. Sign the message
        val keyPair = ECKeyPair.create(BigInteger(1, privateKey))
        val signatureData = Sign.signMessage(message, keyPair)
        val signature = signatureData.signature

        // 5. Create signed witness
        val signedWitnessArgs = serializeWitnessArgs(signature, witnessInputType, null)
        val signedWitnessHex = "0x" + signedWitnessArgs.joinToString("") { "%02x".format(it) }

        // 6. Build witnesses list
        val witnesses = mutableListOf(signedWitnessHex)
        for (i in 1 until inputCount) {
            witnesses.add("0x")
        }

        return tx.copy(witnesses = witnesses)
    }

    /**
     * Serialize raw transaction using molecule encoding.
     * RawTransaction = version (Uint32) + cell_deps (CellDepVec) + header_deps (Byte32Vec)
     *                + inputs (CellInputVec) + outputs (CellOutputVec) + outputs_data (BytesVec)
     */
    private fun serializeRawTransaction(tx: Transaction): ByteArray {
        val version = serializeUint32(tx.version.removePrefix("0x").toInt(16))
        val cellDeps = serializeCellDepVec(tx.cellDeps)
        val headerDeps = serializeByte32Vec(tx.headerDeps)
        val inputs = serializeCellInputVec(tx.cellInputs)
        val outputs = serializeCellOutputVec(tx.cellOutputs)
        val outputsData = serializeBytesVec(tx.outputsData)

        // RawTransaction is a table with 6 fields
        return serializeTable(listOf(version, cellDeps, headerDeps, inputs, outputs, outputsData))
    }

    private fun serializeTable(fields: List<ByteArray>): ByteArray {
        val headerSize = 4 + fields.size * 4 // full_size + offsets
        var currentOffset = headerSize

        val offsets = mutableListOf<Int>()
        for (field in fields) {
            offsets.add(currentOffset)
            currentOffset += field.size
        }

        val output = ByteArrayOutputStream()
        output.write(littleEndianInt(currentOffset)) // full size
        for (offset in offsets) {
            output.write(littleEndianInt(offset))
        }
        for (field in fields) {
            output.write(field)
        }
        return output.toByteArray()
    }

    private fun serializeFixVec(items: List<ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(littleEndianInt(items.size))
        for (item in items) {
            output.write(item)
        }
        return output.toByteArray()
    }

    private fun serializeDynVec(items: List<ByteArray>): ByteArray {
        if (items.isEmpty()) {
            return littleEndianInt(4) // just the header (full_size = 4)
        }

        val headerSize = 4 + items.size * 4 // full_size + offsets
        var currentOffset = headerSize

        val offsets = mutableListOf<Int>()
        for (item in items) {
            offsets.add(currentOffset)
            currentOffset += item.size
        }

        val output = ByteArrayOutputStream()
        output.write(littleEndianInt(currentOffset)) // full size
        for (offset in offsets) {
            output.write(littleEndianInt(offset))
        }
        for (item in items) {
            output.write(item)
        }
        return output.toByteArray()
    }

    private fun serializeUint32(value: Int): ByteArray {
        return littleEndianInt(value)
    }

    private fun serializeUint64(value: Long): ByteArray {
        return littleEndianLong(value)
    }

    private fun serializeByte32(hex: String): ByteArray {
        return Numeric.hexStringToByteArray(hex)
    }

    private fun serializeBytes(hex: String): ByteArray {
        val bytes = Numeric.hexStringToByteArray(hex)
        val output = ByteArrayOutputStream()
        output.write(littleEndianInt(bytes.size))
        output.write(bytes)
        return output.toByteArray()
    }

    private fun serializeByte32Vec(hashes: List<String>): ByteArray {
        val items = hashes.map { serializeByte32(it) }
        return serializeFixVec(items)
    }

    private fun serializeBytesVec(dataList: List<String>): ByteArray {
        val items = dataList.map { serializeBytes(it) }
        return serializeDynVec(items)
    }

    private fun serializeOutPoint(outPoint: OutPoint): ByteArray {
        val txHash = serializeByte32(outPoint.txHash)
        val index = serializeUint32(outPoint.index.removePrefix("0x").toInt(16))
        return txHash + index
    }

    private fun serializeCellDep(cellDep: CellDep): ByteArray {
        val outPoint = serializeOutPoint(cellDep.outPoint)
        val depType = when (cellDep.depType) {
            "code" -> byteArrayOf(0)
            "dep_group" -> byteArrayOf(1)
            else -> byteArrayOf(0)
        }
        return outPoint + depType
    }

    private fun serializeCellDepVec(cellDeps: List<CellDep>): ByteArray {
        val items = cellDeps.map { serializeCellDep(it) }
        return serializeFixVec(items)
    }

    private fun serializeCellInput(input: CellInput): ByteArray {
        val since = serializeUint64(
            input.since.removePrefix("0x").toLongOrNull(16)
                ?: throw IllegalArgumentException("Malformed input since '${input.since}' in transaction")
        )
        val previousOutput = serializeOutPoint(input.previousOutput)
        return since + previousOutput
    }

    private fun serializeCellInputVec(inputs: List<CellInput>): ByteArray {
        val items = inputs.map { serializeCellInput(it) }
        return serializeFixVec(items)
    }

    private fun serializeScript(script: Script): ByteArray {
        val codeHash = serializeByte32(script.codeHash)
        val hashType = when (script.hashType) {
            "data" -> byteArrayOf(0)
            "type" -> byteArrayOf(1)
            "data1" -> byteArrayOf(2)
            "data2" -> byteArrayOf(4)
            else -> byteArrayOf(1)
        }
        val args = serializeBytes(script.args)
        return serializeTable(listOf(codeHash, hashType, args))
    }

    private fun serializeScriptOpt(script: Script?): ByteArray {
        return if (script == null) {
            byteArrayOf() // None option is empty
        } else {
            serializeScript(script)
        }
    }

    private fun serializeCellOutput(output: CellOutput): ByteArray {
        val capacity = serializeUint64(
            output.capacity.removePrefix("0x").toLongOrNull(16)
                ?: throw IllegalArgumentException("Malformed output capacity '${output.capacity}' in transaction")
        )
        val lock = serializeScript(output.lock)
        val type = serializeScriptOpt(output.type)
        return serializeTable(listOf(capacity, lock, type))
    }

    private fun serializeCellOutputVec(outputs: List<CellOutput>): ByteArray {
        val items = outputs.map { serializeCellOutput(it) }
        return serializeDynVec(items)
    }

    /**
     * Serialize WitnessArgs as molecule table.
     * WitnessArgs = lock (BytesOpt) + input_type (BytesOpt) + output_type (BytesOpt)
     */
    private fun serializeWitnessArgs(
        lock: ByteArray?,
        inputType: ByteArray?,
        outputType: ByteArray?
    ): ByteArray {
        val lockOpt = serializeBytesOpt(lock)
        val inputTypeOpt = serializeBytesOpt(inputType)
        val outputTypeOpt = serializeBytesOpt(outputType)
        return serializeTable(listOf(lockOpt, inputTypeOpt, outputTypeOpt))
    }

    private fun serializeBytesOpt(data: ByteArray?): ByteArray {
        return if (data == null || data.isEmpty()) {
            byteArrayOf() // None
        } else {
            serializeBytesRaw(data)
        }
    }

    private fun serializeBytesRaw(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(littleEndianInt(data.size))
        output.write(data)
        return output.toByteArray()
    }

    private fun blake2bHash(data: ByteArray): ByteArray {
        return Blake2b.digest(data)
    }

    private fun littleEndianInt(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun littleEndianLong(value: Long): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 32) and 0xFF).toByte(),
            ((value shr 40) and 0xFF).toByte(),
            ((value shr 48) and 0xFF).toByte(),
            ((value shr 56) and 0xFF).toByte()
        )
    }
}
