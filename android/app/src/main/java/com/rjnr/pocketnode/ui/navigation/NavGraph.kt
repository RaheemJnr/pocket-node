package com.rjnr.pocketnode.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.ui.MainScreen
import com.rjnr.pocketnode.ui.screens.auth.AuthScreen
import com.rjnr.pocketnode.ui.screens.auth.InitialPinSetupScreen
import com.rjnr.pocketnode.ui.screens.auth.PinEntryScreen
import com.rjnr.pocketnode.ui.screens.auth.PinMode
import com.rjnr.pocketnode.ui.screens.help.FaqScreen
import com.rjnr.pocketnode.ui.screens.onboarding.MnemonicBackupScreen
import com.rjnr.pocketnode.ui.screens.onboarding.MnemonicImportScreen
import com.rjnr.pocketnode.ui.screens.receive.ReceiveScreen
import com.rjnr.pocketnode.ui.screens.scanner.QrScannerScreen
import com.rjnr.pocketnode.ui.screens.send.SendScreen
import com.rjnr.pocketnode.ui.screens.settings.SecuritySettingsScreen
import com.rjnr.pocketnode.ui.screens.settings.SecuritySettingsViewModel
import com.rjnr.pocketnode.ui.screens.recovery.RecoveryScreen
import androidx.compose.runtime.collectAsState
import com.rjnr.pocketnode.ui.screens.security.SecurityChecklistScreen
import com.rjnr.pocketnode.ui.screens.security.SecurityChecklistViewModel
import com.rjnr.pocketnode.ui.screens.security.MnemonicVerifyScreen
import com.rjnr.pocketnode.ui.screens.wallet.AddWalletScreen
import com.rjnr.pocketnode.ui.screens.wallet.WalletManagerScreen
import com.rjnr.pocketnode.ui.screens.wallet.WalletSettingsScreen
import com.rjnr.pocketnode.ui.screens.wallet.WalletSettingsViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Main : Screen("main")
    object Send : Screen("send") {
        /** Build a Send route with optional recipient/amount prefill query args. */
        fun routeWithPrefill(recipient: String?, amountShannons: Long?): String {
            if (recipient == null && amountShannons == null) return route
            val r = recipient?.let { android.net.Uri.encode(it) }
            return buildString {
                append(route)
                append("?")
                if (r != null) append("recipient=$r")
                if (amountShannons != null) {
                    if (r != null) append("&")
                    append("amountShannons=$amountShannons")
                }
            }
        }
    }
    object Receive : Screen("receive")
    object Scanner : Screen("scanner")
    object NodeStatus : Screen("node_status")
    object Onboarding : Screen("onboarding")
    object MnemonicBackup : Screen("mnemonic_backup?simplified={simplified}") {
        const val BASE = "mnemonic_backup"
        fun createRoute(simplified: Boolean = false) =
            if (simplified) "mnemonic_backup?simplified=true" else "mnemonic_backup?simplified=false"
    }
    object MnemonicImport : Screen("mnemonic_import")
    object Auth : Screen("auth")
    object PinEntry : Screen("pin_entry/{mode}") {
        fun createRoute(mode: String) = "pin_entry/$mode"
    }
    object SecuritySettings : Screen("security_settings")
    object Recovery : Screen("recovery")
    object SecurityChecklist : Screen("security_checklist")
    object MnemonicVerify : Screen("mnemonic_verify")
    object WalletManager : Screen("wallet_manager")
    object WalletDetail : Screen("wallet_detail/{walletId}") {
        fun createRoute(walletId: String) = "wallet_detail/$walletId"
    }
    object AddWallet : Screen("add_wallet?parentId={parentId}") {
        const val BASE = "add_wallet"
        /**
         * Build the route with an optional pre-selected parent wallet.
         * When [parentId] is non-null AddWalletScreen jumps straight to
         * the HD Sub-Account form with that wallet selected — the
         * "Add" button on a parent wallet card uses this to skip the
         * mode picker and the parent picker.
         */
        fun createRoute(parentId: String? = null): String =
            if (parentId.isNullOrBlank()) BASE else "$BASE?parentId=$parentId"
    }
    object InitialPinSetup : Screen("initial_pin_setup")
    object ForgotPin : Screen("forgot_pin")
    object Faq : Screen("faq?anchor={anchor}") {
        fun routeWithAnchor(anchor: String?): String =
            if (anchor.isNullOrBlank()) "faq" else "faq?anchor=$anchor"
    }
    object Contacts : Screen("contacts")
    object AddContact : Screen("contacts/add")
    object EditContact : Screen("contacts/edit/{id}") {
        fun createRoute(id: String) = "contacts/edit/$id"
    }
    object ContactDetail : Screen("contacts/detail/{id}") {
        fun createRoute(id: String) = "contacts/detail/$id"
    }
}

