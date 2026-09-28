package com.rjnr.pocketnode

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rjnr.pocketnode.core.log.Logger
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.sync.SyncWorkScheduler
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.ui.navigation.CkbNavGraph
import com.rjnr.pocketnode.ui.navigation.Screen
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.screens.auth.AuthScreen
import com.rjnr.pocketnode.ui.screens.auth.AuthViewModel
import com.rjnr.pocketnode.ui.screens.auth.PinEntryScreen
import com.rjnr.pocketnode.ui.screens.auth.PinMode
import com.rjnr.pocketnode.ui.screens.auth.ReauthGate
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
    // Concrete (#461): needs the Android-only reactive themeModeFlow alongside
    // AppStatePreferences' version-code pair.
    lateinit var walletPreferences: WalletPreferences

    @Inject
    lateinit var keyManager: KeyManager

    @Inject
    lateinit var walletRepository: WalletRepository

    @Inject
    lateinit var keyBackupManager: KeyBackupManager

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var syncWorkScheduler: SyncWorkScheduler

    @Inject
    lateinit var logger: Logger

    // Assigned on first composition below (see [ReauthGate]), backed by
    // rememberSaveable so the gate survives configuration change and process
    // death exactly the way the rest of the compose tree's saved state does:
    // onStop() locks it from outside composition, and
    // androidx.compose.runtime.saveable persists the underlying booleans
    // through the activity's saved-instance-state bundle. Null only in the
    // brief window before the first composition has run, which onStop can
    // never observe (onStart/onResume always precede onStop).
    private var reauthGate: ReauthGate? = null

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
                logger.i("MainActivity", "PIN attempt counter reset after upgrade $lastSeen -> $current (#370)")
            }
            if (lastSeen != current) walletPreferences.setLastSeenVersionCode(current)
        }

        // Startup gate: must resolve synchronously to determine start destination.
        // This is the ONE acceptable runBlocking site — it runs once during cold start
        // on the main thread before any UI is shown.
        val startDestination = runBlocking {
            cachedHasWallet = repository.hasWallet()
            @Suppress("DEPRECATION")
            val wasReset = keyManager.wasResetDueToCorruption()
            val hasPin = pinManager.hasPin()
            val route = when {
                // Suppressed, not removed: the corruption flag guards the ESP
                // legacy-key path, which un-migrated installs still read.
                wasReset -> Screen.Recovery.route
                !cachedHasWallet -> Screen.Onboarding.route
                !hasPin -> Screen.InitialPinSetup.route
                else -> Screen.Auth.route
            }
            // #424 diagnostic: users report onboarding after an overwrite
            // install despite preserved data. Log the exact startup-gate inputs
            // so a repro on the reporter's device shows which signal is wrong —
            // detection (keyMaterial>0 but hasWallet=false) vs. actual data loss
            // (keyMaterial=0) vs. the corruption/ESP path. Counts + a UUID only;
            // no key bytes are ever logged.
            //
            // Log.println, NOT Log.i: proguard-rules.pro strips v/d/i/w/e/wtf via
            // -assumenosideeffects in release/playRelease, which would erase this
            // line from exactly the signed builds the reporter runs (a debug
            // build can't overwrite their release-signed install). println is not
            // in that strip list, so it survives R8 and still reaches logcat.
            runCatching {
                Log.println(
                    Log.INFO,
                    "StartupGate",
                    "#424 route=$route hasWallet=$cachedHasWallet wasReset=$wasReset " +
                        "hasPin=$hasPin ${keyManager.diagnosticKeyState()} " +
                        "wallets=${walletRepository.walletCount()} " +
                        "activeWalletId=${walletRepository.activeWalletIdSnapshot()} " +
                        "lastSeenVc=${walletPreferences.getLastSeenVersionCode()} vc=${BuildConfig.VERSION_CODE}"
                )
            }
            route
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

                    // #524: the reauth gate is drawn as an overlay above the
                    // nav graph rather than a navigation destination, so the
                    // back stack and any in-progress screen state (e.g. a
                    // typed Send amount) survive the lock/unlock cycle.
                    // rememberSaveable ties the underlying booleans to the
                    // activity's saved instance state, so the gate stays
                    // locked across a config change or a process-death
                    // recreation exactly like it did before (when the
                    // earlier Auth-route navigation was itself what survived
                    // recreation).
                    val lockedState = rememberSaveable { mutableStateOf(false) }
                    val pinFallbackState = rememberSaveable { mutableStateOf(false) }
                    val gate = remember(lockedState, pinFallbackState) {
                        ReauthGate(lockedState, pinFallbackState)
                    }
                    SideEffect { reauthGate = gate }
                    val reauth = gate.locked

                    Box(modifier = Modifier.fillMaxSize()) {
                        CkbNavGraph(
                            navController = navController,
                            startDestination = startDestination,
                            pinManager = pinManager,
                            needsMnemonicBackup = {
                                runBlocking { repository.needsMnemonicBackup() }
                            },
                            // Still composed underneath while locked (so its
                            // ViewModels and rememberSaveable state survive),
                            // but unreachable: not by touch (the overlay
                            // below consumes every pointer event) and not by
                            // accessibility, which would otherwise still
                            // announce it as navigable content behind the lock.
                            modifier = if (reauth) Modifier.clearAndSetSemantics {} else Modifier,
                        )

                        if (reauth) {
                            val authViewModel: AuthViewModel = hiltViewModel()

                            // Matches the pre-#524 Auth-route behaviour: back
                            // while on the biometric/AuthScreen level used to
                            // finish the activity (Auth was the back stack's
                            // only entry after the old popUpTo(Main)); back
                            // while on the nested PIN-entry level used to pop
                            // back to AuthScreen. Neither level may dismiss
                            // the overlay and reveal the screen underneath.
                            BackHandler(enabled = true) {
                                when (gate.onBackPressed()) {
                                    ReauthGate.BackAction.HandledWithinGate -> Unit
                                    ReauthGate.BackAction.LeaveGate -> finish()
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    // Swallow any touch AuthScreen/PinEntryScreen's
                                    // own children didn't already consume (e.g. a
                                    // tap on empty space) so it can never fall
                                    // through to the nav graph beneath.
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            do {
                                                val event = awaitPointerEvent(pass = PointerEventPass.Final)
                                                event.changes.forEach { it.consume() }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    }
                            ) {
                                if (gate.showingPinFallback) {
                                    PinEntryScreen(
                                        mode = PinMode.VERIFY,
                                        onPinComplete = {
                                            // Mirrors NavGraph's
                                            // previousRoute==Auth branch:
                                            // notify the same AuthViewModel
                                            // AuthScreen is observing, so its
                                            // own LaunchedEffect(authSuccess)
                                            // runs the migration check and
                                            // then clears the gate via
                                            // onAuthSuccess below.
                                            authViewModel.onPinUnlockSuccess()
                                        },
                                        onForgotPin = {
                                            // Destructive reset escape hatch:
                                            // leave the gate entirely and let
                                            // the ordinary ForgotPin
                                            // destination take over.
                                            gate.unlock()
                                            navController.navigate(Screen.ForgotPin.route)
                                        },
                                    )
                                } else {
                                    AuthScreen(
                                        viewModel = authViewModel,
                                        onAuthSuccess = { gate.unlock() },
                                        onNavigateToPinVerify = { gate.showPinFallback() },
                                    )
                                }
                            }
                        }
                    }
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
        // Play build only (self-gated): enqueue a one-time catch-up so the light
        // client keeps syncing for a few minutes after the app is backgrounded,
        // while the process is still warm. Always enqueue: the worker itself
        // checks hasWallet(), so a fresh install that just created its wallet
        // during onboarding is covered even though cachedHasWallet (set once in
        // onCreate) is still stale here (Codex #428 P1). No-op on FGS builds.
        syncWorkScheduler.enqueueBackgroundCatchUp()
        // Use cached value — avoids blocking main thread on every onStop
        if (cachedHasWallet && pinManager.hasPin()) {
            reauthGate?.lock()
            // Wipe the cached session PIN when the app backgrounds so the next
            // foregrounding forces a fresh unlock before any PIN-gated action.
            authManager.clearSession()
            keyManager.clearSessionPin()
        }
    }
}
