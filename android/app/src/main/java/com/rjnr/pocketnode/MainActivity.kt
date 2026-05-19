package com.rjnr.pocketnode

import android.os.Bundle
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

        // Startup gate: must resolve synchronously to determine start destination.
        // This is the ONE acceptable runBlocking site — it runs once during cold start
        // on the main thread before any UI is shown.
        val startDestination = runBlocking {
            cachedHasWallet = repository.hasWallet()
            when {
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
