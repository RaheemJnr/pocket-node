package com.rjnr.pocketnode.ui.screens.dao

import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.util.UiMessage
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
    private lateinit var walletKeyReader: WalletKeyReader
    private lateinit var walletRepository: WalletRepository

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
        walletKeyReader = mockk(relaxed = true)
        walletRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Synchronous ViewModel operations ---

    @Test
    fun `selectTab changes tab to COMPLETED`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)
        vm.selectTab(DaoTab.COMPLETED)
        assertEquals(DaoTab.COMPLETED, vm.uiState.value.selectedTab)
    }

    @Test
    fun `selectTab changes tab back to ACTIVE`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)
        vm.selectTab(DaoTab.COMPLETED)
        vm.selectTab(DaoTab.ACTIVE)
        assertEquals(DaoTab.ACTIVE, vm.uiState.value.selectedTab)
    }

    @Test
    fun `clearError sets error to null`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)
        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `deposit sets pending action to Depositing`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)
        val amount = 10_200_000_000L
        vm.deposit(amount)
        assertEquals(DaoAction.Depositing(amount), vm.uiState.value.pendingAction)
    }

    @Test
    fun `withdraw sets pending action to Withdrawing`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)
        val deposit = makeDaoDeposit()
        vm.withdraw(deposit)
        assertEquals(DaoAction.Withdrawing(deposit.outPoint), vm.uiState.value.pendingAction)
    }

    @Test
    fun `unlock sets pending action to Unlocking`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)
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

    // --- #529: double unlock, spinner clearing, rescan prompt ---
    //
    // NOTE: the remaining half of the spinner contract, "a failed unlockDao
    // clears pendingAction and surfaces the message", cannot be expressed
    // here. `GatewayRepository.unlockDao` returns `kotlin.Result<String>`, and
    // MockK 1.13.16 cannot round-trip a stubbed inline-value-class return
    // through the suspend continuation resume boundary (the same limitation
    // documented in AddWalletViewModelTest): the stub comes back as a boxed
    // `kotlin.Result` and the call site throws ClassCastException before the
    // ViewModel sees it. Any test here that drives the launched coroutine hits
    // it, relaxed stubs included. The failure itself is pinned one layer down
    // in DaoGatewayUnlockTest, which asserts the terminal Result.failure and
    // its message; the `onFailure { pendingAction = null }` clause it feeds is
    // the same one every other DAO operation already uses.

    @Test
    fun `shouldClear Unlocking when the outPoint survives the spend as COMPLETED`() {
        // COMPLETED is a terminal state for the position, whether or not the
        // retired row is surfaced. Insisting on total absence is what left
        // the spinner running forever.
        val deposits = listOf(makeDaoDeposit(outPoint = testOutPoint, status = DaoCellStatus.COMPLETED))
        assertTrue(shouldClearPendingAction(DaoAction.Unlocking(testOutPoint), deposits))
    }

    @Test
    fun `unlock is refused while another action is already in flight for that outPoint`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)

        vm.withdraw(makeDaoDeposit(status = DaoCellStatus.DEPOSITED))
        assertEquals(DaoAction.Withdrawing(testOutPoint), vm.uiState.value.pendingAction)

        // Same position, second operation: must not replace the in-flight one.
        vm.unlock(makeDaoDeposit(status = DaoCellStatus.UNLOCKABLE))

        assertEquals(DaoAction.Withdrawing(testOutPoint), vm.uiState.value.pendingAction)
    }

    @Test
    fun `unlock is refused for a deposit already overlaid as UNLOCKING`() {
        // After a relaunch mid-unlock the in-memory pending action is gone;
        // the persisted marker's UNLOCKING overlay is the only guard left.
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)

        vm.unlock(makeDaoDeposit(status = DaoCellStatus.UNLOCKING))

        assertNull(vm.uiState.value.pendingAction)
    }

    @Test
    fun `a failure on one deposit leaves another deposit's spinner alone`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)

        // One position is genuinely unlocking.
        vm.unlock(makeDaoDeposit(outPoint = testOutPoint, status = DaoCellStatus.UNLOCKABLE))
        assertEquals(DaoAction.Unlocking(testOutPoint), vm.uiState.value.pendingAction)

        // A failure reported against a DIFFERENT outpoint must not stop it.
        vm.failAction(otherOutPoint, "This deposit was already unlocked")

        assertEquals(DaoAction.Unlocking(testOutPoint), vm.uiState.value.pendingAction)
        assertEquals(UiMessage.Raw("This deposit was already unlocked"), vm.uiState.value.error)
    }

    @Test
    fun `a failure on the deposit that is in flight clears its spinner`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)

        vm.unlock(makeDaoDeposit(outPoint = testOutPoint, status = DaoCellStatus.UNLOCKABLE))
        vm.failAction(testOutPoint, "This deposit was already unlocked")

        assertNull(vm.uiState.value.pendingAction)
        assertEquals(UiMessage.Raw("This deposit was already unlocked"), vm.uiState.value.error)
    }

    @Test
    fun `withdraw is refused for a deposit already overlaid as WITHDRAWING`() {
        val vm = DaoViewModel(repository, authManager, pinManager, walletKeyReader, walletRepository)

        vm.withdraw(makeDaoDeposit(status = DaoCellStatus.WITHDRAWING))

        assertNull(vm.uiState.value.pendingAction)
    }

    // --- outsideWindowPromptCount (pure function) ---

    @Test
    fun `rescan prompt ignores a retired outside-window deposit`() {
        val deposits = listOf(
            makeDaoDeposit(status = DaoCellStatus.COMPLETED).copy(outsideSyncWindow = true)
        )
        assertEquals(0, outsideWindowPromptCount(deposits))
    }

    @Test
    fun `rescan prompt still fires for a live outside-window deposit`() {
        val deposits = listOf(
            makeDaoDeposit(status = DaoCellStatus.DEPOSITED).copy(outsideSyncWindow = true)
        )
        assertEquals(1, outsideWindowPromptCount(deposits))
    }

    @Test
    fun `rescan prompt ignores deposits the light client can see`() {
        val deposits = listOf(makeDaoDeposit(status = DaoCellStatus.DEPOSITED))
        assertEquals(0, outsideWindowPromptCount(deposits))
    }
}
