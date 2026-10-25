package com.rjnr.pocketnode.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.vault.VaultDevicePromptMode
import com.rjnr.pocketnode.data.vault.VaultReadPreference
import com.rjnr.pocketnode.ui.security.ProtectionNotice
import com.rjnr.pocketnode.ui.security.WalletProtectionEducation

internal enum class ProtectionSection(val title: String) {
    OVERVIEW("Wallet protection"), APP_LOCK("App lock"), RECOVERY("Recovery & backup"), GUIDE("How protection works")
}
private enum class ProtectionSheet { PASSPHRASE, RESET, UNLOCK, BACKUPS, DEVICES }

/** Public choices only. Secret entry stays in the existing native secure prompts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletProtectionSettings(
    state: SecuritySettingsUiState,
    selectPreference: (VaultReadPreference) -> Unit,
    change: () -> Unit,
    reset: (VaultDevicePromptMode?) -> Unit,
    enroll: (String, VaultDevicePromptMode?) -> Unit,
    cancel: () -> Unit,
    section: ProtectionSection = ProtectionSection.OVERVIEW,
    navigate: (ProtectionSection) -> Unit = {},
    backup: (String) -> Unit = {},
    recover: () -> Unit = {},
) {
    var sheet by remember { mutableStateOf<ProtectionSheet?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Run after composition has removed the informational sheet's window.
    LaunchedEffect(pendingAction) { pendingAction?.let { action -> pendingAction = null; action() } }
    fun perform(action: () -> Unit) { sheet = null; pendingAction = action }
    val enabled = !state.vaultBusy && pendingAction == null
    val preferenceLabel = when (state.unlockPreference) {
        VaultReadPreference.DEFAULT -> "Biometrics when available"
        VaultReadPreference.BIOMETRIC_ONLY -> "Biometrics only"
        VaultReadPreference.PASSPHRASE -> "Wallet passphrase"
        VaultReadPreference.CREDENTIAL_ONLY -> "Android screen lock"
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (section) {
            ProtectionSection.OVERVIEW -> {
                Card(shape = RoundedCornerShape(16.dp)) {
                    ProtectionAction("Wallet passphrase", "Manage your independent protection", enabled) { sheet = ProtectionSheet.PASSPHRASE }
                    HorizontalDivider()
                    ProtectionAction("Unlock wallet with", preferenceLabel, enabled) { sheet = ProtectionSheet.UNLOCK }
                    HorizontalDivider()
                    ProtectionAction("App lock", if (state.hasPin) "PIN enabled" else "No PIN set", enabled) { navigate(ProtectionSection.APP_LOCK) }
                    HorizontalDivider()
                    ProtectionAction("Recovery & backup", "Backup and recovery options", enabled) { navigate(ProtectionSection.RECOVERY) }
                }
                ProtectionNotice("Keep a recovery backup", "Your wallet passphrase protects this installation. Keep your recovery phrase or private key safe.")
                TextButton(onClick = { sheet = ProtectionSheet.BACKUPS }, enabled = enabled) { Text("Review backups") }
                TextButton(onClick = { navigate(ProtectionSection.GUIDE) }, enabled = enabled) { Text("How protection works") }
            }
            ProtectionSection.RECOVERY -> {
                Text("Keep a way back into each wallet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Card(shape = RoundedCornerShape(16.dp)) {
                    ProtectionAction("Review wallet backups", "Check your external recovery backup", enabled) { sheet = ProtectionSheet.BACKUPS }
                    HorizontalDivider()
                    ProtectionAction("Recover access", "Explore available recovery methods", enabled, recover)
                    HorizontalDivider()
                    ProtectionAction("Device access", "Manage optional unlock for each wallet", enabled) { sheet = ProtectionSheet.DEVICES }
                }
                ProtectionNotice("Before changing device security", "Finish passphrase setup and verify your backup before removing your screen lock or changing biometrics.")
                TextButton(onClick = { navigate(ProtectionSection.GUIDE) }, enabled = enabled) { Text("How protection works") }
            }
            ProtectionSection.GUIDE -> WalletProtectionEducation()
            ProtectionSection.APP_LOCK -> Unit
        }
        state.vaultMessage?.let { ProtectionNotice("Wallet protection", it) }
        // Preserve per-wallet outcomes even when the device-access sheet is closed.
        state.vaultWallets.filter { it.message.isNotBlank() }.forEach { ProtectionNotice(it.name, it.message) }
        if (state.vaultBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = cancel) { Text("Cancel") }
        }
    }
    sheet?.let { current ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current) {
                    ProtectionSheet.PASSPHRASE -> {
                        Text("Wallet passphrase", style = MaterialTheme.typography.headlineSmall)
                        Text("Manage the passphrase that protects your wallets on this device.")
                        Button(onClick = { perform(change) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Change passphrase") }
                        TextButton(onClick = { sheet = ProtectionSheet.RESET }, enabled = enabled) { Text("Forgot passphrase?") }
                    }
                    ProtectionSheet.RESET -> {
                        Text("Reset your passphrase", style = MaterialTheme.typography.headlineSmall)
                        Text("First, verify access using this device.")
                        ProtectionNotice("Some wallets may need separate recovery", "Only wallets you can authenticate will get the new passphrase. Other wallets keep their existing protection. Recover those wallets before a later known-passphrase change.")
                        Button(onClick = { perform { reset(null) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Verify with device authentication") }
                        OutlinedButton(onClick = { perform { reset(VaultDevicePromptMode.CREDENTIAL_ONLY) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Use Android screen lock") }
                    }
                    ProtectionSheet.UNLOCK -> {
                        Text("Unlock wallet with", style = MaterialTheme.typography.headlineSmall)
                        Text("Choose your everyday method. Your wallet passphrase remains available.")
                        Column(Modifier.selectableGroup()) {
                            listOf(VaultReadPreference.DEFAULT to "Biometrics when available", VaultReadPreference.PASSPHRASE to "Wallet passphrase", VaultReadPreference.CREDENTIAL_ONLY to "Android screen lock").forEach { (preference, label) ->
                                Row(Modifier.fillMaxWidth().selectable(state.unlockPreference == preference, enabled = enabled, role = Role.RadioButton, onClick = { selectPreference(preference) }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, Modifier.weight(1f))
                                    RadioButton(selected = state.unlockPreference == preference, onClick = null, enabled = enabled)
                                }
                            }
                        }
                    }
                    ProtectionSheet.BACKUPS, ProtectionSheet.DEVICES -> {
                        Text(if (current == ProtectionSheet.BACKUPS) "Review backups" else "Device access", style = MaterialTheme.typography.headlineSmall)
                        Text(if (current == ProtectionSheet.BACKUPS) "Choose a wallet to review its recovery backup." else "Enable optional device authentication for each wallet.")
                        if (state.vaultWallets.isEmpty()) Text("No wallets available. Return to your wallet list to check or add a wallet.")
                        state.vaultWallets.forEach { wallet ->
                            if (current == ProtectionSheet.BACKUPS) ProtectionAction(wallet.name, "Review recovery backup", enabled) { perform { backup(wallet.walletId) } }
                            else {
                                Text(wallet.name, style = MaterialTheme.typography.titleMedium)
                                if (wallet.message.isNotBlank()) Text(wallet.message)
                                OutlinedButton(onClick = { perform { enroll(wallet.walletId, null) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Enrol device authentication") }
                                OutlinedButton(onClick = { perform { enroll(wallet.walletId, VaultDevicePromptMode.CREDENTIAL_ONLY) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Enrol Android screen lock") }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                TextButton(onClick = { sheet = null }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ProtectionAction(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
