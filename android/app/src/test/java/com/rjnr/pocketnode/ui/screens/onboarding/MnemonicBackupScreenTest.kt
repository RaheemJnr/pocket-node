package com.rjnr.pocketnode.ui.screens.onboarding

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.ui.screens.auth.NamedStringsActivity
import io.mockk.*
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
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w320dp-h640dp")
class MnemonicBackupScreenTest {
    private val compose = createAndroidComposeRule<NamedStringsActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, NamedStringsActivity::class.java))
        }
    }).around(compose)
    private val state = MutableStateFlow(MnemonicBackupUiState())
    private val vm = mockk<MnemonicBackupViewModel>(relaxed = true).also { every { it.uiState } returns state }
    private var backs = 0
    private var homes = 0
    private fun show() { compose.setContent { MnemonicBackupScreen({ homes++ }, { backs++ }, viewModel = vm) } }
    private fun click(text: String) { compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) { it() } }

    @Test fun `entry has short steps and starts only after action with app bar back`() {
        show()
        compose.onNodeWithText("1. Authenticate").assertExists()
        compose.onNodeWithText("2. Record your recovery material").assertExists()
        compose.onNodeWithText("3. Check your backup").assertExists()
        verify(exactly = 0) { vm.backup(any()) }
        click("Authenticate and back up")
        verify(exactly = 1) { vm.backup(any()) }
        compose.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, backs); assertEquals(0, homes) }
    }
    @Test fun `busy prevents duplicate start and offers cancellation and back`() {
        state.value = MnemonicBackupUiState(isLoading = true)
        show()
        compose.onNodeWithText("Working…").assertIsNotEnabled()
        click("Cancel")
        verify(exactly = 1) { vm.cancel() }
        compose.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, backs) }
    }
    @Test fun `error stays visible and action can retry`() {
        state.value = MnemonicBackupUiState(error = "Try again")
        show()
        compose.onNodeWithText("Try again").assertExists()
        click("Authenticate and back up")
        verify(exactly = 1) { vm.backup(any()) }
    }
    @Test fun `unknown and private key material never claim verified phrase success`() {
        state.value = MnemonicBackupUiState(currentStep = 3)
        show()
        compose.onNodeWithText("Your recovery phrase backup was verified.", substring = true).assertDoesNotExist()
        compose.runOnIdle { state.value = MnemonicBackupUiState(currentStep = 3, hasMnemonic = false) }
        compose.onNodeWithText("private key", substring = true).assertExists()
        compose.onNodeWithText("Your recovery phrase backup was verified.", substring = true).assertDoesNotExist()
        click("Continue")
        compose.runOnIdle { assertEquals(1, homes) }
    }
    @Test fun `verified phrase success and known key entry explain the right material`() {
        state.value = MnemonicBackupUiState(hasMnemonic = false)
        show()
        compose.onNodeWithText("Saving a key backs up this account only.", substring = true).assertExists()
        compose.runOnIdle { state.value = MnemonicBackupUiState(currentStep = 3, hasMnemonic = true) }
        compose.onNodeWithText("Your recovery phrase backup was verified.", substring = true).assertExists()
        click("Continue")
        compose.runOnIdle { assertEquals(1, homes) }
    }
    @Test fun `large text on a small screen can scroll to a full width primary action`() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.8f),
            ) { MnemonicBackupScreen({}, {}, viewModel = vm) }
        }
        compose.onNodeWithText("Authenticate and back up").performScrollTo().assertIsDisplayed()
        val bounds = compose.onNodeWithText("Authenticate and back up").getUnclippedBoundsInRoot()
        assertTrue("CTA spans the content width", bounds.right.value - bounds.left.value >= 260f)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }
    @Test fun `leaving composition cancels the backup operation`() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { if (visible.value) MnemonicBackupScreen({}, {}, viewModel = vm) }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { verify(exactly = 1) { vm.cancel() } }
    }

}
