package com.rjnr.pocketnode.ui.screens.activity

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.export.TransactionExporter
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ActivityViewModel"

data class ActivityUiState(
    val isLoading: Boolean = false,
    val filter: ActivityViewModel.Filter = ActivityViewModel.Filter.ALL,
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
    val currentNetwork: NetworkType = NetworkType.MAINNET
)

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val transactionDao: TransactionDao,
    private val walletPreferences: WalletPreferences
) : ViewModel() {

    enum class Filter { ALL, RECEIVED, SENT }

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    private val _exportEvent = MutableSharedFlow<String>()
    val exportEvent: SharedFlow<String> = _exportEvent.asSharedFlow()


    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val transactionPagingFlow: Flow<PagingData<TransactionRecord>> = combine(
        _uiState.map { it.filter }.distinctUntilChanged(),
        _uiState.map { it.currentNetwork }.distinctUntilChanged()
    ) { filter, network ->
        filter to network
    }.flatMapLatest { (filter, network) ->
        val walletId = walletPreferences.getActiveWalletId() ?: ""
        Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
            when (filter) {
                Filter.ALL -> transactionDao.getTransactionsPaged(walletId, network.name)
                Filter.RECEIVED -> transactionDao.getReceivedTransactionsPaged(walletId, network.name)
                Filter.SENT -> transactionDao.getSentTransactionsPaged(walletId, network.name)
            }
        }.flow.map { pagingData ->
            pagingData.map { it.toTransactionRecord() }
        }
    }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            repository.network.collect { network ->
                _uiState.update { it.copy(currentNetwork = network) }
                refreshCache()
            }
        }
    }

    fun refreshCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.getTransactions()
                .onSuccess {
                    Log.d(TAG, "Cache refreshed")
                    _uiState.update { it.copy(isLoading = false) }
                    // Room cache is now updated, PagingSource auto-invalidates
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to refresh cache", error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message?.let(com.rjnr.pocketnode.ui.util.UiMessage::Raw)
                                ?: com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                    com.rjnr.pocketnode.R.string.vm_error_export_failed,
                                    listOf(""),
                                ),
                        )
                    }
                }
        }
    }

    fun setFilter(filter: Filter) {
        _uiState.update { it.copy(filter = filter) }
    }

    /**
     * Handles a tap on the Failed chip in the activity list. Re-broadcasts the
     * original signed transaction (#316) — reusing its exact inputs so the
     * original and the retry conflict and at most one can ever commit (no
     * double-pay), with no recipient-guessing prefill. Mirrors HomeViewModel.
     */
    fun retryFailedTransaction(txHash: String) {
        viewModelScope.launch {
            repository.retryBroadcast(txHash)
                .onFailure { e ->
                    Log.e(TAG, "retryFailedTransaction failed for $txHash", e)
                    _uiState.update {
                        it.copy(
                            error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                                com.rjnr.pocketnode.R.string.vm_error_retry_failed,
                                listOf(e.message ?: ""),
                            ),
                        )
                    }
                }
        }
    }

    fun exportTransactions() {
        viewModelScope.launch {
            runCatching {
                val walletId = walletPreferences.getActiveWalletId() ?: ""
                val network = _uiState.value.currentNetwork.name
                val entities = transactionDao.getAllByWalletAndNetwork(walletId, network)
                TransactionExporter().exportToCsv(entities)
            }.onSuccess { csv ->
                _exportEvent.emit(csv)
            }.onFailure { error ->
                Log.e(TAG, "Export failed", error)
                _uiState.update {
                    it.copy(
                        error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(
                            com.rjnr.pocketnode.R.string.vm_error_export_failed,
                            listOf(error.message ?: ""),
                        ),
                    )
                }
            }
        }
    }
}
