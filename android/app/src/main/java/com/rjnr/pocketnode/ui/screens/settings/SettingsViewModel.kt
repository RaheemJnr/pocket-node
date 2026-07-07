package com.rjnr.pocketnode.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.update.UpdateRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.SyncStrategy
import com.rjnr.pocketnode.data.wallet.ThemeMode
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val walletPrefs: WalletPreferences,
    private val pinManager: PinManager,
    private val walletRepository: WalletRepository,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    /** Update-availability state for the About > Version row (#369). */
    sealed interface UpdateStatus {
        data object Idle : UpdateStatus
        data object Checking : UpdateStatus
        data object UpToDate : UpdateStatus
        data class Available(val version: String, val url: String) : UpdateStatus
        data object Failed : UpdateStatus
    }

    data class UiState(
        val isPinEnabled: Boolean = false,
        val syncMode: SyncMode = SyncMode.RECENT,
        val savedCustomBlockHeight: Long? = null,
        val syncStrategy: SyncStrategy = SyncStrategy.ALL_WALLETS,
        val currentNetwork: NetworkType = NetworkType.MAINNET,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val isBackgroundSyncEnabled: Boolean = true,
        val showSyncDialog: Boolean = false,
        val showSyncStrategyDialog: Boolean = false,
        val showNetworkSwitchDialog: Boolean = false,
        val showThemeDialog: Boolean = false,
        val pendingNetworkSwitch: NetworkType? = null,
        val tipBlockNumber: Long = 0L,
        // Active wallet address — used by SyncOptionsDialog's "look up on explorer"
        // helper for the CUSTOM mode (#85). Null until the wallet is initialized.
        val address: String? = null,
        val updateStatus: UpdateStatus = UpdateStatus.Idle,
        val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadState()
        checkForUpdate()

        // Keep network in sync with repository's live StateFlow
        viewModelScope.launch {
            repository.network.collect { network ->
                _uiState.update {
                    it.copy(
                        currentNetwork = network,
                        // Address derives from network — keep it fresh on switch.
                        address = repository.getCurrentAddress()
                    )
                }
            }
        }

        // Track wallet info changes so address updates when the active wallet flips.
        viewModelScope.launch {
            repository.walletInfo.collect { _ ->
                _uiState.update { it.copy(address = repository.getCurrentAddress()) }
            }
        }

        // Track tip block number so the sync options dialog can validate custom heights
        viewModelScope.launch {
            repository.syncProgress.collect { progress ->
                _uiState.update { it.copy(tipBlockNumber = progress.tipBlockNumber) }
            }
        }

        // Reload sync preferences when active wallet changes
        viewModelScope.launch {
            walletRepository.getActiveWallet().collect { wallet ->
                val walletId = wallet?.walletId
                _uiState.update {
                    it.copy(
                        syncMode = walletPrefs.getSyncMode(walletId = walletId),
                        savedCustomBlockHeight = walletPrefs.getCustomBlockHeight(walletId = walletId),
                        syncStrategy = walletPrefs.getSyncStrategy()
                    )
                }
            }
        }
    }

    private fun loadState() {
        val walletId = walletPrefs.getActiveWalletId()
        _uiState.update {
            it.copy(
                isPinEnabled = pinManager.hasPin(),
                syncMode = walletPrefs.getSyncMode(walletId = walletId),
                savedCustomBlockHeight = walletPrefs.getCustomBlockHeight(walletId = walletId),
                syncStrategy = walletPrefs.getSyncStrategy(),
                currentNetwork = repository.currentNetwork,
                themeMode = walletPrefs.getThemeMode(),
                isBackgroundSyncEnabled = walletPrefs.isBackgroundSyncEnabled()
            )
        }
    }

    fun showSyncDialog() {
        _uiState.update { it.copy(showSyncDialog = true) }
    }

    fun hideSyncDialog() {
        _uiState.update { it.copy(showSyncDialog = false) }
    }

    fun showSyncStrategyDialog() {
        _uiState.update { it.copy(showSyncStrategyDialog = true) }
    }

    fun hideSyncStrategyDialog() {
        _uiState.update { it.copy(showSyncStrategyDialog = false) }
    }

    fun setSyncStrategy(strategy: SyncStrategy) {
        walletPrefs.setSyncStrategy(strategy)
        _uiState.update { it.copy(syncStrategy = strategy, showSyncStrategyDialog = false) }
    }

    fun setSyncMode(mode: SyncMode, customBlockHeight: Long? = null) {
        val previousMode = _uiState.value.syncMode
        val previousCustom = _uiState.value.savedCustomBlockHeight
        // No-op when the user re-selects the currently-active mode (no change to
        // resync against). Without this guard, re-selecting RECENT after a full
        // sync would wipe progress and re-sync the last 200k blocks. (#108)
        if (mode == previousMode && customBlockHeight == previousCustom) {
            _uiState.update { it.copy(showSyncDialog = false) }
            return
        }
        _uiState.update { it.copy(syncMode = mode, showSyncDialog = false) }
        viewModelScope.launch {
            repository.resyncAccount(mode, customBlockHeight)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            syncMode = previousMode,
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                com.rjnr.pocketnode.R.string.vm_error_sync_mode_change_failed,
                                listOf(e.message ?: ""),
                            ),
                        )
                    }
                }
        }
    }

    /**
     * #382 Tier 2: explicit gap-limit scan from Settings — the recovery path
     * when the Home banner was dismissed, and the window-extension entry.
     * Feedback rides the same snackbar channel as other Settings actions.
     */
    fun runGapLimitScan() {
        viewModelScope.launch {
            repository.runGapLimitScan()
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                com.rjnr.pocketnode.R.string.gap_limit_scan_started,
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                com.rjnr.pocketnode.R.string.gap_limit_scan_failed,
                                listOf(e.message ?: ""),
                            ),
                        )
                    }
                }
        }
    }

    fun requestNetworkSwitch(target: NetworkType) {
        if (target == _uiState.value.currentNetwork) return
        _uiState.update {
            it.copy(showNetworkSwitchDialog = true, pendingNetworkSwitch = target)
        }
    }

    fun cancelNetworkSwitch() {
        _uiState.update { it.copy(showNetworkSwitchDialog = false, pendingNetworkSwitch = null) }
    }

    fun confirmNetworkSwitch() {
        val target = _uiState.value.pendingNetworkSwitch ?: return
        _uiState.update { it.copy(showNetworkSwitchDialog = false, pendingNetworkSwitch = null) }
        viewModelScope.launch {
            repository.switchNetwork(target)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                com.rjnr.pocketnode.R.string.vm_error_network_switch_failed,
                                listOf(e.message ?: ""),
                            ),
                        )
                    }
                }
        }
    }

    fun showThemeDialog() {
        _uiState.update { it.copy(showThemeDialog = true) }
    }

    fun hideThemeDialog() {
        _uiState.update { it.copy(showThemeDialog = false) }
    }

    fun setThemeMode(mode: ThemeMode) {
        walletPrefs.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode, showThemeDialog = false) }
    }

    fun toggleBackgroundSync(enabled: Boolean) {
        walletPrefs.setBackgroundSyncEnabled(enabled)
        _uiState.update { it.copy(isBackgroundSyncEnabled = enabled) }
        if (enabled) {
            repository.startBackgroundSync()
        } else {
            repository.stopBackgroundSync()
        }
    }

    /**
     * Check GitHub for a newer release and surface it on the About > Version
     * row. Runs on init and on the manual "Check for updates" tap (#369). The
     * Home screen already shows an update dialog; this puts the same signal
     * where users look for it. Non-fatal: failures show a retry-able state.
     */
    fun checkForUpdate() {
        _uiState.update { it.copy(updateStatus = UpdateStatus.Checking) }
        viewModelScope.launch {
            updateRepository.checkForUpdate(BuildConfig.VERSION_NAME)
                .onSuccess { info ->
                    _uiState.update {
                        it.copy(
                            updateStatus = if (info != null) {
                                UpdateStatus.Available(
                                    version = info.latestVersion,
                                    url = info.downloadUrl,
                                )
                            } else {
                                UpdateStatus.UpToDate
                            }
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(updateStatus = UpdateStatus.Failed) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
