package com.rjnr.pocketnode.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.X
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.ui.util.resolveString
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.gateway.models.displayName
import com.composables.icons.lucide.ChevronDown
import androidx.compose.ui.res.stringResource
import com.rjnr.pocketnode.ui.components.SecurityBanner
import com.rjnr.pocketnode.ui.components.SecurityBannerState
import com.rjnr.pocketnode.ui.components.BgSyncStaleBanner
import com.rjnr.pocketnode.ui.components.FoundFundsCard
import com.rjnr.pocketnode.ui.components.GapLimitBanner
import com.rjnr.pocketnode.ui.components.SyncStallBanner
import com.rjnr.pocketnode.ui.components.SyncOptionsSheet
import com.rjnr.pocketnode.ui.components.UpdateDialog
import com.rjnr.pocketnode.ui.components.AccountSelectorSheet
import com.rjnr.pocketnode.ui.components.WalletAvatar
import androidx.compose.material3.rememberModalBottomSheetState
import com.rjnr.pocketnode.ui.education.EducationSheet
import com.rjnr.pocketnode.ui.education.EducationTopic
import com.rjnr.pocketnode.ui.education.coachmark.CoachmarkRegistry
import com.rjnr.pocketnode.ui.education.coachmark.LocalCoachmarkRegistry
import com.rjnr.pocketnode.ui.education.coachmark.SyncCoachmark
import com.rjnr.pocketnode.ui.education.coachmark.coachmarkAnchor
import com.rjnr.pocketnode.ui.education.coachmark.rememberCoachmarkRegistry
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme
import com.rjnr.pocketnode.ui.theme.ErrorRed
import com.rjnr.pocketnode.ui.theme.SuccessGreen
import com.rjnr.pocketnode.ui.theme.TestnetOrange
import com.rjnr.pocketnode.ui.theme.TestnetOrangeDark
import com.rjnr.pocketnode.util.formatBlockTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSend: (recipient: String?, amountShannons: Long?) -> Unit = { _, _ -> },
    onNavigateToReceive: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToDao: () -> Unit = {},
    onNavigateToActivity: () -> Unit = {},
    onNavigateToWalletManager: () -> Unit = {},
    onNavigateToSecurityChecklist: () -> Unit = {},
    onNavigateToFaq: (anchor: String?) -> Unit = {},
    onNavigateToNodeStatus: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTransaction by remember { mutableStateOf<TransactionRecord?>(null) }
    var retryDialogTx by remember { mutableStateOf<TransactionRecord?>(null) }
    var showAccountSelector by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Education sheet + coachmark scaffolding (#90).
    // Hoisted at root so both SyncOptionsSheet call sites and section
    // header `?` icons share the same state.
    val coachmarkRegistry: CoachmarkRegistry = rememberCoachmarkRegistry()
    var educationTopic by rememberSaveable { mutableStateOf<EducationTopic?>(null) }
    var resumeSyncSheet by rememberSaveable { mutableStateOf(false) }
    var resumePostImportSheet by rememberSaveable { mutableStateOf(false) }


    // Refresh security state (PIN, backup) when returning from setup screens.
    // Also refresh CKB/USD price on foreground if it's stale (#117 deferred —
    // periodic ticker keeps it fresh while the app stays open; this covers
    // long backgrounds where the ticker was paused / process slept).
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSecurityState()
                viewModel.refreshPriceIfStale()
                // If we sent the user to Android settings to grant install
                // permission, resume the download automatically when they
                // come back instead of making them re-tap Update.
                viewModel.retryPendingUpdateIfPermissionGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tipBlockNumberLong = uiState.tipBlockNumber.toLongOrNull() ?: 0L

    // "Don't know your block height?" helper — opens the user's address page
    // on the CKB explorer so non-technical users can scroll to their first
    // transaction and read the block number off (#85).
    //
    // Uses an in-process WebView bottom sheet (#139) rather than Custom Tabs:
    // this flow is round-trip critical (the user needs to come back with a
    // specific block number in hand), and an OEM memory manager killing a
    // Custom Tab mid-read forces a full restart. Tx-detail explorer links
    // elsewhere in the app remain on Custom Tabs because they are fire-and-
    // forget. Off-host nav inside the sheet escapes to Custom Tabs.
    var explorerLookupUrl by remember { mutableStateOf<String?>(null) }
    val onLookupBlockHeight: (() -> Unit)? = uiState.address.takeIf { it.isNotBlank() }?.let { addr ->
        {
            explorerLookupUrl = buildExplorerAddressUrl(addr, uiState.currentNetwork)
        }
    }

    explorerLookupUrl?.let { url ->
        com.rjnr.pocketnode.ui.components.ExplorerWebViewSheet(
            url = url,
            onDismiss = { explorerLookupUrl = null },
        )
    }

    // Sync options sheet (settings path)
    if (uiState.showSyncOptionsDialog) {
        SyncOptionsSheet(
            currentMode = uiState.currentSyncMode,
            onDismiss = { viewModel.hideSyncOptions() },
            onSelectMode = { mode, customBlock ->
                viewModel.hideSyncOptions()
                viewModel.changeSyncMode(mode, customBlock)
            },
            onTopicHelp = { topic ->
                viewModel.hideSyncOptions()
                resumeSyncSheet = true
                educationTopic = topic
            },
            savedCustomBlockHeight = uiState.savedCustomBlockHeight,
            tipBlockNumber = tipBlockNumberLong,
            onLookupAddressOnExplorer = onLookupBlockHeight
        )
    }

    // Post-import sync mode sheet (mainnet only)
    if (uiState.showPostImportSyncDialog) {
        SyncOptionsSheet(
            currentMode = SyncMode.RECENT,
            title = stringResource(R.string.home_post_import_sync_title),
            description = stringResource(R.string.home_post_import_sync_description),
            availableModes = listOf(SyncMode.RECENT, SyncMode.CUSTOM),
            onDismiss = { viewModel.hidePostImportSyncDialog() },
            onSelectMode = { mode, customBlock ->
                viewModel.hidePostImportSyncDialog()
                // Apply UNCONDITIONALLY. The old `if (mode != RECENT)` assumed
                // RECENT was already active, but an import can register from
                // the tip first — picking RECENT was then silently dropped:
                // Settings kept the stale mode and the wallet never scanned
                // its history window (device-test 2026-07, also starved
                // sub-account discovery of coverage). changeSyncMode's
                // isSyncSettingApplied guard (#362) already makes a genuine
                // re-selection a safe no-op, so there is nothing to save here.
                viewModel.changeSyncMode(mode, customBlock)
            },
            onTopicHelp = { topic ->
                // Close-and-reopen via the post-import flag so dismissing the
                // education sheet brings the post-import picker back, not the
                // settings picker.
                viewModel.hidePostImportSyncDialog()
                resumePostImportSheet = true
                educationTopic = topic
            },
            tipBlockNumber = tipBlockNumberLong,
            onLookupAddressOnExplorer = onLookupBlockHeight
        )
    }

    // Network switch confirmation dialog
    val pendingSwitch = uiState.pendingNetworkSwitch
    if (uiState.showNetworkSwitchDialog && pendingSwitch != null) {
        val targetName = pendingSwitch.displayName
        AlertDialog(
            onDismissRequest = { viewModel.cancelNetworkSwitch() },
            title = { Text(stringResource(R.string.home_switch_network_title, targetName)) },
            text = {
                Text(
                    "The app will close and reopen on $targetName. " +
                            "Your wallet and data on the current network are safe — " +
                            "you can switch back at any time."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmNetworkSwitch() }) {
                    Text(stringResource(R.string.home_switch_restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelNetworkSwitch() }) {
                    Text(stringResource(R.string.home_cancel))
                }
            }
        )
    }

    // Confirmation dialog announcing a new version. After the user taps
    // Update Now this closes, the download proceeds in the background, and
    // the persistent banner above the bottom navigation surfaces progress
    // and the install CTA.
    if (uiState.showUpdateDialog && uiState.updateInfo != null) {
        UpdateDialog(
            updateInfo = uiState.updateInfo!!,
            onUpdate = { viewModel.startUpdate() },
            onDismiss = { viewModel.dismissUpdate() },
        )
    }

    // Install-from-unknown-sources permission prompt. Previously the
    // ViewModel raised this flag with no UI bound to it, so users who
    // hadn't granted the permission tapped Update and got nothing.
    if (uiState.showInstallPermissionNeeded) {
        com.rjnr.pocketnode.ui.components.InstallPermissionDialog(
            onGrant = {
                viewModel.openInstallPermissionSettings { intent ->
                    context.startActivity(intent)
                }
            },
            onDismiss = { viewModel.dismissInstallPermission() },
        )
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            val resolved = error.resolveString(context)
            snackbarHostState.showSnackbar(resolved)
            Log.e("HomeScreen", "Error: $resolved")
            viewModel.clearError()
        }
    }

    // Retry-failed-tx confirmation. Copy intentionally hedges: FAILED is a
    // heuristic (null × 3 + tip past +25), not proof the network rejected
    // the tx. See spec §6 cases a–d.
    retryDialogTx?.let { tx ->
        AlertDialog(
            onDismissRequest = { retryDialogTx = null },
            title = { Text(stringResource(R.string.home_retry_dialog_title)) },
            text = { Text(stringResource(R.string.home_retry_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    retryDialogTx = null
                    viewModel.retryFailedTransaction(tx.txHash)
                }) { Text(stringResource(R.string.home_retry)) }
            },
            dismissButton = {
                TextButton(onClick = { retryDialogTx = null }) { Text(stringResource(R.string.home_cancel)) }
            }
        )
    }

    // Transaction detail bottom sheet
    if (selectedTransaction != null) {
        TransactionDetailSheet(
            transaction = selectedTransaction!!,
            network = uiState.currentNetwork,
            onDismiss = { selectedTransaction = null },
            onCopyTxHash = { txHash ->
                haptic.performHapticFeedback(HapticFeedbackType.Confirm) // #304
                clipboardManager.setText(AnnotatedString(txHash))
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Transaction hash copied",
                        duration = SnackbarDuration.Short
                    )
                }
            },
            onOpenExplorer = { url ->
                com.rjnr.pocketnode.ui.util.openInBrowser(context, url)
            },
            onRetry = { tx ->
                selectedTransaction = null
                retryDialogTx = tx
            }
        )
    }

    // Account selector bottom sheet
    if (showAccountSelector) {
        AccountSelectorSheet(
            sheetState = rememberModalBottomSheetState(),
            walletGroups = uiState.walletGroups,
            activeWalletId = uiState.wallets.find { it.isActive }?.walletId ?: "",
            onSelectAccount = { viewModel.switchWallet(it) },
            onManageWallets = onNavigateToWalletManager,
            onDismiss = { showAccountSelector = false },
            balances = uiState.walletBalances
        )
    }

    CompositionLocalProvider(LocalCoachmarkRegistry provides coachmarkRegistry) {
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    val activeWallet = uiState.wallets.find { it.isActive }
                    Row(
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable { showAccountSelector = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WalletAvatar(
                            name = activeWallet?.name ?: "P",
                            colorIndex = activeWallet?.colorIndex ?: 0,
                            size = 32.dp
                        )
                        Column {
                            Text(
                                text = "wallet",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = activeWallet?.name ?: "Pocket Node",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            Lucide.ChevronDown,
                            contentDescription = stringResource(R.string.home_switch_cd),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                },
                actions = {
                    if (uiState.isSyncing) {
                        SyncingChip()
                    } else {
                        SyncedChip()
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isSwitchingNetwork) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Switching network...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Loading wallet...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreenUI(
                    uiState = uiState,
                    refresh = { viewModel.refresh() },
                    padding = padding,
                    onNavigateToBackup = onNavigateToBackup,
                    onNavigateToSend = onNavigateToSend,
                    onNavigateToReceive = onNavigateToReceive,
                    onNavigateToDao = onNavigateToDao,
                    onNavigateToActivity = onNavigateToActivity,
                    onNavigateToSecurityChecklist = onNavigateToSecurityChecklist,
                    onNavigateToNodeStatus = onNavigateToNodeStatus,
                    onNavigateToSettings = onNavigateToSettings,
                    dismissBackupReminder = { viewModel.dismissBackupReminder() },
                    onToggleBalanceVisibility = { viewModel.toggleBalanceVisibility() },
                    clipboardManager = clipboardManager,
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                    selectedTransaction = { selectedTransaction = it },
                    onRetryFailed = { retryDialogTx = it },
                    onTopicHelp = { topic -> educationTopic = topic },
                    onSyncStallSwitchToRecent = { viewModel.switchToRecentSyncFromStall() },
                    onSyncStallDismiss = { viewModel.dismissSyncStallBanner() },
                    onBgSyncStaleEnable = { viewModel.enableBackgroundSyncFromPill() },
                    onBgSyncStaleDismiss = { viewModel.dismissBgSyncStalePill() },
                    onGapLimitLearnMore = { onNavigateToFaq("imported_funds") },
                    onGapLimitDismiss = { viewModel.dismissGapLimitBanner() },
                    onGapLimitScanNow = { viewModel.runGapLimitScan() },
                    onGapLimitSweep = { viewModel.requestGapLimitSweep() },
                )
                if (uiState.isSwitchingWallet) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = padding.calculateTopPadding()),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

        // #382 Tier 3: sweep confirm — one transaction moving everything the
        // gap-limit scan found to this wallet's main address. Amount and the
        // exact fee come from the key-free preview.
        uiState.sweepPreview?.let { preview ->
            val netCkb = java.lang.String.format(
                java.util.Locale.US, "%,.2f",
                (preview.totalShannons - preview.feeShannons) / 100_000_000.0,
            )
            val feeCkb = java.math.BigDecimal(preview.feeShannons)
                .divide(java.math.BigDecimal(100_000_000))
                .stripTrailingZeros().toPlainString()
            AlertDialog(
                onDismissRequest = { viewModel.dismissGapLimitSweep() },
                title = { Text(stringResource(R.string.sweep_dialog_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.sweep_dialog_body,
                            netCkb, preview.addressCount, feeCkb,
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmGapLimitSweep() }) {
                        Text(stringResource(R.string.sweep_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissGapLimitSweep() }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }

        // Coachmark overlay (above all content)
        SyncCoachmark(
            show = uiState.showSyncCoachmark,
            anchorKey = "sync_card",
            onDismiss = { viewModel.onCoachmarkDismissed() },
        )

        // Education sheet (overlay)
        educationTopic?.let { topic ->
            EducationSheet(
                topic = topic,
                onDismiss = {
                    val resumeSettings = resumeSyncSheet
                    val resumePostImport = resumePostImportSheet
                    educationTopic = null
                    resumeSyncSheet = false
                    resumePostImportSheet = false
                    when {
                        resumePostImport -> viewModel.showPostImportSyncDialog()
                        resumeSettings -> viewModel.showSyncOptions()
                    }
                },
                onOpenFaq = { anchor ->
                    educationTopic = null
                    resumeSyncSheet = false
                    resumePostImportSheet = false
                    onNavigateToFaq(anchor)
                },
            )
        }
    }
    }
}

@Composable
fun HomeScreenUI(
    uiState: HomeUiState,
    refresh: () -> Unit,
    padding: PaddingValues,
    onNavigateToBackup: () -> Unit,
    onNavigateToSend: (recipient: String?, amountShannons: Long?) -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToDao: () -> Unit = {},
    onNavigateToActivity: () -> Unit = {},
    onNavigateToSecurityChecklist: () -> Unit = {},
    onNavigateToNodeStatus: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    dismissBackupReminder: () -> Unit,
    onToggleBalanceVisibility: () -> Unit,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    selectedTransaction: (tx: TransactionRecord) -> Unit,
    onRetryFailed: (tx: TransactionRecord) -> Unit = {},
    onTopicHelp: (EducationTopic) -> Unit = {},
    onSyncStallSwitchToRecent: () -> Unit = {},
    onSyncStallDismiss: () -> Unit = {},
    onBgSyncStaleEnable: () -> Unit = {},
    onBgSyncStaleDismiss: () -> Unit = {},
    onGapLimitLearnMore: () -> Unit = {},
    onGapLimitDismiss: () -> Unit = {},
    onGapLimitScanNow: () -> Unit = {},
    onGapLimitSweep: () -> Unit = {},
) {
    val homeContentHaptic = LocalHapticFeedback.current
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = { refresh() },
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    )
    {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Security Banner (PIN/biometrics + backup status)
            item {
                SecurityBanner(
                    state = SecurityBannerState(
                        hasPinOrBiometrics = uiState.hasPinOrBiometrics,
                        hasMnemonicBackup = uiState.hasMnemonicBackup
                    ),
                    onActionClick = onNavigateToSecurityChecklist
                )
            }

            // Mnemonic Backup Reminder
            if (uiState.showBackupReminder) {
                item {
                    BackupReminderBanner(
                        onDismiss = { dismissBackupReminder() },
                        onBackup = onNavigateToBackup
                    )
                }
            }

            item {
                NetworkBadge(
                    network = uiState.currentNetwork,
                    onClick = onNavigateToSettings,
                )
                Spacer(Modifier.width(4.dp))
            }

            // Balance Hero Card
            item {
                WalletBalanceCard(
                    balanceCkb = uiState.balanceCkb,
                    fiatBalance = uiState.fiatBalance,
                    address = uiState.address,
                    peerCount = uiState.peerCount,
                    isBalanceHidden = uiState.isBalanceHidden,
                    onToggleVisibility = onToggleBalanceVisibility,
                    onPeersClick = onNavigateToNodeStatus,
                    onCopyAddress = {
                        homeContentHaptic.performHapticFeedback(HapticFeedbackType.Confirm) // #304
                        clipboardManager.setText(AnnotatedString(uiState.address))
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Address copied to clipboard",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                )
            }

            // Sync-stall warning banner (#150): syncedToBlock has not advanced
            // for >= 5 min. Offers one-tap switch to RECENT or dismiss.
            if (uiState.showSyncStallBanner) {
                item {
                    SyncStallBanner(
                        minutesStalled = uiState.syncStallMinutes,
                        onSwitchToRecent = onSyncStallSwitchToRecent,
                        onDismiss = onSyncStallDismiss,
                    )
                }
            }

            // #382: change from this seed left to addresses we never derived
            // (seed also used in Neuron/standard BIP44 wallets). Calm notice
            // with the Tier 2 deep-scan action; switches to the found-funds
            // card once the scan resolves FOUND.
            if (uiState.foundFundsAddressCount > 0) {
                item {
                    FoundFundsCard(
                        foundCkb = uiState.foundFundsShannons / 100_000_000.0,
                        addressCount = uiState.foundFundsAddressCount,
                        onLearnMore = onGapLimitLearnMore,
                        onSweep = onGapLimitSweep,
                        sweepInProgress = uiState.sweepInProgress,
                    )
                }
            } else if (uiState.showGapLimitBanner) {
                item {
                    GapLimitBanner(
                        onLearnMore = onGapLimitLearnMore,
                        onDismiss = onGapLimitDismiss,
                        onScanNow = if (uiState.gapLimitScanAvailable) onGapLimitScanNow else null,
                        scanning = uiState.gapLimitScanning,
                    )
                }
            }

            // #286 staleness banner: wallet went >1h without observed sync
            // while the app was closed. Latched at app open in HomeViewModel.
            if (uiState.showBgSyncStalePill) {
                item {
                    BgSyncStaleBanner(
                        bgSyncEnabled = uiState.bgSyncEnabledAtOpen,
                        onEnable = onBgSyncStaleEnable,
                        onDismiss = onBgSyncStaleDismiss,
                    )
                }
            }

            // Sync warning banner
            if (uiState.isSyncing) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Lucide.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Finish syncing before making transactions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // Quick Actions Row
            item {
                ActionRow(
                    onSend = { onNavigateToSend(null, null) },
                    onReceive = onNavigateToReceive,
                    onStake = onNavigateToDao
                )
            }

            // Sync Progress Bar — only when actively syncing
            if (uiState.isSyncing) {
                item {
                    SyncProgressBar(
                        syncProgress = uiState.syncProgress,
                        isSyncing = uiState.isSyncing,
                        syncedToBlock = uiState.syncedToBlock,
                        tipBlockNumber = uiState.tipBlockNumber,
                        onHelp = { onTopicHelp(EducationTopic.Sync) },
                        modifier = Modifier.coachmarkAnchor("sync_card"),
                    )
                }
            }

            // Transactions Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.home_activity_section_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = { onTopicHelp(EducationTopic.Activity) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Lucide.CircleHelp,
                                contentDescription = stringResource(R.string.common_help_cd),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = onNavigateToActivity) {
                        Text(
                            text = "See All",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Transaction List (last 5)
            if (uiState.transactions.isEmpty()) {
                item {
                    EmptyTransactionState()
                }
            } else {
                items(
                    items = uiState.transactions.take(5),
                    key = { it.txHash }
                ) { tx ->
                    TransactionItems(
                        transaction = tx,
                        onClick = { selectedTransaction(tx) },
                        onRetry = if (tx.status == "FAILED" && tx.isOutgoing()) {
                            { onRetryFailed(tx) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupReminderBanner(
    onDismiss: () -> Unit,
    onBackup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Lucide.TriangleAlert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Back Up Your Wallet",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.home_dismiss_cd),
                        tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your recovery phrase hasn't been backed up yet. Back it up now to protect your funds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onBackup,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.home_backup_now))
            }
        }
    }
}

@Composable
private fun SyncProgressBar(
    syncProgress: Double,
    isSyncing: Boolean,
    syncedToBlock: String?,
    tipBlockNumber: String,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_sync_section_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onHelp, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Lucide.CircleHelp,
                    contentDescription = stringResource(R.string.common_help_cd),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LinearProgressIndicator(
            progress = { syncProgress.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        if (isSyncing) {
            val current = syncedToBlock?.takeIf { it.isNotBlank() }
                ?.toLongOrNull()?.let { formatBlockNumber(it) } ?: "—"
            val tip = tipBlockNumber.takeIf { it.isNotBlank() }
                ?.toLongOrNull()?.let { formatBlockNumber(it) } ?: "—"
            Text(
                text = catchingUpAnnotated(current = current, tip = tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.home_sync_status_up_to_date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Build an AnnotatedString of the form
 * "Catching up from <bold>current</bold> to <bold>tip</bold>"
 * by splitting the localized template on the two `%s` placeholders.
 */
@Composable
private fun catchingUpAnnotated(current: String, tip: String): AnnotatedString {
    val template = stringResource(R.string.home_sync_status_catching_up_from_to)
    return buildAnnotatedString {
        // Locate the two placeholders in the template so we can replace them
        // with bold spans without losing the surrounding localized prose.
        val firstIdx = template.indexOf("%1\$s")
        val secondIdx = template.indexOf("%2\$s")
        if (firstIdx < 0 || secondIdx < 0 || secondIdx <= firstIdx) {
            // Defensive fallback: bold both values appended to a plain prefix.
            append(template.replace("%1\$s", current).replace("%2\$s", tip))
            return@buildAnnotatedString
        }
        append(template.substring(0, firstIdx))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(current) }
        append(template.substring(firstIdx + 4, secondIdx))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(tip) }
        append(template.substring(secondIdx + 4))
    }
}

/** Format a block height with thousands separators, e.g. 18,300,000. */
private fun formatBlockNumber(n: Long): String =
    java.text.NumberFormat.getInstance(java.util.Locale.US).format(n)

@Composable
private fun SyncingChip() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            // Plain-language status — no block height (#90).
            // Power users can find block height on Node Status.
            Text(
                text = stringResource(R.string.home_sync_chip_syncing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SyncedChip() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Text(
            text = "✓ Synced",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EmptyTransactionState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Lucide.FileText,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No transactions yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your transaction history will appear here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(
    transaction: TransactionRecord,
    network: NetworkType,
    onDismiss: () -> Unit,
    onCopyTxHash: (String) -> Unit,
    onOpenExplorer: (String) -> Unit,
    onRetry: ((TransactionRecord) -> Unit)? = null
) {
    val isIncoming = transaction.isIncoming()
    val isOutgoing = transaction.isOutgoing()
    val explorerUrl = buildExplorerUrl(transaction.txHash, network)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = com.rjnr.pocketnode.ui.util.centredContentMaxWidth())
                .align(Alignment.CenterHorizontally)
                .padding(24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transaction Details",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // Status badge — Confirmed / Pending / Failed (#115)
                val isFailed = transaction.status == "FAILED"
                val (badgeLabel, badgeFg, badgeBg) = when {
                    isFailed -> Triple(
                        "Failed",
                        MaterialTheme.colorScheme.error,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    )
                    transaction.isConfirmed() -> Triple(
                        "Confirmed",
                        SuccessGreen,
                        SuccessGreen.copy(alpha = 0.15f)
                    )
                    else -> Triple(
                        "Pending",
                        MaterialTheme.colorScheme.tertiary,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    )
                }
                Surface(color = badgeBg, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Amount card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            isIncoming -> "Received"
                            isOutgoing -> "Sent"
                            else -> "Amount"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = transaction.formattedAmount(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isIncoming -> SuccessGreen
                            isOutgoing -> ErrorRed
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // TX Hash row with copy + explorer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TX Hash",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = transaction.shortTxHash(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = { onCopyTxHash(transaction.txHash) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Lucide.Copy,
                        contentDescription = stringResource(R.string.home_copy_tx_cd),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onOpenExplorer(explorerUrl) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Lucide.ExternalLink,
                        contentDescription = stringResource(R.string.home_explorer_cd),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Block Number",
                value = transaction.blockNumber.removePrefix("0x").toLongOrNull(16)?.toString()
                    ?: transaction.blockNumber
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Time",
                value = formatBlockTimestamp(transaction.blockTimestampHex)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            DetailRow(
                label = "Confirmations",
                value = "${transaction.confirmations}"
            )

            val displayBlockHash = transaction.blockHash
                .takeIf { it.isNotBlank() && it != "0x0" }
                ?.let { "${it.take(10)}...${it.takeLast(8)}" }
            if (displayBlockHash != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                DetailRow(
                    label = "Block Hash",
                    value = displayBlockHash,
                    isMonospace = true
                )
            }

            // Retry CTA — only for FAILED plain transfers. Retry now re-broadcasts
            // the original signed bytes (#316), so it's safe regardless of tx type;
            // the gate stays conservative (plain outgoing transfers) to keep the
            // CTA off DAO/self-transfer rows until those flows are exercised.
            if (transaction.status == "FAILED" && transaction.isOutgoing() && onRetry != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { onRetry(transaction) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.home_retry_transaction))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    isMonospace: Boolean = false,
    showCopy: Boolean = false,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )

        Row(
            modifier = Modifier.weight(0.65f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                textAlign = TextAlign.End,
                maxLines = if (isMonospace) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (showCopy && onCopy != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Lucide.Copy,
                        contentDescription = stringResource(R.string.home_copy_cd),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkBadge(network: NetworkType, onClick: (() -> Unit)? = null) {
    val isTestnet = network == NetworkType.TESTNET
    val backgroundColor = if (isTestnet) TestnetOrange else MaterialTheme.colorScheme.primary
    val textColor = if (isTestnet) Color.White else MaterialTheme.colorScheme.onPrimary
    val dotColor =
        if (isTestnet) TestnetOrangeDark else SuccessGreen

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        // #353: the chip looked tappable but wasn't. Tapping it now opens
        // Settings, where the network switcher (with its restart-confirm
        // dialog) lives.
        modifier = Modifier
            .padding(8.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = "CKB ${network.displayName}",
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenUIPreview() {
    CkbWalletTheme {
        HomeScreenUI(
            uiState = HomeUiState(
                balanceCkb = 1234.56,
                fiatBalance = "≈ $12.34 USD",
                address = "ckt1qzda0cr08m85hc8jve3z9rcr97760lg6xl6llx",
                peerCount = 8,
                transactions = listOf(
                    TransactionRecord(
                        txHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                        blockNumber = "0x123456",
                        blockHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
                        timestamp = System.currentTimeMillis() - 3600000,
                        balanceChange = "0x3b9aca00", // 10 CKB in shannons (0x3b9aca00 = 1000000000)
                        direction = "in",
                        fee = "0x2710",
                        confirmations = 12,
                        blockTimestampHex = "0x18c8d0a7a00"
                    ),
                    TransactionRecord(
                        txHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
                        blockNumber = "0x123457",
                        blockHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                        timestamp = System.currentTimeMillis() - 7200000,
                        balanceChange = "0x1dcd6500", // 5 CKB in shannons
                        direction = "out",
                        fee = "0x2710",
                        confirmations = 5,
                        blockTimestampHex = "0x18c8d0a7a00"
                    )
                ),
                showBackupReminder = true
            ),
            refresh = {},
            padding = PaddingValues(0.dp),
            onNavigateToBackup = {},
            onNavigateToSend = { _, _ -> },
            onNavigateToReceive = {},
            dismissBackupReminder = {},
            onToggleBalanceVisibility = {},
            clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current,
            snackbarHostState = remember { SnackbarHostState() },
            scope = rememberCoroutineScope(),
            selectedTransaction = {}
        )
    }
}

private fun buildExplorerUrl(txHash: String, network: NetworkType): String {
    val base = when (network) {
        NetworkType.MAINNET -> "https://explorer.nervos.org/transaction"
        NetworkType.TESTNET -> "https://testnet.explorer.nervos.org/transaction"
    }
    return "$base/$txHash"
}

/**
 * Address page URL on the public CKB explorer (#85). Used by
 * SyncOptionsDialog's "Don't know your block height?" helper so non-technical
 * users can scroll to their first transaction and read the block number off.
 */
internal fun buildExplorerAddressUrl(address: String, network: NetworkType): String {
    val base = when (network) {
        NetworkType.MAINNET -> "https://explorer.nervos.org/address"
        NetworkType.TESTNET -> "https://testnet.explorer.nervos.org/address"
    }
    return "$base/$address"
}

@Preview(showBackground = true)
@Composable
private fun TransactionItemPreview() {
    CkbWalletTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sampleTxIn = TransactionRecord(
                txHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                blockNumber = "0x123456",
                blockHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
                timestamp = System.currentTimeMillis() - 3600000,
                balanceChange = "0x3b9aca00", // 10 CKB in shannons (0x3b9aca00 = 1000000000)
                direction = "in",
                fee = "0x2710",
                confirmations = 12,
                blockTimestampHex = "0x18c8d0a7a00"
            )
            val sampleTxOut = TransactionRecord(
                txHash = "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef12345678",
                blockNumber = "0x123457",
                blockHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                timestamp = System.currentTimeMillis() - 7200000,
                balanceChange = "0x1dcd6500", // 5 CKB in shannons
                direction = "out",
                fee = "0x2710",
                confirmations = 0,
                blockTimestampHex = "0x18c8d0a7a00"
            )

            TransactionItems(
                transaction = sampleTxIn,
                onClick = {}
            )
            TransactionItems(
                transaction = sampleTxOut,
                onClick = {}
            )
        }
    }
}
