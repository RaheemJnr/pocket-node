package com.rjnr.pocketnode.ui.screens.auth

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.ProcessUnlockState
import com.rjnr.pocketnode.ReauthLockPolicy
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.ui.screens.settings.SecuritySettingsScreen
import com.rjnr.pocketnode.ui.screens.settings.SecuritySettingsUiState
import com.rjnr.pocketnode.ui.screens.settings.SecuritySettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w411dp-h700dp")
class PinChangeScreenTest {
    private val rule = createAndroidComposeRule<NamedStringsActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, NamedStringsActivity::class.java))
        }
    }).around(rule)

    @Test fun `settings change PIN reaches dedicated flow and fresh unlock gate`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pins = PinManager(context, Blake2b(), NoopLogger).apply {
            testPrefs = context.getSharedPreferences("pin_change_screen", Context.MODE_PRIVATE)
            argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
            setPin("123456")
        }
        val auth = AuthManager(context).apply { setSessionPin("123456".toCharArray()) }
        val change = PinChangeViewModel(pins, auth)
        val verify = PinViewModel(pins, auth)
        val settings = mockk<SecuritySettingsViewModel>(relaxed = true)
        every { settings.uiState } returns MutableStateFlow(SecuritySettingsUiState(hasPin = true))
        val gate = ReauthGate(mutableStateOf(false), mutableIntStateOf(0))
        val process = ProcessUnlockState().apply { unlocked = true }
        val policy = ReauthLockPolicy(gate, process, onNewLock = { ReauthLockEvents.onLocked() }, clearSessionSecrets = { auth.clearSession() })
        val changing = mutableStateOf(false)
        var setupCalls = 0
        rule.setContent {
            ReauthGateHost(gate.locked, content = {
                if (changing.value) PinChangeScreen(onBack = { changing.value = false }, onRequireUnlock = {
                    changing.value = false
                    policy.onPinChanged()
                }, viewModel = change)
                else SecuritySettingsScreen(onNavigateToPinSetup = { setupCalls++ },
                    onNavigateToPinChange = { changing.value = true }, viewModel = settings)
            }, overlay = {
                PinEntryScreen(PinMode.VERIFY, viewModel = verify, onPinComplete = { policy.onUnlocked(gate.session) })
            })
        }
        rule.onNodeWithText("App lock").performClick()
        rule.onNodeWithText("security_settings_change_pin").performClick()
        assertEquals("Existing PIN changes must never invoke setup", 0, setupCalls)
        rule.onNodeWithText("Enter current PIN").assertExists()
        fun changePin(pin: String) {
            pin.forEach { rule.onNodeWithTag("pin-change-key-$it").performScrollTo().performClick() }
            rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !change.uiState.value.busy }
        }
        changePin("123456"); changePin("654321"); changePin("654321")
        assertTrue(change.uiState.value.needsUnlock)
        assertFalse(auth.hasSessionPin())
        rule.onNodeWithText("Lock and unlock").performClick()
        assertTrue(gate.locked); assertFalse(process.unlocked)
        "123456".forEach { rule.onNodeWithTag("pin-keypad-$it").performClick() }
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !verify.uiState.value.isVerifying }
        assertTrue(gate.locked); assertFalse(auth.hasSessionPin())
        "654321".forEach { rule.onNodeWithTag("pin-keypad-$it").performClick() }
        rule.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !gate.locked }
        assertTrue(process.unlocked); assertTrue(auth.hasSessionPin())
    }
}
