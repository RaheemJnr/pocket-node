package com.rjnr.pocketnode.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.rjnr.pocketnode.R

/**
 * Home staleness banner (#286): shown when the wallet went stale (>1h with
 * no observed sync) while the app was closed. Two variants:
 *
 *  - [bgSyncEnabled] = false → "background sync is off", with an Enable CTA
 *    that runs the POST_NOTIFICATIONS grant before flipping the preference
 *    (the FGS silently dies without it, #116).
 *  - [bgSyncEnabled] = true → the service was killed behind the user's back
 *    (OEM battery manager, 6h dataSync budget, revoked notifications) —
 *    informational; MainActivity.onStart has already re-armed the service.
 */
@Composable
fun BgSyncStaleBanner(
    bgSyncEnabled: Boolean,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Deny → leave sync off rather than show a lying ON state (#116).
        if (granted) onEnable()
    }

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
                    imageVector = Lucide.RefreshCw,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(
                        if (bgSyncEnabled) R.string.bg_sync_pill_title_dead
                        else R.string.bg_sync_pill_title_off
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = stringResource(
                    if (bgSyncEnabled) R.string.bg_sync_pill_body_dead
                    else R.string.bg_sync_pill_body_off
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.bg_sync_pill_action_dismiss))
                }
                if (!bgSyncEnabled) {
                    FilledTonalButton(onClick = {
                        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onEnable()
                        }
                    }) {
                        Text(stringResource(R.string.bg_sync_pill_action_enable))
                    }
                }
            }
        }
    }
}
