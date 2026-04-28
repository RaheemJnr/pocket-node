package com.rjnr.pocketnode.ui.screens.dao

import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.wallet.WalletInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DaoViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: GatewayRepository
    private lateinit var authManager: AuthManager
    private lateinit var pinManager: PinManager

    private val testOutPoint = OutPoint("0x" + "ab".repeat(32), "0x0")
    private val otherOutPoint = OutPoint("0x" + "cd".repeat(32), "0x0")

    private fun makeDaoDeposit(
        outPoint: OutPoint = testOutPoint,
        status: DaoCellStatus = DaoCellStatus.DEPOSITED
    ) = DaoDeposit(
        outPoint = outPoint,
        capacity = 10_200_000_000L,
        status = status,
        depositBlockNumber = 100L,
        depositBlockHash = "0x" + "aa".repeat(32),
        depositEpoch = EpochInfo(100, 0, 1800),
        withdrawBlockHash = "0x" + "bb".repeat(32)
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true) {
            every { balance } returns MutableStateFlow<BalanceResponse?>(null)
            every { network } returns MutableStateFlow(NetworkType.TESTNET)
            every { walletInfo } returns MutableStateFlow<WalletInfo?>(null)
        }
        coEvery { repository.getDaoDeposits() } returns Result.success<List<DaoDeposit>>(emptyList())
        authManager = mockk(relaxed = true) {
            every { isAuthBeforeSendEnabled() } returns false
        }
        pinManager = mockk(relaxed = true) {
            every { hasPin() } returns false
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Synchronous ViewModel operations ---

    @Test
    fun `selectTab changes tab to COMPLETED`() {
        val vm = DaoViewModel(repository, authManager, pinManager)
        vm.selectTab(DaoTab.COMPLETED)
        assertEquals(DaoTab.COMPLETED, vm.uiState.value.selectedTab)
    }

    @Test
    fun `selectTab changes tab back to ACTIVE`() {
        val vm = DaoViewModel(repository, authManager, pinManager)
        vm.selectTab(DaoTab.COMPLETED)
        vm.selectTab(DaoTab.ACTIVE)
        assertEquals(DaoTab.ACTIVE, vm.uiState.value.selectedTab)
    }

    @Test
    fun `clearError sets error to null`() {
        val vm = DaoViewModel(repository, authManager, pinManager)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `deposit sets pending action to Depositing`() {
        val vm = DaoViewModel(repository, authManager, pinManager)
        val amount = 10_200_000_000L
        vm.deposit(amount)
        assertEquals(DaoAction.Depositing(amount), vm.uiState.value.pendingAction)
    }

    @Test
    fun `withdraw sets pending action to Withdrawing`() {
        val vm = DaoViewModel(repository, authManager, pinManager)
        val deposit = makeDaoDeposit()
        vm.withdraw(deposit)
        assertEquals(DaoAction.Withdrawing(deposit.outPoint), vm.uiState.value.pendingAction)
    }

    @Test
    fun `unlock sets pending action to Unlocking`() {
        val vm = DaoViewModel(repository, authManager, pinManager)
        val deposit = makeDaoDeposit()
        vm.unlock(deposit)
        assertEquals(DaoAction.Unlocking(deposit.outPoint), vm.uiState.value.pendingAction)
    }

    // --- shouldClearPendingAction (pure function) ---

    @Test
    fun `shouldClear Depositing when DEPOSITED exists with matching amount`() {
        val deposits = listOf(makeDaoDeposit(status = DaoCellStatus.DEPOSITED))
        assertTrue(shouldClearPendingAction(DaoAction.Depositing(10_200_000_000L), deposits))
    }

    @Test
    fun `shouldClear Depositing false when DEPOSITED exists but amount differs`() {
        val deposits = listOf(makeDaoDeposit(status = DaoCellStatus.DEPOSITED))
        assertFalse(shouldClearPendingAction(DaoAction.Depositing(999L), deposits))
    }

    @Test
    fun `shouldClear Depositing false when no DEPOSITED`() {
        val deposits = listOf(makeDaoDeposit(status = DaoCellStatus.DEPOSITING))
        assertFalse(shouldClearPendingAction(DaoAction.Depositing(10_200_000_000L), deposits))
    }

    @Test
    fun `shouldClear Depositing false when empty deposits`() {
        assertFalse(shouldClearPendingAction(DaoAction.Depositing(10_200_000_000L), emptyList()))
    }

    @Test
    fun `shouldClear Withdrawing when matching outPoint is LOCKED`() {
        val deposits = listOf(makeDaoDeposit(outPoint = testOutPoint, status = DaoCellStatus.LOCKED))
        assertTrue(shouldClearPendingAction(DaoAction.Withdrawing(testOutPoint), deposits))
    }

    @Test
    fun `shouldClear Withdrawing when matching outPoint is UNLOCKABLE`() {
        val deposits = listOf(makeDaoDeposit(outPoint = testOutPoint, status = DaoCellStatus.UNLOCKABLE))
        assertTrue(shouldClearPendingAction(DaoAction.Withdrawing(testOutPoint), deposits))
    }

    @Test
    fun `shouldClear Withdrawing false when outPoint still DEPOSITED`() {
        val deposits = listOf(makeDaoDeposit(outPoint = testOutPoint, status = DaoCellStatus.DEPOSITED))
        assertFalse(shouldClearPendingAction(DaoAction.Withdrawing(testOutPoint), deposits))
    }

    @Test
    fun `shouldClear Withdrawing true when original outPoint is no longer in deposits list`() {
        // Phase 1 confirms = original deposit cell consumed = its outPoint
        // disappears from live cells. The new withdrawing cell appears with a
        // different outPoint (here represented by `otherOutPoint`, LOCKED).
        // Original behavior treated this as "don't clear" because the strict
        // outPoint+LOCKED match never hit, leaving the spinner stuck. The
        // corrected semantic clears when the tracked outPoint is no longer
        // DEPOSITED — which is true here (it's not in the list at all).
        val deposits = listOf(makeDaoDeposit(outPoint = otherOutPoint, status = DaoCellStatus.LOCKED))
        assertTrue(shouldClearPendingAction(DaoAction.Withdrawing(testOutPoint), deposits))
    }

    @Test
    fun `shouldClear Unlocking when outPoint no longer in deposits`() {
        val deposits = listOf(makeDaoDeposit(outPoint = otherOutPoint))
        assertTrue(shouldClearPendingAction(DaoAction.Unlocking(testOutPoint), deposits))
    }

    @Test
    fun `shouldClear Unlocking true when deposits empty`() {
        assertTrue(shouldClearPendingAction(DaoAction.Unlocking(testOutPoint), emptyList()))
    }

    @Test
    fun `shouldClear Unlocking false when outPoint still present`() {
        val deposits = listOf(makeDaoDeposit(outPoint = testOutPoint))
        assertFalse(shouldClearPendingAction(DaoAction.Unlocking(testOutPoint), deposits))
    }
}
