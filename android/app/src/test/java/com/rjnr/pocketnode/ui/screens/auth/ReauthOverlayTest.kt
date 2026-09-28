package com.rjnr.pocketnode.ui.screens.auth

import android.app.Application
import android.content.ComponentName
import android.content.res.Resources
import android.os.Looper
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import androidx.test.core.app.ApplicationProvider

/**
 * Compose tests for the #524 re-auth overlay: only a genuine credential
 * earned in the current lock opens it, the PIN and Forgot PIN levels never
 * leave it, and nothing from the screen underneath survives on screen while
 * it is up.
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

    // One ViewModel for every session: the worst case, as if it were
    // Activity-scoped (the B1 finding). Production keys them per session.
    private val authViewModel = AuthViewModel(
        authManager, pinManager, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), NoopLogger,
    )
    private val pinViewModel by lazy { PinViewModel(pinManager, authManager) }
    private val forgotPinViewModel = ForgotPinViewModel(
        mockk(relaxed = true), mockk(relaxed = true), pinManager, mockk(relaxed = true),
        mockk(relaxed = true), NoopLogger,
    )

    private val gate = ReauthGate(mutableStateOf(false), mutableIntStateOf(0))
    private var leaveCount = 0

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
            onUnlocked = { gate.unlock(it) },
            onLeave = { leaveCount++ },
            authViewModel = authViewModel,
            pinViewModel = pinViewModel,
            forgotPinViewModel = forgotPinViewModel,
        )
    }

    private fun lock() {
        rule.runOnUiThread { gate.lock() }
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun enterPin() {
        rule.runOnUiThread { repeat(PinManager.PIN_LENGTH) { pinViewModel.onDigitEntered('1') } }
    }

    private fun assertLockedOnBiometricLevel() {
        rule.onNodeWithText("Wallet is locked").assertExists()
        rule.onNodeWithText("SECRET").assertDoesNotExist()
        assertTrue(gate.locked)
    }

    @Test
    fun `a second lock is not opened by the first lock's success (B1)`() {
        setHost()
        lock()
        assertLockedOnBiometricLevel()

        rule.runOnUiThread { authViewModel.onBiometricSuccess() }
        rule.waitUntil { !gate.locked }
        rule.onNodeWithText("SECRET").assertExists()

        lock()
        assertLockedOnBiometricLevel()
        assertFalse(authViewModel.uiState.value.authSuccess)

        rule.runOnUiThread { authViewModel.onBiometricSuccess() }
        rule.waitUntil { !gate.locked }
        rule.onNodeWithText("SECRET").assertExists()
    }

    @Test
    fun `a success left in the ViewModel before the lock does not open it (B1)`() {
        setHost()
        rule.runOnUiThread { authViewModel.onBiometricSuccess() }

        lock()

        assertLockedOnBiometricLevel()
    }

    @Test
    fun `a PIN success unlocks without AuthScreen composed (B2)`() {
        setHost()
        lock()

        rule.onNodeWithTag("auth-use-pin").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Wallet is locked").assertDoesNotExist()
        rule.onNodeWithText("Enter PIN").assertExists()

        enterPin()
        // The verify resumes on the main looper (viewModelScope), which the
        // compose clock does not drive: idle it while waiting.
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !gate.locked
        }
        rule.onNodeWithText("SECRET").assertExists()
    }

    @Test
    fun `Forgot PIN stays inside the lock and back returns to the PIN level (B3)`() {
        setHost()
        lock()
        rule.onNodeWithTag("auth-use-pin").performClick()
        rule.waitForIdle()

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
        rule.onNodeWithTag("auth-use-pin").performClick()
        rule.waitForIdle()

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

        rule.runOnUiThread { authViewModel.onBiometricSuccess() }
        rule.waitUntil { !gate.locked }
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

        rule.runOnUiThread { authViewModel.onBiometricSuccess() }
        rule.waitUntil { !gate.locked }

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
 * too.
 */
class NamedStringsActivity : ComponentActivity() {
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
