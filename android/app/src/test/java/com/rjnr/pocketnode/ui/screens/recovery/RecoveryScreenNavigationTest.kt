package com.rjnr.pocketnode.ui.screens.recovery

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.ui.screens.auth.NamedStringsActivity
import io.mockk.*
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
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w411dp-h1000dp")
class RecoveryScreenNavigationTest {
    private val compose = createAndroidComposeRule<NamedStringsActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, NamedStringsActivity::class.java))
        }
    }).around(compose)
    @Test fun `read only continuation never reports recovery success or opens destructive reset`() {
        val operations = mockk<RecoveryOperations>(relaxed = true)
        coEvery { operations.wallets() } returns listOf(MissingWallet("a", "Savings", false))
        val vm = RecoveryViewModel(operations)
        var completed = 0; var readOnly = 0
        compose.setContent { RecoveryScreen({ completed++ }, { readOnly++ }, vm) }
        compose.onNodeWithText("Why keep a recovery backup?").performScrollTo().performClick()
        compose.onNodeWithText("A new passphrase cannot recreate a lost wallet key.", substring = true).assertExists()
        compose.onNodeWithText("Keep wallets read-only and continue").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, readOnly); assertEquals(0, completed); assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage) }
        coVerify(exactly = 0) { operations.complete() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
    @Test fun `pending cleanup offers explicit external recovery as well as archive retry`() {
        val operations = mockk<RecoveryOperations>(relaxed = true)
        coEvery { operations.wallets() } returns listOf(MissingWallet("a", "Savings", false, cleanupPending = true, hasLegacyArchive = true))
        val vm = RecoveryViewModel(operations)
        compose.setContent { RecoveryScreen({}, {}, vm) }
        compose.onNodeWithText("Resume legacy cleanup").performScrollTo().assertExists()
        compose.onNodeWithText("Use recovery phrase").assertDoesNotExist()
        compose.onNodeWithText("Other recovery methods").performScrollTo().performClick()
        compose.onNodeWithText("Use recovery phrase").performScrollTo().performClick()
        coVerify(exactly = 1) { operations.external(any(), "a", false) }
        compose.onNodeWithText("Use private key (spending only)").assertExists()
        compose.onNodeWithText("Authenticate old backup PIN").assertDoesNotExist()
    }
    @Test fun `empty recovery list offers neutral continuation without marking recovery complete`() {
        val operations = mockk<RecoveryOperations>(relaxed = true)
        coEvery { operations.wallets() } returns emptyList()
        val vm = RecoveryViewModel(operations)
        var completed = 0; var continued = 0
        compose.setContent { RecoveryScreen({ completed++ }, { continued++ }, vm) }
        compose.onNodeWithText("No wallets currently need recovery").assertExists()
        compose.onNodeWithText("Keep wallets read-only and continue").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(1, continued); assertEquals(0, completed) }
        coVerify(exactly = 0) { operations.complete() }
    }

    @Test fun `pending recovery check never claims wallets need no recovery`() {
        val response = kotlinx.coroutines.CompletableDeferred<List<MissingWallet>>()
        val operations = mockk<RecoveryOperations>(relaxed = true)
        coEvery { operations.wallets() } coAnswers { response.await() }
        val vm = RecoveryViewModel(operations)
        compose.setContent { RecoveryScreen({}, {}, vm) }
        compose.onNodeWithText("Checking wallet recovery…").assertExists()
        compose.onNodeWithText("No wallets currently need recovery").assertDoesNotExist()
        compose.runOnIdle { response.complete(emptyList()) }
        compose.onNodeWithText("No wallets currently need recovery").assertExists()
    }

    @Test fun `empty list while completion suspends remains restoring and can cancel`() {
        val completion = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val operations = mockk<RecoveryOperations>(relaxed = true)
        coEvery { operations.wallets() } returnsMany listOf(listOf(MissingWallet("a", "Savings", false)), emptyList())
        coEvery { operations.unlock(any(), "a", any()) } returns com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer.Outcome.Restored
        coEvery { operations.complete() } coAnswers { completion.await() }
        val vm = RecoveryViewModel(operations)
        var completed = 0; var continued = 0
        compose.setContent { RecoveryScreen({ completed++ }, { continued++ }, vm) }
        compose.onNodeWithText("Use wallet passphrase").performClick()
        compose.onNodeWithText("Restoring wallet access…").assertExists()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("Keep wallets read-only and continue").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage); assertEquals(0, completed); assertEquals(0, continued) }
        compose.onNodeWithText("Done").assertExists()
    }

}
