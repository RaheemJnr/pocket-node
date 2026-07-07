package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R
import java.util.Locale

/**
 * #382 Tier 2: the gap-limit scan found live funds on receiving/change-chain
 * addresses derived from this seed (paths another BIP44 wallet used). Shown
 * instead of the Tier 1 GapLimitBanner once the scan resolves FOUND. Reads as
 * good news; Tier 3 replaces the FAQ pointer with a one-tap sweep.
 */
@Composable
fun FoundFundsCard(
    foundCkb: Double,
    addressCount: Int,
    onLearnMore: () -> Unit,
    modifier: Modifier = Modifier,
    /** #382 Tier 3: opens the sweep confirm dialog. */
    onSweep: () -> Unit = {},
    /** True while a sweep is broadcasting — disables the button. */
    sweepInProgress: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
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
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(
                        R.string.found_funds_title,
                        String.format(Locale.US, "%,.2f", foundCkb),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = pluralStringResource(
                    R.plurals.found_funds_body, addressCount, addressCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.TextButton(onClick = onLearnMore) {
                    Text(stringResource(R.string.gap_limit_banner_action_learn_more))
                }
                FilledTonalButton(onClick = onSweep, enabled = !sweepInProgress) {
                    Text(
                        stringResource(
                            if (sweepInProgress) R.string.found_funds_action_sweeping
                            else R.string.found_funds_action_sweep
                        )
                    )
                }
            }
        }
    }
}
