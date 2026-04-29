package com.rjnr.pocketnode.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R

data class SecurityBannerState(
    val hasPinOrBiometrics: Boolean,
    val hasMnemonicBackup: Boolean
) {
    val isVisible: Boolean get() = !hasPinOrBiometrics || !hasMnemonicBackup
    val allComplete: Boolean get() = hasPinOrBiometrics && hasMnemonicBackup

    @get:StringRes val messageRes: Int
        get() = when {
            !hasPinOrBiometrics && !hasMnemonicBackup -> R.string.security_banner_secure_wallet
            !hasPinOrBiometrics -> R.string.security_banner_set_up_pin
            !hasMnemonicBackup -> R.string.security_banner_back_up_phrase
            else -> R.string.empty
        }

    @get:StringRes val actionLabelRes: Int
        get() = when {
            !hasPinOrBiometrics && !hasMnemonicBackup -> R.string.security_banner_action_set_up_security
            !hasPinOrBiometrics -> R.string.security_banner_action_set_up_pin
            !hasMnemonicBackup -> R.string.security_banner_action_back_up_now
            else -> R.string.empty
        }
}

@Composable
fun SecurityBanner(
    state: SecurityBannerState,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(state.messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            FilledTonalButton(onClick = onActionClick) {
                Text(stringResource(state.actionLabelRes))
            }
        }
    }
}
