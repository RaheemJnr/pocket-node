package com.rjnr.pocketnode.ui.screens.onboarding

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #524: a recovery phrase being typed never survives a re-auth lock (R5-3). */
@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicImportViewModelLockTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): MnemonicImportViewModel {
        val repository = mockk<GatewayRepository>(relaxed = true)
        every { repository.syncProgress } returns MutableStateFlow(mockk<SyncProgress>(relaxed = true))
        return MnemonicImportViewModel(
            repository = repository,
            mnemonicManager = mockk(relaxed = true),
            walletRepository = mockk(relaxed = true),
            walletKeyWriter = mockk(relaxed = true),
            authManager = mockk(relaxed = true),
            logger = NoopLogger,
            restoreHintImporter = mockk(relaxed = true),
        )
    }

    @Test
    fun `a re-auth lock clears the typed recovery phrase`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.updateWord(0, "abandon")
        vm.updateWord(1, "ability")
        assertEquals("abandon", vm.uiState.value.words[0])

        ReauthLockEvents.onLocked()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.words.all { it.isEmpty() })
        assertTrue(vm.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `a lock that happened before the ViewModel existed clears nothing`() = runTest {
        ReauthLockEvents.onLocked()
        val vm = newViewModel()
        advanceUntilIdle()
        vm.updateWord(0, "abandon")
        advanceUntilIdle()

        assertEquals("abandon", vm.uiState.value.words[0])
    }
}
