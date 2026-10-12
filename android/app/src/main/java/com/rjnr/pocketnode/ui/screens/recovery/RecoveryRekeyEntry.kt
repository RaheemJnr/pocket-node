package com.rjnr.pocketnode.ui.screens.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.components.MnemonicWordInput
import com.rjnr.pocketnode.ui.util.Bip39WordList

/**
 * Phrase or private-key entry that re-keys one wallet still missing keys on
 * the Recovery screen. The entered words or key live only in this
 * composable's state: cleared on Cancel and dropped when the entry closes. The window
 * is FLAG_SECURE while it shows, as on the import screen.
 */
@Composable
internal fun RecoveryRekeyEntry(
    entry: RekeyEntry,
    busy: Boolean,
    onSubmitPhrase: (List<String>) -> Unit,
    onSubmitPrivateKey: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val secureView = LocalView.current
    DisposableEffect(Unit) {
        val window = (secureView.context as? android.app.Activity)?.window
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val words = remember(entry.wallet.walletId) { mutableStateListOf(*Array(WORD_COUNT) { "" }) }
    var privateKey by remember(entry.wallet.walletId) { mutableStateOf("") }
    fun clearInput() {
        for (i in words.indices) words[i] = ""
        privateKey = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                if (entry.withPrivateKey) R.string.recovery_rekey_key_title else R.string.recovery_rekey_phrase_title,
                entry.wallet.name,
            ),
            style = MaterialTheme.typography.titleMedium
        )

        if (entry.withPrivateKey) {
            OutlinedTextField(
                value = privateKey,
                onValueChange = { privateKey = it.trim() },
                label = { Text(stringResource(R.string.mnemonic_import_private_key_label)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            for (row in 0 until WORD_COUNT / 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (col in 0..1) {
                        val index = row * 2 + col
                        val value = words[index]
                        MnemonicWordInput(
                            index = index,
                            value = value,
                            suggestions = if (value.length >= 2) Bip39WordList.getSuggestions(value) else emptyList(),
                            isError = value.isNotEmpty() && !Bip39WordList.isValidWord(value),
                            onValueChange = { words[index] = it.trim().lowercase() },
                            onSuggestionSelected = { words[index] = it },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        entry.error?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    // The input stays only while the entry is open, so a typo
                    // can be fixed; it is dropped when the entry closes.
                    if (entry.withPrivateKey) onSubmitPrivateKey(privateKey) else onSubmitPhrase(words.toList())
                },
                enabled = if (entry.withPrivateKey) privateKey.length >= 64 else words.all { it.isNotEmpty() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.recovery_rekey_submit))
            }
            TextButton(onClick = {
                clearInput()
                onCancel()
            }) {
                Text(stringResource(R.string.recovery_phrase_pin_cancel))
            }
        }
    }
}

private const val WORD_COUNT = 12
