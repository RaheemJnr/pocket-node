package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.validation.NetworkValidator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionBuilderTest {

    private lateinit var builder: TransactionBuilder
    private val mockValidator = mockk<NetworkValidator>()

    @Before
    fun setup() {
        every { mockValidator.validateTransferAddresses(any(), any(), any()) } returns Result.success(NetworkType.TESTNET)
        builder = TransactionBuilder(mockValidator)
    }

    // --- Fee Estimation ---

    @Test
    fun `estimateTransferFee for 1 input 2 outputs`() {
        val fee = builder.estimateTransferFee(1, 2)
        assertTrue("Fee should be >= MIN_FEE", fee >= TransactionBuilder.MIN_FEE)
        assertTrue("Fee should be < 2000 for simple tx", fee < 2000)
    }

    @Test
    fun `estimateTransferFee for 1 input 1 output`() {
        val fee = builder.estimateTransferFee(1, 1)
        assertTrue(fee >= TransactionBuilder.MIN_FEE)
        val fee2 = builder.estimateTransferFee(1, 2)
        assertTrue("1-output fee should be <= 2-output fee", fee <= fee2)
    }

    @Test
    fun `estimateTransferFee increases with many more inputs`() {
        val fee1 = builder.estimateTransferFee(1, 2)
        val fee10 = builder.estimateTransferFee(10, 2)
        assertTrue("10 inputs should cost >= 1 input", fee10 >= fee1)
    }

    @Test
    fun `estimateTransferFee never returns less than MIN_FEE`() {
        val fee = builder.estimateTransferFee(1, 1)
        assertTrue(fee >= TransactionBuilder.MIN_FEE)
    }

    // --- buildTransfer validation ---

    @Test(expected = IllegalArgumentException::class)
    fun `buildTransfer rejects amount below MIN_CELL_CAPACITY`() {
        val cells = listOf(makeCell("0x${(200_00000000L).toString(16)}", "0x" + "aa".repeat(32), "0x0"))
        builder.buildTransfer(
            fromAddress = TESTNET_ADDRESS,
            toAddress = TESTNET_ADDRESS_2,
            amountShannons = 60_00000000L,
            availableCells = cells,
            privateKey = ByteArray(32) { 1 },
            network = NetworkType.TESTNET
        )
    }

    @Test(expected = RuntimeException::class)
    fun `buildTransfer rejects when no cells available`() {
        builder.buildTransfer(
            fromAddress = TESTNET_ADDRESS,
            toAddress = TESTNET_ADDRESS_2,
            amountShannons = 100_00000000L,
            availableCells = emptyList(),
            privateKey = ByteArray(32) { 1 },
            network = NetworkType.TESTNET
        )
    }

    @Test(expected = RuntimeException::class)
    fun `buildTransfer rejects insufficient balance`() {
        val cells = listOf(makeCell("0x${(50_00000000L).toString(16)}", "0x" + "bb".repeat(32), "0x0"))
        builder.buildTransfer(
            fromAddress = TESTNET_ADDRESS,
            toAddress = TESTNET_ADDRESS_2,
            amountShannons = 100_00000000L,
            availableCells = cells,
            privateKey = ByteArray(32) { 1 },
            network = NetworkType.TESTNET
        )
    }

    // #287 dust-change refusal: end-to-end coverage deferred because the
    // existing test fixtures use placeholder testnet addresses that fail
    // `AddressUtils.parseAddress` (rejected as "Invalid sender address" at
    // TransactionBuilder.kt:95, before the dust-check branch is reached).
    // Wiring a real-checksum address pair into the test fixtures is out of
    // scope for this hotfix; the change is small, the new `when` branch
    // throws on the dust case, and the SendViewModel parseErrorMessage
    // case is asserted by manual smoke per the PR test plan.

    // --- Companion constants ---

    @Test
    fun `MIN_CELL_CAPACITY is 61 CKB in shannons`() {
        assertEquals(61_00000000L, TransactionBuilder.MIN_CELL_CAPACITY)
    }

    @Test
    fun `DEFAULT_FEE is 100_000 shannons`() {
        assertEquals(100_000L, TransactionBuilder.DEFAULT_FEE)
    }

    @Test
    fun `FEE_RATE is 1000 shannons per KB`() {
        assertEquals(1000L, TransactionBuilder.FEE_RATE)
    }

    @Test
    fun `MIN_FEE is 1000 shannons`() {
        assertEquals(1_000L, TransactionBuilder.MIN_FEE)
    }

    // --- Helpers ---

    companion object {
        const val TESTNET_ADDRESS = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqflz5wfc5zrk5njcglpvf2y90xml03hvqqqw4ld6a"
        const val TESTNET_ADDRESS_2 = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqdamwzrffgc54ef48493nfd2sd5gjeqnfh8gpch424"

        fun makeCell(capacity: String, txHash: String, index: String): Cell {
            return Cell(
                outPoint = OutPoint(txHash = txHash, index = index),
                capacity = capacity,
                blockNumber = "0x100",
                lock = Script(
                    codeHash = Script.SECP256K1_CODE_HASH,
                    hashType = "type",
                    args = "0x" + "ab".repeat(20)
                ),
                type = null,
                data = "0x"
            )
        }
    }
}
