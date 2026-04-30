package com.rjnr.pocketnode.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Github
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Network
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Wallet
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.displayName
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.components.SyncOptionsSheet
import com.rjnr.pocketnode.data.wallet.SyncStrategy
import com.rjnr.pocketnode.data.wallet.ThemeMode
import com.rjnr.pocketnode.ui.education.EducationSheet
import com.rjnr.pocketnode.ui.education.EducationTopic
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.ui.theme.PendingAmber

private const val GITHUB_URL = "https://github.com/RaheemJnr/pocket-node/"

private val ColorAmber = PendingAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToNodeStatus: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToSecuritySettings: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
    onNavigateToWalletManager: () -> Unit = {},
    onNavigateToFaq: (anchor: String?) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Education sheet hoisted at root so it composes alongside other dialogs/sheets.
    // `resumeSyncSheet` lets us reopen SyncOptionsSheet after EducationSheet dismisses
    // when the user opened education from inside the sync sheet (Task 3.4 close-and-reopen).
    var educationTopic by rememberSaveable { mutableStateOf<EducationTopic?>(null) }
    var resumeSyncSheet by rememberSaveable { mutableStateOf(false) }

    // Notification permission explanation dialog + system permission launcher
    var showNotificationExplanation by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Only enable background sync if the user actually granted notification
        // permission. The foreground service needs to post a notification to
        // run — without permission, FGS startForeground silently fails and the
        // service gets killed, leaving the user with a misleading "ON" toggle.
        // (#116 — user observed sync freeze on background despite the toggle
        // showing enabled.)
        if (granted) {
            viewModel.toggleBackgroundSync(true)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Background sync needs notification permission to keep running"
                )
            }
        }
    }

    if (showNotificationExplanation) {
        AlertDialog(
            onDismissRequest = { showNotificationExplanation = false },
            title = { Text("Allow Notifications") },
            text = {
                Text(
                    "Pocket Node needs notification permission to show sync progress " +
                        "when the app is in the background.\n\n" +
                        "You'll see a small notification with the current sync percentage " +
                        "and estimated time remaining. This helps you know when your wallet " +
                        "is fully synced without opening the app."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showNotificationExplanation = false
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text("Allow")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // "Skip" leaves background sync OFF. Previously this enabled
                    // sync anyway, but on Android 13+ the FGS can't actually run
                    // without notification permission — so the toggle would show
                    // ON while sync was silently dead. (#116)
                    showNotificationExplanation = false
                }) {
                    Text("Skip")
                }
            }
        )
    }

    // Sync options dialog
    if (uiState.showSyncDialog) {
        // "Don't know your block height?" helper (#85). Opens the user's
        // address page on the CKB explorer when CUSTOM is selected.
        val onLookupBlockHeight: (() -> Unit)? = uiState.address?.takeIf { it.isNotBlank() }?.let { addr ->
            {
                val url = com.rjnr.pocketnode.ui.screens.home.buildExplorerAddressUrl(
                    addr, uiState.currentNetwork
                )
                if (!com.rjnr.pocketnode.ui.util.openInBrowser(context, url)) {
                    scope.launch {
                        snackbarHostState.showSnackbar("No browser available to open the explorer")
                    }
                }
            }
        }
        SyncOptionsSheet(
            currentMode = uiState.syncMode,
            onDismiss = { viewModel.hideSyncDialog() },
            onSelectMode = { mode, customBlock -> viewModel.setSyncMode(mode, customBlock) },
            onTopicHelp = { topic ->
                // Close-and-reopen: dismiss the sync sheet, mark for resume, then open education.
                viewModel.hideSyncDialog()
                resumeSyncSheet = true
                educationTopic = topic
            },
            savedCustomBlockHeight = uiState.savedCustomBlockHeight,
            tipBlockNumber = uiState.tipBlockNumber,
            onLookupAddressOnExplorer = onLookupBlockHeight
        )
    }

    // Network switch confirmation dialog
    val pendingSwitch = uiState.pendingNetworkSwitch
    if (uiState.showNetworkSwitchDialog && pendingSwitch != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelNetworkSwitch() },
            title = { Text("Switch to CKB ${pendingSwitch.displayName}?") },
            text = {
                Text(
                    "The app will close and reopen on CKB ${pendingSwitch.displayName}. " +
                            "Your wallet and data on the current network are safe — " +
                            "you can switch back at any time."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmNetworkSwitch() }) {
                    Text("Switch & Restart")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelNetworkSwitch() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Sync strategy selection dialog
    if (uiState.showSyncStrategyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideSyncStrategyDialog() },
            title = { Text("Wallet Sync Strategy") },
            text = {
                androidx.compose.foundation.layout.Column {
                    SyncStrategy.entries.forEach { strategy ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setSyncStrategy(strategy) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = uiState.syncStrategy == strategy,
                                onClick = { viewModel.setSyncStrategy(strategy) }
                            )
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.foundation.layout.Column {
                                Text(
                                    syncStrategyLabel(strategy),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    syncStrategyDescription(strategy),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Theme selection dialog
    if (uiState.showThemeDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideThemeDialog() },
            title = { Text("Theme") },
            text = {
                androidx.compose.foundation.layout.Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setThemeMode(mode) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = uiState.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System Default"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    // EducationSheet — opens from SyncOptionsSheet help icons. On dismiss, if the user
    // came from the sync sheet, reopen it so they don't lose their place.
    educationTopic?.let { topic ->
        EducationSheet(
            topic = topic,
            onDismiss = {
                val resume = resumeSyncSheet
                educationTopic = null
                if (resume) {
                    resumeSyncSheet = false
                    viewModel.showSyncDialog()
                }
            },
            onOpenFaq = { anchor ->
                educationTopic = null
                resumeSyncSheet = false
                onNavigateToFaq(anchor)
            },
        )
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    SettingsScreenUI(
        snackbarHostState,
        onNavigateToSecuritySettings,
        onNavigateToBackup,
        onNavigateToImport,
        uiState,
        onNavigateToNodeStatus,
        context,
        onNavigateToWalletManager = onNavigateToWalletManager,
        onNavigateToFaq = { onNavigateToFaq(null) },
        showSyncDialog = { viewModel.showSyncDialog() },
        showSyncStrategyDialog = { viewModel.showSyncStrategyDialog() },
        requestNetworkSwitch = {
            viewModel.requestNetworkSwitch(it)
        },
        showThemeDialog = { viewModel.showThemeDialog() },
        onToggleBackgroundSync = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    showNotificationExplanation = true
                    return@SettingsScreenUI
                }
            }
            viewModel.toggleBackgroundSync(enabled)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenUI(
    snackbarHostState: SnackbarHostState,
    onNavigateToSecuritySettings: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToImport: () -> Unit,
    uiState: SettingsViewModel.UiState,
    onNavigateToNodeStatus: () -> Unit,
    context: Context,
    onNavigateToWalletManager: () -> Unit = {},
    onNavigateToFaq: () -> Unit = {},
    showSyncDialog: () -> Unit,
    showSyncStrategyDialog: () -> Unit = {},
    requestNetworkSwitch: (NetworkType) -> Unit,
    showThemeDialog: () -> Unit,
    onToggleBackgroundSync: (Boolean) -> Unit = {}
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        )
        {
            // ── SECURITY ──────────────────────────────────────────────────
            item { SectionHeader("SECURITY") }

            item {
                SettingsLinkRow(
                    icon = Lucide.Shield,
                    title = "Pin & Biometrics",
                    onClick = onNavigateToSecuritySettings
                )
            }

            // ── WALLET ────────────────────────────────────────────────────
            item { SectionHeader("WALLET") }

            item {
                SettingsLinkRow(
                    icon = Lucide.Wallet,
                    title = "Manage Wallets",
                    onClick = onNavigateToWalletManager
                )
            }

            item {
                SettingsLinkRow(
                    icon = Lucide.ShieldCheck,
                    title = "Backup Wallet",
                    onClick = { onNavigateToBackup() }
                )
            }

            item {
                SettingsLinkRow(
                    icon = Lucide.Download,
                    title = "Import Wallet",
                    onClick = onNavigateToImport
                )
            }

            item {
                SettingsLinkRow(
                    icon = Lucide.RefreshCw,
                    title = "Sync Options",
                    badgeText = syncModeLabel(uiState.syncMode),
                    onClick = { showSyncDialog() }
                )
            }

            item {
                SettingsSwitchRow(
                    icon = Lucide.RefreshCw,
                    title = "Background Sync",
                    checked = uiState.isBackgroundSyncEnabled,
                    onCheckedChange = onToggleBackgroundSync
                )
            }

            item {
                SettingsValueRow(
                    icon = Lucide.RefreshCw,
                    title = "Wallet Sync Strategy",
                    value = syncStrategyLabel(uiState.syncStrategy),
                    onClick = { showSyncStrategyDialog() }
                )
            }

            // ── APPEARANCE ────────────────────────────────────────────────
            item { SectionHeader("APPEARANCE") }

            item {
                val themeLabel = when (uiState.themeMode) {
                    ThemeMode.SYSTEM -> "System"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                }
                SettingsValueRow(
                    icon = Lucide.Moon,
                    title = "Theme",
                    value = themeLabel,
                    onClick = { showThemeDialog() }
                )
            }

            // ── NETWORK ───────────────────────────────────────────────────
            item { SectionHeader("NETWORK") }

            item {
                val isTestnet = uiState.currentNetwork == NetworkType.TESTNET
                SettingsValueRow(
                    icon = Lucide.Network,
                    title = "Current Network",
                    value = "CKB ${uiState.currentNetwork.displayName}",
                    valueColor = if (isTestnet) ColorAmber else MaterialTheme.colorScheme.primary,
                    onClick = {
                        val target = if (isTestnet) NetworkType.MAINNET else NetworkType.TESTNET
                        requestNetworkSwitch(target)
                    }
                )
            }

            item {
                SettingsLinkRow(
                    icon = Lucide.Terminal,
                    title = "Node Status & Logs",
                    onClick = onNavigateToNodeStatus
                )
            }

            // ── ABOUT ─────────────────────────────────────────────────────
            item { SectionHeader("ABOUT") }

            item {
                SettingsLinkRow(
                    icon = Lucide.CircleHelp,
                    title = stringResource(R.string.settings_help_faq),
                    onClick = onNavigateToFaq
                )
            }

            item {
                SettingsValueRow(
                    icon = Lucide.Info,
                    title = "Version",
                    value = BuildConfig.VERSION_NAME,
                    onClick = null
                )
            }

            item {
                SettingsLinkRow(
                    icon = Lucide.Github,
                    title = "Open Source",
                    badgeText = "Github",
                    onClick = {
                        // Custom Tabs keeps the GitHub round-trip inside the app
                        // task, same reasoning as the explorer launches (#138).
                        com.rjnr.pocketnode.ui.util.openInBrowser(context, GITHUB_URL)
                    }
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── Section header ─────────────────────────────────────────────────────────────
@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(
            start = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(),
            top = 24.dp,
            bottom = 8.dp
        ),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

// ── Settings row ───────────────────────────────────────────────────────────────

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    trailingContent: @Composable () -> Unit,
    onClick: (() -> Unit)?
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }

    Row(
        modifier = modifier.padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        trailingContent()
    }
}

@Composable
fun SettingsLinkRow(
    icon: ImageVector,
    title: String,
    badgeText: String? = null,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick?.invoke() }
            .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badgeText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        text = badgeText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)
}

@Composable
fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    valueColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = { onClick?.invoke() })
            .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            value,
            color = valueColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)
}

@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)
}

