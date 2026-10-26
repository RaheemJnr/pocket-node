package com.rjnr.pocketnode.ui.screens.send

import androidx.fragment.app.FragmentActivity
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
import com.rjnr.pocketnode.data.gateway.models.Cell
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.send.ReviewedTransfer
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
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

    private val host = com.rjnr.pocketnode.data.wallet.ForegroundTestHost()
    private val foregroundActivity = host.activity

    private val senderAddress = com.rjnr.pocketnode.data.wallet.AddressUtils.encode(Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20)), NetworkType.TESTNET)
    private val recipient = com.rjnr.pocketnode.data.wallet.AddressUtils.encode(Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "bb".repeat(20)), NetworkType.TESTNET)
    private val balanceShannons = 1_000_00000000L

    /** What the repository's dry-run plan reports: a fragmented wallet, 4 inputs, 4,321 shannons. */
    private val plannedFee = 4_321L
    private val plannedTransfer = TransferPlan(
        selectedCells = List(4) { index ->
            Cell(
                outPoint = OutPoint("0x" + "ab".repeat(32), "0x$index"),
                capacity = "0x${(70_00000000L).toString(16)}",
                blockNumber = "0x100",
                lock = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20)),
                data = "0x",
            )
        },
        totalInput = 280_00000000L,
        totalRecipientAmount = 100_00000000L,
        feeShannons = plannedFee,
        changeShannons = 280_00000000L - 100_00000000L - plannedFee,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        repository = mockk(relaxed = true)
        coEvery { repository.sendReviewed(any(), any(), any(), any()) } throws IllegalStateException("sequencing-only fixture stops before broadcast")
        coEvery { repository.previewBatches(any(), any()) } answers { arg<List<List<com.rjnr.pocketnode.data.transaction.RecipientOutput>>>(1).map { 1000L } }
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
        // Sequencing fixtures stop explicitly at the repository boundary. Race tests below
        // assemble real transactions and inspect semantic outputs independently.

        // The review sheet is priced by the real plan, not the form's
        // 1-input estimate (#490 review item 1).
        coEvery { repository.previewTransfer(any(), any()) } returns plannedTransfer

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
        coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } answers {
            WalletKeyReader.Result.Success(ByteArray(32) { 7 })
        }
        keyMaterialDao = mockk(relaxed = true)
        // Every version uses the same authenticated reader boundary.
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

    private fun newViewModel(quarantineStore: com.rjnr.pocketnode.data.transaction.BulkSendQuarantineStore = com.rjnr.pocketnode.data.transaction.BulkSendQuarantineStore(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>())): SendViewModel = SendViewModel(
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
        bulkQuarantineStore = quarantineStore,
    )

    private suspend fun SendViewModel.fillForm(amount: String = "100") {
        updateRecipient(recipient)
        updateAmount(amount)
    }

    @Test fun `single and bulk reject contradictory locks while custom review preserves guidance`() = runTest(testDispatcher) {
        val bad = com.rjnr.pocketnode.data.wallet.AddressUtils.encode(
            Script(Script.SECP256K1_CODE_HASH, "data", "0x" + "bb".repeat(20)), NetworkType.TESTNET)
        val custom = com.rjnr.pocketnode.data.wallet.AddressUtils.encode(
            Script("0x" + "42".repeat(32), "data2", "0x" + "bb".repeat(20)), NetworkType.TESTNET)
        coEvery { repository.getCells() } throws kotlinx.coroutines.CancellationException("public fixture skips unrelated cell-count preview")
        val vm = newViewModel(); advanceUntilIdle()
        try {
            vm.updateRecipient(bad); vm.updateAmount("100"); vm.sendTransaction(); advanceUntilIdle()
            assertNull(vm.uiState.value.reviewRequest)
            assertNotNull(vm.uiState.value.error)
            coVerify(exactly = 0) { repository.previewTransfer(any(), any()) }
            vm.updateRecipient(custom); vm.sendTransaction(); advanceUntilIdle()
            val transfer = vm.uiState.value.reviewRequest!!.transfers.single()
            assertTrue(transfer.destinationAssessments.single().isCustom)
            assertEquals(custom, transfer.recipients.single().address)
            vm.updateSendMode(SendMode.BULK); vm.updateBulkRecipients("$bad\n$custom"); advanceUntilIdle()
            assertEquals(listOf(custom), vm.uiState.value.bulkValidRecipients)
            assertTrue(vm.uiState.value.bulkInvalidEntries.single().contains("Invalid built-in lock parameters"))
        } finally { androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() } }
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
        // The plan's fee, not the form's 1-input estimate.
        assertEquals(plannedFee, review.feeShannons)
        assertEquals(review.amountShannons + review.feeShannons, review.totalShannons)
        assertEquals(plannedFee, vm.uiState.value.estimatedFee)
        assertFalse(vm.uiState.value.requiresAuth)
        coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
    }

    @Test
    fun `auth off - confirm broadcasts once`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.sendTransaction()
        advanceUntilIdle()
        vm.confirmSend(foregroundActivity)
        advanceUntilIdle()

        assertNull(vm.uiState.value.reviewRequest)
        // The fee the user confirmed travels with the broadcast so the
        // repository can refuse if a re-plan disagrees.
        coVerify(exactly = 1) {
            repository.sendReviewed(match { it.fromAddress == senderAddress && it.recipients.single().address == recipient && it.amountShannons == 100_00000000L && it.feeShannons == plannedFee }, any(), any(), any())
        }
    }

    @Test
    fun `auth on - review precedes vault authentication and app PIN callback cannot replace it`() =
        runTest(testDispatcher) {
            every { authManager.isAuthBeforeSendEnabled() } returns true
            coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Cancelled
            val vm = newViewModel(); advanceUntilIdle()
            vm.fillForm(); vm.sendTransaction(foregroundActivity); advanceUntilIdle()
            assertNotNull(vm.uiState.value.reviewRequest)
            assertFalse(vm.uiState.value.requiresAuth)
            coVerify(exactly = 0) { walletKeyReader.readPrivateKey(any(), any(), any(), any()) }
            vm.confirmSend(foregroundActivity); advanceUntilIdle()
            assertNull(vm.uiState.value.reviewRequest)
            vm.executeSend(); advanceUntilIdle()
            coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
            coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } answers {
                WalletKeyReader.Result.Success(ByteArray(32) { 7 })
            }
            vm.sendTransaction(foregroundActivity); advanceUntilIdle()
            vm.confirmSend(foregroundActivity); advanceUntilIdle()
            coVerify(exactly = 1) {
                repository.sendReviewed(match { it.fromAddress == senderAddress && it.recipients.single().address == recipient && it.amountShannons == 100_00000000L && it.feeShannons == plannedFee }, any(), any(), any())
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
        coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
    }

    @Test
    fun `confirm without a pending review does nothing`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.confirmSend()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
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
            coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
        }

    @Test
    fun `double tap on confirm broadcasts once`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.sendTransaction()
        advanceUntilIdle()

        // Two taps land before the first send finishes; the second finds no
        // pending review and must be a no-op.
        vm.confirmSend(foregroundActivity)
        vm.confirmSend(foregroundActivity)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.sendReviewed(any(), any(), any(), any()) }
    }

    @Test
    fun `V2 wallet - review first, then the CryptoObject unlock, then the broadcast`() =
        runTest(testDispatcher) {
            // kdfVersion 2: the key is unlocked per signing operation, whatever
            // the auth-before-send setting says.
            every { authManager.isAuthBeforeSendEnabled() } returns false
            coEvery { keyMaterialDao.getKdfVersion(any()) } returns 2
            coEvery {
                walletKeyReader.readPrivateKey(any(), any(), any(), any())
            } returns WalletKeyReader.Result.Success(ByteArray(32) { 7 })
            val activity = com.rjnr.pocketnode.data.wallet.ForegroundTestHost().activity

            val vm = newViewModel()
            advanceUntilIdle()

            vm.fillForm()
            vm.sendTransaction(activity)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.reviewRequest)
            coVerify(exactly = 0) { walletKeyReader.readPrivateKey(any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }

            vm.confirmSend(activity)
            advanceUntilIdle()

            assertNull(vm.uiState.value.reviewRequest)
            coVerify(exactly = 1) { walletKeyReader.readPrivateKey(any(), any(), any(), any()) }
            coVerify(exactly = 1) {
                repository.sendReviewed(match { it.fromAddress == senderAddress && it.recipients.single().address == recipient && it.amountShannons == 100_00000000L && it.feeShannons == plannedFee }, any(), any(), any())
            }
        }

    @Test
    fun `legacy direct execute callback cannot read secrets or broadcast without foreground vault authentication`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        val vm = newViewModel(); advanceUntilIdle()
        vm.fillForm(); vm.executeSend(); advanceUntilIdle()
        coVerify(exactly = 0) { repository.getPrivateKey() }
        coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
    }

    @Test
    fun `legacy wallet cancelled vault setup cannot broadcast with send authentication disabled`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        coEvery { keyMaterialDao.getKdfVersion(any()) } returns 1
        coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Cancelled
        val activity = com.rjnr.pocketnode.data.wallet.ForegroundTestHost().activity
        val vm = newViewModel(); advanceUntilIdle()
        vm.fillForm(); vm.sendTransaction(activity); advanceUntilIdle()
        vm.confirmSend(activity); advanceUntilIdle()
        coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
    }

    @Test
    fun `legacy wallet without activity cannot broadcast or read UI free secrets`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        coEvery { keyMaterialDao.getKdfVersion(any()) } returns 1
        val vm = newViewModel(); advanceUntilIdle()
        vm.fillForm(); vm.sendTransaction(); advanceUntilIdle()
        vm.confirmSend(); advanceUntilIdle()
        coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
    }

    @Test
    fun `V2 wallet - a cancelled unlock broadcasts nothing`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        coEvery { keyMaterialDao.getKdfVersion(any()) } returns 2
        coEvery {
            walletKeyReader.readPrivateKey(any(), any(), any(), any())
        } returns WalletKeyReader.Result.Cancelled
        val activity = com.rjnr.pocketnode.data.wallet.ForegroundTestHost().activity

        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.sendTransaction(activity)
        advanceUntilIdle()
        vm.confirmSend(activity)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
    }

    @Test
    fun `V2 wallet confirmed without an activity errors instead of broadcasting`() =
        runTest(testDispatcher) {
            every { authManager.isAuthBeforeSendEnabled() } returns false
            coEvery { keyMaterialDao.getKdfVersion(any()) } returns 2

            val vm = newViewModel()
            advanceUntilIdle()

            vm.fillForm()
            vm.sendTransaction()
            advanceUntilIdle()
            // The no-activity overload cannot drive a CryptoObject prompt.
            vm.confirmSend()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.error)
            assertNull(vm.uiState.value.reviewRequest)
            coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
        }

    @Test
    fun `a failed preview shows the error instead of the review`() = runTest(testDispatcher) {
        every { authManager.isAuthBeforeSendEnabled() } returns false
        coEvery {
            repository.previewTransfer(any(), any())
        } throws IllegalStateException("Insufficient balance: have 1, need at least 2")

        val vm = newViewModel()
        advanceUntilIdle()

        vm.fillForm()
        vm.sendTransaction()
        advanceUntilIdle()

        assertNull(vm.uiState.value.reviewRequest)
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isLoading)
        coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
    }
    @Test fun `stop resume before signing cancels send and clears authenticated bytes`() = runTest(testDispatcher) {
        val key = ByteArray(32) { 7 }
        coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Success(key)
        var entered = false
        var cancelled = false
        coEvery { repository.sendReviewed(any(), any(), any(), any()) } coAnswers {
            entered = true
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        }
        val vm = newViewModel(); advanceUntilIdle(); vm.fillForm(); vm.sendTransaction(); advanceUntilIdle()
        vm.confirmSend(foregroundActivity); runCurrent()
        assertTrue(entered); host.stopResume(); runCurrent()
        try { assertTrue(cancelled); assertTrue(key.all { it == 0.toByte() }); assertNull(vm.uiState.value.txHash) }
        finally { androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() } }
    }
    @Test fun `single signed callback wipes before broadcast suspension and cancellation is not failure`() = runTest(testDispatcher) {
        val key = ByteArray(32) { 7 }
        coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Success(key)
        var signed = false
        coEvery { repository.sendReviewed(any(), any(), any(), any()) } coAnswers {
            arg<() -> Unit>(3).invoke(); signed = true
            assertTrue(key.all { it == 0.toByte() })
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = newViewModel(); advanceUntilIdle(); vm.fillForm(); vm.sendTransaction(); advanceUntilIdle()
        vm.confirmSend(foregroundActivity); runCurrent()
        assertTrue(signed); host.stopResume(); runCurrent()
        try {
            assertNull(vm.uiState.value.txHash)
            assertFalse(vm.uiState.value.transactionState == TransactionState.FAILED)
            assertFalse(vm.uiState.value.isLoading)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() } }
    }

    @Test fun `bulk stop after first batch prevents later signing and wipes operation key`() = runTest(testDispatcher) {
        every { repository.balance } returns MutableStateFlow(BalanceResponse(senderAddress,
            "0x${100_000_00000000L.toString(16)}", "100000", "0x1"))
        val key = ByteArray(32) { 7 }
        coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Success(key)
        // Cell-count preview is a separate job; MockK cannot unbox its suspend Result.
        coEvery { repository.getCells() } throws kotlinx.coroutines.CancellationException("public fixture skips cell-count preview")
        var batches = 0
        coEvery { repository.sendReviewed(any(), any(), any(), any()) } coAnswers {
            assertTrue(key.all { it == 7.toByte() }); batches++
            // A public signed batch is now pending; cancellation cannot authorize another signature.
            host.stopResume()
            kotlinx.coroutines.awaitCancellation()
        }
        val recipients = (1..26).joinToString("\n") { index ->
            com.rjnr.pocketnode.data.wallet.AddressUtils.encode(
                Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "%040x".format(index)), NetworkType.TESTNET)
        }
        val vm = newViewModel(); advanceUntilIdle()
        vm.updateSendMode(SendMode.BULK); vm.updateBulkRecipients(recipients); vm.updateAmount("100")
        vm.sendTransaction(); advanceUntilIdle(); assertNotNull(vm.uiState.value.reviewRequest)
        vm.confirmSend(foregroundActivity); runCurrent()
        try {
            assertEquals(1, batches); assertTrue(key.all { it == 0.toByte() })
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(vm.uiState.value.transactionState == TransactionState.FAILED)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() } }
    }

    @Test fun `cancelled bulk retains only never entered recipients and quarantines submitted or uncertain batches`() = runTest(testDispatcher) {
        for (lock in listOf(false, true)) for (betweenBatches in listOf(false, true)) {
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("bulk_send_quarantine", 0).edit().clear().commit()
            every { repository.balance } returns MutableStateFlow(BalanceResponse(senderAddress,
                "0x${100_000_00000000L.toString(16)}", "100000", "0x1"))
            coEvery { repository.getCells() } throws kotlinx.coroutines.CancellationException("public fixture skips cell-count preview")
            val key = ByteArray(32) { 7 }; var calls = 0
            val host = com.rjnr.pocketnode.data.wallet.ForegroundTestHost()
            fun cancelHost() { if (lock) com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked() else host.stopResume() }
            coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Success(key)
            coEvery { repository.sendReviewed(any(), any(), any(), any()) } coAnswers {
                assertTrue(key.all { it == 7.toByte() }); calls++
                if (calls == 1) {
                    if (betweenBatches) cancelHost()
                    "public-submitted-batch-hash"
                } else kotlinx.coroutines.awaitCancellation()
            }
            val addresses = (1..51).map { index -> com.rjnr.pocketnode.data.wallet.AddressUtils.encode(
                Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "%040x".format(index)), NetworkType.TESTNET) }
            val vm = newViewModel(); advanceUntilIdle()
            vm.updateSendMode(SendMode.BULK); vm.updateBulkRecipients(addresses.joinToString("\n")); vm.updateAmount("100")
            vm.sendTransaction(); advanceUntilIdle(); vm.confirmSend(host.activity); runCurrent()
            val expectedCalls = if (betweenBatches) 1 else 2
            val remaining = addresses.drop(if (betweenBatches) 25 else 50)
            assertEquals(expectedCalls, calls); assertEquals(25, vm.uiState.value.bulkCompletedRecipients)
            if (!betweenBatches) cancelHost()
            runCurrent()
            try {
                assertEquals(remaining, vm.uiState.value.bulkValidRecipients)
                val interrupted = vm.uiState.value.interruptedBulkSends.single()
                assertEquals(addresses.take(25), interrupted.submittedRecipients)
                assertEquals(if (betweenBatches) emptyList<String>() else addresses.subList(25, 50), interrupted.uncertainRecipients)
                assertEquals(listOf("public-submitted-batch-hash"), interrupted.submittedTxHashes)
                assertTrue(key.all { it == 0.toByte() }); assertEquals(expectedCalls, calls)
                vm.updateBulkRecipients(addresses.joinToString("\n"))
                assertEquals(remaining, vm.uiState.value.bulkValidRecipients)
                assertTrue(vm.uiState.value.bulkInvalidEntries.isNotEmpty())
                vm.sendTransaction(); advanceUntilIdle()
                assertEquals(remaining.size, vm.uiState.value.reviewRequest!!.recipientCount)
                assertEquals(interrupted, vm.uiState.value.interruptedBulkSends.single())
                val recreated = newViewModel(); advanceUntilIdle()
                try {
                    recreated.updateSendMode(SendMode.BULK)
                    recreated.updateBulkRecipients(addresses.joinToString("\n")); recreated.updateAmount("100")
                    assertEquals("Navigation and recreation must retain quarantine", remaining, recreated.uiState.value.bulkValidRecipients)
                    every { walletRepository.activeWalletIdSnapshot() } returns "other-wallet"
                    recreated.updateAmount("100")
                    assertEquals(addresses, recreated.uiState.value.bulkValidRecipients)
                    every { walletRepository.activeWalletIdSnapshot() } returns "w1"
                    recreated.updateAmount("100")
                    assertEquals(remaining, recreated.uiState.value.bulkValidRecipients)
                    recreated.acknowledgeBulkReconciliation(interrupted.id)
                    assertEquals(addresses, recreated.uiState.value.bulkValidRecipients)
                } finally { androidx.lifecycle.ViewModelStore().apply { put("recreated", recreated); clear() } }
            } finally { androidx.lifecycle.ViewModelStore().apply { put("test", vm); clear() }; runCurrent() }
        }
    }


    @Test fun `network switch cancels authentication and prevents another bulk signature`() = runTest(testDispatcher) {
        for (phase in listOf("auth", "between", "broadcast")) {
            val duringAuth = phase == "auth"
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("bulk_send_quarantine", 0).edit().clear().commit()
            val network = MutableStateFlow(NetworkType.TESTNET)
            every { repository.network } returns network
            every { repository.currentNetwork } answers { network.value }
            every { repository.balance } returns MutableStateFlow(BalanceResponse(senderAddress,
                "0x${100_000_00000000L.toString(16)}", "100000", "0x1"))
            coEvery { repository.getCells() } throws kotlinx.coroutines.CancellationException("skip public preview")
            val key = ByteArray(32) { 7 }; var authCancelled = false; var calls = 0
            coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } coAnswers {
                if (duringAuth) {
                    try { kotlinx.coroutines.awaitCancellation() } finally { authCancelled = true }
                } else WalletKeyReader.Result.Success(key)
            }
            coEvery { repository.sendReviewed(any(), any(), any(), any()) } coAnswers {
                assertEquals("w1", arg<ReviewedTransfer>(0).walletId); assertEquals(NetworkType.TESTNET, arg<ReviewedTransfer>(0).network)
                calls++
                network.value = NetworkType.MAINNET
                if (phase == "broadcast") kotlinx.coroutines.awaitCancellation() else "submitted-before-switch"
            }
            val addresses = (1..26).map { index -> com.rjnr.pocketnode.data.wallet.AddressUtils.encode(
                Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "%040x".format(index)), NetworkType.TESTNET) }
            val vm = newViewModel(); advanceUntilIdle()
            vm.updateSendMode(SendMode.BULK); vm.updateBulkRecipients(addresses.joinToString("\n")); vm.updateAmount("100")
            vm.sendTransaction(); advanceUntilIdle(); vm.confirmSend(foregroundActivity); runCurrent()
            if (duringAuth) { network.value = NetworkType.MAINNET; runCurrent() }
            try {
                if (duringAuth) { assertTrue("Network change must cancel authentication", authCancelled); assertEquals(0, calls) }
                else {
                    assertEquals("No next batch on another network", 1, calls); assertTrue(key.all { it == 0.toByte() })
                    val record = vm.uiState.value.interruptedBulkSends.single()
                    assertEquals(NetworkType.TESTNET, record.network)
                    assertEquals("w1", record.walletId)
                    assertEquals(if (phase == "broadcast") 25 else 0, record.uncertainRecipients.size)
                }
            } finally { androidx.lifecycle.ViewModelStore().apply { put("network", vm); clear() }; runCurrent() }
        }
    }

    @Test fun `failed quarantine persistence prevents first or subsequent signing`() = runTest(testDispatcher) {
        for (failWrite in listOf(1, 2, 0)) {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            context.getSharedPreferences("bulk_send_quarantine", 0).edit().clear().commit()
            val store = io.mockk.spyk(com.rjnr.pocketnode.data.transaction.BulkSendQuarantineStore(context))
            var writes = 0; var calls = 0
            every { store.put(any()) } answers { writes++; if (writes == failWrite) error("public injected write failure") else callOriginal() }
            every { repository.balance } returns MutableStateFlow(BalanceResponse(senderAddress,
                "0x${100_000_00000000L.toString(16)}", "100000", "0x1"))
            coEvery { repository.getCells() } throws kotlinx.coroutines.CancellationException("skip public preview")
            val key = ByteArray(32) { 7 }
            coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Success(key)
            coEvery { repository.sendReviewed(any(), any(), any(), any()) } coAnswers { assertTrue(key.all { it == 7.toByte() }); calls++; "public-hash-$calls" }
            val addresses = (1..26).map { index -> com.rjnr.pocketnode.data.wallet.AddressUtils.encode(
                Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "%040x".format(index)), NetworkType.TESTNET) }
            val vm = newViewModel(store); advanceUntilIdle()
            vm.updateSendMode(SendMode.BULK); vm.updateBulkRecipients(addresses.joinToString("\n")); vm.updateAmount("100")
            vm.sendTransaction(); advanceUntilIdle(); vm.confirmSend(foregroundActivity); runCurrent()
            try {
                assertEquals(if (failWrite == 0) 2 else if (failWrite == 1) 0 else 1, calls)
                assertTrue(key.all { it == 0.toByte() })
                if (failWrite == 0) {
                    assertTrue(com.rjnr.pocketnode.data.transaction.BulkSendQuarantineStore(context).load().isEmpty())
                    vm.updateBulkRecipients(addresses.joinToString("\n")); vm.updateAmount("100")
                    vm.sendTransaction(); advanceUntilIdle(); assertNotNull(vm.uiState.value.reviewRequest)
                } else { vm.sendTransaction(); advanceUntilIdle(); assertNull(vm.uiState.value.reviewRequest) }
                if (failWrite == 2) assertEquals(addresses.take(25), com.rjnr.pocketnode.data.transaction.BulkSendQuarantineStore(context).load().single().uncertainRecipients)
            } finally { androidx.lifecycle.ViewModelStore().apply { put("writes", vm); clear() }; runCurrent() }
        }
    }

    @Test fun `corrupt history blocks bulk review and stop before first batch leaves no uncertain send`() = runTest(testDispatcher) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("bulk_send_quarantine", 0)
        val addresses = (1..26).map { index -> com.rjnr.pocketnode.data.wallet.AddressUtils.encode(
            Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "%040x".format(index)), NetworkType.TESTNET) }
        every { repository.balance } returns MutableStateFlow(BalanceResponse(senderAddress,
            "0x${100_000_00000000L.toString(16)}", "100000", "0x1"))
        coEvery { repository.getCells() } throws kotlinx.coroutines.CancellationException("skip public preview")
        for (corrupt in listOf(true, false)) {
            prefs.edit().clear().commit()
            if (corrupt) prefs.edit().putString("records", "unreadable-public-history").commit()
            val key = ByteArray(32) { 7 }
            coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } answers {
                host.stopResume(); WalletKeyReader.Result.Success(key)
            }
            val vm = newViewModel(); advanceUntilIdle()
            vm.updateSendMode(SendMode.BULK); vm.updateBulkRecipients(addresses.joinToString("\n")); vm.updateAmount("100")
            vm.sendTransaction(); advanceUntilIdle()
            try {
                if (corrupt) { assertNull(vm.uiState.value.reviewRequest); assertEquals("unreadable-public-history", prefs.getString("records", null)) }
                else {
                    assertNotNull(vm.uiState.value.reviewRequest); vm.confirmSend(foregroundActivity); runCurrent()
                    assertTrue(key.all { it == 0.toByte() })
                    assertTrue(com.rjnr.pocketnode.data.transaction.BulkSendQuarantineStore(context).load().isEmpty())
                }
                coVerify(exactly = 0) { repository.sendReviewed(any(), any(), any(), any()) }
            } finally { androidx.lifecycle.ViewModelStore().apply { put("before-first", vm); clear() }; runCurrent() }
        }
    }

    @Test fun `reconciliation reopens bulk only after checked removal and reload both succeed`() = runTest(testDispatcher) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = io.mockk.spyk(com.rjnr.pocketnode.data.transaction.BulkSendQuarantineStore(context))
        every { repository.balance } returns MutableStateFlow(BalanceResponse(senderAddress,
            "0x${100_000_00000000L.toString(16)}", "100000", "0x1"))
        coEvery { repository.getCells() } throws kotlinx.coroutines.CancellationException("skip public preview")
        coEvery { repository.sendReviewed(any(), any(), any(), any()) } throws IllegalStateException("public broadcast failure")
        val addresses = (1..26).map { index -> com.rjnr.pocketnode.data.wallet.AddressUtils.encode(
            Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "%040x".format(index)), NetworkType.TESTNET) }
        val vm = newViewModel(store); advanceUntilIdle()
        vm.updateSendMode(SendMode.BULK); vm.updateBulkRecipients(addresses.joinToString("\n")); vm.updateAmount("100")
        vm.sendTransaction(); advanceUntilIdle(); vm.confirmSend(foregroundActivity); runCurrent()
        try {
            val record = vm.uiState.value.interruptedBulkSends.single()
            vm.sendTransaction(); advanceUntilIdle(); assertNull(vm.uiState.value.reviewRequest)
            every { store.remove(any()) } throws IllegalStateException("public remove failure")
            vm.acknowledgeBulkReconciliation(record.id)
            vm.sendTransaction(); advanceUntilIdle(); assertNull(vm.uiState.value.reviewRequest)
            assertEquals(listOf(record), vm.uiState.value.interruptedBulkSends)
            every { store.remove(any()) } answers { callOriginal() }
            var loads = 0
            every { store.load() } answers { loads++; if (loads == 2) error("public reload failure") else callOriginal() }
            vm.acknowledgeBulkReconciliation(record.id)
            vm.sendTransaction(); advanceUntilIdle(); assertNull(vm.uiState.value.reviewRequest)
            assertEquals(listOf(record), vm.uiState.value.interruptedBulkSends)
            every { store.load() } answers { callOriginal() }
            vm.acknowledgeBulkReconciliation(record.id)
            vm.updateBulkRecipients(addresses.joinToString("\n")); vm.updateAmount("100")
            vm.sendTransaction(); advanceUntilIdle()
            assertNotNull("Successful explicit reconciliation must allow reviewed repayment", vm.uiState.value.reviewRequest)
            assertTrue(vm.uiState.value.interruptedBulkSends.isEmpty())
        } finally { androidx.lifecycle.ViewModelStore().apply { put("reconcile", vm); clear() }; runCurrent() }
    }

    // Real transaction assembly at the signing seam: equal fees cannot conceal changed outputs.
    @Test fun `auth edit cannot substitute same fee recipient output`() = runTest(testDispatcher) {
        val builder = TransactionBuilder(com.rjnr.pocketnode.data.validation.NetworkValidator())
        val from = com.rjnr.pocketnode.data.wallet.AddressUtils.encode(plannedTransfer.selectedCells.first().lock, NetworkType.TESTNET)
        val original = com.rjnr.pocketnode.data.wallet.AddressUtils.encode(Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "bb".repeat(20)), NetworkType.TESTNET)
        val replacement = com.rjnr.pocketnode.data.wallet.AddressUtils.encode(Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "cc".repeat(20)), NetworkType.TESTNET)
        every { repository.getCurrentAddress() } returns from
        coEvery { repository.previewTransfer(any(), any()) } answers { builder.planTransfer(arg(1), plannedTransfer.selectedCells) }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } coAnswers {
            gate.await(); WalletKeyReader.Result.Success(ByteArray(32) { 7 })
        }
        var signed: com.rjnr.pocketnode.data.gateway.models.Transaction? = null
        coEvery { repository.sendReviewed(any(), any(), any(), any()) } coAnswers {
            val intent = arg<ReviewedTransfer>(0)
            signed = builder.buildTransfer(intent.fromAddress, intent.recipients.single().address, intent.amountShannons, plannedTransfer.selectedCells,
                object : com.rjnr.pocketnode.data.transaction.Signer {
                    override fun signRecoverable(messageHash: ByteArray) = ByteArray(65)
                }, NetworkType.TESTNET)
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = newViewModel(); runCurrent()
        try {
            vm.updateRecipient(original); vm.updateAmount("100"); vm.sendTransaction(); runCurrent()
            assertEquals(1000L, vm.uiState.value.reviewRequest!!.feeShannons)
            vm.confirmSend(foregroundActivity); runCurrent()
            vm.updateRecipient(replacement); vm.updateAmount("150"); gate.complete(Unit); runCurrent()
            signed?.let {
                assertEquals("same fee must never authorize another script", "0x" + "bb".repeat(20), it.cellOutputs.first().lock.args)
                assertEquals(100_00000000L, it.cellOutputs.first().capacity.removePrefix("0x").toLong(16))
            }
        } finally { androidx.lifecycle.ViewModelStore().apply { put("race", vm); clear() }; runCurrent() }
    }

    @Test fun `late non cooperative MAX cannot replace manual reviewed amount`() = runTest(testDispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.getCells() } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            Result.success(com.rjnr.pocketnode.data.gateway.models.CellsResponse(plannedTransfer.selectedCells, null))
        }
        every { transactionBuilder.calculateMaxSendable(any()) } returns 279_99999000L
        val vm = newViewModel(); runCurrent()
        try {
            vm.setMaxAmount(); runCurrent()
            vm.fillForm(); vm.sendTransaction(); runCurrent()
            assertEquals(100_00000000L, vm.uiState.value.reviewRequest!!.amountShannons)
            gate.complete(Unit); runCurrent()
            assertEquals("100", vm.uiState.value.amountCkb)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("max", vm); clear() }; runCurrent() }
    }

    @Test fun `MAX quotes only observed empty cells at its captured source lock`() = runTest(testDispatcher) {
        transactionBuilder = TransactionBuilder(com.rjnr.pocketnode.data.validation.NetworkValidator())
        val base = plannedTransfer.selectedCells.first()
        val protected = listOf(base.copy(data = "0x01"), base.copy(data = "0x00"), base.copy(data = ""),
            base.copy(type = base.lock), base.copy(lock = base.lock.copy(args = "0x" + "bb".repeat(20))))
        coEvery { repository.getCells() } coAnswers {
            // Force the suspend resume path so MockK does not double-box inline Result.
            kotlinx.coroutines.yield()
            Result.success(com.rjnr.pocketnode.data.gateway.models.CellsResponse(plannedTransfer.selectedCells + protected, null))
        }
        val vm = newViewModel(); runCurrent()
        try {
            vm.setMaxAmount(); runCurrent()
            assertNull("MAX read must succeed: ${vm.uiState.value.error}", vm.uiState.value.error)
            // Four empty 70 CKB cells minus the 1000-shannon fee, with exact integer accounting.
            assertEquals("279.99999", vm.uiState.value.amountCkb)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("plainMax", vm); clear() }; runCurrent() }
    }

    @Test fun `failed MAX preserves amount and reports unresolved cells`() = runTest(testDispatcher) {
        coEvery { repository.getCells() } returns Result.failure(IllegalStateException("Cell data unavailable"))
        val vm = newViewModel(); runCurrent()
        try {
            vm.updateAmount("123")
            vm.setMaxAmount(); runCurrent()
            assertEquals("123", vm.uiState.value.amountCkb)
            assertNotNull(vm.uiState.value.error)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("maxError", vm); clear() }; runCurrent() }
    }

    @Test fun `late failed MAX cannot replace new draft or publish error`() = runTest(testDispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.getCells() } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            Result.failure(IllegalStateException("Cell data unavailable"))
        }
        val vm = newViewModel(); runCurrent()
        try {
            vm.setMaxAmount(); runCurrent()
            vm.updateAmount("123")
            gate.complete(Unit); runCurrent()
            assertEquals("123", vm.uiState.value.amountCkb)
            assertNull(vm.uiState.value.error)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("lateMaxError", vm); clear() }; runCurrent() }
    }

    /** Only the transport/storage are doubles: real ledger, planner, review validator and signing serializer. */
    private fun recordingPipeline(scope: kotlinx.coroutines.CoroutineScope, signed: MutableList<com.rjnr.pocketnode.data.gateway.models.Transaction>): com.rjnr.pocketnode.data.send.SendPipeline {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val api = mockk<com.rjnr.pocketnode.data.gateway.LightClientApi>(relaxed = true)
        every { api.getTipHeader() } returns null
        every { api.getTransactions(any(), any(), any(), any()) } returns "{\"objects\":[],\"last_cursor\":\"\"}"
        val page = com.rjnr.pocketnode.data.gateway.models.JniPagination(
            plannedTransfer.selectedCells.map { com.rjnr.pocketnode.data.gateway.models.JniCell(
                com.rjnr.pocketnode.data.gateway.models.CellOutput(it.capacity, it.lock), "0x", it.outPoint, it.blockNumber, "0x0") }, "")
        every { api.getCells(any(), any(), any(), any()) } returns json.encodeToString(com.rjnr.pocketnode.data.gateway.models.JniPagination.serializer(com.rjnr.pocketnode.data.gateway.models.JniCell.serializer()), page)
        every { api.sendTransaction(any()) } answers {
            signed += json.decodeFromString<com.rjnr.pocketnode.data.gateway.models.Transaction>(arg(0))
            throw kotlinx.coroutines.CancellationException("fixture stops after observing exact signed broadcast bytes")
        }
        val ledger = com.rjnr.pocketnode.data.gateway.LedgerReader(api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), uiPreferences, json, NoopLogger)
        // Exercise the real repository getCells body without starting its node-owning constructor.
        GatewayRepository::class.java.getDeclaredField("ledgerReader").apply { isAccessible = true }.set(repository, ledger)
        GatewayRepository::class.java.getDeclaredField("_walletInfo").apply { isAccessible = true }.set(repository,
            MutableStateFlow(com.rjnr.pocketnode.data.wallet.WalletInfo("", plannedTransfer.selectedCells.first().lock, senderAddress,
                com.rjnr.pocketnode.data.wallet.AddressUtils.encode(plannedTransfer.selectedCells.first().lock, NetworkType.MAINNET))))
        coEvery { repository.getCells() } coAnswers {
            kotlinx.coroutines.yield()
            // MockK's generic callOriginal boxes inline Result incorrectly; the raw invocation
            // returns the real body's success payload, boxed exactly once here at the seam.
            Result.success(invocation.originalCall() as com.rjnr.pocketnode.data.gateway.models.CellsResponse)
        }
        val pending = mockk<com.rjnr.pocketnode.data.storage.PendingBroadcastStore>(relaxed = true)
        coEvery { pending.getActive(any(), any()) } returns emptyList()
        val pipeline = com.rjnr.pocketnode.data.send.SendPipeline(api,
            TransactionBuilder(com.rjnr.pocketnode.data.validation.NetworkValidator()), ledger, pending,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), uiPreferences, json, NoopLogger, queryContext = testDispatcher)
        coEvery { repository.previewTransfer(any(), any()) } coAnswers {
            pipeline.previewTransfer(com.rjnr.pocketnode.data.send.SendContext(NetworkType.TESTNET, "w1", plannedTransfer.selectedCells.first().lock, { false }, scope), arg(0), arg(1))
        }
        coEvery { repository.sendReviewed(any(), any(), any(), any()) } coAnswers {
            pipeline.sendReviewed(com.rjnr.pocketnode.data.send.SendContext(NetworkType.TESTNET, "w1", plannedTransfer.selectedCells.first().lock, { false }, scope),
                arg(0), object : com.rjnr.pocketnode.data.transaction.Signer {
                    override fun signRecoverable(messageHash: ByteArray) = ByteArray(65)
                }, arg(2), arg(3))
        }
        return pipeline
    }

    @Test fun `late MAX followed by double confirm signs exactly the reviewed payment`() = runTest(testDispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        transactionBuilder = TransactionBuilder(com.rjnr.pocketnode.data.validation.NetworkValidator())
        val signed = mutableListOf<com.rjnr.pocketnode.data.gateway.models.Transaction>()
        recordingPipeline(backgroundScope, signed)
        coEvery { repository.getCells() } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            Result.success(invocation.originalCall() as com.rjnr.pocketnode.data.gateway.models.CellsResponse)
        }
        val vm = newViewModel(); runCurrent()
        try {
            vm.setMaxAmount(); runCurrent(); vm.fillForm(); vm.sendTransaction(); runCurrent()
            assertEquals(1000L, vm.uiState.value.reviewRequest!!.feeShannons)
            gate.complete(Unit); runCurrent()
            vm.confirmSend(foregroundActivity); vm.confirmSend(foregroundActivity); runCurrent()
            assertEquals(1, signed.size)
            assertEquals("0x" + "bb".repeat(20), signed.single().cellOutputs.first().lock.args)
            assertEquals(100_00000000L, signed.single().cellOutputs.first().capacity.removePrefix("0x").toLong(16))
            assertEquals(1000L, 210_00000000L - signed.single().cellOutputs.sumOf { it.capacity.removePrefix("0x").toLong(16) })
        } finally { androidx.lifecycle.ViewModelStore().apply { put("integration", vm); clear() }; runCurrent() }
    }

    @Test fun `stale preview after edit cancel or newer review is discarded`() = runTest(testDispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        coEvery { repository.previewTransfer(any(), any()) } coAnswers {
            calls++
            if (calls == 1) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            plannedTransfer
        }
        val vm = newViewModel(); runCurrent()
        try {
            vm.fillForm(); vm.sendTransaction(); runCurrent()
            vm.updateAmount("150"); vm.sendTransaction(); runCurrent()
            assertEquals(150_00000000L, vm.uiState.value.reviewRequest!!.amountShannons)
            gate.complete(Unit); runCurrent()
            assertEquals(150_00000000L, vm.uiState.value.reviewRequest!!.amountShannons)
            vm.cancelReview(); vm.confirmSend(foregroundActivity); runCurrent()
            assertNull(vm.uiState.value.reviewRequest)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("preview", vm); clear() }; runCurrent() }
    }

    @Test fun `wallet network amount and bulk edits during auth abort and wipe before signing`() = runTest(testDispatcher) {
        for (edit in listOf("wallet", "network", "amount", "bulk")) {
            val signed = mutableListOf<com.rjnr.pocketnode.data.gateway.models.Transaction>()
            recordingPipeline(backgroundScope, signed)
            var wallet = "w1"
            var network = NetworkType.TESTNET
            every { walletRepository.activeWalletIdSnapshot() } answers { wallet }
            every { repository.currentNetwork } answers { network }
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val key = ByteArray(32) { 7 }
            coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } coAnswers {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                WalletKeyReader.Result.Success(key)
            }
            val vm = newViewModel(); runCurrent()
            try {
                vm.fillForm()
                if (edit == "bulk") { vm.updateSendMode(SendMode.BULK); vm.updateBulkRecipients(recipient) }
                vm.sendTransaction(); runCurrent(); assertNotNull(vm.uiState.value.reviewRequest)
                vm.confirmSend(foregroundActivity); runCurrent()
                when (edit) {
                    "wallet" -> wallet = "other"
                    "network" -> network = NetworkType.MAINNET
                    "amount" -> vm.updateAmount("150")
                    "bulk" -> vm.updateBulkRecipients(senderAddress)
                }
                gate.complete(Unit); runCurrent()
                assertTrue("$edit must not sign", signed.isEmpty())
                assertTrue("$edit must wipe returned key", key.all { it == 0.toByte() })
            } finally { androidx.lifecycle.ViewModelStore().apply { put("auth-$edit", vm); clear() }; runCurrent() }
        }
    }

    @Test fun `cancelled auth requires fresh review and retry signs only once`() = runTest(testDispatcher) {
        val signed = mutableListOf<com.rjnr.pocketnode.data.gateway.models.Transaction>()
        recordingPipeline(backgroundScope, signed)
        coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Cancelled
        val vm = newViewModel(); runCurrent()
        try {
            vm.fillForm(); vm.sendTransaction(); runCurrent(); vm.confirmSend(foregroundActivity); runCurrent()
            vm.confirmSend(foregroundActivity); runCurrent(); assertTrue(signed.isEmpty())
            coEvery { walletKeyReader.readPrivateKey(any(), any(), any(), any()) } returns WalletKeyReader.Result.Success(ByteArray(32) { 7 })
            vm.sendTransaction(); runCurrent(); vm.confirmSend(foregroundActivity); vm.confirmSend(foregroundActivity); runCurrent()
            assertEquals(1, signed.size)
            assertEquals(100_00000000L, signed.single().cellOutputs.first().capacity.removePrefix("0x").toLong(16))
        } finally { androidx.lifecycle.ViewModelStore().apply { put("retry", vm); clear() }; runCurrent() }
    }

    private val evidenceHash = "0x" + "ab".repeat(32)
    private fun evidenceStatus(status: String, hash: String = evidenceHash, block: String? = null, confirmations: Int = 0) =
        com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse(hash, status, confirmations, blockHash = block)

    /** MockK double-boxes inline Result on this delegated suspend method. Return its
     * underlying JVM payload at the external repository seam, not a boxed Result. */
    @Suppress("UNCHECKED_CAST")
    private fun statusReads(answer: suspend () -> com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse) {
        val raw = coEvery { repository.getTransactionStatus(evidenceHash) } as io.mockk.MockKStubScope<Any?, Any?>
        raw.coAnswers { answer() }
    }

    private suspend fun broadcastForStatusTest(): SendViewModel {
        coEvery { repository.getCells() } throws kotlinx.coroutines.CancellationException("skip unrelated preview")
        coEvery { repository.sendReviewed(any(), any(), any(), any()) } returns evidenceHash
        val vm = newViewModel()
        testDispatcher.scheduler.runCurrent()
        vm.fillForm(); vm.sendTransaction()
        testDispatcher.scheduler.runCurrent()
        vm.confirmSend(foregroundActivity)
        testDispatcher.scheduler.runCurrent()
        assertEquals(evidenceHash, vm.uiState.value.txHash)
        return vm
    }

    private fun disposeStatusTest(vm: SendViewModel) {
        androidx.lifecycle.ViewModelStore().apply { put("status", vm); clear() }
    }

    @Test fun `status more than twenty unknowns never confirms despite balance movement`() = runTest(testDispatcher) {
        statusReads { evidenceStatus("unknown") }
        coEvery { repository.refreshBalance(any()) } returns Result.success(BalanceResponse(senderAddress, "0x1", "0.00000001", "0x2"))
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(25 * 3000L + 1)
            assertEquals(TransactionState.PENDING, vm.uiState.value.transactionState)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            assertEquals(0, vm.uiState.value.confirmations)
            coVerify(exactly = 25) { repository.getTransactionStatus(evidenceHash) }
            coVerify(exactly = 1) { repository.sendReviewed(any(), any(), any(), any()) }
        } finally { disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status timeout after fifteen unknowns preserves hash without declaring failure`() = runTest(testDispatcher) {
        var reads = 0
        statusReads {
            reads++; evidenceStatus(if (reads <= 105) "pending" else "unknown")
        }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(123 * 3000L)
            assertEquals("TIMED_OUT", vm.uiState.value.transactionState.name)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            assertEquals(0, vm.uiState.value.confirmations)
            coVerify(exactly = 120) { repository.getTransactionStatus(evidenceHash) }
        } finally { disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status unavailable then delayed inclusion alone confirms submitted hash`() = runTest(testDispatcher) {
        var reads = 0
        statusReads {
            reads++
            when {
                reads <= 2 -> throw IllegalStateException("node unavailable")
                reads <= 27 -> evidenceStatus("unknown")
                else -> evidenceStatus("committed", block = "0x" + "bb".repeat(32), confirmations = 3)
            }
        }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(27 * 3000L + 1)
            assertEquals(TransactionState.PENDING, vm.uiState.value.transactionState)
            advanceTimeBy(3000L)
            assertEquals(TransactionState.CONFIRMED, vm.uiState.value.transactionState)
            assertEquals(3, vm.uiState.value.confirmations)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            coVerify(exactly = 1) { repository.sendReviewed(any(), any(), any(), any()) }
        } finally { disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status wrong hash or missing inclusion cannot confirm`() = runTest(testDispatcher) {
        var reads = 0
        statusReads {
            reads++
            if (reads == 1) evidenceStatus("committed", hash = "0xother", block = "0x" + "bb".repeat(32), confirmations = 3)
            else evidenceStatus("committed", confirmations = 3)
        }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(121 * 3000L)
            assertEquals("TIMED_OUT", vm.uiState.value.transactionState.name)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            assertEquals(0, vm.uiState.value.confirmations)
        } finally { disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status explicit rejection is terminal and retains original hash`() = runTest(testDispatcher) {
        statusReads { evidenceStatus("rejected") }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(125 * 3000L)
            assertEquals(TransactionState.FAILED, vm.uiState.value.transactionState)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            assertEquals(0, vm.uiState.value.confirmations)
            coVerify(exactly = 1) { repository.getTransactionStatus(evidenceHash) }
        } finally { disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status cancelled read cannot overwrite cleared state or cause another broadcast`() = runTest(testDispatcher) {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        statusReads {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(3001L)
            assertTrue(entered.isCompleted)
            vm.clearTxHash(); runCurrent()
            advanceTimeBy(125 * 3000L)
            assertEquals(TransactionState.IDLE, vm.uiState.value.transactionState)
            assertNull(vm.uiState.value.txHash)
            coVerify(exactly = 1) { repository.getTransactionStatus(evidenceHash) }
            coVerify(exactly = 1) { repository.sendReviewed(any(), any(), any(), any()) }
        } finally { disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status retry resumes unavailable hash without signing or broadcasting twice`() = runTest(testDispatcher) {
        var available = false
        statusReads {
            if (!available) throw IllegalStateException("bridge unavailable")
            evidenceStatus("committed", block = "0x" + "bb".repeat(32), confirmations = 3)
        }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(121 * 3000L)
            assertEquals("TIMED_OUT", vm.uiState.value.transactionState.name)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            available = true
            vm.retryStatusCheck(); vm.retryStatusCheck(); runCurrent()
            advanceTimeBy(3001L)
            assertEquals(TransactionState.CONFIRMED, vm.uiState.value.transactionState)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            assertEquals(3, vm.uiState.value.confirmations)
            coVerify(exactly = 121) { repository.getTransactionStatus(evidenceHash) }
            coVerify(exactly = 1) { repository.sendReviewed(any(), any(), any(), any()) }
            coVerify(exactly = 1) { walletKeyReader.readPrivateKey(any(), any(), any(), any()) }
        } finally { disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status retry refuses a different wallet without new reads or broadcast`() = runTest(testDispatcher) {
        statusReads { evidenceStatus("unknown") }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(121 * 3000L)
            assertEquals("TIMED_OUT", vm.uiState.value.transactionState.name)
            every { walletRepository.activeWalletIdSnapshot() } returns "other-wallet"
            vm.retryStatusCheck(); runCurrent(); advanceTimeBy(6001L)
            assertEquals("TIMED_OUT", vm.uiState.value.transactionState.name)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            coVerify(exactly = 120) { repository.getTransactionStatus(evidenceHash) }
            coVerify(exactly = 1) { repository.sendReviewed(any(), any(), any(), any()) }
        } finally { disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status late inclusion read cannot overwrite cancellation`() = runTest(testDispatcher) {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        statusReads {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                release.await()
                evidenceStatus("committed", block = "0x" + "bb".repeat(32), confirmations = 1)
            }
        }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(3001L)
            vm.clearTxHash(); release.complete(Unit); runCurrent()
            assertEquals(TransactionState.IDLE, vm.uiState.value.transactionState)
            assertEquals(0, vm.uiState.value.confirmations)
            assertEquals("", vm.uiState.value.statusMessage)
            assertNull(vm.uiState.value.txHash)
            coVerify(exactly = 1) { repository.getTransactionStatus(evidenceHash) }
        } finally { release.complete(Unit); disposeStatusTest(vm); runCurrent() }
    }

    @Test fun `status late balance read cannot rewrite cleared progress`() = runTest(testDispatcher) {
        statusReads { evidenceStatus("committed", block = "0x" + "bb".repeat(32), confirmations = 3) }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.refreshBalance(any()) } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                release.await()
                Result.success(BalanceResponse(senderAddress, "0x1", "0.00000001", "0x2"))
            }
        }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(6001L)
            vm.clearTxHash(); release.complete(Unit); runCurrent()
            assertEquals(TransactionState.IDLE, vm.uiState.value.transactionState)
            assertEquals("", vm.uiState.value.statusMessage)
            assertNull(vm.uiState.value.txHash)
        } finally { release.complete(Unit); disposeStatusTest(vm); runCurrent() }
    }

    private suspend fun retryRefusesChangedSource(change: () -> Unit) {
        statusReads { evidenceStatus("unknown") }
        val vm = broadcastForStatusTest()
        try {
            testDispatcher.scheduler.advanceTimeBy(121 * 3000L)
            assertEquals("TIMED_OUT", vm.uiState.value.transactionState.name)
            change()
            vm.retryStatusCheck(); testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceTimeBy(6001L)
            assertEquals("TIMED_OUT", vm.uiState.value.transactionState.name)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            coVerify(exactly = 120) { repository.getTransactionStatus(evidenceHash) }
            coVerify(exactly = 1) { repository.sendReviewed(any(), any(), any(), any()) }
        } finally { disposeStatusTest(vm); testDispatcher.scheduler.runCurrent() }
    }

    @Test fun `status retry refuses changed native network before UI catches up`() = runTest(testDispatcher) {
        retryRefusesChangedSource { every { repository.currentNetwork } returns NetworkType.MAINNET }
    }

    @Test fun `status retry refuses changed sender on the same wallet`() = runTest(testDispatcher) {
        retryRefusesChangedSource { every { repository.getCurrentAddress() } returns recipient }
    }

    @Test fun `status bridge cancellation retains pending hash and stops further reads`() = runTest(testDispatcher) {
        statusReads { throw kotlinx.coroutines.CancellationException("stop status check") }
        val vm = broadcastForStatusTest()
        try {
            advanceTimeBy(125 * 3000L)
            assertEquals(TransactionState.PENDING, vm.uiState.value.transactionState)
            assertEquals(evidenceHash, vm.uiState.value.txHash)
            assertEquals(0, vm.uiState.value.confirmations)
            coVerify(exactly = 1) { repository.getTransactionStatus(evidenceHash) }
            coVerify(exactly = 1) { repository.sendReviewed(any(), any(), any(), any()) }
        } finally { disposeStatusTest(vm); runCurrent() }
    }

}
