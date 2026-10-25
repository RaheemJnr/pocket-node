package com.rjnr.pocketnode.ui.screens.settings

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.vault.VaultDevicePromptMode
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "w411dp-h1000dp")
class WalletProtectionSettingsTest {
    private val compose = createAndroidComposeRule<NamedStringsActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, NamedStringsActivity::class.java))
        }
    }).around(compose)
    @Test fun `overview hides details and reset retains explicit credential route`() {
        var resetMode: VaultDevicePromptMode? = null
        var calls = 0
        compose.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                WalletProtectionSettings(SecuritySettingsUiState(), {}, {}, { resetMode = it; calls++ }, { _, _ -> }, {})
            }
        }
        compose.onNodeWithText("Unlock wallet with").assertExists()
        compose.onNodeWithText("Change passphrase").assertDoesNotExist()
        compose.onNodeWithText("Wallet passphrase").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Forgot passphrase?").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Other wallets keep their existing protection.", substring = true).assertExists()
        compose.onNodeWithText("Use Android screen lock").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, calls); assertEquals(VaultDevicePromptMode.CREDENTIAL_ONLY, resetMode) }
        compose.onNodeWithText("Reset your passphrase").assertDoesNotExist()
    }
    @Test fun `backup selection preserves the selected wallet id`() {
        var selected: String? = null
        compose.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                WalletProtectionSettings(SecuritySettingsUiState(vaultWallets = listOf(WalletSecurityStatus("a", "Savings", ""), WalletSecurityStatus("b", "Spending", ""))), {}, {}, {}, { _, _ -> }, {}, backup = { selected = it })
            }
        }
        compose.onNodeWithText("Review backups").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Spending").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("b", selected) }
        compose.onNodeWithText("Choose a wallet to review its recovery backup.").assertDoesNotExist()
    }
    @Test fun `device enrollment preserves both authentication choices`() {
        val modes = mutableListOf<VaultDevicePromptMode?>()
        val ids = mutableListOf<String>()
        compose.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                WalletProtectionSettings(SecuritySettingsUiState(vaultWallets = listOf(WalletSecurityStatus("a", "Savings", ""))), {}, {}, {}, { id, mode -> ids.add(id); modes.add(mode) }, {}, section = ProtectionSection.RECOVERY)
            }
        }
        compose.onNodeWithText("Device access").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Enrol Android screen lock").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Device access").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Enrol device authentication").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(listOf("a", "a"), ids); assertEquals(listOf(VaultDevicePromptMode.CREDENTIAL_ONLY, null), modes) }
    }

    @Test fun `saved biometrics only remains selected and can be selected again`() {
        val preference = androidx.compose.runtime.mutableStateOf(com.rjnr.pocketnode.data.vault.VaultReadPreference.BIOMETRIC_ONLY)
        val selections = mutableListOf<com.rjnr.pocketnode.data.vault.VaultReadPreference>()
        compose.setContent {
            WalletProtectionSettings(SecuritySettingsUiState(unlockPreference = preference.value), { selections.add(it); preference.value = it }, {}, {}, { _, _ -> }, {})
        }
        compose.onNodeWithText("Unlock wallet with").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(hasText("Biometrics only") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)).assertIsSelected()
        compose.onNode(hasText("Wallet passphrase") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(hasText("Biometrics only") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)).assertIsNotSelected().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(hasText("Biometrics only") and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)).assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(com.rjnr.pocketnode.data.vault.VaultReadPreference.PASSPHRASE, com.rjnr.pocketnode.data.vault.VaultReadPreference.BIOMETRIC_ONLY), selections) }
    }

}
