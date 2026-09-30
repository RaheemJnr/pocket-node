package com.rjnr.pocketnode.ui.screens.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.update.UpdateRepository
import com.rjnr.pocketnode.data.wallet.SeedPhraseAuthorizer
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel-level tests for the Network services switches (#531).
 * [com.rjnr.pocketnode.data.price.PriceRepositoryTest] and
 * [com.rjnr.pocketnode.data.update.UpdateRepositoryTest] already cover the
 * repositories making zero network requests when their switch is off; this
 * covers [SettingsViewModel] persisting the choice and reflecting it back
 * in [SettingsViewModel.UiState], including the "Update checks are off"
 * status the Settings row shows instead of running a check.
 *
 * A real [UpdateRepository] backed by a Ktor `MockEngine` is used instead of
 * mocking `checkForUpdate` directly: MockK stubbing a suspend function that
 * returns `kotlin.Result` with a `Result.success(...)` answer trips a known
 * ClassCastException (the compiler's special calling convention for
 * `Result`-returning suspend functions expects the raw unwrapped value on
 * success, not a boxed `Result`). Driving the real implementation sidesteps
 * that entirely and is exactly the pattern `UpdateRepositoryTest` already uses.
 *
 * `SettingsViewModel`'s init has no unbounded background loop (unlike
 * `HomeViewModel`'s periodic price ticker), so it is safe to pump with
 * [runCurrent] here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SettingsViewModelNetworkServicesTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var walletPrefs: WalletPreferences
    private lateinit var repository: GatewayRepository
    private lateinit var walletRepository: WalletRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ckb_wallet_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        walletPrefs = WalletPreferences(context, NoopLogger)

        repository = mockk(relaxed = true)
        walletRepository = mockk(relaxed = true)

        every { repository.network } returns MutableStateFlow(NetworkType.MAINNET).asStateFlow()
        every { repository.walletInfo } returns MutableStateFlow(null).asStateFlow()
        every { repository.syncProgress } returns MutableStateFlow(SyncProgress()).asStateFlow()
        every { repository.currentNetwork } returns NetworkType.MAINNET
        every { repository.getCurrentAddress() } returns null
        every { walletRepository.getActiveWallet() } returns flowOf(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A real UpdateRepository whose GitHub response always reports the current version (no update). */
    private fun noUpdateAvailableRepository(): UpdateRepository {
        val engine = MockEngine {
            respond(
                content = """{"tag_name":"v${BuildConfig.VERSION_NAME}","html_url":"https://example.com","body":"","assets":[]}""",
                status = HttpStatusCode.OK,
            )
        }
        return UpdateRepository(HttpClient(engine), Json { ignoreUnknownKeys = true }, NoopLogger, walletPrefs)
    }

    private fun newViewModel(updateRepository: UpdateRepository): SettingsViewModel = SettingsViewModel(
        repository = repository,
        walletPrefs = walletPrefs,
        pinManager = mockk<PinManager>(relaxed = true),
        walletRepository = walletRepository,
        updateRepository = updateRepository,
        seedPhraseAuthorizer = mockk<SeedPhraseAuthorizer>(relaxed = true),
        keyMaterialDao = mockk<KeyMaterialDao>(relaxed = true),
    )

    @Test
    fun `loadState reflects the WalletPreferences defaults, both on`() = runTest(testDispatcher) {
        val viewModel = newViewModel(noUpdateAvailableRepository())
        runCurrent()

        assertTrue(viewModel.uiState.value.isPriceServiceEnabled)
        assertTrue(viewModel.uiState.value.isUpdateServiceEnabled)
    }

    @Test
    fun `togglePriceService persists the choice and updates the UI state`() = runTest(testDispatcher) {
        val viewModel = newViewModel(noUpdateAvailableRepository())
        runCurrent()

        viewModel.togglePriceService(false)

        assertFalse(viewModel.uiState.value.isPriceServiceEnabled)
        assertFalse("must persist so PriceRepository sees it on the next call", walletPrefs.isPriceServiceEnabled())

        viewModel.togglePriceService(true)

        assertTrue(viewModel.uiState.value.isPriceServiceEnabled)
        assertTrue(walletPrefs.isPriceServiceEnabled())
    }

    @Test
    fun `toggleUpdateService off persists, updates UI state, and shows Disabled without checking`() = runTest(testDispatcher) {
        val viewModel = newViewModel(noUpdateAvailableRepository())
        runCurrent()

        viewModel.toggleUpdateService(false)
        runCurrent()

        assertFalse(viewModel.uiState.value.isUpdateServiceEnabled)
        assertFalse(walletPrefs.isUpdateServiceEnabled())
        assertEquals(SettingsViewModel.UpdateStatus.Disabled, viewModel.uiState.value.updateStatus)
    }

    @Test
    fun `toggleUpdateService back on checks again`() = runTest(testDispatcher) {
        // checkForUpdate() (called by toggleUpdateService(true)) early-returns
        // on the playRelease build type before ever touching the switch or
        // the repository, since Play forbids the self-update flow entirely
        // (BuildConfig.UPDATER_ENABLED = false there). This test needs a real
        // check to run, so it only applies where the updater is compiled in.
        assumeTrue(BuildConfig.UPDATER_ENABLED)
        walletPrefs.setUpdateServiceEnabled(false)
        val viewModel = newViewModel(noUpdateAvailableRepository())
        runCurrent()
        assertEquals(SettingsViewModel.UpdateStatus.Disabled, viewModel.uiState.value.updateStatus)

        viewModel.toggleUpdateService(true)
        // The real MockEngine HTTP round-trip hops off the virtual test
        // dispatcher onto the engine's own dispatcher, so a single
        // runCurrent() can observe it mid-flight (still "Checking"). Poll
        // with tiny real sleeps until it settles; the round-trip is a local
        // in-memory mock response, so this converges in well under the cap.
        var attempts = 0
        while (viewModel.uiState.value.updateStatus == SettingsViewModel.UpdateStatus.Checking && attempts < 50) {
            Thread.sleep(10)
            runCurrent()
            attempts++
        }

        assertTrue(viewModel.uiState.value.isUpdateServiceEnabled)
        assertEquals(SettingsViewModel.UpdateStatus.UpToDate, viewModel.uiState.value.updateStatus)
    }

    @Test
    fun `checkForUpdate reports Disabled instead of Failed when the switch is off`() = runTest(testDispatcher) {
        // Same reason as the test above: checkForUpdate() itself is a no-op
        // on the playRelease build type regardless of the switch, so there is
        // no Disabled transition to observe there.
        assumeTrue(BuildConfig.UPDATER_ENABLED)
        walletPrefs.setUpdateServiceEnabled(false)
        val viewModel = newViewModel(noUpdateAvailableRepository())
        runCurrent()

        viewModel.checkForUpdate()
        runCurrent()

        assertEquals(SettingsViewModel.UpdateStatus.Disabled, viewModel.uiState.value.updateStatus)
    }
}
