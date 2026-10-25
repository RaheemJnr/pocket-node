package com.rjnr.pocketnode.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

@Composable
internal fun WalletProtectionSettings(
    state: SecuritySettingsUiState,
    selectPreference: (VaultReadPreference) -> Unit,
    change: () -> Unit,
    reset: (VaultDevicePromptMode?) -> Unit,
    enroll: (String, VaultDevicePromptMode?) -> Unit,
    cancel: () -> Unit,
) {
    var resetExpanded by remember { mutableStateOf(false) }
    var devicesExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your passphrase is the fallback. Choose how you unlock day to day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Preferred unlock method", style = MaterialTheme.typography.titleSmall)
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.selectableGroup()) {
                listOf(
                    Triple(VaultReadPreference.DEFAULT, "Biometrics when available", "Optional everyday authentication"),
                    Triple(VaultReadPreference.PASSPHRASE, "Wallet passphrase", "Independent wallet protection"),
                    Triple(VaultReadPreference.CREDENTIAL_ONLY, "Android screen lock", "Use device PIN, pattern or password"),
                ).forEach { (preference, label, detail) ->
                    Row(Modifier.fillMaxWidth().selectable(state.unlockPreference == preference, enabled = !state.vaultBusy,
                        role = Role.RadioButton, onClick = { selectPreference(preference) }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall) }
                        RadioButton(selected = state.unlockPreference == preference, onClick = null, enabled = !state.vaultBusy)
                    }
                }
            }
        }
        Text("Wallet passphrase", style = MaterialTheme.typography.titleSmall)
        Card(shape = RoundedCornerShape(16.dp)) {
            ProtectionAction("Change passphrase", "You know your current passphrase", !state.vaultBusy, change)
            HorizontalDivider()
            ProtectionAction("Forgot passphrase?", "Choose how to verify device access", !state.vaultBusy) { resetExpanded = !resetExpanded }
            if (resetExpanded) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reset authenticates each accessible wallet. Others keep their previous protection and require separate recovery before a later known-passphrase change.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { reset(null) }, enabled = !state.vaultBusy, modifier = Modifier.fillMaxWidth()) { Text("Verify with device authentication") }
                OutlinedButton(onClick = { reset(VaultDevicePromptMode.CREDENTIAL_ONLY) }, enabled = !state.vaultBusy, modifier = Modifier.fillMaxWidth()) { Text("Use Android screen lock") }
            }
        }
        Card(shape = RoundedCornerShape(16.dp)) {
            ProtectionAction("Manage device access", "Enrol optional authentication for each wallet", !state.vaultBusy) { devicesExpanded = !devicesExpanded }
            if (devicesExpanded) state.vaultWallets.forEach { wallet ->
                HorizontalDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(wallet.name, style = MaterialTheme.typography.titleSmall)
                    if (wallet.message.isNotBlank()) Text(wallet.message, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { enroll(wallet.walletId, null) }, enabled = !state.vaultBusy, modifier = Modifier.fillMaxWidth()) { Text("Enrol device authentication") }
                    OutlinedButton(onClick = { enroll(wallet.walletId, VaultDevicePromptMode.CREDENTIAL_ONLY) }, enabled = !state.vaultBusy, modifier = Modifier.fillMaxWidth()) { Text("Enrol Android screen lock") }
                }
            }
        }
        state.vaultMessage?.let { ProtectionNotice("Wallet protection", it) }
        if (state.vaultBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = cancel) { Text("Cancel") }
        }
        WalletProtectionEducation()
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
