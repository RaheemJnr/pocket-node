package com.rjnr.pocketnode.ui.screens.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.rjnr.pocketnode.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    /** Every recovered wallet's keys are stored and verified. */
    onRecoveryComplete: () -> Unit,
    onMnemonicRestore: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var pinInput by remember { mutableStateOf("") }
    // MainActivity extends FragmentActivity: the restored keys are written
    // through the key store's screen-lock prompt.
    val activity = LocalContext.current as FragmentActivity

    // The phrase restore opens only when the ViewModel allows it: after the
    // app PIN is verified, or with no PIN set. Start fresh restarts the app.
    LaunchedEffect(uiState.phraseRestoreReady) {
        if (uiState.phraseRestoreReady) {
            viewModel.onPhraseRestoreOpened()
            onMnemonicRestore()
        }
    }

    // No screen lock: same consent as wallet import before the software-only key.
    if (uiState.showNoLockConsent) {
        com.rjnr.pocketnode.ui.components.NoDeviceLockConsentDialog(
            onConfirm = { viewModel.confirmNoLockConsent(activity) },
            onDismiss = viewModel::dismissNoLockConsent,
        )
    }

    if (uiState.showStartFreshConfirm) {
        StartFreshDialog(
            inProgress = uiState.isStartingFresh,
            failed = uiState.startFreshFailed,
            onConfirm = viewModel::confirmStartFresh,
            onDismiss = viewModel::dismissStartFresh,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.recovery_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = com.rjnr.pocketnode.ui.util.responsiveDp(16.dp, 24.dp, 32.dp),
                    vertical = 24.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )

            Text(
                text = "Your wallet data needs recovery",
                style = MaterialTheme.typography.headlineSmall
            )

            when (uiState.stage) {
                RecoveryStage.PIN_ENTRY -> {
                    Text(
                        text = if (uiState.hasBackups) {
                            "Enter your PIN to restore your wallets from backup."
                        } else {
                            stringResource(R.string.recovery_pin_for_missing)
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text(stringResource(R.string.recovery_pin_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    uiState.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.attemptPinRecovery(pinInput.toCharArray(), activity)
                            pinInput = ""
                        },
                        enabled = pinInput.length == 6,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.recovery_with_pin))
                    }

                    TextButton(onClick = {
                        pinInput = ""
                        viewModel.requestPhraseRestore()
                    }) {
                        Text(stringResource(R.string.recovery_use_phrase_instead))
                    }
                }

                RecoveryStage.PHRASE_PIN_ENTRY -> {
                    Text(
                        text = stringResource(R.string.recovery_phrase_pin_message),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinInput = it },
                        label = { Text(stringResource(R.string.recovery_pin_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    uiState.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.attemptPhrasePin(pinInput.toCharArray())
                            pinInput = ""
                        },
                        enabled = pinInput.length == 6,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.recovery_phrase_pin_continue))
                    }

                    TextButton(onClick = {
                        pinInput = ""
                        viewModel.cancelPhrasePin()
                    }) {
                        Text(stringResource(R.string.recovery_phrase_pin_cancel))
                    }
                }

                RecoveryStage.MNEMONIC_ENTRY -> {
                    Text(
                        text = "Enter your 12-word recovery phrase or private key to restore your wallet.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    uiState.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (uiState.hasMissingWallets) {
                        // Wallets on this phone still lack keys: restore them
                        // in place (the import path refuses their addresses).
                        Button(
                            onClick = viewModel::requestMissingRestore,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.recovery_restore_missing))
                        }
                    }

                    Button(
                        onClick = viewModel::requestPhraseRestore,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.recovery_enter_phrase))
                    }
                }

                RecoveryStage.RESTORING -> {
                    Text(
                        text = stringResource(R.string.recovery_restoring),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    CircularProgressIndicator()
                }

                RecoveryStage.RESTORE_FAILED -> {
                    uiState.error?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    val rekey = uiState.rekey
                    if (rekey != null) {
                        RecoveryRekeyEntry(
                            entry = rekey,
                            busy = uiState.isRekeying,
                            onSubmitPhrase = { viewModel.submitRekeyPhrase(activity, it) },
                            onSubmitPrivateKey = { viewModel.submitRekeyPrivateKey(activity, it) },
                            onCancel = viewModel::cancelRekey,
                        )
                        return@Column
                    }

                    if (uiState.missingWallets.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.recovery_missing_wallets,
                                uiState.missingWalletNames.joinToString(", ")
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        for (wallet in uiState.missingWallets) {
                            OutlinedButton(
                                onClick = { viewModel.openRekey(wallet.walletId, withPrivateKey = false) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.recovery_rekey_with_phrase, wallet.name))
                            }
                            if (wallet.isRawKey) {
                                OutlinedButton(
                                    onClick = { viewModel.openRekey(wallet.walletId, withPrivateKey = true) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.recovery_rekey_with_private_key, wallet.name))
                                }
                            }
                        }
                    }

                    if (uiState.canRetryRestore) {
                        Button(
                            onClick = viewModel::retryRestore,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.recovery_restore_retry))
                        }
                    }

                    TextButton(onClick = viewModel::requestPhraseRestore) {
                        Text(stringResource(R.string.recovery_import_other_wallet))
                    }
                }

                RecoveryStage.SUCCESS -> {
                    LaunchedEffect(Unit) {
                        onRecoveryComplete()
                    }

                    Text(
                        text = "Recovery successful!",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    if (uiState.failedWalletIds.isNotEmpty()) {
                        Text(
                            text = "${uiState.failedWalletIds.size} wallet(s) could not be recovered. You can restore them with their recovery phrase.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    CircularProgressIndicator()
                }

                RecoveryStage.ERROR -> {
                    Text(
                        text = uiState.error ?: "An unexpected error occurred.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Button(onClick = viewModel::requestPhraseRestore, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.recovery_import_other_wallet))
                    }
                }
            }
        }
    }
}

/**
 * Shown when the app PIN is permanently locked: the app removes every wallet
 * and key on this phone and restarts as a fresh install.
 */
@Composable
private fun StartFreshDialog(
    inProgress: Boolean,
    failed: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recovery_start_fresh_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recovery_start_fresh_body))
                if (failed) {
                    Text(
                        text = stringResource(R.string.recovery_start_fresh_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !inProgress) {
                Text(
                    text = stringResource(R.string.recovery_start_fresh_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inProgress) {
                Text(stringResource(R.string.recovery_start_fresh_cancel))
            }
        }
    )
}
