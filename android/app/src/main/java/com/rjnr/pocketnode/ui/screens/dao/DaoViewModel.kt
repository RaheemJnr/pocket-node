package com.rjnr.pocketnode.ui.screens.dao

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.AuthMethod
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletRepository
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
import com.rjnr.pocketnode.data.gateway.models.DaoAction
import com.rjnr.pocketnode.data.gateway.models.DaoCellStatus
import com.rjnr.pocketnode.data.gateway.models.DaoDeposit
import com.rjnr.pocketnode.data.gateway.models.OutPoint

@HiltViewModel
class DaoViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val authManager: AuthManager,
    private val pinManager: PinManager,
    private val walletKeyReader: WalletKeyReader,
    private val walletRepository: WalletRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DaoUiState())
    val uiState: StateFlow<DaoUiState> = _uiState.asStateFlow()

    val availableBalance: StateFlow<Long> = repository.balance
        .map { it?.capacityAsLong() ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val networkType: StateFlow<NetworkType> = repository.network

    private var pendingDepositAmount: Long = 0L

    init {
        // #347: rehydrate the "Withdrawing from DAO…" banner from the persisted
        // marker so it survives process death (it was previously in-memory
        // only). The per-deposit card overlay shows independently via the
        // repository overlay; this also restores the faster 10s poll cadence.
        viewModelScope.launch {
            repository.getInFlightWithdrawOutPoints().firstOrNull()?.let { outPoint ->
                _uiState.update { it.copy(pendingAction = DaoAction.Withdrawing(outPoint)) }
            }
        }

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

    /**
     * #332: user-confirmed deeper rescan to re-index DAO deposits that
     * predate the sync window. Multi-hour cost — DaoScreen gates this
     * behind an explicit confirmation dialog.
     */
    fun deepRescanForOlderDeposits() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeepRescanning = true) }
            repository.rescanForOlderDaoDeposits()
                .onSuccess { target ->
                    _uiState.update {
                        it.copy(
                            isDeepRescanning = false,
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Raw(
                                "Deep rescan started from block $target. " +
                                    "This can take a while — leave the app open or enable background sync."
                            )
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isDeepRescanning = false,
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Raw(
                                e.message ?: "Deep rescan failed"
                            )
                        )
                    }
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
                        error = null,
                        outsideWindowCount = outsideWindowPromptCount(deposits)
                    )
                }

                // Auto-clear pending actions when state transitions
                resolvePendingAction(deposits)
            }
            .onFailure { e ->
                _uiState.update {
                    it.copy(error = e.message?.let(com.rjnr.pocketnode.ui.util.UiMessage::Raw), isLoading = false)
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
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_invalid_deposit_amount), requiresAuth = false, authMethod = null) }
            return
        }
        _uiState.update {
            it.copy(requiresAuth = false, authMethod = null, pendingAction = DaoAction.Depositing(amount))
        }
        viewModelScope.launch {
            repository.depositToDao(amount)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = e.message?.let(com.rjnr.pocketnode.ui.util.UiMessage::Raw), pendingAction = null)
                    }
                }
        }
    }

    /**
     * V2-aware deposit entry point. Reads the active wallet's private
     * key via [WalletKeyReader] (which drives a BiometricPrompt
     * CryptoObject on V2 wallets, or returns silently for V1), then
     * invokes the overload of [GatewayRepository.depositToDao] that
     * accepts an explicit key — avoiding a second key read inside the
     * repository (#213 sub-PR 5).
     */
    fun depositWithActivity(activity: FragmentActivity, amountShannons: Long) {
        viewModelScope.launch {
            executeDaoOperationWithActivity(
                activity = activity,
                pendingAction = DaoAction.Depositing(amountShannons),
                promptTitle = "Authenticate to deposit",
                promptSubtitle = "Verify your identity to lock CKB in Nervos DAO",
            ) { privateKey ->
                repository.depositToDao(amountShannons, privateKey)
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
        if (!daoActionEnabled(deposit, _uiState.value.pendingAction)) return
        _uiState.update { it.copy(pendingAction = DaoAction.Withdrawing(deposit.outPoint)) }
        viewModelScope.launch {
            repository.withdrawFromDao(deposit.outPoint)
                .onFailure { e -> failAction(deposit.outPoint, e.message) }
        }
    }

    /** V2-aware withdraw entry point. See [depositWithActivity]. */
    fun withdrawWithActivity(activity: FragmentActivity, deposit: DaoDeposit) {
        if (!daoActionEnabled(deposit, _uiState.value.pendingAction)) return
        viewModelScope.launch {
            executeDaoOperationWithActivity(
                activity = activity,
                pendingAction = DaoAction.Withdrawing(deposit.outPoint),
                promptTitle = "Authenticate to withdraw",
                promptSubtitle = "Verify your identity to begin Nervos DAO withdrawal",
            ) { privateKey ->
                repository.withdrawFromDao(deposit.outPoint, privateKey)
            }
        }
    }

    fun unlock(deposit: DaoDeposit) {
        if (!daoActionEnabled(deposit, _uiState.value.pendingAction)) return
        _uiState.update { it.copy(pendingAction = DaoAction.Unlocking(deposit.outPoint)) }
        viewModelScope.launch {
            repository.unlockDao(withdrawingOutPoint = deposit.outPoint)
                .onFailure { e -> failAction(deposit.outPoint, e.message) }
        }
    }

    /**
     * Report a failed operation on [outPoint] and stop ITS spinner (#529).
     *
     * Clearing `pendingAction` outright dropped whichever operation happened
     * to be in flight, so a refusal on one deposit could leave another
     * deposit's card spinning with nothing left to clear it.
     *
     * Internal rather than private only so the test can drive it: the
     * failures that reach it come back from `kotlin.Result`-returning
     * repository calls, which MockK 1.13.16 cannot stub through a suspend
     * resume (see the note in DaoViewModelTest).
     */
    internal fun failAction(outPoint: OutPoint, message: String?) {
        _uiState.update {
            it.copy(
                error = message?.let(com.rjnr.pocketnode.ui.util.UiMessage::Raw),
                pendingAction = if (daoActionTargets(it.pendingAction, outPoint)) null else it.pendingAction,
            )
        }
    }

    /** V2-aware unlock entry point. See [depositWithActivity]. */
    fun unlockWithActivity(activity: FragmentActivity, deposit: DaoDeposit) {
        if (!daoActionEnabled(deposit, _uiState.value.pendingAction)) return
        viewModelScope.launch {
            // #529: ask the wallet's own records first. An already-claimed
            // position used to cost a full biometric or PIN prompt before
            // unlockDao could tell the user it was already unlocked.
            val preflight = repository.unlockPreflight(deposit.outPoint)
            if (preflight.isFailure) {
                failAction(deposit.outPoint, preflight.exceptionOrNull()?.message)
                return@launch
            }
            executeDaoOperationWithActivity(
                activity = activity,
                pendingAction = DaoAction.Unlocking(deposit.outPoint),
                promptTitle = "Authenticate to unlock",
                promptSubtitle = "Verify your identity to claim your CKB and DAO compensation",
            ) { privateKey ->
                repository.unlockDao(deposit.outPoint, privateKey)
            }
        }
    }

    /**
     * Null when [action] is the operation currently in flight, otherwise the
     * one that is: a failure must only stop its own spinner (#529).
     */
    fun clearedIfCurrent(action: DaoAction): DaoAction? =
        _uiState.value.pendingAction.takeIf { it != action }

    private suspend inline fun executeDaoOperationWithActivity(
        activity: FragmentActivity,
        pendingAction: DaoAction,
        promptTitle: String,
        promptSubtitle: String,
        crossinline operation: suspend (ByteArray) -> Result<String>,
    ) {
        val walletId = walletRepository.activeWalletIdSnapshot()
        if (walletId.isNullOrEmpty()) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_no_active_wallet), pendingAction = clearedIfCurrent(pendingAction)) }
            return
        }
        _uiState.update {
            it.copy(requiresAuth = false, authMethod = null, pendingAction = pendingAction)
        }
        when (val read = walletKeyReader.readPrivateKey(
            activity = activity,
            walletId = walletId,
            promptTitle = promptTitle,
            promptSubtitle = promptSubtitle,
        )) {
            is WalletKeyReader.Result.Cancelled ->
                _uiState.update { it.copy(pendingAction = clearedIfCurrent(pendingAction)) }
            is WalletKeyReader.Result.AuthError ->
                _uiState.update {
                    it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_auth_failed_with_reason, listOf(read.message.toString())), pendingAction = clearedIfCurrent(pendingAction))
                }
            is WalletKeyReader.Result.NotAvailable ->
                _uiState.update {
                    it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_cannot_read_wallet_key, listOf(read.reason)), pendingAction = clearedIfCurrent(pendingAction))
                }
            is WalletKeyReader.Result.KeyInvalidated ->
                _uiState.update {
                    it.copy(
                        error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_biometric_changed_send),
                        pendingAction = clearedIfCurrent(pendingAction),
                    )
                }
            is WalletKeyReader.Result.Success ->
                operation(read.privateKey).onFailure { e ->
                    _uiState.update { it.copy(error = e.message?.let(com.rjnr.pocketnode.ui.util.UiMessage::Raw), pendingAction = clearedIfCurrent(pendingAction)) }
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

/**
 * How many cached deposits justify offering the deep rescan (#529).
 *
 * Only a deposit that is still LIVE somewhere the light client cannot see
 * warrants an operation that costs hours on mainnet. A retired position
 * (unlocked, its funds already back in the balance) is not missing, and used
 * to raise "Deep rescan for older deposits?" moments after the user claimed
 * it.
 */
internal fun outsideWindowPromptCount(deposits: List<DaoDeposit>): Int =
    deposits.count { it.outsideSyncWindow && it.status != DaoCellStatus.COMPLETED }

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
    // Phase 2 (Unlock) consumes the withdrawing cell, so the spinner clears
    // when its outPoint leaves the list, which the #529 retirement now makes
    // reliable. COMPLETED counts as gone too: the retired row is excluded from
    // the list today, but it is a real persisted state, and a spinner that
    // insisted on total absence is exactly what ran forever before.
    is DaoAction.Unlocking -> deposits.none {
        it.outPoint == pendingAction.outPoint && it.status != DaoCellStatus.COMPLETED
    }
}

