package com.rjnr.pocketnode.ui.screens.dao

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.AuthMethod
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DaoViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val authManager: AuthManager,
    private val pinManager: PinManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DaoUiState())
    val uiState: StateFlow<DaoUiState> = _uiState.asStateFlow()

    val availableBalance: StateFlow<Long> = repository.balance
        .map { it?.capacityAsLong() ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val networkType: StateFlow<NetworkType> = repository.network

    private var pendingDepositAmount: Long = 0L

    init {
        startPolling()

        // Refresh DAO deposits when active wallet changes
        viewModelScope.launch {
            repository.walletInfo.collect { info ->
                if (info != null) refreshDaoData()
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                refreshDaoData()
                val interval = if (_uiState.value.pendingAction != null) 10_000L else 30_000L
                delay(interval)
            }
        }
    }

    private suspend fun refreshDaoData() {
        repository.getDaoDeposits()
            .onSuccess { deposits ->
                val active = deposits
                    .filter { it.status != DaoCellStatus.COMPLETED }
                    .sortedByDescending { it.depositBlockNumber }
                val completed = deposits
                    .filter { it.status == DaoCellStatus.COMPLETED }
                    .sortedByDescending { it.depositBlockNumber }

                val depositsWithApc = active.filter { it.apc > 0.0 }
                val weightedApc = if (depositsWithApc.isNotEmpty()) {
                    val totalCap = depositsWithApc.sumOf { it.capacity }.toDouble()
                    depositsWithApc.sumOf { it.apc * it.capacity } / totalCap
                } else 2.47

                val overview = DaoOverview(
                    totalLocked = active.sumOf { it.capacity },
                    totalCompensation = deposits.sumOf { it.compensation },
                    currentApc = weightedApc,
                    activeCount = active.size,
                    completedCount = completed.size
                )

                _uiState.update {
                    it.copy(
                        overview = overview,
                        activeDeposits = active,
                        completedDeposits = completed,
                        isLoading = false,
                        error = null
                    )
                }

                // Auto-clear pending actions when state transitions
                resolvePendingAction(deposits)
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(error = e.message, isLoading = false)
                }
            }
    }

    private fun resolvePendingAction(deposits: List<DaoDeposit>) {
        val pending = _uiState.value.pendingAction ?: return
        if (shouldClearPendingAction(pending, deposits)) {
            _uiState.update { it.copy(pendingAction = null) }
        }
    }

    fun deposit(amountShannons: Long) {
        if (authManager.isAuthBeforeSendEnabled() && pinManager.hasPin()) {
            pendingDepositAmount = amountShannons
            val method = if (authManager.isBiometricEnabled() && authManager.isBiometricEnrolled()) {
                AuthMethod.BIOMETRIC
            } else {
                AuthMethod.PIN
            }
            _uiState.update { it.copy(requiresAuth = true, authMethod = method) }
            return
        }
        executeDeposit(amountShannons)
    }

    fun executeDeposit(amountShannons: Long? = null) {
        val amount = amountShannons ?: pendingDepositAmount
        pendingDepositAmount = 0L
        if (amount <= 0L) {
            _uiState.update { it.copy(error = "Invalid deposit amount", requiresAuth = false, authMethod = null) }
            return
        }
        _uiState.update {
            it.copy(requiresAuth = false, authMethod = null, pendingAction = DaoAction.Depositing(amount))
        }
        viewModelScope.launch {
            repository.depositToDao(amount)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    /** Clear auth UI state but keep pendingDepositAmount (for PIN navigation fallback). */
    fun dismissAuthPrompt() {
        _uiState.update { it.copy(requiresAuth = false, authMethod = null) }
    }

    /** True cancel — user gave up on auth entirely. */
    fun cancelAuth() {
        pendingDepositAmount = 0L
        _uiState.update { it.copy(requiresAuth = false, authMethod = null) }
    }

    fun withdraw(deposit: DaoDeposit) {
        _uiState.update { it.copy(pendingAction = DaoAction.Withdrawing(deposit.outPoint)) }
        viewModelScope.launch {
            repository.withdrawFromDao(deposit.outPoint)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    fun unlock(deposit: DaoDeposit) {
        _uiState.update { it.copy(pendingAction = DaoAction.Unlocking(deposit.outPoint)) }
        viewModelScope.launch {
            repository.unlockDao(withdrawingOutPoint = deposit.outPoint)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message, pendingAction = null)
                    }
                }
        }
    }

    fun selectTab(tab: DaoTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    /**
     * Pull-to-refresh entry point. Distinct from the periodic poll started in
     * [startPolling] — this fires immediately on user gesture and surfaces
     * its progress through `isRefreshing` (the PTR indicator) rather than the
     * fullscreen `isLoading` spinner.
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                refreshDaoData()
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

internal fun shouldClearPendingAction(
    pendingAction: DaoAction,
    deposits: List<DaoDeposit>
): Boolean = when (pendingAction) {
    is DaoAction.Depositing -> deposits.any {
        it.status == DaoCellStatus.DEPOSITED && it.capacity == pendingAction.amount
    }
    // Phase 1 (Withdraw) consumes the deposit cell on chain — it disappears
    // from the live cells list, replaced by a NEW withdrawing cell with a
    // different outPoint. The previous check (`outPoint == pendingAction.outPoint
    // && status in (LOCKED, UNLOCKABLE)`) could never match because the original
    // outPoint is gone forever. Spinner stuck. Fix: clear when the original
    // deposit's outPoint no longer appears as DEPOSITED — that means Phase 1
    // confirmed and consumed the cell. The new withdrawing cell shows up
    // separately with its own LOCKED/UNLOCKABLE status; UI surface for the
    // user is the cell card with "Unlockable in Xd Yh".
    is DaoAction.Withdrawing -> deposits.none {
        it.outPoint == pendingAction.outPoint && it.status == DaoCellStatus.DEPOSITED
    }
    is DaoAction.Unlocking -> deposits.none { it.outPoint == pendingAction.outPoint }
}
