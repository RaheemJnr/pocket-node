package com.rjnr.pocketnode.ui.screens.send

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.contacts.ContactRepository
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.diagnostics.ErrorJournal
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.Cell
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.transaction.TransferPlan
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #447: a send that takes all, or nearly all, of the wallet must say so on the
 * review sheet and make the user tick "I understand" before Confirm does
 * anything. It is a warning, not a block — Max sends stay possible, and the
 * change-output rules still own dust.
 *
 * Threshold: the send must leave at least one minimal cell (61 CKB) or 1% of
 * the balance, whichever is larger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendSweepWarningTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: GatewayRepository
    private lateinit var keyManager: KeyManager
    private lateinit var transactionBuilder: TransactionBuilder
    private lateinit var authManager: AuthManager
    private lateinit var pinManager: PinManager
    private lateinit var walletRepository: WalletRepository
    private lateinit var walletKeyReader: WalletKeyReader
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var contactRepository: ContactRepository
    private lateinit var errorJournal: ErrorJournal
    private lateinit var uiPreferences: com.rjnr.pocketnode.core.prefs.UiPreferences

    private val senderAddress = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqsender"
    private val recipient = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqrecipient"

    private val ckb = 100_000_000L

    /** 10,000 CKB: 1% of it (100 CKB) is above one minimal cell, so 1% is the live rule. */
    private val balanceShannons = 10_000L * ckb
    private val plannedFee = 4_321L

    private val balanceFlow = MutableStateFlow<BalanceResponse?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        repository = mockk(relaxed = true)
        balanceFlow.value = BalanceResponse(
            address = senderAddress,
            capacity = "0x${balanceShannons.toString(16)}",
            capacityCkb = "10000",
            asOfBlock = "0x1",
        )
        every { repository.balance } returns balanceFlow
        every { repository.network } returns MutableStateFlow(NetworkType.TESTNET)
        every { repository.currentNetwork } returns NetworkType.TESTNET
        every { repository.getCurrentAddress() } returns senderAddress
        coEvery { repository.getPrivateKey() } returns ByteArray(32) { 1 }

        keyManager = mockk(relaxed = true)
        transactionBuilder = mockk(relaxed = true)
        every { transactionBuilder.estimateTransferFee(any(), any()) } returns 1_000L

        authManager = mockk(relaxed = true)
        every { authManager.isAuthBeforeSendEnabled() } returns false
        pinManager = mockk(relaxed = true)
        every { pinManager.hasPin() } returns true
        every { authManager.isBiometricEnabled() } returns false
        every { authManager.isBiometricEnrolled() } returns false

        walletRepository = mockk(relaxed = true)
        every { walletRepository.walletsFlow } returns MutableStateFlow(emptyList())
        every { walletRepository.activeWalletIdSnapshot() } returns "w1"

        walletKeyReader = mockk(relaxed = true)
        keyMaterialDao = mockk(relaxed = true)
        coEvery { keyMaterialDao.getKdfVersion(any()) } returns 1

        contactRepository = mockk(relaxed = true)
        coEvery { contactRepository.getByAddress(any()) } returns null

        errorJournal = mockk(relaxed = true)
        uiPreferences = mockk(relaxed = true)
        every { uiPreferences.isBulkSendUnlocked() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): SendViewModel = SendViewModel(
        repository = repository,
        keyManager = keyManager,
        transactionBuilder = transactionBuilder,
        authManager = authManager,
        pinManager = pinManager,
        walletRepository = walletRepository,
        walletKeyReader = walletKeyReader,
        keyMaterialDao = keyMaterialDao,
        contactRepository = contactRepository,
        errorJournal = errorJournal,
        uiPreferences = uiPreferences,
        logger = NoopLogger,
    )

    /**
     * Pin the plan's fee so the leftover is exact: the ViewModel prices the
     * review from [GatewayRepository.previewTransfer], not from the form's
     * 1-input estimate.
     */
    private fun planFor(amountShannons: Long) {
        coEvery { repository.previewTransfer(any(), any()) } returns TransferPlan(
            selectedCells = listOf(
                Cell(
                    outPoint = OutPoint("0x" + "ab".repeat(32), "0x0"),
                    capacity = "0x${balanceShannons.toString(16)}",
                    blockNumber = "0x100",
                    lock = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20)),
                )
            ),
            totalInput = balanceShannons,
            totalRecipientAmount = amountShannons,
            feeShannons = plannedFee,
            changeShannons = balanceShannons - amountShannons - plannedFee,
        )
    }

    /** Drive the form to a review sheet for [amountShannons]. */
    private suspend fun TestScope.reviewFor(amountShannons: Long): SendViewModel {
        planFor(amountShannons)
        val vm = newViewModel()
        advanceUntilIdle()
        vm.updateRecipient(recipient)
        vm.updateAmount(shannonsToCkbInput(amountShannons))
        vm.sendTransaction()
        advanceUntilIdle()
        return vm
    }

    private fun shannonsToCkbInput(shannons: Long): String =
        java.math.BigDecimal(shannons).movePointLeft(8).stripTrailingZeros().toPlainString()

    // -- The calculation --

    @Test
    fun `below threshold - a send that leaves under 1 percent warns`() = runTest(testDispatcher) {
        // Leaves 99 CKB of a 10,000 CKB balance: under the 100 CKB (1%) line.
        val amount = balanceShannons - 99 * ckb - plannedFee
        val vm = reviewFor(amount)

        val review = vm.uiState.value.reviewRequest
        assertNotNull(review)
        assertTrue("expected a sweep warning", review!!.isNearlyFullBalance)
        assertEquals(99 * ckb, review.remainingShannons)
        assertEquals(100 * ckb, review.sweepThresholdShannons)
        // 99 CKB is still more than one minimal cell, so the softened copy applies.
        assertFalse(review.remainingBelowMinCell)
    }

    @Test
    fun `at threshold - a send that leaves exactly the threshold does not warn`() =
        runTest(testDispatcher) {
            val amount = balanceShannons - 100 * ckb - plannedFee
            val vm = reviewFor(amount)

            val review = vm.uiState.value.reviewRequest
            assertNotNull(review)
            assertEquals(100 * ckb, review!!.remainingShannons)
            assertFalse("exactly at the threshold is fine", review.isNearlyFullBalance)
        }

    @Test
    fun `one shannon under the threshold warns`() = runTest(testDispatcher) {
        val amount = balanceShannons - (100 * ckb - 1) - plannedFee
        val vm = reviewFor(amount)

        val review = vm.uiState.value.reviewRequest!!
        assertEquals(100 * ckb - 1, review.remainingShannons)
        assertTrue(review.isNearlyFullBalance)
    }

    @Test
    fun `normal send - a small amount off a large balance does not warn`() =
        runTest(testDispatcher) {
            val amount = 100 * ckb
            val vm = reviewFor(amount)

            val review = vm.uiState.value.reviewRequest!!
            assertFalse(review.isNearlyFullBalance)
            assertEquals(balanceShannons - amount - plannedFee, review.remainingShannons)
        }

    @Test
    fun `a full sweep leaves nothing and uses the not-enough-for-another-transaction copy`() =
        runTest(testDispatcher) {
            val amount = balanceShannons - plannedFee
            val vm = reviewFor(amount)

            val review = vm.uiState.value.reviewRequest!!
            assertTrue(review.isNearlyFullBalance)
            assertEquals(0L, review.remainingShannons)
            assertTrue(review.remainingBelowMinCell)
        }

    @Test
    fun `on a small wallet the 61 CKB minimum cell is the threshold, not 1 percent`() {
        // 1% of 100 CKB is 1 CKB, which would let a wallet be emptied to dust
        // without a word; the minimal cell floor wins.
        val smallBalance = 100 * ckb
        assertEquals(61 * ckb, SendReview.sweepThreshold(smallBalance))
        assertTrue(SendReview.shouldWarn(smallBalance, totalShannons = 40 * ckb))
        assertFalse(SendReview.shouldWarn(smallBalance, totalShannons = 39 * ckb))
    }

    @Test
    fun `a zero balance never warns - it means the balance has not loaded yet`() {
        assertFalse(SendReview.shouldWarn(balanceShannons = 0L, totalShannons = 10 * ckb))
    }

    // -- Confirm is gated on the acknowledgement --

    @Test
    fun `confirm does nothing until the warning is acknowledged`() = runTest(testDispatcher) {
        val amount = balanceShannons - plannedFee
        val vm = reviewFor(amount)
        assertTrue(vm.uiState.value.reviewRequest!!.isNearlyFullBalance)
        assertFalse(vm.uiState.value.sweepWarningAcknowledged)

        vm.confirmSend()
        advanceUntilIdle()

        // Sheet still up, nothing signed or broadcast.
        assertNotNull(vm.uiState.value.reviewRequest)
        coVerify(exactly = 0) { repository.prepareAndSend(any(), any(), any(), any(), any()) }

        vm.setSweepWarningAcknowledged(true)
        vm.confirmSend()
        advanceUntilIdle()

        assertNull(vm.uiState.value.reviewRequest)
        coVerify(exactly = 1) {
            repository.prepareAndSend(senderAddress, recipient, amount, any(), plannedFee)
        }
    }

    @Test
    fun `an unwarned send confirms without any acknowledgement`() = runTest(testDispatcher) {
        val amount = 100 * ckb
        val vm = reviewFor(amount)
        assertFalse(vm.uiState.value.reviewRequest!!.isNearlyFullBalance)

        vm.confirmSend()
        advanceUntilIdle()

        assertNull(vm.uiState.value.reviewRequest)
        coVerify(exactly = 1) {
            repository.prepareAndSend(senderAddress, recipient, amount, any(), plannedFee)
        }
    }

    @Test
    fun `the acknowledgement does not carry over to the next review`() = runTest(testDispatcher) {
        val amount = balanceShannons - plannedFee
        val vm = reviewFor(amount)
        vm.setSweepWarningAcknowledged(true)

        vm.cancelReview()
        assertFalse(vm.uiState.value.sweepWarningAcknowledged)

        vm.sendTransaction()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.reviewRequest!!.isNearlyFullBalance)
        assertFalse("a new draft must be acknowledged again", vm.uiState.value.sweepWarningAcknowledged)
        vm.confirmSend()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.reviewRequest)
        coVerify(exactly = 0) { repository.prepareAndSend(any(), any(), any(), any(), any()) }
    }
}
