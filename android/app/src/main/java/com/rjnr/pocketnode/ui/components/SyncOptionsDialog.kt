package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.rjnr.pocketnode.data.gateway.models.SyncMode

@Composable
internal fun SyncOptionsDialog(
    currentMode: SyncMode,
    onDismiss: () -> Unit,
    onSelectMode: (SyncMode, Long?) -> Unit,
    title: String = "Sync Options",
    description: String = "Choose how much transaction history to sync:",
    availableModes: List<SyncMode> = SyncMode.entries.toList(),
    savedCustomBlockHeight: Long? = null,
    tipBlockNumber: Long = 0L,
    // Optional — when supplied and CUSTOM is selected, render a "Don't know
    // your block height? Look up on explorer" helper that opens the user's
    // address page in the CKB explorer (#85). Caller owns the URL launch so
    // this composable stays free of Intent / Network plumbing.
    onLookupAddressOnExplorer: (() -> Unit)? = null
) {
    // If the parent hides currentMode from availableModes, fall back to the first
    // shown option so the dialog never opens with an invisible selection.
    val initialMode = remember(currentMode, availableModes) {
        currentMode.takeIf { it in availableModes } ?: availableModes.firstOrNull() ?: currentMode
    }
    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }
    var customBlockHeight by remember(savedCustomBlockHeight) {
        mutableStateOf(savedCustomBlockHeight?.toString() ?: "")
    }
    var showCustomInput by remember(initialMode) { mutableStateOf(initialMode == SyncMode.CUSTOM) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))

                if (SyncMode.NEW_WALLET in availableModes) {
                    SyncOptionItem(
                        title = "New Wallet",
                        description = "No history \u2014 fastest startup",
                        icon = Lucide.Sparkles,
                        isSelected = selectedMode == SyncMode.NEW_WALLET,
                        onClick = { selectedMode = SyncMode.NEW_WALLET; showCustomInput = false }
                    )
                }
                if (SyncMode.RECENT in availableModes) {
                    SyncOptionItem(
                        title = "Recent (~30 days)",
                        description = "Last ~200k blocks \u2014 recommended",
                        icon = Lucide.Clock,
                        isSelected = selectedMode == SyncMode.RECENT,
                        onClick = { selectedMode = SyncMode.RECENT; showCustomInput = false }
                    )
                }
                if (SyncMode.FULL_HISTORY in availableModes) {
                    SyncOptionItem(
                        title = "Full History",
                        description = "From genesis \u2014 complete but slow",
                        icon = Lucide.History,
                        isSelected = selectedMode == SyncMode.FULL_HISTORY,
                        onClick = { selectedMode = SyncMode.FULL_HISTORY; showCustomInput = false }
                    )
                }
                if (SyncMode.CUSTOM in availableModes) {
                    SyncOptionItem(
                        title = "Custom Block Height",
                        description = "Start from a specific block",
                        icon = Lucide.SlidersHorizontal,
                        isSelected = selectedMode == SyncMode.CUSTOM,
                        onClick = { selectedMode = SyncMode.CUSTOM; showCustomInput = true }
                    )
                }

                if (showCustomInput) {
                    Spacer(Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Lucide.TriangleAlert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Transactions before this block won't appear in history, but your balance will still be correct.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val parsedHeight = customBlockHeight.toLongOrNull()
                    val exceedsTip = parsedHeight != null && tipBlockNumber > 0 && parsedHeight > tipBlockNumber
                    val invalidNumber = customBlockHeight.isNotBlank() && parsedHeight == null
                    OutlinedTextField(
                        value = customBlockHeight,
                        onValueChange = { customBlockHeight = it.filter { c -> c.isDigit() } },
                        label = { Text("Block Height") },
                        placeholder = { Text("e.g., 12000000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = invalidNumber || exceedsTip,
                        supportingText = when {
                            invalidNumber -> { { Text("Invalid block height") } }
                            exceedsTip -> { {
                                Text("Exceeds current tip ($tipBlockNumber). Enter a lower value.")
                            } }
                            else -> null
                        }
                    )

                    // "Don't know your block height?" helper (#85). Opens the
                    // CKB explorer at the user's address so they can scroll to
                    // their first transaction and read the block number off.
                    if (onLookupAddressOnExplorer != null) {
                        TextButton(
                            onClick = onLookupAddressOnExplorer,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Icon(
                                Lucide.ExternalLink,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Don't know your block height? Look up on explorer",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (selectedMode == SyncMode.FULL_HISTORY && SyncMode.FULL_HISTORY in availableModes) {
                    Spacer(Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Lucide.TriangleAlert,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Full history sync may take a long time on mainnet (18M+ blocks).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val confirmParsedHeight = customBlockHeight.toLongOrNull()
            val withinTip = tipBlockNumber <= 0L || confirmParsedHeight == null ||
                confirmParsedHeight <= tipBlockNumber
            Button(
                onClick = {
                    val custom = if (selectedMode == SyncMode.CUSTOM) confirmParsedHeight else null
                    onSelectMode(selectedMode, custom)
                },
                enabled = selectedMode != SyncMode.CUSTOM ||
                    (confirmParsedHeight != null && confirmParsedHeight > 0 && withinTip)
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
internal fun SyncOptionItem(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
