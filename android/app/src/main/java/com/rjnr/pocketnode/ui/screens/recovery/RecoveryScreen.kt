package com.rjnr.pocketnode.ui.screens.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    onRecoveryComplete: (List<RecoveredWallet>) -> Unit,
    onMnemonicRestore: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var pinInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.recovery_title)) })
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
                        text = "Enter your PIN to restore your wallets from backup.",
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
                            viewModel.attemptPinRecovery(pinInput.toCharArray())
                            pinInput = ""
                        },
                        enabled = pinInput.length == 6,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.recovery_with_pin))
                    }

                    TextButton(onClick = onMnemonicRestore) {
                        Text(stringResource(R.string.recovery_use_phrase_instead))
                    }
                }

                RecoveryStage.MNEMONIC_ENTRY -> {
                    Text(
                        text = "Enter your 12-word recovery phrase or private key to restore your wallet.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Button(
                        onClick = onMnemonicRestore,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.recovery_enter_phrase))
                    }
                }

                RecoveryStage.SUCCESS -> {
                    LaunchedEffect(uiState.recoveredWallets) {
                        onRecoveryComplete(uiState.recoveredWallets)
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

                    Button(onClick = onMnemonicRestore, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.recovery_restore_with_phrase))
                    }
                }
            }
        }
    }
}
