package com.rjnr.pocketnode.ui.screens.settings
import com.rjnr.pocketnode.BuildConfig
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w411dp-h1000dp")
class SettingsNavigationTest {
    private val compose = createAndroidComposeRule<NamedStringsActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, NamedStringsActivity::class.java))
        }
    }).around(compose)
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
    @Test fun `group navigation keeps sync privacy and help actions reachable`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            SettingsScreenUI(remember { SnackbarHostState() }, { calls += "protection" }, SettingsViewModel.UiState(),
                { calls += "node" }, LocalContext.current, onNavigateToWalletManager = { calls += "wallets" },
                onNavigateToContacts = { calls += "contacts" }, onNavigateToFaq = { calls += "faq" },
                showSyncDialog = { calls += "sync" }, requestNetworkSwitch = { calls += "network" },
                showThemeDialog = { calls += "theme" }, onTogglePriceService = { calls += "price:$it" }, onToggleUpdateService = { calls += "updates:$it" },
                onToggleBackgroundSync = { calls += "background:$it" }, showSyncStrategyDialog = { calls += "strategy" },
                onCheckForUpdate = { calls += "check" },
                onVersionTap = { calls += "version" }, onScanOtherAddresses = { calls += "scan" },
                onExportRestoreHint = { calls += "hint" })
        }
        click("Wallet protection"); click("Wallets"); click("settings_address_book")
        click("Sync & network")
        click("Sync Options"); click("Current Network"); click("Wallet Sync Strategy")
        click("settings_scan_other_addresses"); click("restore_hint_export_title")
        if (BuildConfig.BG_FGS_ENABLED) compose.onNode(isToggleable()).performScrollTo().performClick()
        else compose.onNodeWithText("Background Sync").assertDoesNotExist()
        click("Node Status & Logs")
        compose.onNodeWithContentDescription("common_back_cd").performClick()
        click("Privacy & services")
        compose.onNodeWithText("These services see your IP address.").assertExists()
        compose.onNodeWithText("Fiat price").assertExists()
        compose.onNodeWithText("Update checks").assertExists()
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithContentDescription("common_back_cd").performClick()
        click("Appearance")
        click("Help & about"); click("settings_help_faq"); click("Version")
        compose.onNodeWithText("Open Source").performScrollTo().assertExists()
        compose.onNodeWithText("settings_privacy_policy").performScrollTo().assertExists()
        if (BuildConfig.UPDATER_ENABLED) click("Check for updates")
        else compose.onNodeWithText("Check for updates").assertDoesNotExist()
        val expected = mutableListOf("protection", "wallets", "contacts", "sync", "network", "strategy", "scan", "hint")
        if (BuildConfig.BG_FGS_ENABLED) expected += "background:false"
        expected += listOf("node", "price:false", "updates:false", "theme", "faq", "version")
        if (BuildConfig.UPDATER_ENABLED) expected += "check"
        compose.runOnIdle { assertEquals(expected, calls) }
    }
    @Test fun `nested settings restores its destination and system Back returns to groups`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SettingsScreenUI(remember { SnackbarHostState() }, {}, SettingsViewModel.UiState(), {}, LocalContext.current,
                showSyncDialog = {}, requestNetworkSwitch = {}, showThemeDialog = {})
        }
        click("Privacy & services")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("These services see your IP address.").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Wallets").assertExists()
        compose.onNodeWithText("These services see your IP address.").assertDoesNotExist()
    }

}
