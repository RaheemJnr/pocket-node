package com.rjnr.pocketnode.ui.screens.auth

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.ProcessUnlockState
import com.rjnr.pocketnode.ReauthLockPolicy
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.crypto.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.ui.navigation.Screen
import com.rjnr.pocketnode.ui.navigation.rememberLockSafeNavController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real setup and PIN composables, retained destination VM, gate state removal and overlay auth. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w411dp-h1000dp")
class InitialPinSetupRestorationTest {
    private val rule = createAndroidComposeRule<NamedStringsActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, NamedStringsActivity::class.java))
        }
    }).around(rule)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = spyk(PinManager(context, Blake2b(), NoopLogger).apply {
        testPrefs = context.getSharedPreferences("setup_restoration", Context.MODE_PRIVATE)
        argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
    })
    private val auth = AuthManager(context)
    private val started = CountDownLatch(1)
    private val finish = CountDownLatch(1)
    private val gate = ReauthGate(mutableStateOf(false), mutableIntStateOf(0))
    private val sessions = ReauthSessionStore()
    private val policy = ReauthLockPolicy(gate, ProcessUnlockState(),
        onNewLock = { ReauthLockEvents.onLocked(); sessions.clearAll() },
        clearSessionSecrets = { auth.clearSession() })
    private val migration = mockk<KeystoreV2MigrationHelper>(relaxed = true).apply {
        coEvery { pendingWalletIds() } returns emptyList()
        coEvery { finalize() } returns Result.success(Unit)
    }
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            PinViewModel::class.java -> PinViewModel(manager, auth)
            AuthViewModel::class.java -> AuthViewModel(auth, manager, mockk(relaxed = true), migration, mockk(relaxed = true), NoopLogger)
            ForgotPinViewModel::class.java -> ForgotPinViewModel(mockk(relaxed = true), mockk(relaxed = true), NoopLogger)
            else -> error("unexpected $modelClass")
        } as T
    }
    private lateinit var setupVm: PinViewModel
    private lateinit var nav: NavHostController
    private var completions = 0

    private fun mount() {
        every { manager.setPin(any()) } answers {
            started.countDown()
            check(finish.await(15, TimeUnit.SECONDS)) { "test did not release setup" }
            callOriginal()
        }
        rule.setContent {
            nav = rememberLockSafeNavController()
            ReauthGateHost(gate.locked, content = {
                NavHost(nav, startDestination = Screen.InitialPinSetup.route) {
                    composable(Screen.InitialPinSetup.route) {
                        setupVm = viewModel(factory = factory)
                        InitialPinSetupScreen(viewModel = setupVm,
                            onForgotPin = { nav.navigate(Screen.ForgotPin.route) }, onPinCreated = {
                            completions++
                            // Same post-setup callback contract: preserve the opt-in step.
                            nav.navigate(Screen.BackgroundSyncOptIn.route) {
                                popUpTo(nav.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        })
                    }
                    composable(Screen.BackgroundSyncOptIn.route) { Text("Post setup opt-in") }
                    composable(Screen.ForgotPin.route) { Text("Reset confirmation destination") }
                }
            }, overlay = {
                ReauthOverlay(gate.session, sessions, onUnlocked = { policy.onUnlocked(it) },
                    onLeave = {}, viewModelFactory = factory)
            })
        }
        rule.onNodeWithTag("pin-intro-continue").performClick()
        enterPin()
        rule.onNodeWithText("Confirm PIN").assertExists()
        enterPin()
        assertTrue(started.await(5, TimeUnit.SECONDS))
    }

    private fun enterPin() { "123456".forEach { rule.onNodeWithTag("pin-keypad-$it").performClick() } }
    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    private fun stop() = rule.runOnUiThread {
        policy.onStop(listOf(Screen.InitialPinSetup.route), hasWallet = false, hasPin = manager.hasPin(), isChangingConfigurations = false)
    }
    private fun start() = rule.runOnUiThread { policy.onStart(manager.hasPin()) }
    private fun completeSetup() {
        finish.countDown()
        waitUntil { !setupVm.uiState.value.isVerifying }
        assertTrue(manager.hasPin())
        assertFalse(auth.hasSessionPin())
        assertEquals(0, completions)
        rule.onNodeWithText("Post setup opt-in").assertDoesNotExist()
    }
    private fun assertFinished() {
        waitUntil { nav.currentDestination?.route == Screen.BackgroundSyncOptIn.route }
        rule.onNodeWithText("Post setup opt-in").assertExists()
        rule.waitForIdle()
        assertEquals(1, completions)
        assertTrue(auth.hasSessionPin())
        assertTrue(manager.verifyPin("123456"))
        assertFalse(manager.verifyPin("654321"))
    }

    @Test fun `retained setup verification exposes existing reset confirmation destination`() {
        mount()
        stop()
        start()
        completeSetup()
        rule.onNodeWithText("Forgot PIN?").performClick()
        rule.onNodeWithText("Reset confirmation destination").assertExists()
        assertEquals(Screen.ForgotPin.route, nav.currentDestination?.route)
        assertEquals(0, completions)
        assertFalse(auth.hasSessionPin())
        assertTrue(manager.verifyPin("123456"))
    }

    @Test fun `ordinary first setup advances once and preserves its PIN`() {
        mount()
        finish.countDown()
        assertFinished()
    }

    @Test fun `PIN persisted before return completes restored setup only after overlay authentication`() {
        mount()
        stop()
        completeSetup()
        start()
        rule.onNodeWithText("Wallet is locked").assertExists()
        rule.onNodeWithTag("auth-use-pin").performClick()
        enterPin()
        waitUntil { !gate.locked }
        assertFinished()
    }

    @Test fun `PIN persisted after return uses retained setup verification and finishes original destination`() {
        mount()
        stop()
        start()
        assertFalse(gate.locked)
        completeSetup()
        rule.onNodeWithText("Enter PIN").assertExists()
        enterPin()
        assertFinished()
    }
}
