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
    fun estimateTransferFee(inputCount: Int, outputCount: Int): Long {
        // Fixed overhead: version(4) + RawTransaction table header(28) +
        // cell_deps fixvec with 1 dep_group(45) + header_deps empty fixvec(4)
        val fixedOverhead = 81
        // Each input: since(8) + outpoint(36) = 44 bytes + fixvec count header
        val inputsSize = 4 + inputCount * 44
        // Each output: molecule table(16 header) + capacity(8) + lock script table(~53) + empty type opt = ~77 bytes
        val outputsSize = 4 + outputCount * 4 + outputCount * 77 // dynvec
        // outputs_data: each "0x" = 4 bytes (length-prefixed empty) + dynvec overhead
        val outputsDataSize = 4 + outputCount * 4 + outputCount * 4 // dynvec
        // Witnesses: first has WitnessArgs with 65-byte sig (~85 bytes), rest are empty
        val witnessSize = 85 + (inputCount - 1).coerceAtLeast(0) * 4
        // Safety buffer for molecule encoding overhead
        val buffer = 100

        val estimatedBytes = fixedOverhead + inputsSize + outputsSize + outputsDataSize + witnessSize + buffer
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
        Log.d(TAG, "  From: $fromAddress")
        Log.d(TAG, "  To: $toAddress")
        Log.d(TAG, "  Amount: $amountShannons shannons")

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
            // 69 bytes per input: 65-byte secp256k1 signature + 4-byte molecule length prefix.
            // Only the first witness has a full WitnessArgs table (~16 byte header); subsequent
            // witnesses are empty ("0x"). The +100 buffer covers the first witness table header,
            // the molecule dynvec wrapper, and any rounding in the molecule encoding.
            val witnessOverhead = tx.cellInputs.size * 69
            rawSize + witnessOverhead + 100
        } catch (e: Exception) {
            Log.w(TAG, "Transaction size estimation failed: ${e.message}")
            Int.MAX_VALUE // fail-safe: reject if we can't estimate size
        }
    }

    private fun selectCells(cells: List<Cell>, requiredCapacity: Long): Pair<List<Cell>, Long> {
        val sortedCells = cells
            .filter { it.type == null }
            .sortedByDescending { parseCapacity(it.capacity) }

        val selected = mutableListOf<Cell>()
        var total = 0L

        for (cell in sortedCells) {
            if (total >= requiredCapacity) break
            selected.add(cell)
            total += parseCapacity(cell.capacity)
        }

        return Pair(selected, total)
    }

    private fun parseCapacity(hex: String): Long {
        return hex.removePrefix("0x").toLong(16)
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

    private fun signTransaction(
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
        val since = serializeUint64(input.since.removePrefix("0x").toLong(16))
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
        val capacity = serializeUint64(output.capacity.removePrefix("0x").toLong(16))
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
