package com.rjnr.pocketnode.ui.screens.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.rjnr.pocketnode.data.vault.VaultReadPreference
import com.rjnr.pocketnode.ui.security.ProtectionNotice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(onRecoveryComplete: () -> Unit, onContinueReadOnly: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val activity = LocalContext.current as FragmentActivity
    var explainRecovery by remember { mutableStateOf(false) }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelOperation() } }
    LaunchedEffect(state.stage) { if (state.stage == RecoveryStage.SUCCESS) onRecoveryComplete() }
    Scaffold(topBar = { TopAppBar(title = { Text("Recover wallet access") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.walletsLoaded) {
                if (state.error != null) ProtectionNotice("Recovery check unavailable", state.error!!, error = true)
                else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking wallet recovery…") }
                TextButton(onClick = onContinueReadOnly) { Text("Done") }
            } else if (state.missingWallets.isEmpty() && state.stage != RecoveryStage.SUCCESS) {
                ProtectionNotice("No wallets currently need recovery", "Keep your external recovery backups safe. This check does not verify your backups.")
                state.error?.let { ProtectionNotice("Recovery did not complete", it, error = true) }
                TextButton(onClick = onContinueReadOnly) { Text("Done") }
            } else {
                Text("Let’s get you back in", style = MaterialTheme.typography.headlineSmall)
                Text("Your balances and sync remain available while you recover access.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.restoredWalletCount > 0) Text("${state.restoredWalletCount} wallet(s) restored and verified.")
                state.error?.let { ProtectionNotice("Recovery did not complete", it, error = true) }
                state.outcomes.forEach { (id, message) -> ProtectionNotice(state.missingWallets.firstOrNull { it.walletId == id }?.name ?: id, message) }
                if (state.stage == RecoveryStage.RESTORING) {
                    Text("Restoring wallet access…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = viewModel::cancelOperation) { Text("Cancel") }
                } else state.missingWallets.forEach { wallet ->
                  key(wallet.walletId) {
                    var alternatives by remember { mutableStateOf(false) }
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(wallet.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (wallet.cleanupPending) "Backup cleanup pending" else "Recovery required", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (wallet.cleanupPending) {
                        ProtectionNotice("Keep your old backups", "Every retained backup must authenticate before cleanup. Backups made with different old PINs may remain unresolved. Recovery data stays preserved.")
                        if (wallet.hasBackup) Button(onClick = { viewModel.restoreBackup(activity, wallet.walletId) }, modifier = Modifier.fillMaxWidth()) { Text("Authenticate old backup PIN") }
                        if (!wallet.hasBackup && wallet.hasLegacyArchive) Button(onClick = { viewModel.resumeCleanup(activity, wallet.walletId) }, modifier = Modifier.fillMaxWidth()) { Text("Resume legacy cleanup") }
                    }
                    Button(onClick = { viewModel.unlock(activity, wallet.walletId, VaultReadPreference.PASSPHRASE) }, modifier = Modifier.fillMaxWidth()) { Text("Use wallet passphrase") }
                    OutlinedButton(onClick = { viewModel.unlock(activity, wallet.walletId, VaultReadPreference.DEFAULT) }, modifier = Modifier.fillMaxWidth()) { Text("Use device authentication") }
                    TextButton(onClick = { alternatives = !alternatives }) { Text(if (alternatives) "Hide other recovery methods" else "Other recovery methods") }
                    if (alternatives) {
                        if (wallet.hasBackup && !wallet.cleanupPending) OutlinedButton(onClick = { viewModel.restoreBackup(activity, wallet.walletId) }, modifier = Modifier.fillMaxWidth()) { Text("Authenticate old backup PIN") }
                        OutlinedButton(onClick = { viewModel.recoverExternal(activity, wallet.walletId, false) }, modifier = Modifier.fillMaxWidth()) { Text("Use recovery phrase") }
                        OutlinedButton(onClick = { viewModel.recoverExternal(activity, wallet.walletId, true) }, modifier = Modifier.fillMaxWidth()) { Text("Use private key (spending only)") }
                        Text("A private key restores spending for its wallet, not a missing recovery phrase or other accounts.", style = MaterialTheme.typography.bodySmall)
                    }
                      }
                    }
                  }
                }
                TextButton(onClick = onContinueReadOnly, enabled = state.stage != RecoveryStage.RESTORING) { Text("Keep wallets read-only and continue") }
                TextButton(onClick = { explainRecovery = !explainRecovery }) { Text(if (explainRecovery) "Hide recovery guide" else "Why keep a recovery backup?") }
                if (explainRecovery) ProtectionNotice("Keep the app and its data", "Your app PIN opens the dashboard; wallet secrets need independent wallet authentication. Try a surviving old-PIN backup, readable device key, recovery phrase or private key. A new passphrase cannot recreate a lost wallet key. Changing the Android PIN back cannot restore an erased key. Do not uninstall or clear app data while trying recovery. Without a usable backup or external secret, keep the affected wallet read-only.")
            }
        }
    }
}
