package com.rjnr.pocketnode.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.rjnr.pocketnode.R

/**
 * Confirmation screen for the Forgot-PIN destructive recovery flow.
 *
 * The previous Forgot-PIN wire routed the user to the standard
 * `MnemonicImport` screen which then refused the import as
 * "already imported" — a dead-end for any user actually locked out
 * (reported via Telegram).
 *
 * This screen makes the destructive cost explicit before any state
 * is touched: every local wallet, key, and cache is wiped; only the
 * 12-word recovery phrase can restore access. After the user
 * confirms, [ForgotPinViewModel.executeReset] runs the wipe and
 * restarts the process so onboarding starts fresh.
 *
 * Back navigation works from this screen — the caller no longer
 * `popUpTo(Auth) inclusive=true`-s on the way in, so the user can
 * always return to the PIN entry if they remember the PIN after
 * arriving here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPinScreen(
    onBack: () -> Unit,
    viewModel: ForgotPinViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.forgot_pin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !uiState.isResetting) {
                        Icon(Lucide.ArrowLeft, contentDescription = stringResource(R.string.forgot_pin_back_cd))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(56.dp),
            )

            Text(
                text = stringResource(R.string.forgot_pin_heading),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = stringResource(R.string.forgot_pin_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.forgot_pin_reassurance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.executeReset() },
                enabled = !uiState.isResetting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isResetting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.forgot_pin_resetting))
                } else {
                    Text(stringResource(R.string.forgot_pin_confirm))
                }
            }

            TextButton(
                onClick = onBack,
                enabled = !uiState.isResetting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.forgot_pin_cancel))
            }
        }
    }
}
