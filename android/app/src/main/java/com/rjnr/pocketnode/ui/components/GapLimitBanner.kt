package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R

/**
 * #382: history contains an outgoing transaction whose change went to an
 * address derived from this seed that Pocket Node does not scan yet (seed
 * previously used in Neuron or another standard BIP44 wallet). Deliberately
 * calm — secondaryContainer, Info icon — because the symptom already reads
 * as fund loss and the message is "your funds are safe".
 */
@Composable
fun GapLimitBanner(
    onLearnMore: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null when the explicit deep scan is available (#382 Tier 2 — wallet imported before auto-scan shipped). */
    onScanNow: (() -> Unit)? = null,
    /** True while chain-axis candidates are still resolving — scan in progress. */
    scanning: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.gap_limit_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = stringResource(
                    if (scanning) R.string.gap_limit_banner_body_scanning
                    else R.string.gap_limit_banner_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.gap_limit_banner_action_dismiss))
                }
                if (onScanNow != null && !scanning) {
                    TextButton(onClick = onLearnMore) {
                        Text(stringResource(R.string.gap_limit_banner_action_learn_more))
                    }
                    FilledTonalButton(onClick = onScanNow) {
                        Text(stringResource(R.string.gap_limit_banner_action_scan_now))
                    }
                } else {
                    FilledTonalButton(onClick = onLearnMore) {
                        Text(stringResource(R.string.gap_limit_banner_action_learn_more))
                    }
                }
            }
        }
    }
}
