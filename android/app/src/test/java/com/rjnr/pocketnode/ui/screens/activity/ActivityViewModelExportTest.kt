package com.rjnr.pocketnode.ui.screens.activity

import android.content.ContentResolver
import android.net.Uri
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.AppStatePreferences
import com.rjnr.pocketnode.core.prefs.UiPreferences
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.export.TransactionExporter
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.ui.util.UiMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * #562: the pending CSV used to live in the screen's `remember` state, lost
 * when the re-auth lock hides the nav graph (or on rotation) while the
 * "save as" picker is open, so the result came back with nothing to write
 * and a 0-byte file was created silently. The CSV now waits in the
 * ViewModel and is written from there, mirroring the restore hint export
 * fix in #561/#559 (SettingsViewModelRestoreHintTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ActivityViewModelExportTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<GatewayRepository>(relaxed = true)
    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val pendingBroadcastDao = mockk<PendingBroadcastDao>(relaxed = true)
    private val appStatePreferences = mockk<AppStatePreferences>(relaxed = true)
    private val uiPreferences = mockk<UiPreferences>(relaxed = true)
    private val uri = Uri.parse("content://docs/document/export")

    // Deterministic: TransactionExporter().exportToCsv(emptyList()) is just the
    // header row. The real exporter runs unmocked, so this is the exact byte-
    // for-byte CSV format the fix must keep (BOM + header, no trailing rows).
    private val expectedCsv = TransactionExporter().exportToCsv(emptyList())

    /**
     * `ActivityViewModel.init` collects `repository.network` and calls
     * `refreshCache()` on every emission, which calls `repository.getTransactions()`
     * — a suspend fun returning `kotlin.Result<TransactionsResponse>`. MockK
     * 1.13.16 cannot round-trip a stubbed (or even relaxed-auto-answered)
     * inline-value-class return through the suspend continuation resume
     * boundary under Robolectric (the same limitation documented in
     * AddWalletViewModelTest / AddWalletViewModelImportSyncTest): it surfaces
     * as `ClassCastException: ... cannot be cast to TransactionsResponse`,
     * unrelated to anything this test is exercising. A `StateFlow` that never
     * emits keeps `collect` suspended forever so `refreshCache()` (and that
     * call) never runs; `.value` still answers TESTNET for any code that
     * reads it directly instead of collecting.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private val noOpNetworkFlow: StateFlow<NetworkType> = object : StateFlow<NetworkType> {
        override val replayCache: List<NetworkType> get() = listOf(NetworkType.TESTNET)
        override val value: NetworkType get() = NetworkType.TESTNET
        override suspend fun collect(collector: FlowCollector<NetworkType>): Nothing = awaitCancellation()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.network } returns noOpNetworkFlow
        every { appStatePreferences.getActiveWalletId() } returns "wallet-1"
        coEvery { transactionDao.getAllByWalletAndNetwork(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel() = ActivityViewModel(
        repository = repository,
        transactionDao = transactionDao,
        pendingBroadcastDao = pendingBroadcastDao,
        appStatePreferences = appStatePreferences,
        uiPreferences = uiPreferences,
        logger = NoopLogger,
    ).also { it.exportIo = Dispatchers.Unconfined }

    @Test
    fun `the export is written from the ViewModel after the screen state is recreated`() = runTest(testDispatcher) {
        val vm = newViewModel()

        vm.exportTransactions()
        // The first composition receives the file name and opens the picker...
        assertEquals("transactions.csv", vm.exportEvent.first())
        // ...then leaves composition (re-auth gate, rotation). A new collector
        // holds nothing of its own; the picker result goes straight to the
        // ViewModel, which still has the pending CSV.
        val sink = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>(relaxed = true)
        every { resolver.openOutputStream(uri) } returns sink
        vm.onExportDocumentChosen(uri, resolver)
        advanceUntilIdle()

        val expectedBytes = "\uFEFF".toByteArray(Charsets.UTF_8) + expectedCsv.toByteArray(Charsets.UTF_8)
        assertArrayEquals(expectedBytes, sink.toByteArray())
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `nothing pending writes nothing and tells the user`() = runTest(testDispatcher) {
        val vm = newViewModel() // e.g. a recreated ViewModel: the export was lost
        val resolver = mockk<ContentResolver>(relaxed = true)

        vm.onExportDocumentChosen(uri, resolver)
        advanceUntilIdle()

        verify(exactly = 0) { resolver.openOutputStream(any()) }
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertEquals(UiMessage.Resource(R.string.activity_export_not_saved), vm.uiState.value.error)
    }

    @Test
    fun `backing out of the picker writes nothing and drops the pending export`() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.exportTransactions()
        vm.exportEvent.first()

        vm.onExportDocumentChosen(null, mockk(relaxed = true))
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)

        // A later document gets nothing stale: the pending CSV was cleared
        // the moment the picker result (even a cancelled one) arrived.
        val resolver = mockk<ContentResolver>(relaxed = true)
        vm.onExportDocumentChosen(uri, resolver)
        advanceUntilIdle()

        verify(exactly = 0) { resolver.openOutputStream(any()) }
        assertEquals(UiMessage.Resource(R.string.activity_export_not_saved), vm.uiState.value.error)
    }

    @Test
    fun `a write failure shows the save-failed message`() = runTest(testDispatcher) {
        val vm = newViewModel()
        vm.exportTransactions()
        vm.exportEvent.first()

        val resolver = mockk<ContentResolver>(relaxed = true)
        every { resolver.openOutputStream(uri) } throws java.io.IOException("disk full")
        vm.onExportDocumentChosen(uri, resolver)
        advanceUntilIdle()

        assertEquals(UiMessage.Resource(R.string.activity_export_save_failed), vm.uiState.value.error)
    }
}
