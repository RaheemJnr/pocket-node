package com.rjnr.pocketnode.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.ui.util.uaTestTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One-time point-of-choice for background sync, shown right after PIN setup
 * (#286). Background sync defaults OFF; this screen is the surface that
 * tells users it exists instead of leaving it buried in Settings → Sync —
 * Matt's review flagged "the app stops updating when I close it" as the
 * symptom of nobody finding the toggle.
 *
 * Both choices land in the same place ([onContinue] → the normal
 * post-wallet-ready destination); only the preference and the foreground
 * service differ. "Keep syncing" requires POST_NOTIFICATIONS on 13+ — the
 * FGS silently dies without it (#116), so denial leaves sync off and the
 * user on this screen to pick the other option.
 */
@HiltViewModel
class BackgroundSyncOptInViewModel @Inject constructor(
    private val walletPreferences: WalletPreferences,
    private val repository: GatewayRepository,
) : ViewModel() {

    fun isAlreadyEnabled(): Boolean = walletPreferences.isBackgroundSyncEnabled()

    fun enableBackgroundSync() {
        walletPreferences.setBackgroundSyncEnabled(true)
        repository.startBackgroundSync()
    }

    fun declineBackgroundSync() {
        // Already the default, but persist explicitly so the choice is a
        // recorded decision, not an absence of one.
        walletPreferences.setBackgroundSyncEnabled(false)
    }
}

@Composable
fun BackgroundSyncOptInScreen(
    onContinue: () -> Unit,
    viewModel: BackgroundSyncOptInViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Existing users who already enabled background sync in Settings should
    // not be re-asked when they pass through PIN adoption.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (viewModel.isAlreadyEnabled()) onContinue()
    }

    fun enableAndContinue() {
        viewModel.enableBackgroundSync()
        onContinue()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableAndContinue()
        } else {
            // FGS can't run without the notification on 13+ (#116) — leave
            // sync off rather than show a lying ON state. User can still
            // continue with the foreground-only option.
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Background sync needs notification permission to keep running"
                )
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Lucide.RefreshCw,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Keep your wallet up to date?",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Pocket Node can keep syncing while the app is closed, so your " +
                    "balance and history are fresher when you come back. This shows a " +
                    "small ongoing notification.\n\n" +
                    "Android limits background work, so syncing may still pause until " +
                    "you next open the app. You can change this anytime in Settings → Sync.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = {
                    val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        enableAndContinue()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .uaTestTag("bg-sync-optin-keep"),
            ) {
                Text("Keep syncing in background")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    viewModel.declineBackgroundSync()
                    onContinue()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .uaTestTag("bg-sync-optin-skip"),
            ) {
                Text("Only when app is open")
            }
        }
    }
}
