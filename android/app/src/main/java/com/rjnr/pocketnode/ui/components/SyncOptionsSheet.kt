package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.TriangleAlert
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.ui.education.EducationTopic
import com.rjnr.pocketnode.ui.util.centredContentMaxWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncOptionsSheet(
    currentMode: SyncMode,
    onDismiss: () -> Unit,
    onSelectMode: (SyncMode, Long?) -> Unit,
    onTopicHelp: (EducationTopic) -> Unit,
    title: String? = null,
    description: String? = null,
    availableModes: List<SyncMode> = SyncMode.entries.toList(),
    savedCustomBlockHeight: Long? = null,
    tipBlockNumber: Long = 0L,
    onLookupAddressOnExplorer: (() -> Unit)? = null,
    showHelpIcons: Boolean = true,
) {
    val initialMode = remember(currentMode, availableModes) {
        currentMode.takeIf { it in availableModes } ?: availableModes.firstOrNull() ?: currentMode
    }
    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }
    var customBlockHeight by remember(savedCustomBlockHeight) {
        mutableStateOf(savedCustomBlockHeight?.toString() ?: "")
    }
    var showCustomInput by remember(initialMode) { mutableStateOf(initialMode == SyncMode.CUSTOM) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = centredContentMaxWidth())
                .align(Alignment.CenterHorizontally)
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title ?: stringResource(R.string.sync_options_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description ?: stringResource(R.string.sync_options_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            if (SyncMode.NEW_WALLET in availableModes) {
                SyncOptionRow(
                    titleRes = R.string.sync_mode_new_title,
                    pickIfRes = R.string.sync_mode_new_pick_if,
                    icon = Lucide.Sparkles,
                    isSelected = selectedMode == SyncMode.NEW_WALLET,
                    isRecommended = false,
                    onClick = { selectedMode = SyncMode.NEW_WALLET; showCustomInput = false },
                    onHelp = if (showHelpIcons) { { onTopicHelp(EducationTopic.SyncModeNew) } } else null,
                )
            }
            if (SyncMode.RECENT in availableModes) {
                SyncOptionRow(
                    titleRes = R.string.sync_mode_recent_title,
                    pickIfRes = R.string.sync_mode_recent_pick_if,
                    icon = Lucide.Clock,
                    isSelected = selectedMode == SyncMode.RECENT,
                    isRecommended = true,
                    onClick = { selectedMode = SyncMode.RECENT; showCustomInput = false },
                    onHelp = if (showHelpIcons) { { onTopicHelp(EducationTopic.SyncModeRecent) } } else null,
                )
            }
            if (SyncMode.CUSTOM in availableModes) {
                SyncOptionRow(
                    titleRes = R.string.sync_mode_custom_title,
                    pickIfRes = R.string.sync_mode_custom_pick_if,
                    icon = Lucide.SlidersHorizontal,
                    isSelected = selectedMode == SyncMode.CUSTOM,
                    isRecommended = false,
                    onClick = { selectedMode = SyncMode.CUSTOM; showCustomInput = true },
                    onHelp = if (showHelpIcons) { { onTopicHelp(EducationTopic.SyncModeCustom) } } else null,
                )
            }
            if (SyncMode.FULL_HISTORY in availableModes) {
                SyncOptionRow(
                    titleRes = R.string.sync_mode_full_title,
                    pickIfRes = R.string.sync_mode_full_pick_if,
                    icon = Lucide.History,
                    isSelected = selectedMode == SyncMode.FULL_HISTORY,
                    isRecommended = false,
                    onClick = { selectedMode = SyncMode.FULL_HISTORY; showCustomInput = false },
                    onHelp = if (showHelpIcons) { { onTopicHelp(EducationTopic.SyncModeFull) } } else null,
                )
            }

            if (showCustomInput) {
                CustomBlockInput(
                    value = customBlockHeight,
                    onValueChange = { customBlockHeight = it.filter(Char::isDigit) },
                    tipBlockNumber = tipBlockNumber,
                    onLookupAddressOnExplorer = onLookupAddressOnExplorer,
                    onHelp = if (showHelpIcons) { { onTopicHelp(EducationTopic.BlockHeight) } } else null,
                )
            }

            if (selectedMode == SyncMode.FULL_HISTORY) {
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Lucide.TriangleAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sync_full_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.width(8.dp))
                val parsedHeight = customBlockHeight.toLongOrNull()
                val withinTip = tipBlockNumber <= 0L || parsedHeight == null || parsedHeight <= tipBlockNumber
                Button(
                    onClick = {
                        val custom = if (selectedMode == SyncMode.CUSTOM) parsedHeight else null
                        onSelectMode(selectedMode, custom)
                    },
                    enabled = selectedMode != SyncMode.CUSTOM ||
                        (parsedHeight != null && parsedHeight > 0 && withinTip),
                ) {
                    Text(stringResource(R.string.common_apply))
                }
            }
        }
    }
}

@Composable
private fun SyncOptionRow(
    titleRes: Int,
    pickIfRes: Int,
    icon: ImageVector,
    isSelected: Boolean,
    isRecommended: Boolean,
    onClick: () -> Unit,
    onHelp: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (isRecommended) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = stringResource(R.string.sync_mode_recent_recommended),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(pickIfRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onHelp != null) {
                IconButton(onClick = onHelp, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Lucide.CircleHelp,
                        contentDescription = stringResource(R.string.common_help_cd),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomBlockInput(
    value: String,
    onValueChange: (String) -> Unit,
    tipBlockNumber: Long,
    onLookupAddressOnExplorer: (() -> Unit)?,
    onHelp: (() -> Unit)?,
) {
    Spacer(Modifier.height(4.dp))
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Lucide.TriangleAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.sync_custom_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    val parsedHeight = value.toLongOrNull()
    val exceedsTip = parsedHeight != null && tipBlockNumber > 0 && parsedHeight > tipBlockNumber
    val invalidNumber = value.isNotBlank() && parsedHeight == null

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.sync_custom_block_label)) },
            placeholder = { Text(stringResource(R.string.sync_custom_block_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
            singleLine = true,
            isError = invalidNumber || exceedsTip,
            supportingText = when {
                invalidNumber -> { { Text(stringResource(R.string.sync_custom_invalid)) } }
                exceedsTip -> { { Text(stringResource(R.string.sync_custom_exceeds_tip, tipBlockNumber)) } }
                else -> null
            },
        )
        if (onHelp != null) {
            IconButton(onClick = onHelp) {
                Icon(
                    Lucide.CircleHelp,
                    contentDescription = stringResource(R.string.common_help_cd),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (onLookupAddressOnExplorer != null) {
        TextButton(
            onClick = onLookupAddressOnExplorer,
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.sync_explorer_lookup),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
