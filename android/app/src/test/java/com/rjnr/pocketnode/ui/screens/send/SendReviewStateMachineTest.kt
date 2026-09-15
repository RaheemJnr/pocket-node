package com.rjnr.pocketnode.ui.screens.send

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.AuthMethod
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.contacts.ContactRepository
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.diagnostics.ErrorJournal
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.BalanceResponse
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
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
 * #490: the Send button used to go straight to the network whenever
 * "Authenticate before sending" was off. These tests pin the state machine:
 * a review is shown before any broadcast in BOTH auth modes, cancelling it
 * returns to the form with the inputs intact, and nothing broadcasts without
 * a confirmed review.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendReviewStateMachineTest {

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
    private val balanceShannons = 1_000_00000000L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        repository = mockk(relaxed = true)
        every { repository.balance } returns MutableStateFlow(
            BalanceResponse(
                address = senderAddress,
                capacity = "0x${balanceShannons.toString(16)}",
                capacityCkb = "1000",
                asOfBlock = "0x1",
            )
        )
        every { repository.network } returns MutableStateFlow(NetworkType.TESTNET)
        every { repository.currentNetwork } returns NetworkType.TESTNET
        every { repository.getCurrentAddress() } returns senderAddress
        coEvery { repository.getPrivateKey() } returns ByteArray(32) { 1 }
        // NOTE: `prepareAndSend` returns `kotlin.Result<String>` and MockK
        // 1.13.16 cannot round-trip an inline-value-class return through the
        // suspend resume boundary (same limitation documented in
        // AddWalletViewModelTest). These tests therefore assert on the CALL —
        // whether a broadcast was attempted, and when — which is exactly the
        // sequencing contract #490 is about. The relaxed mock's return value
        // lands in the ViewModel's own catch block; the post-broadcast states
        // (txHash, polling) are covered elsewhere.

        keyManager = mockk(relaxed = true)
        transactionBuilder = mockk(relaxed = true)
        every { transactionBuilder.estimateTransferFee(any(), any()) } returns 1_000L

        authManager = mockk(relaxed = true)
        pinManager = mockk(relaxed = true)
        every { pinManager.hasPin() } returns true
        every { authManager.isBiometricEnabled() } returns false
        every { authManager.isBiometricEnrolled() } returns false

        walletRepository = mockk(relaxed = true)
        every { walletRepository.walletsFlow } returns MutableStateFlow(emptyList())
        every { walletRepository.activeWalletIdSnapshot() } returns "w1"

        walletKeyReader = mockk(relaxed = true)
        keyMaterialDao = mockk(relaxed = true)
        // V1 wallet: the legacy path, where the auth-before-send setting decides
        // whether a PIN/biometric gate appears at all.
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

    private suspend fun SendViewModel.fillForm(amount: String = "100") {
        updateRecipient(recipient)
        updateAmount(amount)
    }

    @Test
    fun `auth off - send shows review and broadcasts nothing`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.sendTransaction()
        advanceUntilIdle()

        val review = vm.uiState.value.reviewRequest
        assertNotNull("review sheet must be requested before broadcast", review)
        assertEquals(recipient, review!!.recipientAddress)
        assertEquals(100_00000000L, review.amountShannons)
        assertEquals(review.amountShannons + review.feeShannons, review.totalShannons)
        assertFalse(vm.uiState.value.requiresAuth)
        coVerify(exactly = 0) { repository.prepareAndSend(any(), any(), any(), any()) }
    }

    @Test
    fun `auth off - confirm broadcasts once`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.sendTransaction()
        advanceUntilIdle()
        vm.confirmSend()
        advanceUntilIdle()

        assertNull(vm.uiState.value.reviewRequest)
        coVerify(exactly = 1) {
            repository.prepareAndSend(senderAddress, recipient, 100_00000000L, any())
        }
    }

    @Test
    fun `auth on - review comes first, then the auth prompt, then the broadcast`() =
        runTest(testDispatcher) {
            every { authManager.isAuthBeforeSendEnabled() } returns true
            val vm = newViewModel()
            advanceUntilIdle()

            vm.fillForm()
            vm.sendTransaction()
            advanceUntilIdle()

            // Review first — the auth prompt must not pre-empt it.
            assertNotNull(vm.uiState.value.reviewRequest)
            assertFalse(vm.uiState.value.requiresAuth)
            coVerify(exactly = 0) { repository.prepareAndSend(any(), any(), any(), any()) }

            vm.confirmSend()
            advanceUntilIdle()

            // Confirm hands over to the single existing auth gate — still no broadcast.
            assertNull(vm.uiState.value.reviewRequest)
            assertTrue(vm.uiState.value.requiresAuth)
            assertEquals(AuthMethod.PIN, vm.uiState.value.authMethod)
            coVerify(exactly = 0) { repository.prepareAndSend(any(), any(), any(), any()) }

            // The screen calls executeSend() once the PIN/biometric check passes.
            vm.executeSend()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.requiresAuth)
            coVerify(exactly = 1) {
                repository.prepareAndSend(senderAddress, recipient, 100_00000000L, any())
            }
        }

    @Test
    fun `cancel returns to the form without broadcasting`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.sendTransaction()
        advanceUntilIdle()
        vm.cancelReview()
        advanceUntilIdle()

        assertNull(vm.uiState.value.reviewRequest)
        assertFalse(vm.uiState.value.requiresAuth)
        // Inputs survive so the user can correct a typo instead of retyping.
        assertEquals(recipient, vm.uiState.value.recipientAddress)
        assertEquals("100", vm.uiState.value.amountCkb)
        assertEquals(TransactionState.IDLE, vm.uiState.value.transactionState)
        coVerify(exactly = 0) { repository.prepareAndSend(any(), any(), any(), any()) }
    }

    @Test
    fun `confirm without a pending review does nothing`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.confirmSend()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.prepareAndSend(any(), any(), any(), any()) }
    }

    @Test
    fun `invalid amount fails validation instead of opening the review`() =
        runTest(testDispatcher) {
            every { authManager.isAuthBeforeSendEnabled() } returns false
            val vm = newViewModel()
            advanceUntilIdle()

            // Below the 61 CKB minimum cell capacity.
            vm.fillForm(amount = "1")
            vm.sendTransaction()
            advanceUntilIdle()

            assertNull(vm.uiState.value.reviewRequest)
            assertNotNull(vm.uiState.value.error)
            coVerify(exactly = 0) { repository.prepareAndSend(any(), any(), any(), any()) }
        }
}
