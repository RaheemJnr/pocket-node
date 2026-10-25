package com.rjnr.pocketnode.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Guidance is not a migration flag or proof of a verified backup. */
@Composable
fun WalletProtectionEducation(onOpenBackup: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .08f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Protect your wallet before changing device security", style = MaterialTheme.typography.titleSmall)
            Text("Finish wallet passphrase setup and verify an external backup before removing or resetting your device lock or changing enrolled biometrics.", style = MaterialTheme.typography.bodySmall)
            Text("Existing wallets are checked before sending, DAO actions or viewing secrets. Follow the protection setup when prompted; reading this guide does not complete it.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide protection guide" else "Understand your PIN, passphrase and backup") }
            if (expanded) {
                Text("App PIN: opens the dashboard.\nAndroid PIN / biometrics: device security and optional wallet authentication.\nWallet passphrase: independent protection for this installation.\nRecovery phrase / private key: external backup for restoring a wallet.", style = MaterialTheme.typography.bodySmall)
                Text("Keep your wallet passphrase safely and separately from this phone. It does not replace an external backup after uninstall, cleared app data or device loss. Never share secrets with support.", style = MaterialTheme.typography.bodySmall)
                Text("If access already fails, keep the app and its data. Try a surviving old-PIN backup or external recovery material. A new passphrase cannot recreate a lost wallet key.", style = MaterialTheme.typography.bodySmall)
            }
            if (onOpenBackup != null) Button(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) { Text("Review wallet backup") }
            else Text("Next: open each wallet’s settings → Backup wallet to verify your external backup.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ProtectionNotice(title: String, message: String, error: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
