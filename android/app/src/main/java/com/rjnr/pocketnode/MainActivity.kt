package com.rjnr.pocketnode

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rjnr.pocketnode.core.log.Logger
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
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
import com.rjnr.pocketnode.ui.screens.auth.ReauthGate
import com.rjnr.pocketnode.ui.screens.auth.ReauthGateHost
import com.rjnr.pocketnode.ui.screens.auth.ReauthOverlay
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

    // #524: the re-auth lock replaces the nav graph on screen instead of
    // navigating to Screen.Auth, so the back stack and screen state survive.
    // Kept in the Activity (and mirrored into its saved state) rather than in
    // composition, so onStop/onSaveInstanceState can lock it before the first
    // frame.
    private val reauthGate = ReauthGate(mutableStateOf(false), mutableIntStateOf(0))

    // Set on first composition; read in onStop/onSaveInstanceState for the
    // current route. Until then, [initialRoute] stands in on a fresh start.
    private var navController: NavHostController? = null
    private var initialRoute: String? = null

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

        // #524: a start with nothing to unlock (onboarding, first PIN setup)
        // counts as unlocked for this process.
        if (savedInstanceState == null) {
            initialRoute = startDestination
            if (startDestination == Screen.Onboarding.route ||
                startDestination == Screen.InitialPinSetup.route
            ) {
                unlockedThisProcess = true
            }
        } else {
            val savedLocked = savedInstanceState.getBoolean(KEY_REAUTH_LOCKED)
            val startLocked = shouldStartLocked(
                savedLocked = savedLocked,
                savedOnAuthRoute = savedInstanceState.getBoolean(KEY_REAUTH_ON_AUTH_ROUTE),
                unlockedThisProcess = unlockedThisProcess,
                hasWallet = cachedHasWallet,
                hasPin = pinManager.hasPin(),
            )
            // A lock carried over (e.g. a rotation on the lock screen) keeps
            // its session, so the lock screen's own state survives; a new
            // lock starts a new session.
            reauthGate.restore(
                session = savedInstanceState.getInt(KEY_REAUTH_SESSION),
                locked = startLocked && savedLocked,
            )
            if (startLocked && !savedLocked) reauthGate.lock()
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
                    SideEffect { this@MainActivity.navController = navController }

                    // While locked the nav graph is not composed at all (see
                    // ReauthGateHost); the hoisted NavController keeps the
                    // back stack and its ViewModels.
                    ReauthGateHost(
                        locked = reauthGate.locked,
                        content = {
                            CkbNavGraph(
                                navController = navController,
                                startDestination = startDestination,
                                pinManager = pinManager,
                                needsMnemonicBackup = {
                                    runBlocking { repository.needsMnemonicBackup() }
                                },
                                onAuthUnlocked = { unlockedThisProcess = true },
                            )
                        },
                        overlay = {
                            ReauthOverlay(
                                session = reauthGate.session,
                                onUnlocked = { session ->
                                    if (reauthGate.unlock(session)) unlockedThisProcess = true
                                },
                                // Back on the lock screen leaves the app
                                // without finishing the activity, so the
                                // screen underneath is kept for the unlock.
                                onLeave = { moveTaskToBack(true) },
                            )
                        },
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
        // Play build only (self-gated): enqueue a one-time catch-up so the light
        // client keeps syncing for a few minutes after the app is backgrounded,
        // while the process is still warm. Always enqueue: the worker itself
        // checks hasWallet(), so a fresh install that just created its wallet
        // during onboarding is covered even though cachedHasWallet (set once in
        // onCreate) is still stale here (Codex #428 P1). No-op on FGS builds.
        syncWorkScheduler.enqueueBackgroundCatchUp()
        // Use cached value — avoids blocking main thread on every onStop
        if (cachedHasWallet && pinManager.hasPin()) {
            if (!routeSkipsLock(currentRoute())) reauthGate.lock()
            // Wipe the cached session PIN when the app backgrounds so the next
            // foregrounding forces a fresh unlock before any PIN-gated action.
            authManager.clearSession()
            keyManager.clearSessionPin()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // API 26-27 save state before onStop, so lock here too: a process
        // killed in the background must not restore unlocked.
        if (!isChangingConfigurations && cachedHasWallet && pinManager.hasPin() &&
            !routeSkipsLock(currentRoute())
        ) {
            reauthGate.lock()
        }
        outState.putBoolean(KEY_REAUTH_LOCKED, reauthGate.locked)
        outState.putInt(KEY_REAUTH_SESSION, reauthGate.session)
        outState.putBoolean(KEY_REAUTH_ON_AUTH_ROUTE, isOnColdStartAuthRoute())
        super.onSaveInstanceState(outState)
    }

    private fun currentRoute(): String? =
        navController?.currentBackStackEntry?.destination?.route ?: initialRoute

    // The cold-start Auth route is still on the back stack: the user has not
    // passed it yet, and it is itself a lock (a successful unlock pops it).
    private fun isOnColdStartAuthRoute(): Boolean {
        val controller = navController ?: return initialRoute == Screen.Auth.route
        return runCatching { controller.getBackStackEntry(Screen.Auth.route) }.isSuccess
    }

    companion object {
        private const val KEY_REAUTH_LOCKED = "reauth_locked"
        private const val KEY_REAUTH_SESSION = "reauth_session"
        private const val KEY_REAUTH_ON_AUTH_ROUTE = "reauth_on_auth_route"

        /**
         * True once this process has passed a lock (or started with nothing
         * to unlock). A recreated activity in a process where this is still
         * false, e.g. restored after process death, starts locked (#524).
         */
        @Volatile
        private var unlockedThisProcess = false

        /**
         * Backgrounding on the Auth or PinEntry route does not lock: those
         * screens already ask for the credential (the pre-#524 rule).
         */
        internal fun routeSkipsLock(route: String?): Boolean =
            route == Screen.Auth.route || route == Screen.PinEntry.route

        /**
         * Whether a recreated activity starts locked: it was locked when
         * saved, or this process has not been unlocked and the saved screen
         * was not the cold-start Auth route (which asks by itself).
         */
        internal fun shouldStartLocked(
            savedLocked: Boolean,
            savedOnAuthRoute: Boolean,
            unlockedThisProcess: Boolean,
            hasWallet: Boolean,
            hasPin: Boolean,
        ): Boolean = hasWallet && hasPin &&
            (savedLocked || (!unlockedThisProcess && !savedOnAuthRoute))
    }
}
