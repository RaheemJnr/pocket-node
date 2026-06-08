package com.rjnr.pocketnode.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.CacheManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.sync.SyncStallDetector
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.price.PriceRepository
import com.rjnr.pocketnode.data.update.UpdateDownloader
import com.rjnr.pocketnode.data.update.UpdateInfo
import com.rjnr.pocketnode.data.update.UpdateRepository
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.ui.components.WalletGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.util.Locale
import javax.inject.Inject

private const val TAG = "HomeViewModel"

// Price refresh tunables (#117 deferred items).
//   PRICE_REFRESH_INTERVAL_MS — how often the periodic ticker fires.
//   PRICE_STALENESS_THRESHOLD_MS — minimum age before refreshPriceIfStale
//     actually re-fetches. Lower than the interval so a foreground tap can
//     bypass the timer and refresh immediately if the cached price is old.
private const val PRICE_REFRESH_INTERVAL_MS: Long = 5L * 60L * 1000L
private const val PRICE_STALENESS_THRESHOLD_MS: Long = 60L * 1000L

/**
 * One-shot navigation events from [HomeViewModel]. UI collects via
 * `viewModel.navEvents` and routes the user accordingly. Modeled as a
 * [Channel]-backed flow so each event is delivered exactly once and
 * re-collection (e.g. after config change) doesn't replay stale events.
 */
sealed class HomeNavEvent {
    /**
     * Navigate to SendScreen with prefilled recipient + amount, used by the
     * Failed-tx retry CTA in the activity list.
     */
    data class NavigateToSendWithPrefill(
        val recipientAddress: String,
        val amountShannons: Long
    ) : HomeNavEvent()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val priceRepository: PriceRepository,
    private val walletRepository: WalletRepository,
    private val updateRepository: UpdateRepository,
    private val updateDownloader: UpdateDownloader,
    private val pinManager: PinManager,
    private val authManager: AuthManager,
    private val cacheManager: CacheManager,
    private val walletPreferences: com.rjnr.pocketnode.data.wallet.WalletPreferences,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    /**
     * Survives process death so the sync-options bottom sheet re-opens after
     * the user taps the explorer-lookup link, gets briefly killed by the OEM
     * memory manager / auth gate, and returns. Without this the dialog closes
     * silently and the user lands on Home instead of where they were. (#90)
     *
     * Both flows go through `SyncOptionsSheet` and both expose the explorer
     * lookup, so both must be restored: the settings/runtime change flow
     * (`showSyncOptionsDialog`) AND the post-import flow that asks the user
     * to choose a sync start point right after wallet creation/import
     * (`showPostImportSyncDialog`).
     */
    private val SAVED_KEY_SHOW_SYNC_OPTIONS = "showSyncOptionsDialog"
    private val SAVED_KEY_SHOW_POST_IMPORT_SYNC = "showPostImportSyncDialog"

    // Skip-overlapping guard for refreshTransactionsOnly. The fn is called from
    // refresh(), the syncProgress observer (silent), and wallet switches; under
    // load these can fire within milliseconds. tryLock means we never queue a
    // duplicate JNI/Room round-trip — the second caller bails immediately.
    private val txRefreshMutex = Mutex()

    // Tracks whether the user has dismissed the stall banner this session.
    // Reset on sync mode change, wallet switch, network switch (each clears
    // the detector and re-opens the banner if the new sync also stalls).
    private var stallBannerDismissed: Boolean = false
    private val syncStallDetector = SyncStallDetector()

