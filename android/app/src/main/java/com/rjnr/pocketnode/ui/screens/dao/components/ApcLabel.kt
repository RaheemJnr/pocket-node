package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide

/**
 * APC label with a tap-to-explain affordance. Reused by both the DAO
 * overview header and the per-deposit cards so the explanation lives
 * in one place.
 *
 * Why a tooltip exists: Pocket Node shows *realized* APC (the actual
 * compensation accrued on this specific deposit divided by elapsed
 * time), while the Neuron desktop wallet shows *theoretical* APC (a
 * forward-looking projection from CKB tokenomics). Users who compare
 * the two numbers will see different values and reasonably assume one
 * is wrong. The tooltip explains the difference so the user can act
 * on the right mental model.
 */
@Composable
fun ApcLabel(
    apc: Double,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    prefix: String = "APC",
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.clickable { showInfo = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$prefix ~${"%.2f".format(apc)}%",
            style = style,
            color = color,
            textDecoration = TextDecoration.Underline,
        )
        Icon(
            imageVector = Lucide.Info,
            contentDescription = "What is APC?",
            tint = color,
            modifier = Modifier.size(12.dp),
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("About this APC") },
            text = {
                Text(
                    text = "This number is the realized annual compensation rate: " +
                        "the actual CKB earned on this deposit divided by the time " +
                        "it has been deposited.\n\n" +
                        "It may differ from the APC shown by other CKB wallets such " +
                        "as Neuron. Neuron displays a theoretical APC computed from " +
                        "the protocol's tokenomics — a forward-looking projection " +
                        "assuming idealised conditions. Realized APC tracks what " +
                        "your specific deposit has earned, which fluctuates with " +
                        "actual on-chain participation.\n\n" +
                        "Both numbers are valid. The realized number is the truth " +
                        "about your deposit; the theoretical number is an estimate " +
                        "of what a new deposit might earn.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it")
                }
            },
        )
    }
}
