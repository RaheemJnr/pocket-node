package com.rjnr.pocketnode.ui.screens.auth

import android.app.Application
import android.content.ComponentName
import android.content.res.Resources
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.ProcessUnlockState
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ReauthLockPolicy
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.ui.navigation.Screen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

/**
 * Compose tests for the #524 re-auth overlay, wired the way MainActivity
 * wires it: a [ReauthLockPolicy] over a [ReauthGate], a [ReauthSessionStore]
 * holding each lock session's real ViewModels, and a FragmentActivity host so
 * the migration branch runs. Only a genuine credential earned in the current
 * lock opens it, the PIN and Forgot PIN levels never leave it, and nothing
 * from the screen underneath survives on screen while it is up.
 */
@RunWith(RobolectricTestRunner::class)
// Tall enough for the whole PIN pad, Forgot PIN link included.
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w411dp-h1000dp")
class ReauthOverlayTest {

    private val rule = createAndroidComposeRule<NamedStringsActivity>()

    // The host activity is not in a manifest under manifest = NONE; register
    // it with Robolectric before the compose rule launches it.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(ExternalResourceRule {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context, NamedStringsActivity::class.java)
            )
        })
        .around(rule)

    private val pinManager = mockk<PinManager>(relaxed = true).apply {
        every { hasPin() } returns true
        every { verifyPin(any()) } returns true
        every { isLockedOut() } returns false
        every { isPermanentlyLocked() } returns false
        every { getRemainingAttempts() } returns PinManager.MAX_ATTEMPTS
    }
    private val authManager = mockk<AuthManager>(relaxed = true).apply {
        every { isBiometricEnrolled() } returns false
        every { isBiometricEnabled() } returns false
    }
    private val migrationHelper = mockk<KeystoreV2MigrationHelper>(relaxed = true).apply {
        coEvery { pendingWalletIds() } returns emptyList()
        coEvery { finalize() } returns Result.success(Unit)
    }

    // Every ViewModel the overlay asks for, in creation order.
    private val created = mutableListOf<ViewModel>()
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
            AuthViewModel::class.java -> AuthViewModel(
                authManager, pinManager, mockk(relaxed = true), migrationHelper,
                mockk(relaxed = true), NoopLogger,
            )
            PinViewModel::class.java -> PinViewModel(pinManager, authManager)
            ForgotPinViewModel::class.java -> ForgotPinViewModel(
                mockk(relaxed = true), mockk(relaxed = true), pinManager, mockk(relaxed = true),
                mockk(relaxed = true), NoopLogger,
            )
            else -> error("unexpected $modelClass")
        }.also { created += it } as T
    }

    private val gate = ReauthGate(mutableStateOf(false), mutableIntStateOf(0))
    private val sessionStore = ReauthSessionStore()
    private var secretsCleared = 0
    private val policy = ReauthLockPolicy(
        gate = gate,
        process = ProcessUnlockState(),
        onNewLock = { sessionStore.clearAll() },
        clearSessionSecrets = { secretsCleared++ },
    )
    private var leaveCount = 0

    private inline fun <reified VM : ViewModel> vms(): List<VM> = created.filterIsInstance<VM>()

    private fun setHost(content: @Composable () -> Unit = { Text("SECRET") }) {
        rule.setContent {
            ReauthGateHost(
                locked = gate.locked,
                content = content,
                overlay = { Overlay() },
            )
        }
    }

    @Composable
    private fun Overlay() {
        ReauthOverlay(
            session = gate.session,
            sessionStore = sessionStore,
            onUnlocked = { policy.onUnlocked(it) },
            onLeave = { leaveCount++ },
            viewModelFactory = factory,
        )
    }

    /** Background the app on an ordinary screen (MainActivity.onStop). */
    private fun lock() {
        rule.runOnUiThread {
            policy.onStop(listOf(Screen.Main.route), hasWallet = true, hasPin = true)
        }
        rule.waitForIdle()
    }

    // viewModelScope work resumes on the main looper, which the compose
    // clock does not drive: idle it while waiting.
    private fun idleUntil(condition: () -> Boolean) {
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun idle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun usePin() {
        rule.onNodeWithTag("auth-use-pin").performClick()
        rule.waitForIdle()
    }

    private fun enterPin() {
        repeat(PinManager.PIN_LENGTH) { rule.onNodeWithTag("pin-keypad-1").performClick() }
    }

    private fun biometricSuccess() {
        rule.runOnUiThread { vms<AuthViewModel>().last().onBiometricSuccess() }
    }

    private fun assertLockedOnBiometricLevel() {
        rule.onNodeWithText("Wallet is locked").assertExists()
        rule.onNodeWithText("SECRET").assertDoesNotExist()
        assertTrue(gate.locked)
    }

    private fun ViewModel.isCleared() = !viewModelScope.isActive

    @Test
    fun `each lock gets its own ViewModels and a stale one cannot open it (B1)`() {
        setHost()
        lock()
        assertLockedOnBiometricLevel()
        val firstAuth = vms<AuthViewModel>().single()

        biometricSuccess()
        idleUntil { !gate.locked }
        rule.onNodeWithText("SECRET").assertExists()
        assertTrue(firstAuth.isCleared())

        lock()
        assertLockedOnBiometricLevel()
        val secondAuth = vms<AuthViewModel>().last()
        assertNotSame(firstAuth, secondAuth)
        assertFalse(secondAuth.uiState.value.authSuccess)

        // The first lock's ViewModel succeeding again does nothing.
        rule.runOnUiThread { firstAuth.onBiometricSuccess() }
        idle()
        assertLockedOnBiometricLevel()

        biometricSuccess()
        idleUntil { !gate.locked }
        rule.onNodeWithText("SECRET").assertExists()
    }

    @Test
    fun `a new lock clears the previous session's ViewModels at once (S-c)`() {
        setHost()
        lock()
        usePin()
        val firstAuth = vms<AuthViewModel>().single()
        val firstPin = vms<PinViewModel>().single()

        lock()

        assertTrue(firstAuth.isCleared())
        assertTrue(firstPin.isCleared())
        assertLockedOnBiometricLevel()
        assertTrue(vms<AuthViewModel>().last().viewModelScope.isActive)
    }

    @Test
    fun `a relock while locked returns to a fresh biometric level (N-a)`() {
        setHost()
        lock()
        usePin()
        rule.onNodeWithText("Wallet is locked").assertDoesNotExist()

        lock()

        // A fresh AuthScreen composition, so its once-per-instance
        // biometric auto-prompt runs again for the new lock.
        assertLockedOnBiometricLevel()
    }

    @Test
    fun `a migration from an older lock never opens the new one (S-b)`() {
        val migrationGo = CompletableDeferred<Unit>()
        coEvery { migrationHelper.pendingWalletIds() } coAnswers {
            migrationGo.await()
            emptyList()
        }
        setHost()
        lock()
        biometricSuccess()
        idle()
        assertTrue(gate.locked)

        lock()
        migrationGo.complete(Unit)
        idle()

        assertLockedOnBiometricLevel()
    }

    @Test
    fun `a PIN success unlocks without AuthScreen composed (B2)`() {
        setHost()
        lock()

        usePin()
        rule.onNodeWithText("Wallet is locked").assertDoesNotExist()
        rule.onNodeWithText("Enter PIN").assertExists()

        enterPin()
        idleUntil { !gate.locked }
        rule.onNodeWithText("SECRET").assertExists()
    }

    @Test
    fun `a PIN verify still running when the app locks again sets no session PIN (N-b)`() {
        val verifyGo = CountDownLatch(1)
        every { pinManager.verifyPin(any()) } answers {
            verifyGo.await(5, TimeUnit.SECONDS)
            true
        }
        setHost()
        lock()
        usePin()
        enterPin()

        lock()
        verifyGo.countDown()
        Thread.sleep(200)
        idle()

        verify(exactly = 0) { authManager.setSessionPin(any()) }
        assertLockedOnBiometricLevel()
    }

    @Test
    fun `Forgot PIN stays inside the lock and back returns to the PIN level (B3)`() {
        setHost()
        lock()
        usePin()

        rule.onNodeWithText("Forgot PIN?").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("forgot_pin_title").assertExists()
        rule.onNodeWithText("SECRET").assertDoesNotExist()

        // System back from Forgot PIN: PIN level, still locked.
        pressBack()
        rule.onNodeWithText("Enter PIN").assertExists()
        rule.onNodeWithText("SECRET").assertDoesNotExist()
        assertTrue(gate.locked)

        // Forgot PIN's own back arrow does the same.
        rule.onNodeWithText("Forgot PIN?").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("forgot_pin_back_cd").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enter PIN").assertExists()
        assertTrue(gate.locked)

        // Back from PIN reaches the biometric level; back again leaves the
        // app (onLeave) without unlocking.
        pressBack()
        assertLockedOnBiometricLevel()
        pressBack()
        assertEquals(1, leaveCount)
        assertLockedOnBiometricLevel()
    }

    @Test
    fun `the reset path offered at permanent lockout never unlocks (B3)`() {
        every { pinManager.isLockedOut() } returns true
        every { pinManager.isPermanentlyLocked() } returns true
        every { pinManager.getRemainingAttempts() } returns 0
        setHost()
        lock()
        usePin()

        rule.onNodeWithText("Wallet locked").assertExists()
        rule.onNodeWithText("Reset & restore").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("forgot_pin_title").assertExists()

        pressBack()
        rule.onNodeWithText("SECRET").assertDoesNotExist()
        assertTrue(gate.locked)
    }

    @Test
    fun `while locked the content and its dialogs are gone, and come back on unlock (B4)`() {
        setHost {
            var showDialog by rememberSaveable { mutableStateOf(true) }
            Text("SECRET")
            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    confirmButton = { TextButton(onClick = {}) { Text("BROADCAST") } },
                    text = { Text("DIALOG") },
                )
            }
        }
        rule.onNodeWithText("DIALOG").assertExists()

        lock()

        rule.onNodeWithText("SECRET").assertDoesNotExist()
        rule.onNodeWithText("DIALOG").assertDoesNotExist()
        rule.onNodeWithText("BROADCAST").assertDoesNotExist()
        // Only the overlay's own window is left.
        assertEquals(1, rule.onAllNodes(isRoot()).fetchSemanticsNodes().size)

        biometricSuccess()
        idleUntil { !gate.locked }
        rule.onNodeWithText("SECRET").assertExists()
        rule.onNodeWithText("DIALOG").assertExists()
    }

    class CounterViewModel : ViewModel() {
        var value = 0
    }

    @Test
    fun `the back stack, saved state and entry ViewModels survive a lock (decision 1)`() {
        lateinit var navController: NavHostController
        lateinit var sendViewModel: CounterViewModel
        rule.setContent {
            navController = rememberNavController()
            ReauthGateHost(
                locked = gate.locked,
                content = {
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") { Text("HOME") }
                        composable("send") {
                            val vm: CounterViewModel = viewModel()
                            sendViewModel = vm
                            var amount by rememberSaveable { mutableStateOf(0) }
                            Column {
                                Text("amount=$amount")
                                Button(onClick = { amount++ }) { Text("ADD") }
                            }
                        }
                    }
                },
                overlay = { Overlay() },
            )
        }
        rule.runOnUiThread { navController.navigate("send") }
        rule.waitForIdle()
        repeat(3) { rule.onNodeWithText("ADD").performClick() }
        rule.onNodeWithText("amount=3").assertExists()
        val vmBefore = sendViewModel
        vmBefore.value = 42

        lock()
        rule.onNodeWithText("amount=3").assertDoesNotExist()

        biometricSuccess()
        idleUntil { !gate.locked }

        rule.onNodeWithText("amount=3").assertExists()
        assertEquals("send", navController.currentDestination?.route)
        assertSame(vmBefore, sendViewModel)
        assertEquals(42, sendViewModel.value)
        rule.onNodeWithText("HOME").assertDoesNotExist()
    }

    private class ExternalResourceRule(private val setUp: () -> Unit) : ExternalResource() {
        override fun before() = setUp()
    }
}

/**
 * Unit tests run without the merged app resources, so this host activity
 * resolves the app's string resources to their names (e.g.
 * "forgot_pin_title") and strings the app does not declare (Material's own)
 * to an empty string. Dialog windows take their resources from the activity
 * too. A FragmentActivity, like MainActivity, so AuthScreen and the overlay
 * take their FragmentActivity (migration) branch.
 */
class NamedStringsActivity : FragmentActivity() {
    private var named: Resources? = null

    @Suppress("DEPRECATION")
    override fun getResources(): Resources {
        named?.let { return it }
        val base = super.getResources()
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        return object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun getText(id: Int): CharSequence = lookup(id)
            override fun getString(id: Int): String = lookup(id)
            override fun getString(id: Int, vararg formatArgs: Any?): String = lookup(id)

            private fun lookup(id: Int): String =
                names[id] ?: runCatching { base.getString(id) }.getOrDefault("")
        }.also { named = it }
    }
}