    private val _uiState = MutableStateFlow(
        // Restore the sync-options dialog visibility across process death so
        // the explorer-lookup → Chrome → return flow doesn't drop the user
        // back on Home instead of the dialog they were filling out (#90).
        HomeUiState(
            showSyncOptionsDialog = savedStateHandle.get<Boolean>(SAVED_KEY_SHOW_SYNC_OPTIONS) ?: false,
            showPostImportSyncDialog = savedStateHandle.get<Boolean>(SAVED_KEY_SHOW_POST_IMPORT_SYNC) ?: false,
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // One-shot nav events (e.g. retry-failed-tx → SendScreen with prefill).
    // BUFFERED so an event isn't dropped if the UI is mid-recomposition.
    private val _navEvents = Channel<HomeNavEvent>(Channel.BUFFERED)
    val navEvents = _navEvents.receiveAsFlow()

    // Captures a pending APK download URL + size when the user is sent to
    // Android settings to grant install-from-unknown-sources. On ON_RESUME
    // we check `canInstallPackages()` and start the download automatically
    // so the user does not have to wait for the next launch / re-tap Update.
    private var pendingUpdateApkUrl: String? = null
    private var pendingUpdateApkSize: Long = 0L

    // Tracks the last successful CKB/USD fetch so we can throttle
    // refresh-on-foreground and the 5-min ticker (#117 deferred items).
    private var lastPriceFetchAt: Long = 0L

    private fun formatFiat(ckb: Double, price: Double): String =
        String.format(Locale.US, "≈ $%.2f USD", ckb * price)

    // 1-second clock tick used by the sync coachmark combine. Cancelled with
    // viewModelScope. Kept as a class-level Flow (rather than a suspend loop) so
    // we can compose it into `combine` cleanly. Declared before `init` because
    // the init block references it.
    private val coachmarkClockTick = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    init {
        checkBackupStatus()
        refreshSecurityState()
        checkForUpdate()

        viewModelScope.launch {
            initializeWallet()
        }

        // Periodic price refresh — fetches every 5 min while the VM is alive
        // (i.e. while HomeScreen is in the back stack). Kept simple: a delay
        // loop, no separate Job lifecycle. Cancelled automatically when
        // viewModelScope tears down.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(PRICE_REFRESH_INTERVAL_MS)
                refreshPriceIfStale()
            }
        }

        viewModelScope.launch {
            var previousWalletInfo: WalletInfo? = null
            repository.walletInfo.collect { info ->
                val walletChanged = previousWalletInfo != null && info != null && previousWalletInfo != info
                previousWalletInfo = info
                _uiState.update {
                    it.copy(
                        walletInfo = info,
                        address = repository.getCurrentAddress() ?: ""
                    )
                }
                // When wallet changes externally (e.g. from WalletManager), refresh data
                if (walletChanged) {
                    refresh()
                }
            }
        }

        viewModelScope.launch {
            repository.balance.collect { balance ->
                val ckb = balance?.capacityAsCkb() ?: 0.0
                _uiState.update { current ->
                    val price = current.ckbUsdPrice
                    val fiat = if (price != null) formatFiat(ckb, price) else null
                    current.copy(
                        balanceCkb = ckb,
                        fiatBalance = fiat ?: current.fiatBalance
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.network.collect { network ->
                _uiState.update { it.copy(currentNetwork = network) }
            }
        }

        viewModelScope.launch {
            repository.isSwitchingNetwork.collect { switching ->
                _uiState.update { it.copy(isSwitchingNetwork = switching) }
            }
        }

        viewModelScope.launch {
            walletRepository.walletsFlow.collect { wallets ->
                val parentWallets = wallets.filter { it.parentWalletId == null }
                val groups = parentWallets.map { parent ->
                    WalletGroup(
                        wallet = parent,
                        subAccounts = wallets.filter { it.parentWalletId == parent.walletId }
                    )
                }
                _uiState.update { it.copy(wallets = wallets, walletGroups = groups) }
                // Fetch cached balances for all wallets
                refreshWalletBalances(wallets)
            }
        }

        // Sync coachmark visibility (#90): show first-run education when sync has
        // been catching up for at least the grace period. Combines the prefs flag,
        // the repository SyncProgress, and a 1-second clock tick so the UI updates
        // as soon as the elapsed-time threshold is crossed without waiting for the
        // next progress emission.
        combine(
            walletPreferences.hasSeenSyncCoachmarkFlow,
            repository.syncProgress,
            coachmarkClockTick,
        ) { seen, progress, nowMs ->
            val catching = progress.isSyncing && progress.percentage < 100
            shouldShowSyncCoachmark(
                seen = seen,
                isCatchingUp = catching,
                firstCatchingUpAtMs = progress.firstCatchingUpAtMs,
                nowMs = nowMs,
            )
        }
            .distinctUntilChanged()
            .onEach { show -> _uiState.update { it.copy(showSyncCoachmark = show) } }
            .launchIn(viewModelScope)
    }

    private suspend fun initializeWallet() {
        _uiState.update { it.copy(isLoading = true) }

        repository.initializeWallet()
            .onSuccess { info ->
                Log.d(TAG, "Wallet initialized: ${info.testnetAddress}")
                _uiState.update { it.copy(walletInfo = info, isLoading = false) }
                fetchPrice()
                registerAndRefresh()
            }
            .onFailure { error ->
                Log.e(TAG, "Wallet initialization failed", error)
                _uiState.update {
                    it.copy(error = error.message?.let(com.rjnr.pocketnode.ui.util.UiMessage::Raw), isLoading = false)
                }
            }
    }

    /**
     * Fetches the CKB/USD spot price and computes the fiat equivalent of the current balance.
     * Failures are silent — the UI falls back to "≈ — USD".
     */
    private suspend fun fetchPrice() {
        priceRepository.getCkbUsdPrice()
            .onSuccess { price ->
                val balanceCkb = _uiState.value.balanceCkb
                val formatted = formatFiat(balanceCkb, price)
                _uiState.update { it.copy(fiatBalance = formatted, ckbUsdPrice = price) }
                lastPriceFetchAt = System.currentTimeMillis()
                Log.d(TAG, "CKB price: $$price, fiat balance: $formatted")
            }
            .onFailure { error ->
                Log.w(TAG, "Price fetch failed (non-critical): ${error.message}")
                // Leave fiatBalance as-is; UI shows "≈ — USD" when null
            }
    }

    /**
     * Refresh the CKB/USD price if the last successful fetch is older than
     * the staleness threshold. Called from the periodic ticker and from
     * HomeScreen on ON_RESUME (#117 deferred items). Throttled to avoid
     * hammering CoinGecko/Binance on rapid foreground/background cycles.
     */
    fun refreshPriceIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastPriceFetchAt < PRICE_STALENESS_THRESHOLD_MS) return
        viewModelScope.launch { fetchPrice() }
    }

    private suspend fun registerAndRefresh() {
        // Always refresh saved sync preferences for UI display
        val savedSyncModeForUi = repository.getSavedSyncMode()
        val savedCustomBlockHeightForUi = repository.getSavedCustomBlockHeight()
        _uiState.update { it.copy(currentSyncMode = savedSyncModeForUi, savedCustomBlockHeight = savedCustomBlockHeightForUi) }

        // If already registered (e.g., ViewModel recreated by tab navigation),
        // skip re-registration to avoid resetting sync progress
        if (repository.isRegistered.value) {
            Log.d(TAG, "Already registered, skipping re-registration")
            checkSyncStatusAndRefresh()
            return
        }

        // Load saved sync preferences
        val savedSyncMode = repository.getSavedSyncMode()
        val savedCustomBlockHeight = repository.getSavedCustomBlockHeight()
        val hasCompletedInitialSync = repository.hasCompletedInitialSync()

        Log.d(TAG, "Loading saved sync preferences: mode=$savedSyncMode, customBlock=$savedCustomBlockHeight, completedSync=$hasCompletedInitialSync")

        _uiState.update { it.copy(currentSyncMode = savedSyncMode, savedCustomBlockHeight = savedCustomBlockHeight) }

        // First-registration sync-mode resolution:
        // 1. If the user has completed an initial sync before, respect their
        //    last choice (savedSyncMode).
        // 2. Otherwise, if a per-wallet sync mode has been EXPLICITLY written
        //    (e.g. by WalletRepository.markFreshWalletSyncMode on createWallet,
        //    or by the post-import sheet on importWallet), use that. This is
        //    the path that was being silently overwritten in earlier releases:
        //    a fresh wallet would get NEW_WALLET written, then a network-default
        //    heuristic would replace it with RECENT in registerAccount.
        // 3. Only when neither (1) nor (2) applies (legacy single-wallet
        //    upgraders, etc.) do we fall back to the network-default heuristic.
        val activeWid = walletPreferences.getActiveWalletId()?.takeIf { it.isNotBlank() }
        val explicitSyncMode = walletPreferences.getSyncModeOrNull(walletId = activeWid)
        val networkDefault = if (repository.currentNetwork == NetworkType.TESTNET)
            SyncMode.NEW_WALLET else SyncMode.RECENT
        val syncMode = when {
            hasCompletedInitialSync -> savedSyncMode
            explicitSyncMode != null -> explicitSyncMode
            else -> networkDefault
        }
        val customBlockHeight = if (syncMode == SyncMode.CUSTOM) savedCustomBlockHeight else null

        Log.d(TAG, "Registering account with sync mode: $syncMode")

        repository.registerAccountWithStrategy(
            syncMode = syncMode,
            customBlockHeight = customBlockHeight,
            savePreference = !hasCompletedInitialSync // Only save if first time
        )
            .onSuccess {
                Log.d(TAG, "Account registered successfully with sync mode: $syncMode")
                // Check sync status before fetching transactions
                checkSyncStatusAndRefresh()
            }
            .onFailure { error ->
                Log.e(TAG, "Registration failed", error)
                _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_registration_failed, listOf(error.message ?: ""))) }
            }
    }

    private suspend fun checkSyncStatusAndRefresh() {
        observeSyncProgress()
        // Refresh data regardless of sync status
        refresh()
    }

    private fun observeSyncProgress() {
        viewModelScope.launch {
            repository.syncProgress.collect { progress ->
                val stallInfo = syncStallDetector.evaluate(
                    syncedBlock = progress.syncedToBlock,
                    tipBlock = progress.tipBlockNumber,
                    nowMs = System.currentTimeMillis(),
                )
                val showStallBanner = stallInfo.isStalled
                    && progress.isSyncing
                    && !stallBannerDismissed
                _uiState.update {
                    it.copy(
                        syncProgress = progress.percentage / 100.0,
                        isSyncing = progress.isSyncing,
                        syncedToBlock = progress.syncedToBlock.toString(),
                        tipBlockNumber = progress.tipBlockNumber.toString(),
                        showSyncStallBanner = showStallBanner,
                        syncStallMinutes = stallInfo.stalledForMinutes,
                    )
                }

                if (progress.justReachedTip) {
                    Log.d(TAG, "Sync just reached tip -- refreshing all data")
                    refresh()
                } else if (!progress.isSyncing && progress.tipBlockNumber > 0) {
                    // Periodically refresh when synced (the flow emits on each poll)
                    refresh()
                } else if (progress.isSyncing) {
                    refreshTransactionsOnly(silent = true)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            Log.d(TAG, "Refreshing balance...")
            repository.refreshBalance()
                .onSuccess { balance ->
                    Log.d(TAG, "Balance: ${balance.capacityCkb} CKB")
                    // Recompute fiat with the cached price if available
                    val price = _uiState.value.ckbUsdPrice
                    if (price != null) {
                        _uiState.update { it.copy(fiatBalance = formatFiat(balance.capacityAsCkb(), price)) }
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to refresh balance", error)
                }

            // Refresh peer count (best-effort: parse JSON array size)
            try {
                val peersJson = repository.getPeers()
                if (peersJson != null) {
                    val count = Json.parseToJsonElement(peersJson).jsonArray.size
                    _uiState.update { it.copy(peerCount = count) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh peer count", e)
            }

            refreshTransactionsOnly()

            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private suspend fun refreshWalletBalances(wallets: List<WalletEntity>) {
        val network = _uiState.value.currentNetwork.name
        val balanceMap = mutableMapOf<String, String>()
        for (wallet in wallets) {
            try {
                val cached = cacheManager.getCachedBalance(network, walletId = wallet.walletId)
                if (cached != null) {
                    val ckb = cached.capacityAsCkb()
                    balanceMap[wallet.walletId] = String.format(Locale.US, "%,.2f CKB", ckb)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get cached balance for ${wallet.walletId}", e)
            }
        }
        _uiState.update { it.copy(walletBalances = balanceMap) }
    }

    private suspend fun refreshTransactionsOnly(silent: Boolean = false) {
        if (!txRefreshMutex.tryLock()) {
            Log.d(TAG, "refreshTransactionsOnly skipped — already in flight")
            return
        }
        try {
            Log.d(TAG, "Fetching transactions (limit=50)...")
            repository.getTransactions(limit = 50)
                .onSuccess { response ->
                    Log.d(TAG, "Fetched ${response.items.size} transactions")
                    response.items.forEachIndexed { index, tx ->
                        Log.d(TAG, "  [$index] ${tx.txHash.take(16)}... dir=${tx.direction} amount=${tx.balanceChange} conf=${tx.confirmations}")
                    }
                    _uiState.update {
                        it.copy(transactions = response.items)
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to fetch transactions", error)
                    if (!silent) {
                        _uiState.update {
                            it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_load_transactions_failed, listOf(error.message ?: "")))
                        }
                    }
                }
        } finally {
            txRefreshMutex.unlock()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Handles a tap on the Failed chip in the activity list. Loads the failed
     * `pending_broadcasts` row, deletes the failed-state rows so the retry
     * doesn't see itself as a reservation, and emits a nav event with the
     * decoded recipient + amount for SendScreen prefill.
     */
    fun retryFailedTransaction(txHash: String) {
        viewModelScope.launch {
            repository.loadFailedForRetry(txHash)
                .onSuccess { prefill ->
                    _navEvents.send(
                        HomeNavEvent.NavigateToSendWithPrefill(
                            recipientAddress = prefill.recipientAddress,
                            amountShannons = prefill.amountShannons
                        )
                    )
                    // Drop the row from the in-memory list so the chip disappears
                    // immediately (the next refresh will confirm the row is gone).
                    _uiState.update { state ->
                        state.copy(transactions = state.transactions.filter { it.txHash != txHash })
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "retryFailedTransaction failed for $txHash", e)
                    _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_retry_failed, listOf(e.message ?: ""))) }
                }
        }
    }

    /**
     * Change sync mode and resync the account.
     * This will re-register with the light client using the new sync settings.
     */
    fun changeSyncMode(syncMode: SyncMode, customBlockHeight: Long? = null) {
        // No-op when the user re-selects the currently-active mode (no change to
        // resync against). Without this guard, re-selecting RECENT after a full
        // sync would wipe progress and re-sync the last 200k blocks. (#108)
        val current = _uiState.value
        if (syncMode == current.currentSyncMode && customBlockHeight == current.savedCustomBlockHeight) {
            Log.d(TAG, "changeSyncMode: same mode + height already active — no-op")
            return
        }
        viewModelScope.launch {
            Log.d(TAG, "Changing sync mode to: $syncMode, customBlock: $customBlockHeight")

            repository.stopSyncPolling()
            syncStallDetector.reset()
            stallBannerDismissed = false

            _uiState.update {
                it.copy(
                    isLoading = true,
                    isSyncing = true,
                    syncProgress = 0.0,
                    transactions = emptyList(),
                    currentSyncMode = syncMode,
                    savedCustomBlockHeight = customBlockHeight,
                    showSyncStallBanner = false,
                    syncStallMinutes = 0L,
                )
            }

            repository.resyncAccount(syncMode, customBlockHeight)
                .onSuccess {
                    Log.d(TAG, "Resync initiated successfully")
                    _uiState.update { it.copy(isLoading = false) }
                    checkSyncStatusAndRefresh()
                }
                .onFailure { error ->
                    Log.e(TAG, "Resync failed", error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isSyncing = false,
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_sync_mode_change_failed_home, listOf(error.message ?: ""))
                        )
                    }
                }
        }
    }

    /**
     * Show the sync options dialog
     */
    fun showSyncOptions() {
        _uiState.update { it.copy(showSyncOptionsDialog = true) }
        savedStateHandle[SAVED_KEY_SHOW_SYNC_OPTIONS] = true
    }

    /**
     * Hide the sync options dialog
     */
    fun hideSyncOptions() {
        _uiState.update { it.copy(showSyncOptionsDialog = false) }
        savedStateHandle[SAVED_KEY_SHOW_SYNC_OPTIONS] = false
    }

    private fun checkBackupStatus() {
        viewModelScope.launch {
            val type = repository.getWalletType()
            // Sub-accounts (parentWalletId != null) don't hold their own mnemonic —
            // the parent wallet's backup covers them. Don't show backup reminder.
            val activeWallet = _uiState.value.wallets.find { it.isActive }
            val isSubAccount = activeWallet?.parentWalletId != null
            val needsBackup = type == KeyManager.WALLET_TYPE_MNEMONIC
                && !isSubAccount
                && !repository.hasMnemonicBackupForActiveWallet()
            _uiState.update { it.copy(walletType = type, showBackupReminder = needsBackup) }
        }
    }

    fun refreshSecurityState() {
        viewModelScope.launch {
            val hasPin = pinManager.hasPin()
            val hasBiometrics = authManager.isBiometricEnabled()
            // Sub-accounts inherit parent's backup status — treat as backed up
            val activeWallet = _uiState.value.wallets.find { it.isActive }
            val isSubAccount = activeWallet?.parentWalletId != null
            val hasMnemonicBackup = isSubAccount || repository.hasMnemonicBackupForActiveWallet()
            _uiState.update {
                it.copy(
                    hasPinOrBiometrics = hasPin || hasBiometrics,
                    hasMnemonicBackup = hasMnemonicBackup
                )
            }
        }
    }

    fun dismissBackupReminder() {
        _uiState.update { it.copy(showBackupReminder = false) }
    }

    fun toggleBalanceVisibility() {
        _uiState.update { it.copy(isBalanceHidden = !it.isBalanceHidden) }
    }

    fun isMnemonicWallet(): Boolean = _uiState.value.walletType == KeyManager.WALLET_TYPE_MNEMONIC

    /**
     * Hide the post-import sync mode dialog
     */
    fun hidePostImportSyncDialog() {
        _uiState.update { it.copy(showPostImportSyncDialog = false) }
        savedStateHandle[SAVED_KEY_SHOW_POST_IMPORT_SYNC] = false
    }

    /** Open the post-import sync mode dialog (used by Education close-and-reopen). */
    fun showPostImportSyncDialog() {
        _uiState.update { it.copy(showPostImportSyncDialog = true) }
        savedStateHandle[SAVED_KEY_SHOW_POST_IMPORT_SYNC] = true
    }

    fun switchWallet(walletId: String) {
        viewModelScope.launch {
            try {
                syncStallDetector.reset()
                stallBannerDismissed = false
                _uiState.update { it.copy(isSwitchingWallet = true, showSyncStallBanner = false, syncStallMinutes = 0L) }
                walletRepository.switchActiveWallet(walletId)
                val wallet = walletRepository.getById(walletId) ?: return@launch
                repository.onActiveWalletChanged(wallet)
                // Refresh per-wallet sync preferences for UI
                val newSyncMode = repository.getSavedSyncMode()
                val newCustomHeight = repository.getSavedCustomBlockHeight()
                checkBackupStatus()
                refreshSecurityState()
                refreshWalletBalances(_uiState.value.wallets)
                // Clear stale transactions and refresh from new wallet
                _uiState.update { it.copy(
                    isSwitchingWallet = false,
                    transactions = emptyList(),
                    currentSyncMode = newSyncMode,
                    savedCustomBlockHeight = newCustomHeight
                ) }
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch wallet", e)
                _uiState.update { it.copy(isSwitchingWallet = false, error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_switch_wallet_failed, listOf(e.message ?: ""))) }
            }
        }
    }

    fun requestNetworkSwitch(target: NetworkType) {
        _uiState.update { it.copy(showNetworkSwitchDialog = true, pendingNetworkSwitch = target) }
    }

    fun cancelNetworkSwitch() {
        _uiState.update { it.copy(showNetworkSwitchDialog = false, pendingNetworkSwitch = null) }
    }

    fun confirmNetworkSwitch() {
        val target = _uiState.value.pendingNetworkSwitch ?: return
        _uiState.update { it.copy(showNetworkSwitchDialog = false, pendingNetworkSwitch = null) }

        viewModelScope.launch {
            // Cancel active polling before switching
            repository.stopSyncPolling()
            syncStallDetector.reset()
            stallBannerDismissed = false

            // Clear UI state for fresh network
            _uiState.update {
                it.copy(
                    balanceCkb = 0.0,
                    fiatBalance = null,
                    ckbUsdPrice = null,
                    transactions = emptyList(),
                    syncProgress = 0.0,
                    syncedToBlock = null,
                    tipBlockNumber = "",
                    peerCount = 0,
                    isSyncing = true,
                    error = null,
                    showSyncStallBanner = false,
                    syncStallMinutes = 0L,
                )
            }

            repository.switchNetwork(target)
                .onSuccess {
                    Log.d(TAG, "Network switched to ${target.name}")
                    _uiState.update {
                        it.copy(address = repository.getCurrentAddress() ?: "")
                    }
                    fetchPrice()
                    checkSyncStatusAndRefresh()
                }
                .onFailure { error ->
                    Log.e(TAG, "Network switch failed", error)
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_network_switch_failed, listOf(error.message ?: ""))
                        )
                    }
                }
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            updateRepository.checkForUpdate(BuildConfig.VERSION_NAME)
                .onSuccess { info ->
                    if (info != null) {
                        Log.d(TAG, "Update available: ${info.latestVersion}")
                        _uiState.update { it.copy(updateInfo = info, showUpdateDialog = true) }
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Update check failed (non-critical): ${error.message}")
                }
        }
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun startUpdate() {
        val info = _uiState.value.updateInfo ?: return

        if (info.apkDownloadUrl == null) {
            // No APK asset attached to this release. Nothing safe to do
            // without surprising the user with a browser jump; just close.
            _uiState.update { it.copy(showUpdateDialog = false) }
            return
        }

        if (!updateDownloader.canInstallPackages()) {
            // Cannot proceed until the user grants "install from unknown
            // sources" for this app. Hide the update dialog and show the
            // permission prompt instead. Previously this flag was set but
            // never rendered, so tapping Update did nothing.
            _uiState.update {
                it.copy(
                    showUpdateDialog = false,
                    showInstallPermissionNeeded = true,
                )
            }
            return
        }

        // Close the dialog. The UpdateProgressBanner at MainScreen scope
        // takes over and surfaces progress + install CTA.
        _uiState.update { it.copy(showUpdateDialog = false) }
        updateDownloader.downloadAndInstall(
            apkUrl = info.apkDownloadUrl,
            totalBytesHint = info.fileSize,
        )
    }

    /**
     * Dismiss the install-from-unknown-sources prompt without opening
     * settings. The user can re-trigger the update flow from the dialog
     * later when they are ready.
     */
    fun dismissInstallPermission() {
        _uiState.update { it.copy(showInstallPermissionNeeded = false) }
        pendingUpdateApkUrl = null
        pendingUpdateApkSize = 0L
    }

    /**
     * Open the system settings screen that lets the user grant install
     * permission for this app. Captures the pending download details so
     * [retryPendingUpdateIfPermissionGranted] can resume automatically on
     * ON_RESUME once the user returns with permission granted.
     */
    fun openInstallPermissionSettings(launch: (android.content.Intent) -> Unit) {
        val info = _uiState.value.updateInfo
        pendingUpdateApkUrl = info?.apkDownloadUrl
        pendingUpdateApkSize = info?.fileSize ?: 0L
        _uiState.update { it.copy(showInstallPermissionNeeded = false) }
        launch(updateDownloader.getInstallPermissionIntent())
    }

    /**
     * Called from the HomeScreen lifecycle observer when the activity
     * resumes. If we previously sent the user to Android settings to grant
     * "install from unknown sources" and they returned with the permission
     * now granted, kick off the download automatically instead of forcing
     * them to re-tap Update.
     */
    fun retryPendingUpdateIfPermissionGranted() {
        val url = pendingUpdateApkUrl ?: return
        if (!updateDownloader.canInstallPackages()) return
        val size = pendingUpdateApkSize
        pendingUpdateApkUrl = null
        pendingUpdateApkSize = 0L
        updateDownloader.downloadAndInstall(apkUrl = url, totalBytesHint = size)
    }

    /**
     * Called by the UI when the user dismisses the sync coachmark (e.g. taps
     * "Got it"). Persists the seen flag; the combined flow re-emits `false` on
     * the next clock tick and the UI clears.
     *
     * Note: [com.rjnr.pocketnode.data.wallet.WalletPreferences.markSyncCoachmarkSeen]
     * is non-suspend (writes via SharedPreferences.edit().apply()), so no
     * coroutine wrapper is needed.
     */
    fun onCoachmarkDismissed() {
        walletPreferences.markSyncCoachmarkSeen()
    }

    /**
     * Hide the sync-stall banner without changing sync mode. Stays hidden
     * until the user changes sync mode / wallet / network. The detector
     * itself keeps running and would re-flag if the next reset matches.
     */
    fun dismissSyncStallBanner() {
        stallBannerDismissed = true
        _uiState.update { it.copy(showSyncStallBanner = false) }
    }

    /**
     * One-tap recovery from a stall on a 2021-era wallet (#150): switch to
     * RECENT sync mode. Delegates to [changeSyncMode] so the polling teardown,
     * detector reset, and resync run via the existing path.
     */
    fun switchToRecentSyncFromStall() {
        changeSyncMode(SyncMode.RECENT, customBlockHeight = null)
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT call updateDownloader.cleanup() here. The downloader is a
        // @Singleton owned at app scope; the banner above the bottom nav
        // (driven by UpdateBannerViewModel in MainScreen) is what surfaces
        // its state. Cancelling here would kill an in-flight update the
        // moment HomeScreen unmounts (e.g. configuration change, swap to
        // Activity tab). The downloader cleans itself up on init for stale
        // APKs and on cancel/resetState for in-flight ones.
    }

    companion object {
        private const val COACHMARK_GRACE_MS = 2_000L

        /**
         * True when the user has not seen the sync coachmark yet AND sync has been
         * actively catching up for at least [COACHMARK_GRACE_MS]. The grace prevents
         * the coachmark from flashing for NEW_WALLET sync mode that completes in
         * milliseconds.
         *
         * Defensive: returns false when [firstCatchingUpAtMs] is null even if
         * [isCatchingUp] is true. Negative deltas (clock skew / frozen test clocks)
         * are coerced to 0 and treated as "still in grace".
         */
        fun shouldShowSyncCoachmark(
            seen: Boolean,
            isCatchingUp: Boolean,
            firstCatchingUpAtMs: Long?,
            nowMs: Long,
        ): Boolean {
            if (seen) return false
            if (!isCatchingUp) return false
            val firstAt = firstCatchingUpAtMs ?: return false
            val elapsed = (nowMs - firstAt).coerceAtLeast(0)
            return elapsed >= COACHMARK_GRACE_MS
        }
    }
}

data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val syncProgress: Double = 0.0,
    val syncedToBlock: String? = null,
    val tipBlockNumber: String = "",
    val walletInfo: WalletInfo? = null,
    val address: String = "",
    val balanceCkb: Double = 0.0,
    val fiatBalance: String? = null,
    val ckbUsdPrice: Double? = null,
    val peerCount: Int = 0,
    val transactions: List<TransactionRecord> = emptyList(),
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
    val currentSyncMode: SyncMode = SyncMode.RECENT,
    val showSyncOptionsDialog: Boolean = false,
    val showPostImportSyncDialog: Boolean = false,
    val showBackupReminder: Boolean = false,
    val walletType: String = KeyManager.WALLET_TYPE_RAW_KEY,
    val currentNetwork: NetworkType = NetworkType.MAINNET,
    val isSwitchingNetwork: Boolean = false,
    val showNetworkSwitchDialog: Boolean = false,
    val pendingNetworkSwitch: NetworkType? = null,
    val isBalanceHidden: Boolean = false,
    val wallets: List<WalletEntity> = emptyList(),
    val walletGroups: List<WalletGroup> = emptyList(),
    val updateInfo: UpdateInfo? = null,
    val showUpdateDialog: Boolean = false,
    val showInstallPermissionNeeded: Boolean = false,
    val hasPinOrBiometrics: Boolean = false,
    val hasMnemonicBackup: Boolean = false,
    val isSwitchingWallet: Boolean = false,
    val savedCustomBlockHeight: Long? = null,
    val walletBalances: Map<String, String> = emptyMap(),
    val showSyncCoachmark: Boolean = false,
    val showSyncStallBanner: Boolean = false,
    val syncStallMinutes: Long = 0L,
)