// ── Trailing content helpers ───────────────────────────────────────────────────

@Composable
private fun StatusPill(
    enabled: Boolean,
    enabledLabel: String = "Enabled",
    disabledLabel: String = "Disabled"
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
    ) {
        Text(
            text = if (enabled) enabledLabel else disabledLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ValuePill(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color
    )
}

@Composable
private fun ChevronTrailing() {
    Icon(
        imageVector = Lucide.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun syncModeLabel(mode: SyncMode): String = when (mode) {
    SyncMode.NEW_WALLET -> "New Wallet"
    SyncMode.RECENT -> "Recent"
    SyncMode.FULL_HISTORY -> "Full History"
    SyncMode.CUSTOM -> "Custom"
}

private fun syncStrategyLabel(strategy: SyncStrategy): String = when (strategy) {
    SyncStrategy.ACTIVE_ONLY -> "Active Only"
    SyncStrategy.ALL_WALLETS -> "All Wallets"
    SyncStrategy.BALANCED -> "Balanced"
}

private fun syncStrategyDescription(strategy: SyncStrategy): String = when (strategy) {
    SyncStrategy.ACTIVE_ONLY -> "Only syncs the wallet you're using"
    SyncStrategy.ALL_WALLETS -> "Keeps all wallets synced (up to 3)"
    SyncStrategy.BALANCED -> "Active wallet real-time, others every 15 min"
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenUIPreview() {
    CkbWalletTheme {
        SettingsScreenUI(
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateToSecuritySettings = {},
            onNavigateToBackup = {},
            onNavigateToImport = {},
            uiState = SettingsViewModel.UiState(
                isPinEnabled = true,
                syncMode = SyncMode.RECENT,
                currentNetwork = NetworkType.MAINNET,
                themeMode = ThemeMode.SYSTEM
            ),
            onNavigateToNodeStatus = {},
            context = LocalContext.current,
            showSyncDialog = {},
            requestNetworkSwitch = {},
            showThemeDialog = {}
        )
    }
}
