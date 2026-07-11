package com.rjnr.pocketnode.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Informed-consent dialog shown before persisting a wallet at the V1
 * software-only fallback because the device has no biometric and no device
 * credential (security F1/F3).
 *
 * Onboarding already warned about this weaker storage mode; the other flows
 * (Add Wallet, sub-account creation, discovery restore) previously downgraded
 * to V1 silently. This is the shared warning so every flow surfaces the same
 * informed consent before the downgrade. [onConfirm] proceeds with the V1
 * fallback; [onDismiss] aborts.
 */
@Composable
fun NoDeviceLockConsentDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Continue without a device lock?") },
        text = {
            Text(
                text = "This phone has no PIN, pattern, password, or biometric set.\n\n" +
                    "Your wallet will still work, but the keys will be stored in a software-only " +
                    "encryption layer that protects against casual theft of the device but is " +
                    "not hardware-bound to your fingerprint or PIN.\n\n" +
                    "If you enable a device lock later, the app will upgrade your wallet to " +
                    "hardware-backed protection automatically on the next launch.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Continue anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
