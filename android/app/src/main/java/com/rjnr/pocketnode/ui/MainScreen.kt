package com.rjnr.pocketnode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Wallet
import com.rjnr.pocketnode.ui.components.UpdateProgressBanner
import com.rjnr.pocketnode.ui.navigation.BottomTab
import com.rjnr.pocketnode.ui.screens.activity.ActivityScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.ui.screens.dao.DaoScreen
import com.rjnr.pocketnode.ui.screens.home.HomeScreen
import com.rjnr.pocketnode.ui.screens.settings.SettingsScreen
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme
import com.rjnr.pocketnode.ui.update.UpdateBannerViewModel

@Composable
fun MainScreen(
    onNavigateToSend: (recipient: String?, amountShannons: Long?) -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToNodeStatus: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToSecuritySettings: () -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToPinVerify: () -> Unit = {},
    onNavigateToSecurityChecklist: () -> Unit = {},
    onNavigateToWalletManager: () -> Unit = {},
    onNavigateToFaq: (anchor: String?) -> Unit = {},
    daoPinVerified: Boolean = false,
) {
    val innerNav = rememberNavController()
    val navBackStackEntry by innerNav.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Auto-update banner sits above the bottom navigation and reflects the
    // singleton UpdateDownloader's state. Hilt scopes the VM to this
    // composable; the underlying StateFlow is shared so the banner persists
    // across tab switches.
    val updateBannerVm: UpdateBannerViewModel = hiltViewModel()
    val updateDownloadState by updateBannerVm.state.collectAsState()

    // Clear "Installing…" state when the user returns from the system
    // installer (cancelled, signing conflict, success, anything). The
    // downloader also drops the on-disk APK here so we are not holding
    // ~40 MB indefinitely between updates.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                updateBannerVm.onActivityResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        bottomBar = {
            Column {
                UpdateProgressBanner(
                    state = updateDownloadState,
                    onInstallClick = { updateBannerVm.installNow() },
                    onCancelClick = { updateBannerVm.cancel() },
                    onRetryClick = { updateBannerVm.dismiss() },
                    onDismissClick = { updateBannerVm.dismiss() },
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    BottomTab.entries.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                innerNav.navigate(tab.route) {
                                    popUpTo(innerNav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = tabIcon(tab, selected),
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = innerNav,
            startDestination = BottomTab.Home.route,
            modifier = Modifier
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            composable(BottomTab.Home.route) {
                HomeScreen(
                    onNavigateToSend = onNavigateToSend,
                    onNavigateToReceive = onNavigateToReceive,
                    onNavigateToBackup = onNavigateToBackup,
                    onNavigateToSecurityChecklist = onNavigateToSecurityChecklist,
                    onNavigateToWalletManager = onNavigateToWalletManager,
                    onNavigateToNodeStatus = onNavigateToNodeStatus,
                    onNavigateToDao = {
                        innerNav.navigate(BottomTab.DAO.route) {
                            popUpTo(innerNav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToActivity = {
                        innerNav.navigate(BottomTab.Activity.route) {
                            popUpTo(innerNav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToFaq = onNavigateToFaq,
                )
            }
            composable(BottomTab.Activity.route) {
                ActivityScreen(
                    onNavigateToSend = onNavigateToSend
                )
            }
            composable(BottomTab.DAO.route) {
                DaoScreen(
                    viewModel = hiltViewModel(),
                    onNavigateToPinVerify = onNavigateToPinVerify,
                    daoPinVerified = daoPinVerified
                )
            }
            composable(BottomTab.Settings.route) {
                SettingsScreen(
                    onNavigateToNodeStatus = onNavigateToNodeStatus,
                    onNavigateToBackup = onNavigateToBackup,
                    onNavigateToSecuritySettings = onNavigateToSecuritySettings,
                    onNavigateToImport = onNavigateToImport,
                    onNavigateToWalletManager = onNavigateToWalletManager,
                    onNavigateToFaq = onNavigateToFaq,
                )
            }
        }
    }
}

private fun tabIcon(tab: BottomTab, selected: Boolean): ImageVector = when (tab) {
    BottomTab.Home -> Lucide.Wallet
    BottomTab.Activity -> Lucide.Activity
    BottomTab.DAO -> Lucide.Lock
    BottomTab.Settings -> Lucide.Settings
    else -> Lucide.Circle
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    CkbWalletTheme {
        MainScreen(
            onNavigateToSend = { _, _ -> },
            onNavigateToReceive = {},
            onNavigateToNodeStatus = {},
            onNavigateToBackup = {},
            onNavigateToSecuritySettings = {},
            onNavigateToImport = {}
        )
    }
}
