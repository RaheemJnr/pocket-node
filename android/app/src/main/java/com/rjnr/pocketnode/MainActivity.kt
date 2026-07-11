package com.rjnr.pocketnode

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.ui.navigation.CkbNavGraph
import com.rjnr.pocketnode.ui.navigation.Screen
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme
import com.rjnr.pocketnode.ui.util.LocalWindowSizeClass
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var repository: GatewayRepository

    @Inject
    lateinit var pinManager: PinManager

    @Inject
    lateinit var walletPreferences: WalletPreferences

    @Inject
    lateinit var keyManager: KeyManager

    @Inject
    lateinit var keyBackupManager: KeyBackupManager

    @Inject
    lateinit var authManager: AuthManager

    private val _requireReauth = mutableStateOf(false)

    // Cached at startup — updated when wallet state changes
    private var cachedHasWallet = false

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Clean up any orphaned .tmp files from interrupted PIN re-encryption
        keyBackupManager.cleanupOrphanedTmpFiles()

        // #370: reset the PIN failed-attempt counter once after an overwrite
        // install / version upgrade, BEFORE the startup gate below reads the
        // lockout state. A fresh install (last-seen 0) just records the version;
        // an ordinary same-version relaunch is a no-op. Note: this is a
        // deliberate product choice (see #370) that trades a little brute-force
        // hardening — a sideloaded higher-versionCode build would also clear the
        // count — for not stranding a user who fumbled their PIN before updating.
        run {
            val lastSeen = walletPreferences.getLastSeenVersionCode()
            val current = BuildConfig.VERSION_CODE
            if (PinManager.shouldResetAttemptsForUpgrade(lastSeen, current)) {
                pinManager.resetFailedAttempts()
                Log.i("MainActivity", "PIN attempt counter reset after upgrade $lastSeen -> $current (#370)")
            }
            if (lastSeen != current) walletPreferences.setLastSeenVersionCode(current)
        }

        // Startup gate: must resolve synchronously to determine start destination.
        // This is the ONE acceptable runBlocking site — it runs once during cold start
        // on the main thread before any UI is shown.
        val startDestination = runBlocking {
            cachedHasWallet = repository.hasWallet()
            when {
                // Suppressed, not removed: the corruption flag guards the ESP
                // legacy-key path, which un-migrated installs still read.
                @Suppress("DEPRECATION")
                keyManager.wasResetDueToCorruption() -> Screen.Recovery.route
                !cachedHasWallet -> Screen.Onboarding.route
                !pinManager.hasPin() -> Screen.InitialPinSetup.route
                else -> Screen.Auth.route
            }
        }

        setContent {
            val themeMode by walletPreferences.themeModeFlow.collectAsState()
            val windowSizeClass = calculateWindowSizeClass(this)

            CkbWalletTheme(themeMode = themeMode) {
                CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    val reauth = _requireReauth.value
                    LaunchedEffect(reauth) {
                        if (reauth) {
                            _requireReauth.value = false
                            val currentRoute =
                                navController.currentBackStackEntry?.destination?.route
                            if (currentRoute != Screen.Auth.route &&
                                currentRoute != Screen.PinEntry.route
                            ) {
                                navController.navigate(Screen.Auth.route) {
                                    popUpTo(Screen.Main.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }

                    CkbNavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        pinManager = pinManager,
                        needsMnemonicBackup = {
                            runBlocking { repository.needsMnemonicBackup() }
                        }
                    )
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-arm background sync on every foreground (#286). The FGS dies
        // silently in three ways the preference can't see — Android 15's 6h
        // dataSync budget (onTimeout → stopSelf, nothing reschedules), OEM
        // battery managers, and post-grant notification revocation — leaving
        // an ON toggle with a dead service. startBackgroundSync() no-ops when
        // the preference is off and is idempotent when the service is already
        // running; starting from the foreground also grants a fresh FGS time
        // budget per the platform rules.
        repository.startBackgroundSync()
    }

    override fun onStop() {
        super.onStop()
        // Use cached value — avoids blocking main thread on every onStop
        if (cachedHasWallet && pinManager.hasPin()) {
            _requireReauth.value = true
            // Wipe the cached session PIN when the app backgrounds so the next
            // foregrounding forces a fresh unlock before any PIN-gated action.
            authManager.clearSession()
            keyManager.clearSessionPin()
        }
    }
}