sealed class BottomTab(val route: String, val label: String) {
    object Home     : BottomTab("tab_home",     "Wallet")
    object Activity : BottomTab("tab_activity", "Activity")
    object DAO      : BottomTab("tab_dao",      "DAO")
    object Settings : BottomTab("tab_settings", "Settings")

    companion object {
        val entries = listOf(Home, Activity, DAO, Settings)
    }
}

@Composable
fun CkbNavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Onboarding.route,
    pinManager: PinManager,
    needsMnemonicBackup: () -> Boolean = { false }
) {
    // PIN is mandatory: if the user doesn't have one yet, any "go to Main" action
    // must first pass through PIN setup. Once a PIN exists, mnemonic backup is
    // enforced after authentication/setup so recovery material is not shown from
    // the unauthenticated startup path.
    fun destinationAfterWalletReady(): String = when {
        !pinManager.hasPin() -> Screen.InitialPinSetup.route
        needsMnemonicBackup() -> Screen.MnemonicBackup.createRoute()
        else -> Screen.Main.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            com.rjnr.pocketnode.ui.screens.onboarding.OnboardingScreen(
                onNavigateToHome = {
                    navController.navigate(destinationAfterWalletReady()) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onNavigateToBackup = {
                    navController.navigate(Screen.MnemonicBackup.createRoute()) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onNavigateToImport = {
                    navController.navigate(Screen.MnemonicImport.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.MnemonicBackup.route,
            arguments = listOf(navArgument("simplified") { defaultValue = false; type = NavType.BoolType })
        ) { backStackEntry ->
            val simplified = backStackEntry.arguments?.getBoolean("simplified") ?: false
            MnemonicBackupScreen(
                simplified = simplified,
                onNavigateToHome = {
                    navController.navigate(destinationAfterWalletReady()) {
                        popUpTo(Screen.MnemonicBackup.BASE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                pinVerifiedFlow = backStackEntry.savedStateHandle,
            )
        }

        composable(Screen.MnemonicImport.route) {
            MnemonicImportScreen(
                onNavigateToHome = {
                    navController.navigate(destinationAfterWalletReady()) {
                        popUpTo(Screen.MnemonicImport.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Auth.route) { backStackEntry ->
            AuthScreen(
                onAuthSuccess = {
                    navController.navigate(destinationAfterWalletReady()) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                pinUnlockFlow = backStackEntry.savedStateHandle,
            )
        }

        composable(
            route = Screen.PinEntry.route,
            arguments = listOf(navArgument("mode") { type = NavType.StringType })
        ) { backStackEntry ->
            val modeString = backStackEntry.arguments?.getString("mode") ?: "verify"
            val mode = runCatching { PinMode.valueOf(modeString.uppercase()) }
                .getOrDefault(PinMode.VERIFY)
            val setupPin = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.get<String>("setup_pin")

            // If CONFIRM mode but setupPin was lost (e.g. process death), go back to SETUP
            if (mode == PinMode.CONFIRM && setupPin == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }

            PinEntryScreen(
                mode = mode,
                setupPin = setupPin,
                onPinComplete = { enteredPin ->
                    when (mode) {
                        PinMode.SETUP -> {
                            navController.currentBackStackEntry
                                ?.savedStateHandle
                                ?.set("setup_pin", enteredPin)
                            navController.navigate(Screen.PinEntry.createRoute("confirm"))
                        }
                        PinMode.CONFIRM -> {
                            // Pop back past both PinEntry screens (confirm + setup)
                            // to wherever the user came from (SecuritySettings or SecurityChecklist)
                            navController.popBackStack() // pop confirm
                            navController.popBackStack() // pop setup
                        }
                        PinMode.VERIFY -> {
                            val previousRoute = navController.previousBackStackEntry
                                ?.destination?.route
                            // The Send route is registered with optional query args
                            // (e.g. "send?recipient=…&amountShannons=…"), so match on
                            // the base segment rather than full equality.
                            val isSendRoute = previousRoute != null &&
                                (previousRoute == Screen.Send.route ||
                                    previousRoute.startsWith("${Screen.Send.route}?"))
                            when {
                                previousRoute == Screen.SecuritySettings.route -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("pin_verified", true)
                                    navController.popBackStack()
                                }
                                isSendRoute -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("send_pin_verified", true)
                                    navController.popBackStack()
                                }
                                previousRoute == Screen.Main.route -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("dao_pin_verified", true)
                                    navController.popBackStack()
                                }
                                previousRoute == Screen.WalletDetail.route -> {
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("pin_verified", true)
                                    navController.popBackStack()
                                }
                                previousRoute != null &&
                                    (previousRoute == Screen.MnemonicBackup.route ||
                                        previousRoute.startsWith("${Screen.MnemonicBackup.BASE}?") ||
                                        previousRoute.startsWith("${Screen.MnemonicBackup.BASE}/")) -> {
                                    // Raw-key backup PIN gate (#290). The screen
                                    // consumes the flag in a LaunchedEffect and
                                    // calls viewModel.onPinVerified() to fetch
                                    // the key.
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("pin_verified", true)
                                    navController.popBackStack()
                                }
                                previousRoute == Screen.Auth.route -> {
                                    // User unlocked via PIN from AuthScreen. Pop back
                                    // to AuthScreen and signal success via savedStateHandle
                                    // so the AuthScreen LaunchedEffect can flip
                                    // `authSuccess` and trigger `runMigrationIfNeeded`.
                                    // Without this hook the V1→V2 migration loop was
                                    // silently dead for every PIN-only user (#289
                                    // follow-up bug found during v1.7.3 testing —
                                    // pre-fix the previous route's `else` branch
                                    // navigated straight to Screen.Main and popped
                                    // AuthScreen, so its LaunchedEffect never fired).
                                    navController.previousBackStackEntry
                                        ?.savedStateHandle
                                        ?.set("pin_unlock_success", true)
                                    navController.popBackStack()
                                }
                                else -> {
                                    navController.navigate(Screen.Main.route) {
                                        popUpTo(Screen.Auth.route) { inclusive = true }
                                    }
                                }
                            }
                        }
                    }
                },
                onForgotPin = {
                    // Destructive recovery: route to the confirmation
                    // screen rather than the regular import flow. Keep
                    // Auth on the back stack so the user can return if
                    // they remember the PIN. The previous wiring popped
                    // Auth via `inclusive = true` which (a) trapped the
                    // back arrow and (b) navigated to a screen that
                    // refused already-imported phrases — the locked-out
                    // user's recovery path was a dead end.
                    navController.navigate(Screen.ForgotPin.route)
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SecuritySettings.route) { backStackEntry ->
            val viewModel: SecuritySettingsViewModel = hiltViewModel()

            DisposableEffect(backStackEntry) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshState()
                    }
                }
                backStackEntry.lifecycle.addObserver(observer)
                onDispose { backStackEntry.lifecycle.removeObserver(observer) }
            }

            // Observe pin_verified result from PinEntry screen
            val pinVerified = backStackEntry.savedStateHandle
                .get<Boolean>("pin_verified") == true
            if (pinVerified) {
                LaunchedEffect(Unit) {
                    backStackEntry.savedStateHandle.remove<Boolean>("pin_verified")
                    viewModel.executePendingAction()
                }
            }

            SecuritySettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPinSetup = {
                    navController.navigate(Screen.PinEntry.createRoute("setup"))
                },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                viewModel = viewModel
            )
        }

        composable(Screen.Main.route) { backStackEntry ->
            val daoPinVerified = backStackEntry.savedStateHandle
                .get<Boolean>("dao_pin_verified") == true
            if (daoPinVerified) {
                LaunchedEffect(Unit) {
                    backStackEntry.savedStateHandle.remove<Boolean>("dao_pin_verified")
                }
            }

            MainScreen(
                onNavigateToSend = { recipient, amountShannons ->
                    val route = if (recipient != null || amountShannons != null) {
                        val r = recipient?.let { android.net.Uri.encode(it) }
                        buildString {
                            append(Screen.Send.route)
                            append("?")
                            if (r != null) append("recipient=$r")
                            if (amountShannons != null) {
                                if (r != null) append("&")
                                append("amountShannons=$amountShannons")
                            }
                        }
                    } else {
                        Screen.Send.route
                    }
                    navController.navigate(route)
                },
                onNavigateToReceive = { navController.navigate(Screen.Receive.route) },
                onNavigateToNodeStatus = { navController.navigate(Screen.NodeStatus.route) },
                onNavigateToBackup = { navController.navigate(Screen.MnemonicBackup.createRoute()) },
                onNavigateToSecuritySettings = { navController.navigate(Screen.SecuritySettings.route) },
                onNavigateToImport = { navController.navigate(Screen.MnemonicImport.route) },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                onNavigateToSecurityChecklist = {
                    navController.navigate(Screen.SecurityChecklist.route)
                },
                onNavigateToWalletManager = {
                    navController.navigate(Screen.WalletManager.route)
                },
                onNavigateToContacts = {
                    navController.navigate(Screen.Contacts.route)
                },
                onNavigateToFaq = { anchor ->
                    navController.navigate(Screen.Faq.routeWithAnchor(anchor))
                },
                daoPinVerified = daoPinVerified,
            )
        }

        composable(
            // Optional query args for retry-failed-tx prefill (#115). Both
            // nullable; arg-less navigation to "send" still matches because
            // both default to null.
            route = "${Screen.Send.route}?recipient={recipient}&amountShannons={amountShannons}",
            arguments = listOf(
                navArgument("recipient") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("amountShannons") {
                    // StringType so we can carry null; parse to Long below.
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val sendPinVerified = backStackEntry.savedStateHandle
                .get<Boolean>("send_pin_verified") == true
            if (sendPinVerified) {
                LaunchedEffect(Unit) {
                    backStackEntry.savedStateHandle.remove<Boolean>("send_pin_verified")
                }
            }

            val prefillRecipient = backStackEntry.arguments?.getString("recipient")
            val prefillAmountShannons = backStackEntry.arguments
                ?.getString("amountShannons")?.toLongOrNull()

            SendScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToScanner = { navController.navigate(Screen.Scanner.route) },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                scannedAddress = backStackEntry.savedStateHandle
                    .get<String>("scanned_address"),
                sendAuthVerified = sendPinVerified,
                prefillRecipient = prefillRecipient,
                prefillAmountShannons = prefillAmountShannons,
            )
        }

        composable(Screen.Receive.route) {
            ReceiveScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Scanner.route) {
            QrScannerScreen(
                // The screen calls both onScanResult AND onNavigateBack on a
                // successful scan (see QrScannerScreen.kt:85-88). Don't pop
                // here — onNavigateBack does it. Earlier this lambda also
                // popped, which double-popped the back stack and dumped the
                // user on Home after scanning into Send / AddContact.
                onScanResult = { address ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("scanned_address", address)
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.NodeStatus.route) {
            com.rjnr.pocketnode.ui.screens.status.NodeStatusScreen(
                navController = navController
            )
        }

        composable(Screen.Recovery.route) {
            RecoveryScreen(
                onRecoveryComplete = { recoveredWallets ->
                    navController.navigate(destinationAfterWalletReady()) {
                        popUpTo(Screen.Recovery.route) { inclusive = true }
                    }
                },
                onMnemonicRestore = {
                    navController.navigate(Screen.Onboarding.route) {
                        popUpTo(Screen.Recovery.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.SecurityChecklist.route) {
            val viewModel: SecurityChecklistViewModel = hiltViewModel()
            val state = viewModel.uiState.collectAsState().value

            // Refresh state when returning from PIN setup or mnemonic backup
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshState()
                    }
                }
                val lifecycle = it.lifecycle
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            SecurityChecklistScreen(
                hasPinOrBiometrics = state.hasPinOrBiometrics,
                hasMnemonicBackup = state.hasMnemonicBackup,
                isMnemonicWallet = state.isMnemonicWallet,
                onSetupPin = {
                    navController.navigate(Screen.PinEntry.createRoute("setup"))
                },
                onBackupMnemonic = {
                    navController.navigate(Screen.MnemonicBackup.createRoute())
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MnemonicVerify.route) {
            MnemonicVerifyScreen(
                mnemonicWords = emptyList(), // TODO: wire from KeyManager via ViewModel
                onVerified = {
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.WalletManager.route) {
            WalletManagerScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAddWallet = { parentId ->
                    navController.navigate(Screen.AddWallet.createRoute(parentId))
                },
                onNavigateToWalletDetail = { walletId ->
                    navController.navigate(Screen.WalletDetail.createRoute(walletId))
                }
            )
        }

        composable(
            route = Screen.WalletDetail.route,
            arguments = listOf(navArgument("walletId") { type = NavType.StringType })
        ) { backStackEntry ->
            val viewModel: WalletSettingsViewModel = hiltViewModel()

            // Observe pin_verified result from PinEntry screen
            val pinVerified = backStackEntry.savedStateHandle
                .get<Boolean>("pin_verified") == true
            if (pinVerified) {
                LaunchedEffect(Unit) {
                    backStackEntry.savedStateHandle.remove<Boolean>("pin_verified")
                    viewModel.onPinVerified()
                }
            }

            WalletSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPinVerify = {
                    navController.navigate(Screen.PinEntry.createRoute("verify"))
                },
                viewModel = viewModel
            )
        }

        composable(Screen.InitialPinSetup.route) {
            InitialPinSetupScreen(
                onPinCreated = {
                    navController.navigate(destinationAfterWalletReady()) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.ForgotPin.route) {
            com.rjnr.pocketnode.ui.screens.auth.ForgotPinScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.AddWallet.route,
            arguments = listOf(
                navArgument("parentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) {
            AddWalletScreen(
                onNavigateBack = { navController.popBackStack() },
                onWalletCreated = {
                    navController.popBackStack(Screen.Main.route, inclusive = false)
                },
                onNewMnemonicWalletCreated = {
                    // Use the same 3-step backup flow as first-wallet
                    // onboarding (display → verify → success) instead
                    // of the `simplified` single-screen shortcut. The
                    // shortcut was reported as an inconsistency
                    // (Telegram bug 4): first-wallet creation prompts
                    // for verification, but adding a new parent wallet
                    // from inside the app did not. Aligned now.
                    navController.navigate(Screen.MnemonicBackup.createRoute(simplified = false)) {
                        popUpTo(Screen.Main.route) { inclusive = false }
                    }
                }
            )
        }

        composable(
            route = Screen.Faq.route,
            arguments = listOf(
                navArgument("anchor") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) {
            FaqScreen(onBack = { navController.popBackStack() })
        }

        // -- Address book (#193, #194, #195) --

        composable(Screen.Contacts.route) {
            com.rjnr.pocketnode.ui.screens.contacts.ContactsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAdd = { navController.navigate(Screen.AddContact.route) },
                onNavigateToDetail = { id ->
                    navController.navigate(Screen.ContactDetail.createRoute(id))
                },
                onSendToContact = { address ->
                    navController.navigate(Screen.Send.routeWithPrefill(address, null))
                },
            )
        }

        composable(Screen.AddContact.route) { backStackEntry ->
            // Scanner returns to AddContact the same way it returns to Send:
            // via the previous back stack entry's savedStateHandle.
            com.rjnr.pocketnode.ui.screens.contacts.AddContactScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToScanner = { navController.navigate(Screen.Scanner.route) },
                scannedAddress = backStackEntry.savedStateHandle.get<String>("scanned_address"),
            )
        }

        composable(
            route = Screen.EditContact.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            com.rjnr.pocketnode.ui.screens.contacts.EditContactScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.ContactDetail.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            com.rjnr.pocketnode.ui.screens.contacts.ContactDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Screen.EditContact.createRoute(id)) },
                onSend = { address ->
                    // Pop back to Contacts then push Send so the back stack
                    // is Contacts → Send rather than Contacts → Detail → Send.
                    navController.navigate(Screen.Send.routeWithPrefill(address, null)) {
                        popUpTo(Screen.ContactDetail.route) { inclusive = true }
                    }
                },
            )
        }
    }
}
