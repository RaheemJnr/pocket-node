package com.rjnr.pocketnode.ui.screens.security

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityChecklistScreen(
    hasPinOrBiometrics: Boolean,
    hasMnemonicBackup: Boolean,
    isMnemonicWallet: Boolean,
    onSetupPin: () -> Unit,
    onBackupMnemonic: () -> Unit,
    onBack: () -> Unit
) {
    // For raw-key wallets, only PIN matters (no mnemonic to back up)
    val totalItems = if (isMnemonicWallet) 2 else 1
    val completedItems = buildList {
        if (hasPinOrBiometrics) add(true)
        if (isMnemonicWallet && hasMnemonicBackup) add(true)
    }
    val completedCount = completedItems.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_checklist_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back_cd),
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(
                    horizontal = com.rjnr.pocketnode.ui.util.responsiveDp(16.dp, 24.dp, 32.dp),
                    vertical = 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "$completedCount of $totalItems complete",
                style = MaterialTheme.typography.titleMedium,
                color = if (completedCount == totalItems) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )

            LinearProgressIndicator(
                progress = { completedCount.toFloat() / totalItems },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            SecurityChecklistItem(
                title = "PIN or biometrics",
                description = "Protects your wallet and enables encrypted backups",
                isComplete = hasPinOrBiometrics,
                onAction = onSetupPin,
                actionLabel = if (hasPinOrBiometrics) "Done" else "Set up"
            )

            if (isMnemonicWallet) {
                SecurityChecklistItem(
                    title = "Recovery phrase",
                    description = "Write down your 12 words so you can restore your wallet if this device is lost",
                    isComplete = hasMnemonicBackup,
                    onAction = onBackupMnemonic,
                    actionLabel = if (hasMnemonicBackup) "Done" else "Back up"
                )
            }
        }
    }
}

@Composable
private fun SecurityChecklistItem(
    title: String,
    description: String,
    isComplete: Boolean,
    onAction: () -> Unit,
    actionLabel: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isComplete) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isComplete) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isComplete) {
                FilledTonalButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
