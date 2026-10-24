package com.rjnr.pocketnode.ui.screens.recovery

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
import com.rjnr.pocketnode.data.vault.VaultReadPreference
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val operations = mockk<RecoveryOperations>(relaxed = true)
    private val activity = mockk<FragmentActivity>()
    private val wallets = mutableListOf(MissingWallet("a", "Savings", false), MissingWallet("b", "Other", true))
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { operations.wallets() } answers { wallets.toList() }
        coEvery { operations.complete() } answers { wallets.isEmpty() }
    }
    @After fun close() { Dispatchers.resetMain() }
    @Test fun `external recovery remains available without current dashboard PIN`() = runTest {
        val vm = RecoveryViewModel(operations); advanceUntilIdle()
        assertEquals(listOf("a", "b"), vm.uiState.value.missingWallets.map { it.walletId })
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
    }
    @Test fun `partial success retains other wallets and reports authenticated outcomes`() = runTest {
        val vm = RecoveryViewModel(operations); advanceUntilIdle()
        coEvery { operations.backup(activity, "a") } answers { wallets.removeAt(0); RecoveredKeyRestorer.Outcome.Restored }
        vm.restoreBackup(activity, "a"); advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.restoredWalletCount)
        assertEquals(listOf("b"), vm.uiState.value.missingWallets.map { it.walletId })
        assertTrue(vm.uiState.value.outcomes.getValue("a").contains("verified"))
    }
    @Test fun `all authenticated wallets complete but empty installation does not assume success`() = runTest {
        val vm = RecoveryViewModel(operations); advanceUntilIdle()
        coEvery { operations.external(activity, "a", false) } answers {
            wallets.clear()
            RecoveredKeyRestorer.RekeyResult.Done(mapOf("a" to RecoveredKeyRestorer.Outcome.Restored, "b" to RecoveredKeyRestorer.Outcome.Restored))
        }
        vm.recoverExternal(activity, "a", false); advanceUntilIdle()
        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(2, vm.uiState.value.restoredWalletCount)
        val fresh = RecoveryViewModel(operations); advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, fresh.uiState.value.stage)
    }
    @Test fun `cancellation aborts pending native operation and keeps wallets`() = runTest {
        val vm = RecoveryViewModel(operations); advanceUntilIdle()
        coEvery { operations.backup(activity, "a") } coAnswers { awaitCancellation() }
        vm.restoreBackup(activity, "a"); runCurrent()
        vm.cancelOperation(); advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertEquals(2, vm.uiState.value.missingWallets.size)
        assertTrue(vm.uiState.value.outcomes.getValue("a").contains("Cancelled"))
    }
    @Test fun `pending cleanup external input remains explicit and incomplete outcome stays pending`() = runTest {
        wallets[0] = wallets[0].copy(cleanupPending = true, hasLegacyArchive = true)
        val vm = RecoveryViewModel(operations); advanceUntilIdle()
        coEvery { operations.external(activity, "a", true) } returns RecoveredKeyRestorer.RekeyResult.Done(mapOf("a" to RecoveredKeyRestorer.Outcome.CleanupRequired))
        vm.recoverExternal(activity, "a", true); advanceUntilIdle()
        coVerify(exactly = 1) { operations.external(activity, "a", true) }
        assertEquals(0, vm.uiState.value.restoredWalletCount)
        coEvery { operations.cleanup(activity, "a") } returns RecoveredKeyRestorer.Outcome.Cancelled
        vm.resumeCleanup(activity, "a"); advanceUntilIdle()
        assertTrue(vm.uiState.value.missingWallets.first().cleanupPending)
        assertTrue(vm.uiState.value.outcomes.getValue("a").contains("Cancelled"))
    }
    @Test fun `wrong secret and address mismatch retain state without completed health claim`() = runTest {
        val vm = RecoveryViewModel(operations); advanceUntilIdle()
        coEvery { operations.external(activity, "a", true) } returns RecoveredKeyRestorer.RekeyResult.NotThisWallet
        vm.recoverExternal(activity, "a", true); advanceUntilIdle()
        assertEquals(0, vm.uiState.value.restoredWalletCount)
        assertTrue(vm.uiState.value.outcomes.getValue("a").contains("does not match"))
    }
    @Test fun `phrase authentication is selectable without prior device decrypt`() = runTest {
        val vm = RecoveryViewModel(operations); advanceUntilIdle()
        coEvery { operations.unlock(activity, "a", VaultReadPreference.PASSPHRASE) } returns RecoveredKeyRestorer.Outcome.Cancelled
        vm.unlock(activity, "a", VaultReadPreference.PASSPHRASE); advanceUntilIdle()
        assertTrue(vm.uiState.value.outcomes.getValue("a").contains("Cancelled"))
        coVerify(exactly = 0) { operations.external(any(), any(), any()) }
    }
}
