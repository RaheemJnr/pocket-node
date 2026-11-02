package com.rjnr.pocketnode.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.rjnr.pocketnode.ui.util.uaTestTag

/** A separate authenticated change flow; no setup-route argument can authorize replacement. */
@Composable
fun PinChangeScreen(
    onBack: () -> Unit,
    onRequireUnlock: () -> Unit,
    viewModel: PinChangeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val back = { if (state.needsUnlock) onRequireUnlock() else if (!state.busy) onBack() }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = back, enabled = !state.busy, modifier = Modifier.align(Alignment.Start)) { Text("Back") }
        Text(when (state.step) {
            PinChangeStep.CURRENT -> "Enter current PIN"
            PinChangeStep.NEW -> "Choose new PIN"
            PinChangeStep.CONFIRM -> "Confirm new PIN"
        }, style = MaterialTheme.typography.headlineMedium)
        Text("Older recovery copies still use their original backup PIN. Wallet passphrases stay the same.")
        state.message?.let { Text(it) }
        if (state.needsUnlock) {
            Button(onClick = onRequireUnlock) { Text("Lock and unlock") }
        } else {
            Text("●".repeat(state.digits.length), style = MaterialTheme.typography.headlineMedium)
            if (state.busy) CircularProgressIndicator()
            listOf("123", "456", "789", "0").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { digit ->
                        FilledTonalButton(onClick = { viewModel.digit(digit) }, enabled = !state.busy,
                            modifier = Modifier.size(64.dp).uaTestTag("pin-change-key-$digit")) { Text(digit.toString()) }
                    }
                }
            }
            TextButton(onClick = viewModel::deleteDigit, enabled = !state.busy) { Text("Delete digit") }
        }
    }
}
