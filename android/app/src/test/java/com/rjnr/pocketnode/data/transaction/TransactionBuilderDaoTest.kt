package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.DaoConstants
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.validation.NetworkValidator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionBuilderDaoTest {

    private val builder = TransactionBuilder(NetworkValidator())
    private val testPrivateKey = ByteArray(32) { (it + 1).toByte() }
    private val testScript = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = "0x" + "aa".repeat(20)
    )

    private fun makeCell(capacityShannons: Long, index: Int = 0): Cell = Cell(
        outPoint = OutPoint("0x" + "ab".repeat(32), "0x${index.toString(16)}"),
        capacity = "0x${capacityShannons.toString(16)}",
        blockNumber = "0x100",
        lock = testScript
    )

    private fun makeDepositCell(): Cell = Cell(
        outPoint = OutPoint("0x" + "cd".repeat(32), "0x0"),
        capacity = "0x${DaoConstants.MIN_DEPOSIT_SHANNONS.toString(16)}",
        blockNumber = "0x100",
        lock = testScript,
        type = DaoConstants.DAO_TYPE_SCRIPT,
        data = "0x0000000000000000"
    )

    private fun makeWithdrawingCell(): Cell = Cell(
        outPoint = OutPoint("0x" + "cc".repeat(32), "0x0"),
        capacity = "0x${DaoConstants.MIN_DEPOSIT_SHANNONS.toString(16)}",
        blockNumber = "0x200",
        lock = testScript,
        type = DaoConstants.DAO_TYPE_SCRIPT
    )

    // --- buildDaoDeposit ---

    @Test
    fun `buildDaoDeposit output has DAO type script`() {
        val tx = builder.buildDaoDeposit(
            amountShannons = DaoConstants.MIN_DEPOSIT_SHANNONS,
            availableCells = listOf(makeCell(20_000_00000000L)),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
        assertEquals(DaoConstants.DAO_TYPE_SCRIPT, tx.cellOutputs[0].type)
    }

    @Test
    fun `buildDaoDeposit first output data is 8 zero bytes`() {
        val tx = builder.buildDaoDeposit(
            amountShannons = DaoConstants.MIN_DEPOSIT_SHANNONS,
            availableCells = listOf(makeCell(20_000_00000000L)),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
        assertEquals("0x0000000000000000", tx.outputsData[0])
    }

    @Test
    fun `buildDaoDeposit has secp256k1 and DAO cell deps`() {
        val tx = builder.buildDaoDeposit(
            amountShannons = DaoConstants.MIN_DEPOSIT_SHANNONS,
            availableCells = listOf(makeCell(20_000_00000000L)),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
        assertEquals(2, tx.cellDeps.size)
        assertEquals(CellDep.SECP256K1_TESTNET, tx.cellDeps[0])
        assertEquals(DaoConstants.daoCellDep(NetworkType.TESTNET), tx.cellDeps[1])
    }

    @Test
    fun `buildDaoDeposit creates change output when sufficient`() {
        val tx = builder.buildDaoDeposit(
            amountShannons = DaoConstants.MIN_DEPOSIT_SHANNONS,
            availableCells = listOf(makeCell(20_000_00000000L)),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
        assertEquals(2, tx.cellOutputs.size)
        assertNull(tx.cellOutputs[1].type)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildDaoDeposit rejects below minimum deposit`() {
        builder.buildDaoDeposit(
            amountShannons = DaoConstants.MIN_DEPOSIT_SHANNONS - 1,
            availableCells = listOf(makeCell(20_000_00000000L)),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
    }

    @Test
    fun `buildDaoDeposit throws on insufficient balance`() {
        try {
            builder.buildDaoDeposit(
                amountShannons = DaoConstants.MIN_DEPOSIT_SHANNONS,
                availableCells = listOf(makeCell(100_00000000L)), // 100 CKB < 102 needed
                senderScript = testScript,
                privateKey = testPrivateKey,
                network = NetworkType.TESTNET
            )
            fail("Should throw on insufficient balance")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Insufficient"))
        }
    }

    @Test
    fun `buildDaoDeposit uses mainnet cell deps for MAINNET`() {
        val tx = builder.buildDaoDeposit(
            amountShannons = DaoConstants.MIN_DEPOSIT_SHANNONS,
            availableCells = listOf(makeCell(20_000_00000000L)),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.MAINNET
        )
        assertEquals(CellDep.SECP256K1_MAINNET, tx.cellDeps[0])
        assertEquals(DaoConstants.daoCellDep(NetworkType.MAINNET), tx.cellDeps[1])
    }

    // --- buildDaoWithdraw ---

    @Test
    fun `buildDaoWithdraw data contains block number in little-endian`() {
        val tx = builder.buildDaoWithdraw(
            depositCell = makeDepositCell(),
            depositBlockNumber = 12345L,
            depositBlockHash = "0x" + "ee".repeat(32),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET,
            availableCells = listOf(makeCell(200_00000000L, index = 1))
        )
        // 12345 = 0x3039, LE bytes: 39 30 00 00 00 00 00 00
        assertEquals("0x3930000000000000", tx.outputsData[0])
    }

    @Test
    fun `buildDaoWithdraw has deposit block hash in header deps`() {
        val depositBlockHash = "0x" + "ee".repeat(32)
        val tx = builder.buildDaoWithdraw(
            depositCell = makeDepositCell(),
            depositBlockNumber = 100L,
            depositBlockHash = depositBlockHash,
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET,
            availableCells = listOf(makeCell(200_00000000L, index = 1))
        )
        assertEquals(1, tx.headerDeps.size)
        assertEquals(depositBlockHash, tx.headerDeps[0])
    }

    @Test
    fun `buildDaoWithdraw output has DAO type script`() {
        val tx = builder.buildDaoWithdraw(
            depositCell = makeDepositCell(),
            depositBlockNumber = 100L,
            depositBlockHash = "0x" + "ee".repeat(32),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET,
            availableCells = listOf(makeCell(200_00000000L, index = 1))
        )
        assertEquals(DaoConstants.DAO_TYPE_SCRIPT, tx.cellOutputs[0].type)
    }

    @Test
    fun `buildDaoWithdraw includes fee input cell so fee is positive (#119)`() {
        val feeCellCapacity = 200_00000000L
        val tx = builder.buildDaoWithdraw(
            depositCell = makeDepositCell(),
            depositBlockNumber = 100L,
            depositBlockHash = "0x" + "ee".repeat(32),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET,
            availableCells = listOf(makeCell(feeCellCapacity, index = 1))
        )
        // 1 deposit input + 1 fee input
        assertEquals(2, tx.cellInputs.size)
        // First output is the withdrawing cell — same capacity as the deposit
        val depositCapacity = DaoConstants.MIN_DEPOSIT_SHANNONS
        assertEquals("0x${depositCapacity.toString(16)}", tx.cellOutputs[0].capacity)
        // Total inputs > total outputs so fee > 0 (the heart of the #119 fix)
        val totalInputs = depositCapacity + feeCellCapacity
        val totalOutputs = tx.cellOutputs.sumOf { it.capacity.removePrefix("0x").toLong(16) }
        assertEquals(true, totalInputs > totalOutputs)
    }

    @Test
    fun `buildDaoWithdraw throws when no fee-covering cells available`() {
        try {
            builder.buildDaoWithdraw(
                depositCell = makeDepositCell(),
                depositBlockNumber = 100L,
                depositBlockHash = "0x" + "ee".repeat(32),
                senderScript = testScript,
                privateKey = testPrivateKey,
                network = NetworkType.TESTNET,
                availableCells = emptyList()
            )
            org.junit.Assert.fail("Expected exception")
        } catch (e: Exception) {
            org.junit.Assert.assertTrue(
                "expected 'Insufficient' in '${e.message}'",
                e.message?.contains("Insufficient") == true
            )
        }
    }

    // --- buildDaoUnlock ---

    @Test
    fun `buildDaoUnlock header deps order is deposit then withdraw`() {
        val depositHash = "0x" + "aa".repeat(32)
        val withdrawHash = "0x" + "bb".repeat(32)
        val tx = builder.buildDaoUnlock(
            withdrawingCell = makeWithdrawingCell(),
            maxWithdraw = 10_300_000_000L,
            sinceValue = "0x2000180000b0000a",
            depositBlockHash = depositHash,
            withdrawBlockHash = withdrawHash,
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
        assertEquals(2, tx.headerDeps.size)
        assertEquals(depositHash, tx.headerDeps[0])
        assertEquals(withdrawHash, tx.headerDeps[1])
    }

    @Test
    fun `buildDaoUnlock witness carries deposit header index in input_type not output_type (#315)`() {
        val tx = builder.buildDaoUnlock(
            withdrawingCell = makeWithdrawingCell(),
            maxWithdraw = 10_300_000_000L,
            sinceValue = "0x2000180000b0000a",
            depositBlockHash = "0x" + "aa".repeat(32),
            withdrawBlockHash = "0x" + "bb".repeat(32),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )

        // Parse the molecule WitnessArgs table: [totalSize u32le][off0][off1][off2]
        // followed by lock / input_type / output_type BytesOpt fields.
        val witness = tx.witnesses[0].removePrefix("0x")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun u32le(offset: Int): Int =
            (witness[offset].toInt() and 0xff) or
                ((witness[offset + 1].toInt() and 0xff) shl 8) or
                ((witness[offset + 2].toInt() and 0xff) shl 16) or
                ((witness[offset + 3].toInt() and 0xff) shl 24)

        val totalSize = u32le(0)
        val lockOffset = u32le(4)
        val inputTypeOffset = u32le(8)
        val outputTypeOffset = u32le(12)
        assertEquals(witness.size, totalSize)

        // lock = 65-byte signature wrapped in fixvec (4-byte length prefix)
        assertEquals(65 + 4, inputTypeOffset - lockOffset)

        // input_type = Some(8-byte LE deposit header index 0): fixvec len 8 + 8 zero bytes.
        // dao.c reads the header index from input_type (MolReader_WitnessArgs_get_input_type);
        // a None input_type fails on-chain script verification.
        val inputTypeField = witness.copyOfRange(inputTypeOffset, outputTypeOffset)
        assertEquals(12, inputTypeField.size)
        assertEquals(8, (inputTypeField[0].toInt() and 0xff))
        assertTrue(inputTypeField.copyOfRange(4, 12).all { it == 0.toByte() })

        // output_type = None (zero-length field)
        assertEquals(totalSize, outputTypeOffset)
    }

    @Test
    fun `buildDaoUnlock output has no type script`() {
        val tx = builder.buildDaoUnlock(
            withdrawingCell = makeWithdrawingCell(),
            maxWithdraw = 10_300_000_000L,
            sinceValue = "0x2000180000b0000a",
            depositBlockHash = "0x" + "aa".repeat(32),
            withdrawBlockHash = "0x" + "bb".repeat(32),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
        assertNull(tx.cellOutputs[0].type)
    }

    @Test
    fun `buildDaoUnlock output capacity is maxWithdraw minus fee`() {
        val maxWithdraw = 10_300_000_000L
        val tx = builder.buildDaoUnlock(
            withdrawingCell = makeWithdrawingCell(),
            maxWithdraw = maxWithdraw,
            sinceValue = "0x2000180000b0000a",
            depositBlockHash = "0x" + "aa".repeat(32),
            withdrawBlockHash = "0x" + "bb".repeat(32),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
        val expected = maxWithdraw - TransactionBuilder.DEFAULT_FEE
        val actual = tx.cellOutputs[0].capacity.removePrefix("0x").toLong(16)
        assertEquals(expected, actual)
    }

    @Test
    fun `buildDaoUnlock input has since value set`() {
        val sinceValue = "0x2000180000b0000a"
        val tx = builder.buildDaoUnlock(
            withdrawingCell = makeWithdrawingCell(),
            maxWithdraw = 10_300_000_000L,
            sinceValue = sinceValue,
            depositBlockHash = "0x" + "aa".repeat(32),
            withdrawBlockHash = "0x" + "bb".repeat(32),
            senderScript = testScript,
            privateKey = testPrivateKey,
            network = NetworkType.TESTNET
        )
        assertEquals(sinceValue, tx.cellInputs[0].since)
    }
}
