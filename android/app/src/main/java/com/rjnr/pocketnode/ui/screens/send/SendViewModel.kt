package com.rjnr.pocketnode.ui.screens.send

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.util.sanitizeAmount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import com.rjnr.pocketnode.data.auth.AuthMethod
import javax.inject.Inject

enum class TransactionState {
    IDLE,           // No transaction in progress
    SENDING,        // Transaction being built and sent
    PENDING,        // Transaction submitted, waiting for confirmation
    PROPOSED,       // Transaction in proposal stage
    CONFIRMED,      // Transaction confirmed on chain
    FAILED          // Transaction failed
}

data class SendUiState(
    val recipientAddress: String = "",
    val amountCkb: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val txHash: String? = null,
    val availableBalance: Long = 0L,
    val estimatedFee: Long = 0L,
    val networkType: NetworkType = NetworkType.MAINNET,
    val transactionState: TransactionState = TransactionState.IDLE,
    val confirmations: Int = 0,
    val statusMessage: String = "",
    val burnWarning: String? = null,
    val requiresAuth: Boolean = false,
    val authMethod: AuthMethod? = null,
    val otherWallets: List<WalletEntity> = emptyList()
)

@HiltViewModel
class SendViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val keyManager: KeyManager,
    private val transactionBuilder: TransactionBuilder,
    private val authManager: AuthManager,
    private val pinManager: PinManager,
    private val walletRepository: WalletRepository,
    private val walletKeyReader: WalletKeyReader,
    private val keyMaterialDao: KeyMaterialDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SendUiState())
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var sendJob: Job? = null

    companion object {
        private const val TAG = "SendViewModel"
        private const val POLLING_INTERVAL_MS = 3000L // Poll every 3 seconds
        private const val MAX_POLLING_ATTEMPTS = 120  // Stop after ~6 minutes
        private const val REQUIRED_CONFIRMATIONS = 3  // Consider fully confirmed after 3 confirmations
    }

    init {
        viewModelScope.launch {
            repository.balance.collect { balance ->
                _uiState.update { it.copy(availableBalance = balance?.capacityAsLong() ?: 0L) }
            }
        }

        // Load other wallets for "My Wallets" shortcut (excludes active wallet)
        viewModelScope.launch {
            walletRepository.walletsFlow.collect { wallets ->
                val active = wallets.find { it.isActive }
                val others = wallets.filter { it.walletId != active?.walletId }
                _uiState.update { it.copy(otherWallets = others) }
            }
        }

        // Track network type and cancel in-flight transaction if network changes mid-send
        viewModelScope.launch {
            repository.network.collect { network ->
                val state = _uiState.value.transactionState
                if (state != TransactionState.IDLE && state != TransactionState.CONFIRMED && state != TransactionState.FAILED) {
                    sendJob?.cancel()
                    pollingJob?.cancel()
                    _uiState.update {
                        it.copy(
                            networkType = network,
                            isLoading = false,
                            error = "Network changed. Transaction cancelled.",
                            transactionState = TransactionState.FAILED,
                            statusMessage = "Transaction cancelled due to network switch"
                        )
                    }
                } else {
                    _uiState.update { it.copy(networkType = network) }
                }
            }
        }
    }

    fun updateRecipient(address: String) {
        _uiState.update { it.copy(recipientAddress = address, error = null) }
    }

    fun updateAmount(amount: String) {
        val sanitized = sanitizeAmount(amount) ?: return  // silently reject invalid chars
        val amountShannons = try {
            if (sanitized.isEmpty()) 0L
            else BigDecimal(sanitized).setScale(8, RoundingMode.DOWN)
                .multiply(BigDecimal(100_000_000)).toLong()
        } catch (e: Exception) {
            0L
        }

        val balance = _uiState.value.availableBalance
        // Estimate: 1 input, 2 outputs (recipient + change) for typical transfer
        // If sending ~all balance, assume 1 output (no change)
        val outputCount = if (amountShannons > 0 && balance - amountShannons < 61_00000000L) 1 else 2
        val estimatedFee = transactionBuilder.estimateTransferFee(inputCount = 1, outputCount = outputCount)
        val potentialChange = balance - amountShannons - estimatedFee
        val minCapacity = 61_00000000L

        val warning = if (potentialChange in 1 until minCapacity) {
            val lostCkb = potentialChange / 100_000_000.0
            "Warning: Your remaining %.4f CKB is below the 61 CKB minimum and will be lost as a fee.".format(lostCkb)
        } else {
            null
        }

        _uiState.update { it.copy(amountCkb = sanitized, error = null, burnWarning = warning, estimatedFee = estimatedFee) }
    }

    fun setMaxAmount() {
        val balanceShannons = _uiState.value.availableBalance
        // Max send: 1 input, 1 output (no change — sending everything)
        val feeShannons = transactionBuilder.estimateTransferFee(inputCount = 1, outputCount = 1)
        val maxShannons = (balanceShannons - feeShannons).coerceAtLeast(0L)
        val maxCkb = maxShannons / 100_000_000.0
        // Locale.US — sanitizeAmount rejects comma decimals; on de/fr/es devices
        // `"%.8f".format(...)` would emit "0,12345678" and silently drop the prefill.
        val formatted = String.format(java.util.Locale.US, "%.8f", maxCkb)
            .trimEnd('0')
            .trimEnd('.')
        updateAmount(formatted.ifEmpty { "0" })
    }

    /**
     * Legacy entry point (V1 wallets). Kept so non-Compose callers and
     * tests that don't have a `FragmentActivity` still work. On a V2
     * wallet this returns an error — Compose callers should use the
     * [sendTransaction] overload that accepts an activity.
     */
    fun sendTransaction() {
        viewModelScope.launch {
            if (peekActiveKdfVersion() == 2) {
                _uiState.update {
                    it.copy(error = "This wallet requires biometric unlock; reopen Send and try again")
                }
                return@launch
            }
            sendTransactionV1OrFallback()
        }
    }

    /**
     * V2-aware entry point. If the active wallet is on kdfVersion=2,
     * acquires the private key via [WalletKeyReader] which drives the
     * BiometricPrompt CryptoObject dance. Otherwise falls back to the
     * V1 flow (which itself may show a non-CryptoObject biometric gate
     * via the existing `requiresAuth` state, unchanged from v1.6.x).
     */
    fun sendTransaction(activity: FragmentActivity) {
        viewModelScope.launch {
            if (!validateInputs()) return@launch

            val kdfVersion = peekActiveKdfVersion()
            if (kdfVersion == 2) {
                executeSendV2(activity)
            } else {
                sendTransactionV1OrFallback()
            }
        }
    }

    private suspend fun peekActiveKdfVersion(): Int? {
        val walletId = walletPreferencesActiveId() ?: return null
        return runCatching { keyMaterialDao.getKdfVersion(walletId) }.getOrNull()
    }

    private fun walletPreferencesActiveId(): String? {
        return walletRepository.activeWalletIdSnapshot()?.takeIf { it.isNotEmpty() }
    }

    private fun validateInputs(): Boolean {
        val state = _uiState.value
        if (state.recipientAddress.isBlank()) {
            _uiState.update { it.copy(error = "Please enter recipient address") }
            return false
        }
        if (state.amountCkb.isBlank()) {
            _uiState.update { it.copy(error = "Please enter amount") }
            return false
        }
        val amountShannons = try {
            BigDecimal(state.amountCkb).setScale(8, RoundingMode.DOWN)
                .multiply(BigDecimal(100_000_000)).toLong()
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Invalid amount") }
            return false
        }
        val minCapacity = 61_00000000L
        if (amountShannons < minCapacity) {
            _uiState.update { it.copy(error = "Minimum transfer is 61 CKB") }
            return false
        }
        if (amountShannons > state.availableBalance) {
            _uiState.update { it.copy(error = "Insufficient balance") }
            return false
        }
        return true
    }

    private fun sendTransactionV1OrFallback() {
        if (!validateInputs()) return

        // Check if authentication is required before sending (V1 path).
        if (authManager.isAuthBeforeSendEnabled() && pinManager.hasPin()) {
            val method = if (authManager.isBiometricEnabled() && authManager.isBiometricEnrolled()) {
                AuthMethod.BIOMETRIC
            } else {
                AuthMethod.PIN
            }
            _uiState.update { it.copy(requiresAuth = true, authMethod = method) }
            return
        }

        executeSend()
    }

    private suspend fun executeSendV2(activity: FragmentActivity) {
        val state = _uiState.value
        val amountShannons = BigDecimal(state.amountCkb).setScale(8, RoundingMode.DOWN)
            .multiply(BigDecimal(100_000_000)).toLong()

        val capturedAddress = repository.getCurrentAddress()
        if (capturedAddress == null) {
            _uiState.update { it.copy(error = "Wallet not initialized") }
            return
        }

        val walletId = walletPreferencesActiveId() ?: run {
            _uiState.update { it.copy(error = "No active wallet") }
            return
        }

        when (val readResult = walletKeyReader.readPrivateKey(
            activity = activity,
            walletId = walletId,
            promptTitle = "Authenticate to Send",
            promptSubtitle = "Verify your identity to send CKB",
        )) {
            is WalletKeyReader.Result.Cancelled -> {
                _uiState.update { it.copy(error = null, isLoading = false) }
                return
            }
            is WalletKeyReader.Result.AuthError -> {
                _uiState.update {
                    it.copy(error = "Authentication failed: ${readResult.message}", isLoading = false)
                }
                return
            }
            is WalletKeyReader.Result.NotAvailable -> {
                _uiState.update {
                    it.copy(error = "Cannot read wallet key: ${readResult.reason}", isLoading = false)
                }
                return
            }
            is WalletKeyReader.Result.Success -> {
                proceedWithSend(amountShannons, capturedAddress, readResult.privateKey)
            }
        }
    }

    private suspend fun proceedWithSend(
        amountShannons: Long,
        capturedAddress: String,
        privateKey: ByteArray,
    ) {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                transactionState = TransactionState.SENDING,
                statusMessage = "Building transaction..."
            )
        }
        try {
            _uiState.update { it.copy(statusMessage = "Broadcasting transaction...") }
            val txHash = repository.prepareAndSend(
                fromAddress = capturedAddress,
                toAddress = state.recipientAddress,
                amountShannons = amountShannons,
                privateKey = privateKey,
            ).getOrThrow()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    txHash = txHash,
                    recipientAddress = "",
                    amountCkb = "",
                    transactionState = TransactionState.PENDING,
                    statusMessage = "Transaction submitted. Waiting for confirmation..."
                )
            }
            startPollingTransactionStatus(txHash, capturedAddress)
        } catch (e: Exception) {
            Log.e(TAG, "V2 send failed", e)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = parseErrorMessage(e),
                    transactionState = TransactionState.FAILED,
                    statusMessage = "Transaction failed"
                )
            }
        }
    }

    fun executeSend() {
        val state = _uiState.value
        _uiState.update { it.copy(requiresAuth = false, authMethod = null) }

        val amountShannons = try {
            BigDecimal(state.amountCkb).setScale(8, RoundingMode.DOWN)
                .multiply(BigDecimal(100_000_000)).toLong()
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Invalid amount") }
            return
        }

        // Capture wallet identity upfront to prevent wallet-switch race conditions
        val capturedAddress = repository.getCurrentAddress()

        if (capturedAddress == null) {
            _uiState.update { it.copy(error = "Wallet not initialized") }
            return
        }

        sendJob = viewModelScope.launch {
            val capturedKey = try { repository.getPrivateKey() } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to access wallet keys: ${e.message}") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    transactionState = TransactionState.SENDING,
                    statusMessage = "Building transaction..."
                )
            }

            try {
                Log.d(TAG, "Starting send transaction flow")
                Log.d(TAG, "  Recipient: ${state.recipientAddress}")
                Log.d(TAG, "  Amount: ${state.amountCkb} CKB ($amountShannons shannons)")
                Log.d(TAG, "  From address: $capturedAddress")

                _uiState.update { it.copy(statusMessage = "Broadcasting transaction...") }
                Log.d(TAG, "📡 prepareAndSend: fetching cells, filtering reserved, building, broadcasting...")

                val txHash = repository.prepareAndSend(
                    fromAddress = capturedAddress,
                    toAddress = state.recipientAddress,
                    amountShannons = amountShannons,
                    privateKey = capturedKey
                ).getOrThrow()
                Log.d(TAG, "✅ Transaction sent! Hash: $txHash")

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        txHash = txHash,
                        recipientAddress = "",
                        amountCkb = "",
                        transactionState = TransactionState.PENDING,
                        statusMessage = "Transaction submitted. Waiting for confirmation..."
                    )
                }

                // Start polling for transaction status using the captured address
                startPollingTransactionStatus(txHash, capturedAddress)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Transaction failed", e)
                Log.e(TAG, "  Error type: ${e.javaClass.simpleName}")
                Log.e(TAG, "  Error message: ${e.message}")
                e.printStackTrace()

                val userFriendlyError = parseErrorMessage(e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = userFriendlyError,
                        transactionState = TransactionState.FAILED,
                        statusMessage = "Transaction failed"
                    )
                }
            }
        }
    }

    /**
     * Parse technical error messages into user-friendly descriptions
     */
    private fun parseErrorMessage(e: Exception): String {
        val message = e.message ?: "Unknown error"

        return when {
            // Cell/UTXO errors
            message.contains("Failed to get cells", ignoreCase = true) ->
                "Could not fetch your available funds. Please ensure your wallet is synced and try again."
            message.contains("No cells available", ignoreCase = true) ||
            message.contains("Insufficient cells", ignoreCase = true) ->
                "Not enough funds available. Please wait for your wallet to fully sync."

            // Transaction building errors
            message.contains("Insufficient balance", ignoreCase = true) ->
                "Insufficient balance for this transaction."
            message.contains("minimum", ignoreCase = true) && message.contains("61", ignoreCase = true) ->
                "Minimum transfer amount is 61 CKB due to CKB's cell model."

            // Network/broadcast errors
            message.contains("Send failed", ignoreCase = true) ||
            message.contains("broadcast", ignoreCase = true) ->
                "Could not broadcast transaction. Please check your network connection and try again."
            message.contains("verification failed", ignoreCase = true) ->
                "Transaction verification failed. The transaction may be invalid."

            // Sync errors
            message.contains("not synced", ignoreCase = true) ||
            message.contains("sync", ignoreCase = true) ->
                "Wallet is still syncing. Please wait for sync to complete before sending."

            // JSON/parsing errors (likely a bug)
            message.contains("json", ignoreCase = true) ||
            message.contains("parse", ignoreCase = true) ||
            message.contains("serial", ignoreCase = true) ||
            message.contains("missing", ignoreCase = true) ->
                "Internal error processing transaction data. Please try again or restart the app."

            // Generic fallback
            else -> "Transaction failed: $message"
        }
    }

    private fun startPollingTransactionStatus(txHash: String, address: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var attempts = 0
            var consecutiveUnknowns = 0
            val previousBalance = _uiState.value.availableBalance

            Log.d(TAG, "🔄 Starting to poll for tx status: $txHash (previous balance: $previousBalance)")

            while (attempts < MAX_POLLING_ATTEMPTS) {
                delay(POLLING_INTERVAL_MS)
                attempts++

                Log.d(TAG, "🔄 Polling attempt #$attempts for $txHash")

                try {
                    val statusResult = repository.getTransactionStatus(txHash)
                    statusResult.onSuccess { status ->
                        Log.d(TAG, "📊 Poll result: status=${status.status}, confirmations=${status.confirmations}")

                        // Handle "unknown" status - tx might be in network mempool
                        if (status.isUnknown()) {
                            consecutiveUnknowns++
                            Log.d(TAG, "⏳ Unknown status (attempt $consecutiveUnknowns) - tx likely in network mempool")

                            // Update UI to show we're waiting for network confirmation
                            _uiState.update {
                                it.copy(
                                    transactionState = TransactionState.PENDING,
                                    statusMessage = "Transaction broadcast. Waiting for network confirmation..."
                                )
                            }

                            // After many unknown responses, the tx was likely already confirmed
                            // but the light client hasn't synced that block yet
                            if (consecutiveUnknowns > 20) {
                                Log.d(TAG, "✅ Many unknowns - assuming tx confirmed on network")
                                // Poll for balance until light client syncs the change output
                                pollForBalanceUpdate(address, previousBalance)
                                _uiState.update {
                                    it.copy(
                                        transactionState = TransactionState.CONFIRMED,
                                        confirmations = 1,
                                        statusMessage = "Transaction confirmed ✓"
                                    )
                                }
                                return@launch
                            }
                            return@onSuccess
                        }

                        // Reset counter when we get a non-unknown status
                        consecutiveUnknowns = 0
                        updateTransactionStatus(status)

                        // Stop polling only after reaching required confirmations
                        if (status.isConfirmed() && (status.confirmations ?: 0) >= REQUIRED_CONFIRMATIONS) {
                            Log.d(TAG, "✅ Transaction fully confirmed with ${status.confirmations} confirmations")
                            // Poll for balance until light client syncs the change output
                            pollForBalanceUpdate(address, previousBalance)
                            _uiState.update {
                                it.copy(
                                    statusMessage = "Fully confirmed with ${status.confirmations} confirmations ✓"
                                )
                            }
                            return@launch
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "⚠️ Poll failed: ${e.message}")
                        // Continue polling on failure
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Poll exception: ${e.message}")
                    // Continue polling on exception
                }
            }

            // Max attempts reached
            Log.d(TAG, "⏰ Polling timed out after $attempts attempts")

            // If we got here and got lots of unknowns, tx probably went through
            if (consecutiveUnknowns > 10) {
                pollForBalanceUpdate(address, previousBalance)
                _uiState.update {
                    it.copy(
                        transactionState = TransactionState.CONFIRMED,
                        statusMessage = "Transaction sent successfully ✓"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        statusMessage = "Status check timed out. Transaction may still confirm."
                    )
                }
            }
        }
    }

    /**
     * Poll for balance update after a transaction.
     * The light client needs to sync the block containing the change output before
     * the new balance is visible. This function polls until balance is non-zero
     * or a reasonable timeout is reached.
     */
    private suspend fun pollForBalanceUpdate(address: String, previousBalance: Long) {
        Log.d(TAG, "💰 Starting balance polling (previous: $previousBalance shannons)")

        var balanceAttempts = 0
        val maxBalanceAttempts = 30 // Try for ~90 seconds

        while (balanceAttempts < maxBalanceAttempts) {
            delay(POLLING_INTERVAL_MS)
            balanceAttempts++

            repository.refreshBalance(address).onSuccess { balance ->
                val newBalance = balance.capacityAsLong()
                Log.d(TAG, "💰 Balance poll #$balanceAttempts: $newBalance shannons (${balance.capacityCkb} CKB)")

                // Balance updated when it differs from pre-send value
                // (for self-transfers, change is just the fee)
                if (newBalance != previousBalance) {
                    Log.d(TAG, "✅ Balance updated: $newBalance shannons")
                    return
                }
            }.onFailure { e ->
                Log.w(TAG, "⚠️ Balance poll failed: ${e.message}")
            }
        }

        Log.w(TAG, "⏰ Balance polling timed out - balance may update later when light client syncs")
        // Do one final refresh attempt
        repository.refreshBalance(address)
    }

    private fun updateTransactionStatus(status: TransactionStatusResponse) {
        val newState = when {
            status.isConfirmed() -> TransactionState.CONFIRMED
            status.status == "proposed" -> TransactionState.PROPOSED
            status.isPending() -> TransactionState.PENDING
            status.isUnknown() -> TransactionState.PENDING // Treat unknown as pending initially
            else -> TransactionState.PENDING
        }

        val confirmations = status.confirmations ?: 0

        val message = when (newState) {
            TransactionState.PENDING -> "Transaction pending..."
            TransactionState.PROPOSED -> "Transaction in proposal stage..."
            TransactionState.CONFIRMED -> when {
                confirmations >= REQUIRED_CONFIRMATIONS ->
                    "Fully confirmed with $confirmations confirmations ✓"
                confirmations == 1 ->
                    "1 confirmation (waiting for ${REQUIRED_CONFIRMATIONS - 1} more)..."
                else ->
                    "$confirmations confirmations (waiting for ${REQUIRED_CONFIRMATIONS - confirmations} more)..."
            }
            else -> "Processing..."
        }

        _uiState.update {
            it.copy(
                transactionState = newState,
                confirmations = confirmations,
                statusMessage = message
            )
        }
    }

    fun clearTxHash() {
        pollingJob?.cancel()
        _uiState.update {
            it.copy(
                txHash = null,
                transactionState = TransactionState.IDLE,
                confirmations = 0,
                statusMessage = ""
            )
        }
    }

    fun cancelAuth() {
        _uiState.update { it.copy(requiresAuth = false, authMethod = null) }
    }

    fun clearError() {
        _uiState.update {
            it.copy(
                error = null,
                transactionState = if (it.txHash != null) it.transactionState else TransactionState.IDLE
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        pollingJob?.cancel()
    }
}
