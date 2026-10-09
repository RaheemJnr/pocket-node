package com.rjnr.pocketnode.ui.screens.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.restorehint.RestoreHintExporter
import com.rjnr.pocketnode.data.update.UpdateRepository
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.util.UiMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * #561 review F3 (and the Codex rotation finding): the exported restore hint
 * waits in the ViewModel and is written from there, so a screen that left
 * composition (re-auth gate) or was recreated (rotation) while the "save as"
 * picker was open still writes it. With nothing pending, the empty document
 * is deleted and the user is told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SettingsViewModelRestoreHintTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var walletPrefs: WalletPreferences
    private val repository = mockk<GatewayRepository>(relaxed = true)
    private val walletRepository = mockk<WalletRepository>(relaxed = true)
    private val exporter = mockk<RestoreHintExporter>()
    private val activity = mockk<FragmentActivity>(relaxed = true)
    private val uri = Uri.parse("content://docs/document/hint")
    private val hintText = """{"format":"pocket-node-restore-hint","v":1,"payload":"e30=","mac":"00"}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ckb_wallet_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        walletPrefs = WalletPreferences(context, NoopLogger)
        every { repository.network } returns MutableStateFlow(NetworkType.TESTNET).asStateFlow()
        every { repository.walletInfo } returns MutableStateFlow(null).asStateFlow()
        every { repository.syncProgress } returns MutableStateFlow(SyncProgress()).asStateFlow()
        every { repository.currentNetwork } returns NetworkType.TESTNET
        every { repository.getCurrentAddress() } returns null
        every { walletRepository.getActiveWallet() } returns flowOf(null)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel() = SettingsViewModel(
        repository = repository,
        walletPrefs = walletPrefs,
        pinManager = mockk(relaxed = true),
        walletRepository = walletRepository,
        updateRepository = UpdateRepository(
            HttpClient(MockEngine {
                respond(
                    content = """{"tag_name":"v${BuildConfig.VERSION_NAME}","html_url":"https://example.com","body":"","assets":[]}""",
                    status = HttpStatusCode.OK,
                )
            }),
            Json { ignoreUnknownKeys = true },
            NoopLogger,
            walletPrefs,
        ),
        seedPhraseAuthorizer = mockk(relaxed = true),
        keyMaterialDao = mockk(relaxed = true),
        restoreHintExporter = exporter,
    ).also { it.restoreHintIo = Dispatchers.Unconfined }

    @Test
    fun `the export is written from the ViewModel after the screen was recreated`() = runTest(testDispatcher) {
        coEvery { exporter.export(activity, NetworkType.TESTNET, any()) } returns
            RestoreHintExporter.ExportResult.Ready("pocket-node-restore-hint-testnet.json", hintText)
        val vm = newViewModel()

        vm.exportRestoreHint(activity)
        // The first composition receives the file name and opens the picker...
        assertEquals("pocket-node-restore-hint-testnet.json", vm.restoreHintFiles.first())
        // ...then leaves composition (re-auth gate, rotation). A new composition
        // holds nothing; the picker result goes straight to the ViewModel.
        val sink = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>(relaxed = true)
        every { resolver.openOutputStream(uri) } returns sink
        vm.onRestoreHintDocumentChosen(uri, resolver)
        advanceUntilIdle()

        assertArrayEquals(hintText.toByteArray(Charsets.UTF_8), sink.toByteArray())
        assertEquals(UiMessage.Resource(R.string.restore_hint_export_saved), vm.uiState.value.error)
    }

    @Test
    fun `a document with nothing pending is not left empty and the user is told`() = runTest(testDispatcher) {
        val vm = newViewModel() // e.g. a recreated ViewModel: the export was lost
        val resolver = mockk<ContentResolver>(relaxed = true)

        vm.onRestoreHintDocumentChosen(uri, resolver)
        advanceUntilIdle()

        verify(exactly = 0) { resolver.openOutputStream(any()) }
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertEquals(UiMessage.Resource(R.string.restore_hint_export_not_saved), vm.uiState.value.error)
    }

    @Test
    fun `backing out of the picker writes nothing and drops the pending export`() = runTest(testDispatcher) {
        coEvery { exporter.export(activity, NetworkType.TESTNET, any()) } returns
            RestoreHintExporter.ExportResult.Ready("f.json", hintText)
        val vm = newViewModel()
        vm.exportRestoreHint(activity)
        vm.restoreHintFiles.first()

        vm.onRestoreHintDocumentChosen(null, mockk(relaxed = true))
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)

        // A later document gets nothing stale.
        vm.onRestoreHintDocumentChosen(uri, mockk(relaxed = true))
        advanceUntilIdle()
        assertEquals(UiMessage.Resource(R.string.restore_hint_export_not_saved), vm.uiState.value.error)
    }

    @Test
    fun `a failed export shows a generic message, never the internal reason`() = runTest(testDispatcher) {
        coEvery { exporter.export(activity, NetworkType.TESTNET, any()) } returns
            RestoreHintExporter.ExportResult.Failed("no key_material row for w1")
        val vm = newViewModel()

        vm.exportRestoreHint(activity)
        advanceUntilIdle()

        assertEquals(UiMessage.Resource(R.string.restore_hint_export_failed), vm.uiState.value.error)
    }
}
