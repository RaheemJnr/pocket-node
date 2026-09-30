package com.rjnr.pocketnode.ui.screens.onboarding

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.NodeLifecycle
import com.rjnr.pocketnode.data.gateway.SyncPoller
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.testGatewayRepository
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #431 (review of PR #532): the onboarding import's post-import sync sheet.
 * A failed apply keeps the sheet and does not navigate; a double tap on
 * Apply resyncs once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MnemonicImportViewModelSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var walletPreferences: WalletPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletPreferences = WalletPreferences(context, NoopLogger)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun newViewModel(repository: GatewayRepository) = MnemonicImportViewModel(
        repository = repository,
        mnemonicManager = MnemonicManager(),
        walletRepository = mockk(relaxed = true),
        walletKeyWriter = mockk(relaxed = true),
        authManager = mockk(relaxed = true),
        logger = NoopLogger,
    )

    @Test
    fun `a returned Result failure keeps the sheet open and does not navigate`() = runTest {
        // Real repository: ACTIVE_ONLY with a node that never becomes ready
        // RETURNS Result.failure from resyncAccount (no MockK boxing).
        walletPreferences.setSyncStrategy(SyncStrategy.ACTIVE_ONLY)
        val nodeLifecycle = mockk<NodeLifecycle>(relaxed = true)
        coEvery { nodeLifecycle.awaitNodeReady() } returns false
        val syncPoller = mockk<SyncPoller>(relaxed = true)
        every { syncPoller.syncProgress } returns MutableStateFlow(SyncProgress())
        val repository = testGatewayRepository(
            db = db,
            walletPreferences = walletPreferences,
            nodeLifecycle = nodeLifecycle,
            syncPoller = syncPoller,
        )
        val vm = newViewModel(repository)

        vm.onSyncModeSelected(SyncMode.FULL_HISTORY, null)
        // The real repository hops to Dispatchers.IO, which advanceUntilIdle
        // cannot see: keep draining the Main queue until the apply resolves.
        for (attempt in 0 until 500) {
            advanceUntilIdle()
            if (!vm.uiState.value.isApplyingSyncChoice) break
            Thread.sleep(10)
        }

        assertFalse(vm.uiState.value.importSuccess)
        assertFalse(vm.uiState.value.isApplyingSyncChoice)
        assertTrue(vm.uiState.value.syncChoiceError != null)
    }

    @Test
    fun `a double tap on Apply resyncs once and completes the import`() = runTest {
        val repository = mockk<GatewayRepository>(relaxed = true)
        every { repository.syncProgress } returns MutableStateFlow(SyncProgress())
        coEvery { repository.resyncAccount(any(), any()) } returns Result.success(Unit)
        val vm = newViewModel(repository)

        vm.onSyncModeSelected(SyncMode.RECENT, null)
        vm.onSyncModeSelected(SyncMode.RECENT, null)
        vm.skipSyncSelection()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.resyncAccount(any(), any()) }
        assertTrue(vm.uiState.value.importSuccess)
        assertFalse(vm.uiState.value.isApplyingSyncChoice)
    }
}