// Pure action guards shared by DaoScreen and the view model. They live in this
// file so the codemap sees them on the view-model layer, which both the screen
// and the view model may call (the screen must not be called by the view model).
/**
 * Is a DAO chain operation already in flight against [outPoint]? (#529)
 *
 * A deposit carries no outpoint yet, so [DaoAction.Depositing] never matches
 * a specific card.
 */
fun daoActionTargets(pendingAction: DaoAction?, outPoint: OutPoint): Boolean = when (pendingAction) {
    is DaoAction.Withdrawing -> pendingAction.outPoint == outPoint
    is DaoAction.Unlocking -> pendingAction.outPoint == outPoint
    else -> false
}

/**
 * Whether the card's action button (Withdraw or Unlock) may be tapped (#529).
 *
 * The chain gives no instant feedback: a withdrawing cell keeps scanning
 * UNLOCKABLE for minutes after its unlock is broadcast, and a deposit keeps
 * scanning DEPOSITED after its withdraw is. So the button has to be closed by
 * what THIS app knows is in flight: the pending action it just started, and
 * the confirming status the persisted markers overlay for it. A second tap
 * can only build a transaction against an outpoint that is already spent.
 */
fun daoActionEnabled(deposit: DaoDeposit, pendingAction: DaoAction?): Boolean =
    !daoActionTargets(pendingAction, deposit.outPoint) &&
        deposit.status != DaoCellStatus.WITHDRAWING &&
        deposit.status != DaoCellStatus.UNLOCKING
