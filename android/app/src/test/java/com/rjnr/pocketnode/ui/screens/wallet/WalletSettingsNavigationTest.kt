package com.rjnr.pocketnode.ui.screens.wallet
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.ui.screens.auth.NamedStringsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.rjnr.pocketnode.data.database.entity.WalletEntity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w411dp-h1000dp")
class WalletSettingsNavigationTest {
    private val compose = createAndroidComposeRule<NamedStringsActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, NamedStringsActivity::class.java))
        }
    }).around(compose)
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
    private val wallet = WalletEntity("w", "Savings", "mnemonic", "m/44'/309'/0'", null)
    @Test fun `wallet actions details education and accounts retain routes`() {
        val calls = mutableListOf<String>()
        compose.setContent { WalletSettingsContent(WalletSettingsUiState(wallet = wallet, hasMnemonic = true),
            onNavigateToBackup = { calls += "backup" }, onRecovery = { calls += "recovery" },
            onRemove = { calls += "remove" }, onStartEditing = { calls += "rename" }, onAddAccount = { calls += "add" }) }
        click("Wallet name"); click("Backup wallet"); click("View recovery material")
        click("Wallet details")
        compose.onNodeWithText("m/44'/309'/0'").assertExists()
        compose.onNodeWithText("Done").performClick()
        click("Accounts"); click("wallet_settings_add_account")
        compose.onNodeWithContentDescription("common_back_cd").performClick()
        click("How wallet protection works")
        compose.onNodeWithText("Review wallet backup").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.waitUntil { compose.onAllNodesWithText("Review wallet backup").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("How wallet protection works").assertExists()
        click("Remove this wallet")
        compose.runOnIdle { assertEquals(listOf("rename", "backup", "recovery", "add", "backup", "remove"), calls) }
    }
    @Test fun `rename is focused cancellable and saves the edited name`() {
        var state by mutableStateOf(WalletSettingsUiState(wallet = wallet))
        var saved = ""
        compose.setContent { WalletSettingsContent(state,
            onStartEditing = { state = state.copy(isEditing = true, editName = wallet.name) },
            onEditName = { state = state.copy(editName = it) },
            onCancelEditing = { state = state.copy(isEditing = false) },
            onSaveName = { saved = state.editName; state = state.copy(isEditing = false) }) }
        click("Wallet name")
        compose.onNode(hasSetTextAction()).performTextReplacement("Trip")
        compose.onNodeWithText("wallet_settings_save").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.waitUntil { saved == "Trip" }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        click("Wallet name")
        compose.onNodeWithText("wallet_settings_cancel").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.waitUntil { !state.isEditing }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }
    @Test fun `wallet detail sheet survives saved state restoration`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { WalletSettingsContent(WalletSettingsUiState(wallet = wallet)) }
        click("Wallet details")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("m/44'/309'/0'").assertExists()
    }
    @Test fun `private key wallet hides unsupported accounts and shows real completed backup`() {
        compose.setContent { WalletSettingsContent(WalletSettingsUiState(wallet = wallet.copy(type = "raw_key", derivationPath = null), hasMnemonic = false, isBackedUp = true)) }
        compose.onNodeWithText("Accounts").assertDoesNotExist()
        compose.onNodeWithText("Completed").assertExists()
        compose.onNodeWithText("Source").assertDoesNotExist()
        click("Wallet details")
        compose.onNodeWithText("Imported Key").assertExists()
    }
    @Test fun `subaccount cannot create accounts even when mnemonic is available`() {
        compose.setContent { WalletSettingsContent(WalletSettingsUiState(wallet = wallet.copy(parentWalletId = "parent"), hasMnemonic = true)) }
        compose.onNodeWithText("Accounts").assertDoesNotExist()
        compose.onNodeWithText("Pending").assertExists()
    }
}
