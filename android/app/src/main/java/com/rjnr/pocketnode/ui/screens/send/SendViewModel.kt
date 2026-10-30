package com.rjnr.pocketnode.ui.screens.send

import com.rjnr.pocketnode.data.send.DestinationPolicy
import com.rjnr.pocketnode.data.send.SendStatusPoller
import com.rjnr.pocketnode.data.send.hasSendInclusionFor
import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.log.Logger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.contacts.ContactRepository
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.ContactEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.TransactionStatusResponse
import com.rjnr.pocketnode.data.transaction.BulkSendQuarantineStore
import com.rjnr.pocketnode.data.transaction.InterruptedBulkSend
import com.rjnr.pocketnode.data.send.ReviewedTransfer
import com.rjnr.pocketnode.data.transaction.RecipientOutput
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletRepository
import com.rjnr.pocketnode.core.format.ckbToShannons
import com.rjnr.pocketnode.util.sanitizeAmount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.math.BigDecimal
import com.rjnr.pocketnode.data.auth.AuthMethod
import javax.inject.Inject

enum class TransactionState {
    IDLE,           // No transaction in progress
    SENDING,        // Transaction being built and sent
    PENDING,        // Transaction submitted, waiting for confirmation
    PROPOSED,       // Transaction in proposal stage
    CONFIRMED,      // Transaction confirmed on chain
    FAILED,         // Explicit rejection or pre-broadcast failure
    TIMED_OUT       // Poll ended; transaction outcome remains unknown
}

enum class SendMode {
    SINGLE,
    BULK
}

private data class BulkParseResult(
    val validAddresses: List<String>,
    val invalidEntries: List<String>,
    val duplicateCount: Int,
)

/**
 * What the pre-broadcast review sheet renders (#490).
 *
 * Built from validated inputs, so every field is final: confirming it is the
 * user's last decision point. It is a snapshot rather than a set of flags on
 * [SendUiState] so the sheet can never disagree with the amount that is about
 * to be signed — editing the form is only reachable after Cancel clears it.
 */
data class SendReview(
    val isBulk: Boolean,
    val transfers: List<ReviewedTransfer> = emptyList(),
    /** Empty in bulk mode; the full ordered recipients are in [transfers]. */
    val recipientAddress: String,
    /** Address-book name for [recipientAddress], when the address is saved. */
    val recipientName: String?,
    val recipientCount: Int,
    /** Total capacity leaving the wallet, excluding the fee. */
    val amountShannons: Long,
    val feeShannons: Long,
    val totalShannons: Long,
    /**
     * Spendable capacity left in the wallet once [totalShannons] has gone,
     * clamped at zero. Integer shannons throughout — the displayed balance
     * is rounded and must never drive this (#447).
     */
    val remainingShannons: Long = 0L,
    /** True when [remainingShannons] is under [sweepThresholdShannons]. */
    val isNearlyFullBalance: Boolean = false,
    /** The threshold [remainingShannons] was measured against, for the copy. */
    val sweepThresholdShannons: Long = MIN_CELL_SHANNONS,
) {
    /**
     * Below one minimal cell the wallet genuinely cannot fund another
     * transaction; between that and the 1%-of-balance line it still can,
     * so the sheet softens the wording (see `send_review_sweep_warning_*`).
     */
    val remainingBelowMinCell: Boolean get() = remainingShannons < MIN_CELL_SHANNONS

    companion object {
        /** A secp256k1-blake160 cell cannot hold less than 61 CKB. */
        const val MIN_CELL_SHANNONS = TransactionBuilder.MIN_CELL_CAPACITY

        /**
         * How much the wallet should keep back: one minimal cell, or 1% of the
         * balance when that is larger. The percentage catches the "swept a big
         * wallet" case, where 61 CKB left out of 50,000 is still effectively
         * everything gone.
         */
        fun sweepThreshold(balanceShannons: Long): Long =
            maxOf(MIN_CELL_SHANNONS, balanceShannons / 100)

        /** Spendable capacity after the send, clamped at zero. */
        fun remainingAfter(balanceShannons: Long, totalShannons: Long): Long =
            (balanceShannons - totalShannons).coerceAtLeast(0L)

        /**
         * A zero balance means "not loaded yet" rather than "swept": warning on
         * it would fire on every send made before the first balance tick.
         */
        fun shouldWarn(balanceShannons: Long, totalShannons: Long): Boolean =
            balanceShannons > 0L &&
                remainingAfter(balanceShannons, totalShannons) < sweepThreshold(balanceShannons)
    }
}

data class SendUiState(
    /** Founder-only easter egg: the Single/Bulk toggle is hidden until unlocked. */
    val bulkUnlocked: Boolean = false,
    val sendMode: SendMode = SendMode.SINGLE,
    val recipientAddress: String = "",
    val bulkRecipientsText: String = "",
    val bulkValidRecipients: List<String> = emptyList(),
    val bulkInvalidEntries: List<String> = emptyList(),
    val bulkDuplicateCount: Int = 0,
    val bulkBatchCount: Int = 0,
    val bulkTotalAmountShannons: Long = 0L,
    val bulkCurrentBatch: Int = 0,
    val bulkCompletedRecipients: Int = 0,
    val bulkSubmittedTxHashes: List<String> = emptyList(),
    val bulkFailureMessage: String? = null,
    val interruptedBulkSends: List<InterruptedBulkSend> = emptyList(),
    val amountCkb: String = "",
    val isLoading: Boolean = false,
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
    /**
     * Raw failure reason (e.message) behind the friendly [error] copy.
     * Shown in the failure dialog with a Copy action so a user can paste
     * the EXACT reason into a bug report — release builds strip logcat,
     * so this is the only diagnostic that reaches us from the field
     * (Alex, Telegram 2026-07).
     */
    val errorDetail: String? = null,
    val txHash: String? = null,
    val availableBalance: Long = 0L,
    /** Spendable cell count, fetched on entering bulk mode and when its source changes, for a realistic bulk fee estimate (review item 3). */
    val spendableCellCount: Int = 0,
    val estimatedFee: Long = 0L,
    val networkType: NetworkType = NetworkType.MAINNET,
    val transactionState: TransactionState = TransactionState.IDLE,
    val confirmations: Int = 0,
    val statusMessage: String = "",
    val burnWarning: String? = null,
    val requiresAuth: Boolean = false,
    val authMethod: AuthMethod? = null,
    val otherWallets: List<WalletEntity> = emptyList(),
    /**
     * Autocomplete suggestions matching the current recipient field.
     * Populated 200ms after the user stops typing. Empty when the
     * field is blank or the user has tapped a suggestion. (#196)
     */
    val recipientSuggestions: List<ContactEntity> = emptyList(),
    /** Contact whose address exactly matches the typed recipient. */
    val matchedContact: ContactEntity? = null,
    /**
     * Non-null when broadcast succeeded for an address that is NOT in
     * the user's address book. UI shows a SaveContactDialog with this
     * address prefilled; user can save or dismiss. Dismiss tracking is
     * per-address (see [dismissedSavePrompts]) so the dialog never
     * re-appears for the same recipient. (#197)
     */
    val saveContactPromptAddress: String? = null,
    /**
     * Non-null while the review sheet is up. Set by [SendViewModel.sendTransaction]
     * after validation and cleared by confirm or cancel; nothing broadcasts
     * while it is non-null, and nothing broadcasts without it having been set
     * first. (#490)
     */
    val reviewRequest: SendReview? = null,
    /**
     * Whether the user has ticked "I understand" on the review sheet's
     * leave-something-behind warning (#447). Reset with every new review, so
     * an acknowledgement never carries over to a different draft.
     */
    val sweepWarningAcknowledged: Boolean = false,
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
    private val contactRepository: ContactRepository,
    private val errorJournal: com.rjnr.pocketnode.data.diagnostics.ErrorJournal,
    private val uiPreferences: com.rjnr.pocketnode.core.prefs.UiPreferences,
    private val logger: Logger,
    private val bulkQuarantineStore: BulkSendQuarantineStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SendUiState())
    val uiState: StateFlow<SendUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var pollingGeneration = 0
    private data class StatusWatch(val hash: String, val address: String, val walletId: String?, val network: NetworkType)
    private var statusWatch: StatusWatch? = null
    private var sendJob: Job? = null
    private var bulkHistoryUnavailable = false

    private var generation = 0L
    private var maxJob: Job? = null
    private var reviewJob: Job? = null
    private var bulkCellCountJob: Job? = null
    private var bulkCellCountGeneration = 0L
    private data class BulkCellCountSource(val walletId: String, val network: NetworkType, val address: String)
    private var bulkCellCountSource: BulkCellCountSource? = null
    private var bulkCellCountWallets: List<WalletEntity> = emptyList()

    // Count requests belong to the source, not to amount/recipient edits.
    private fun invalidateBulkCellCount() {
        bulkCellCountGeneration++
        bulkCellCountJob?.cancel()
        bulkCellCountJob = null
        bulkCellCountSource = null
        _uiState.update { it.copy(spendableCellCount = 0).refreshBulkPreview() }
    }

    private fun refreshBulkCellCount() {
        val walletId = walletPreferencesActiveId()
        val network = repository.currentNetwork
        val address = repository.getCurrentAddress()
        val wallet = bulkCellCountWallets.find { it.isActive && it.walletId == walletId }
        val expectedAddress = when (network) {
            NetworkType.MAINNET -> wallet?.mainnetAddress
            NetworkType.TESTNET -> wallet?.testnetAddress
        }
        // Room/preferences can select a wallet before the repository initializes
        // its address. Wait for both observations to agree, in either order.
        val source = if (wallet != null && !address.isNullOrBlank() && address == expectedAddress) {
            BulkCellCountSource(wallet.walletId, network, address)
        } else null
        if (source == bulkCellCountSource) return
        invalidateBulkCellCount()
        bulkCellCountSource = source
        if (source == null || _uiState.value.sendMode != SendMode.BULK) return

        val request = bulkCellCountGeneration
        fun isCurrentSource() = bulkCellCountGeneration == request &&
            walletPreferencesActiveId() == source.walletId && repository.currentNetwork == source.network &&
            repository.getCurrentAddress() == source.address && _uiState.value.sendMode == SendMode.BULK
        bulkCellCountJob = viewModelScope.launch {
            if (!isCurrentSource()) return@launch
            val result = repository.getCells(address = source.address)
            // Result can contain cancellation; neither a late success nor its
            // fallback zero may overwrite a replacement request's preview.
            coroutineContext.ensureActive()
            if (!isCurrentSource()) return@launch
            val count = result.map { it.items.size }.getOrNull() ?: 0
            _uiState.update { it.copy(spendableCellCount = count).refreshBulkPreview() }
        }
    }

    private fun invalidateDraft() {
        generation++
        maxJob?.cancel()
        reviewJob?.cancel()
        _uiState.update { it.copy(reviewRequest = null, sweepWarningAcknowledged = false, isLoading = false) }
    }

    private fun isCurrent(review: ReviewedTransfer): Boolean =
        generation == review.generation && walletPreferencesActiveId() == review.walletId &&
            repository.currentNetwork == review.network && repository.getCurrentAddress() == review.fromAddress

    /**
     * Addresses the user has explicitly dismissed the "Save to contacts?"
     * prompt for. Lives in the ViewModel (not in UI state) because it's
     * an interaction history, not a render input. Cleared on process
     * death, which is acceptable — the user can always re-trigger by
     * sending again. (#197)
     */
    private val dismissedSavePrompts: MutableSet<String> = mutableSetOf()

    /**
     * Called from broadcast-success paths with the recipient address.
     * Either bumps the contact's usage counter (if saved) or queues
     * the save-prompt dialog (if new and not previously dismissed).
     */
    private suspend fun afterSendBookkeeping(recipient: String) {
        val existing = contactRepository.getByAddress(recipient)
        if (existing != null) {
            contactRepository.markUsed(recipient)
            return
        }
        if (recipient in dismissedSavePrompts) return
        _uiState.update { it.copy(saveContactPromptAddress = recipient) }
    }

    fun saveContactPromptDismissed() {
        val addr = _uiState.value.saveContactPromptAddress ?: return
        dismissedSavePrompts.add(addr)
        _uiState.update { it.copy(saveContactPromptAddress = null) }
    }

    fun saveContactPromptSubmit(name: String, notes: String?) {
        val addr = _uiState.value.saveContactPromptAddress ?: return
        viewModelScope.launch {
            contactRepository.add(
                name = name,
                address = addr,
                notes = notes?.ifBlank { null },
                tags = null,
                activeNetwork = repository.currentNetwork,
            ).onSuccess {
                contactRepository.markUsed(addr)
            }
            _uiState.update { it.copy(saveContactPromptAddress = null) }
        }
    }

    companion object {
        private const val TAG = "SendViewModel"
        private const val BULK_BATCH_SIZE = 25
        private const val POLLING_INTERVAL_MS = 3000L // Poll every 3 seconds
        private const val MAX_POLLING_ATTEMPTS = 120  // Stop after ~6 minutes
        private const val REQUIRED_CONFIRMATIONS = 3  // Consider fully confirmed after 3 confirmations
    }

    init {
        try { _uiState.update { it.copy(interruptedBulkSends = bulkQuarantineStore.load()) } }
        catch (_: Exception) {
            bulkHistoryUnavailable = true
            _uiState.update { it.copy(bulkFailureMessage = "Bulk send history is unreadable. Bulk sending is blocked until the history can be recovered and reconciled.") }
        }
        // Bulk-airdrop easter egg is unlocked from Settings > Version (7 taps);
        // the Send screen just reads the persisted flag to show/hide the toggle.
        _uiState.update { it.copy(bulkUnlocked = uiPreferences.isBulkSendUnlocked()) }

        viewModelScope.launch {
            repository.balance.collect { balance ->
                _uiState.update { it.copy(availableBalance = balance?.capacityAsLong() ?: 0L) }
            }
        }

        // Load other wallets for "My Wallets" shortcut (excludes active wallet)
        viewModelScope.launch {
            var previousWalletId = walletPreferencesActiveId()
            walletRepository.walletsFlow.collect { wallets ->
                val currentWalletId = walletPreferencesActiveId()
                if (previousWalletId != currentWalletId) {
                    invalidateDraft()
                    sendJob?.cancel(kotlinx.coroutines.CancellationException("Wallet changed"))
                    previousWalletId = currentWalletId
                }
                val active = wallets.find { it.isActive }
                val others = wallets.filter { it.walletId != active?.walletId }
                _uiState.update { it.copy(otherWallets = others).refreshBulkPreview() }
                bulkCellCountWallets = wallets
                refreshBulkCellCount()
            }
        }

        // Track network type and cancel in-flight transaction if network changes mid-send
        viewModelScope.launch {
            repository.network.collect { network ->
                if (network != _uiState.value.networkType) {
                    invalidateDraft()
                    sendJob?.cancel(kotlinx.coroutines.CancellationException("Network changed"))
                    pollingJob?.cancel()
                    _uiState.update { it.copy(networkType = network, reviewRequest = null,
                        isLoading = false, transactionState = TransactionState.IDLE,
                        statusMessage = "Network changed. Check the original network for pending transactions before retrying.").refreshBulkPreview() }
                }
                refreshBulkCellCount()
            }
        }
        viewModelScope.launch {
            repository.walletInfo.collect { refreshBulkCellCount() }
        }
    }

    private var suggestionsJob: kotlinx.coroutines.Job? = null

    fun updateRecipient(address: String) {
        invalidateDraft()
        _uiState.update { it.copy(recipientAddress = address, error = null) }
        updateAmount(_uiState.value.amountCkb)
        // Debounced autocomplete + saved-contact lookup. Cancel any in-flight
        // search so each keystroke supersedes the previous one. (#196)
        suggestionsJob?.cancel()
        if (address.isBlank()) {
            _uiState.update { it.copy(recipientSuggestions = emptyList(), matchedContact = null) }
            return
        }
        suggestionsJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            val matches = contactRepository.search(address).take(5)
            val exactMatch = matches.firstOrNull { it.address == address }
                ?: contactRepository.getByAddress(address)
            _uiState.update {
                it.copy(
                    recipientSuggestions = if (exactMatch != null) emptyList() else matches,
                    matchedContact = exactMatch,
                )
            }
        }
    }

    fun updateSendMode(mode: SendMode) {
        invalidateBulkCellCount()
        invalidateDraft()
        _uiState.update {
            it.copy(
                sendMode = mode,
                error = null,
                burnWarning = if (mode == SendMode.BULK) null else it.burnWarning,
                estimatedFee = if (mode == SendMode.BULK) it.estimatedFee else it.estimatedFee,
            ).refreshBulkPreview()
        }
        if (mode == SendMode.BULK) {
            suggestionsJob?.cancel()
            _uiState.update { it.copy(recipientSuggestions = emptyList(), matchedContact = null) }
            // Snapshot count improves fragmentation estimates; the actual fee
            // is still computed from the selected inputs during review/build.
            refreshBulkCellCount()
        }
    }

    fun updateBulkRecipients(text: String) {
        invalidateDraft()
        _uiState.update {
            it.copy(
                bulkRecipientsText = text,
                error = null,
                bulkFailureMessage = null,
            ).refreshBulkPreview()
        }
    }

    /**
     * Pick a contact from the autocomplete dropdown or the picker sheet.
     * Fills the recipient field and clears suggestions so the dropdown
     * dismisses.
     */
    fun selectContact(contact: ContactEntity) {
        invalidateDraft()
        suggestionsJob?.cancel()
        _uiState.update {
            it.copy(
                recipientAddress = contact.address,
                recipientSuggestions = emptyList(),
                matchedContact = contact,
                error = null,
            )
        }
        updateAmount(_uiState.value.amountCkb)
    }

    /**
     * Snapshot for the picker sheet's empty-query state. Returns the
     * full address book (alphabetical) with `recentlyUsed` floated to
     * the top — so a freshly-added contact (useCount=0) still appears,
     * while frequently-used recipients stay one tap away.
     *
     * Earlier this returned just `recentlyUsed()`, which surfaced
     * nothing for new wallets / new contacts (bug: empty picker even
     * when contacts exist).
     */
    suspend fun recentContacts(): List<ContactEntity> {
        val recent = contactRepository.recentlyUsed()
        val all = contactRepository.listAll()
        if (recent.isEmpty()) return all
        val recentIds = recent.map { it.id }.toSet()
        return recent + all.filter { it.id !in recentIds }
    }

    /** Snapshot of contacts matching [query] for the picker sheet search. */
    suspend fun searchContacts(query: String): List<ContactEntity> =
        if (query.isBlank()) recentContacts() else contactRepository.search(query)

    fun updateAmount(amount: String) {
        val sanitized = sanitizeAmount(amount) ?: return // Reject the whole edit.
        invalidateDraft()
        val amountShannons = ckbToShannons(sanitized) ?: 0L

        if (_uiState.value.sendMode == SendMode.BULK) {
            _uiState.update {
                it.copy(
                    amountCkb = sanitized,
                    error = null,
                    burnWarning = null,
                ).refreshBulkPreview(amountShannonsOverride = amountShannons)
            }
            return
        }

        val balance = _uiState.value.availableBalance
        // Estimate: 1 input, 2 outputs (recipient + change) for typical transfer
        // If sending ~all balance, assume 1 output (no change)
        val outputCount = if (amountShannons > 0 && balance - amountShannons < 61_00000000L) 1 else 2
        val estimatedFee = transactionBuilder.estimateTransferFeeForDraft(1, listOf(_uiState.value.recipientAddress),
            repository.getCurrentAddress(), outputCount == 2)
        val potentialChange = balance - amountShannons - estimatedFee
        val minCapacity = 61_00000000L

        val warning = if (potentialChange in 1 until minCapacity) {
            // Strip trailing zeros so "50.0000" reads as "50", "50.5" stays
            // "50.5", and a precise dust amount like "60.99999500" reads
            // as "60.999995". Matches typical wallet UX for amounts.
            val lostCkb = java.math.BigDecimal(potentialChange)
                .divide(java.math.BigDecimal(100_000_000))
                .stripTrailingZeros()
                .toPlainString()
            "Heads up: this would leave $lostCkb CKB change, below the 61 CKB minimum cell size. " +
                "The send will be refused — adjust the amount so the change is 0 or at least 61 CKB."
        } else {
            null
        }

        _uiState.update { it.copy(amountCkb = sanitized, error = null, burnWarning = warning, estimatedFee = estimatedFee) }
    }

    fun setMaxAmount() {
        invalidateDraft()
        val request = generation
        val walletId = walletPreferencesActiveId()
        val network = repository.currentNetwork
        val address = repository.getCurrentAddress()
        val recipient = _uiState.value.recipientAddress
        maxJob = viewModelScope.launch {
            // MAX uses the same proven-empty cells and source lock as the send.
            // A displayed/cached balance cannot replace a failed cell-data read.
            val result = repository.getCells().mapCatching { response ->
                val source = requireNotNull(address?.let { AddressUtils.parseAddress(it) }) { "Wallet not initialized" }
                transactionBuilder.calculateMaxSendable(response.items.filter { it.isPlainCapacity(source) }, recipient)
            }
            coroutineContext.ensureActive()
            if (generation != request || walletPreferencesActiveId() != walletId ||
                repository.currentNetwork != network || repository.getCurrentAddress() != address) return@launch
            val maxShannons = result.getOrElse { error ->
                _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Raw(error.message ?: "Could not resolve spendable cells")) }
                return@launch
            }
            // BigDecimal, not Double: shannon precision is lost above ~90M CKB.
            // toPlainString always uses '.' regardless of device locale, which
            // sanitizeAmount requires.
            val formatted = BigDecimal(maxShannons)
                .movePointLeft(8)
                .stripTrailingZeros()
                .toPlainString()
            if (generation == request && walletPreferencesActiveId() == walletId &&
                repository.currentNetwork == network && repository.getCurrentAddress() == address) updateAmount(formatted)
        }
    }

    /**
     * Legacy entry point (V1 wallets). Kept so non-Compose callers and
     * tests that don't have a `FragmentActivity` still work. On a V2
     * wallet [confirmSend] returns an error — Compose callers should use the
     * [sendTransaction] overload that accepts an activity.
     */
    fun sendTransaction() {
        if (sendJob?.isActive == true) return
        invalidateDraft()
        reviewJob = viewModelScope.launch {
            if (!validateInputs()) return@launch
            showReview()
        }
    }

    /**
     * V2-aware entry point. If the active wallet is on kdfVersion=2,
     * acquires the private key via [WalletKeyReader] which drives the
     * BiometricPrompt CryptoObject dance. Otherwise falls back to the
     * V1 flow (which itself may show a non-CryptoObject biometric gate
     * via the existing `requiresAuth` state, unchanged from v1.6.x).
     *
     * Both entry points now stop at the review sheet (#490). Nothing is
     * built, signed or broadcast until [confirmSend]; the auth gate — the
     * V1 `requiresAuth` prompt or the V2 CryptoObject prompt — follows the
     * confirmation rather than replacing it, so "Authenticate before
     * sending = off" no longer means "no confirmation at all".
     */
    fun sendTransaction(activity: FragmentActivity) = sendTransaction()

    /**
     * Builds the review snapshot from already-validated inputs.
     *
     * A single send is priced by [GatewayRepository.previewTransfer], which
     * runs the real cell selection and fee math against the cells the send
     * will spend. `estimatedFee` in the form is a 1-input guess, and cell
     * selection is smallest-first, so a fragmented wallet pays materially more
     * than that guess — the sheet must not quote it (#490).
     */
    private suspend fun showReview() {
        val request = generation
        val state = _uiState.value
        val amount = parseAmountShannons(state.amountCkb) ?: return
        val from = repository.getCurrentAddress() ?: run {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_wallet_not_initialized)) }
            return
        }
        val walletId = walletPreferencesActiveId() ?: run {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_no_active_wallet)) }
            return
        }
        val network = repository.currentNetwork
        val recipients = if (state.sendMode == SendMode.BULK)
            state.refreshBulkPreview(amount).bulkValidRecipients.map { RecipientOutput(it, amount) }
        else listOf(RecipientOutput(state.recipientAddress, amount))
        val batches = recipients.chunked(BULK_BATCH_SIZE)
        fun current() = generation == request && walletPreferencesActiveId() == walletId &&
            repository.currentNetwork == network && repository.getCurrentAddress() == from
        _uiState.update { it.copy(isLoading = true, statusMessage = "Preparing transaction...") }
        try {
            val fees = if (state.sendMode == SendMode.BULK) repository.previewBatches(from, batches)
                else listOf(repository.previewTransfer(from, recipients).feeShannons)
            if (!current()) return
            require(fees.size == batches.size)
            val transfers = batches.zip(fees).map { (batch, fee) ->
                ReviewedTransfer(walletId, from, network, batch, fee, request, state.sendMode == SendMode.BULK)
            }
            val totalAmount = transfers.fold(0L) { total, transfer -> Math.addExact(total, transfer.amountShannons) }
            val totalFee = fees.fold(0L, Math::addExact)
            _uiState.update { it.copy(reviewRequest = withSweepWarning(SendReview(
                isBulk = state.sendMode == SendMode.BULK, transfers = transfers,
                recipientAddress = if (state.sendMode == SendMode.SINGLE) recipients.single().address else "",
                recipientName = state.matchedContact?.takeIf { it.address == state.recipientAddress }?.name,
                recipientCount = recipients.size, amountShannons = totalAmount, feeShannons = totalFee,
                totalShannons = Math.addExact(totalAmount, totalFee)), it.availableBalance),
                estimatedFee = totalFee, error = null) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) {
            if (current()) _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Raw(mapSendErrorMessage(e.message)), errorDetail = e.message) }
        } finally {
            if (current()) _uiState.update { it.copy(isLoading = false, statusMessage = "") }
        }
    }

    /**
     * Fill in the leave-something-behind fields of a review snapshot (#447).
     *
     * Priced off [SendReview.totalShannons] — the amount plus the planned fee,
     * both integer shannons — against the spendable balance, so the sheet's
     * "you will have X left" is the same number the chain will end up with,
     * not a subtraction of rounded display values.
     */
    private fun withSweepWarning(review: SendReview, balanceShannons: Long): SendReview =
        review.copy(
            remainingShannons = SendReview.remainingAfter(balanceShannons, review.totalShannons),
            isNearlyFullBalance = SendReview.shouldWarn(balanceShannons, review.totalShannons),
            sweepThresholdShannons = SendReview.sweepThreshold(balanceShannons),
        )

    /** "I understand" on the review sheet's sweep warning (#447). */
    fun setSweepWarningAcknowledged(acknowledged: Boolean) {
        _uiState.update { it.copy(sweepWarningAcknowledged = acknowledged) }
    }

    /** Review dismissed without sending: back to the form, nothing broadcast. */
    fun cancelReview() {
        invalidateDraft()
    }

    /** Confirm from the review sheet — V2-capable (Compose) caller. */
    fun confirmSend(activity: FragmentActivity) = confirmSendInternal(activity)

    /** Confirm from the review sheet — caller without a FragmentActivity. */
    fun confirmSend() = confirmSendInternal(null)

    private fun confirmSendInternal(activity: FragmentActivity?) {
        // No review on screen means nothing was confirmed: refuse rather than
        // broadcast. Also makes a double-tap on Confirm a no-op.
        val review = _uiState.value.reviewRequest ?: return
        // The sheet disables Confirm until the box is ticked; enforce it here
        // too so the warning cannot be skipped by a caller that doesn't (#447).
        if (review.isNearlyFullBalance && !_uiState.value.sweepWarningAcknowledged) return
        if (sendJob?.isActive == true) return
        if (review.transfers.isEmpty() || review.transfers.any { !isCurrent(it) }) {
            invalidateDraft()
            return
        }
        _uiState.update { it.copy(reviewRequest = null, sweepWarningAcknowledged = false) }

        sendJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            if (activity == null) {
                _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_send_v2_reopen)) }
                return@launch
            }
            try { executeSendV2(activity, review.transfers) }
            catch (cancelled: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoading = false, error = null, errorDetail = null,
                    transactionState = TransactionState.IDLE,
                    statusMessage = it.bulkFailureMessage ?: "Operation stopped. Check wallet activity for pending transactions before retrying.") }
                throw cancelled
            }
        }
        sendJob?.start()
    }

    private suspend fun peekActiveKdfVersion(): Int? {
        val walletId = walletPreferencesActiveId() ?: return null
        return runCatching { keyMaterialDao.getKdfVersion(walletId) }.getOrNull()
    }

    private fun walletPreferencesActiveId(): String? {
        return walletRepository.activeWalletIdSnapshot()?.takeIf { it.isNotEmpty() }
    }

    private fun validateInputs(): Boolean {
        return if (_uiState.value.sendMode == SendMode.BULK) {
            validateBulkInputs()
        } else {
            validateSingleInputs()
        }
    }

    private fun validateSingleInputs(): Boolean {
        val state = _uiState.value
        if (state.recipientAddress.isBlank()) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_enter_recipient)) }
            return false
        }
        val destination = DestinationPolicy.assess(state.recipientAddress, state.networkType)
        if (!destination.canSend) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Raw(destination.message)) }
            return false
        }
        if (state.amountCkb.isBlank()) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_enter_amount)) }
            return false
        }
        val amountShannons = ckbToShannons(state.amountCkb) ?: run {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_invalid_amount)) }
            return false
        }
        val minCapacity = 61_00000000L
        if (amountShannons < minCapacity) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_min_transfer)) }
            return false
        }
        if (amountShannons > state.availableBalance) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_insufficient_balance)) }
            return false
        }
        return true
    }

    private fun validateBulkInputs(): Boolean {
        if (bulkHistoryUnavailable) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Raw("Bulk send history must be recovered before sending.")) }
            return false
        }
        val state = _uiState.value.refreshBulkPreview()
        if (state.bulkRecipientsText.isBlank()) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Raw("Paste at least one recipient address.")) }
            return false
        }
        if (state.amountCkb.isBlank()) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_enter_amount)) }
            return false
        }
        val amountShannons = parseAmountShannons(state.amountCkb)
        if (amountShannons == null) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_invalid_amount)) }
            return false
        }
        if (amountShannons < TransactionBuilder.MIN_CELL_CAPACITY) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_min_transfer)) }
            return false
        }
        if (state.bulkValidRecipients.isEmpty()) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Raw("No valid ${state.networkType.name.lowercase()} recipient addresses found.")) }
            return false
        }
        val estimatedTotal = state.bulkTotalAmountShannons + state.estimatedFee
        if (estimatedTotal > state.availableBalance) {
            _uiState.update { it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_insufficient_balance)) }
            return false
        }
        return true
    }

    private suspend fun executeSendV2(activity: FragmentActivity, transfers: List<ReviewedTransfer>) = com.rjnr.pocketnode.data.vault.VaultOperationSession.run(activity) owned@ {
        val intent = transfers.first()
        if (!isCurrent(intent)) return@owned
        val walletId = intent.walletId

        when (val readResult = walletKeyReader.readPrivateKey(
            activity = activity,
            walletId = walletId,
            promptTitle = "Authenticate to Send",
            promptSubtitle = "Verify your identity to send CKB",
        )) {
            is WalletKeyReader.Result.Cancelled -> {
                _uiState.update { it.copy(error = null, isLoading = false) }
                return@owned
            }
            is WalletKeyReader.Result.AuthError -> {
                _uiState.update {
                    it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_auth_failed_with_reason, listOf(readResult.message.toString())), isLoading = false)
                }
                return@owned
            }
            is WalletKeyReader.Result.NotAvailable -> {
                _uiState.update {
                    it.copy(error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_cannot_read_wallet_key, listOf(readResult.reason)), isLoading = false)
                }
                return@owned
            }
            is WalletKeyReader.Result.KeyInvalidated -> {
                _uiState.update {
                    it.copy(
                        error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_biometric_changed_send),
                        isLoading = false,
                    )
                }
                return@owned
            }
            is WalletKeyReader.Result.Success -> try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!isCurrent(intent)) {
                    throw kotlinx.coroutines.CancellationException("Send source changed")
                }
                if (intent.isBulk) proceedWithBulkSend(transfers, readResult.privateKey)
                else proceedWithSend(intent, readResult.privateKey)
            } finally { readResult.privateKey.fill(0) }
        }
    }

    private suspend fun proceedWithSend(
        intent: ReviewedTransfer,
        privateKey: ByteArray,
    ) {
        val capturedAddress = intent.fromAddress
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
            val recipient = intent.recipients.single().address
            val txHash = repository.sendReviewed(intent, privateKey,
                isCurrent = { isCurrent(intent) }, onSigned = { privateKey.fill(0) })
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
            // #197: bump useCount for saved contacts, queue save-prompt for new addresses.
            afterSendBookkeeping(recipient)
            startPollingTransactionStatus(txHash, capturedAddress, intent.walletId, intent.network)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) {
            logger.e(TAG, "V2 send failed", e)
            errorJournal.record("send", e.message ?: e.javaClass.simpleName)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = com.rjnr.pocketnode.ui.util.UiMessage.Raw(parseErrorMessage(e)),
                    errorDetail = e.message,
                    transactionState = TransactionState.FAILED,
                    statusMessage = "Transaction failed"
                )
            }
        } finally {
            privateKey.fill(0) // transient signing key — zero after use (#321)
        }
    }

    /** Legacy PIN/dashboard callbacks do not authorize vault access. Reopen the foreground review. */
    fun executeSend() {
        _uiState.update { it.copy(requiresAuth = false, authMethod = null,
            error = com.rjnr.pocketnode.ui.util.UiMessage.Resource(com.rjnr.pocketnode.R.string.vm_error_send_v2_reopen)) }
    }

    private suspend fun proceedWithBulkSend(transfers: List<ReviewedTransfer>, privateKey: ByteArray) {
        val intent = transfers.first()
        val capturedAddress = intent.fromAddress
        val capturedWalletId = intent.walletId
        val capturedNetwork = intent.network
        val recipients = transfers.flatMap { it.recipients }
        val batches = transfers.map { it.recipients }
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                txHash = null,
                transactionState = TransactionState.SENDING,
                statusMessage = "Broadcasting batch 1 of ${batches.size}...",
                bulkCurrentBatch = 0,
                bulkCompletedRecipients = 0,
                bulkSubmittedTxHashes = emptyList(),
                bulkFailureMessage = null,
            )
        }

        val submitted = mutableListOf<String>()
        var entered = emptyList<String>()
        val recordId = java.util.UUID.randomUUID().toString()
        var durableRecord: InterruptedBulkSend? = null
        try {
            batches.forEachIndexed { index, batch ->
                // Cancellation between successful batches must prevent another signing call.
                kotlinx.coroutines.yield()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!isCurrent(intent)) {
                    throw kotlinx.coroutines.CancellationException("Send source changed")
                }
                val next = batch.map { it.address }
                val before = InterruptedBulkSend(recordId, capturedWalletId, capturedAddress, capturedNetwork,
                    submitted.toList(), _uiState.value.bulkSubmittedTxHashes.toList(), next)
                bulkQuarantineStore.put(before)
                durableRecord = before
                entered = next
                _uiState.update {
                    it.copy(
                        statusMessage = "Broadcasting batch ${index + 1} of ${batches.size}...",
                        bulkCurrentBatch = index + 1,
                    )
                }
                val reviewedBatch = transfers[index]
                val txHash = repository.sendReviewed(reviewedBatch, privateKey,
                    isCurrent = { isCurrent(reviewedBatch) },
                    onSigned = { if (index == transfers.lastIndex) privateKey.fill(0) })
                submitted += entered
                val after = before.copy(submittedRecipients = submitted.toList(),
                    submittedTxHashes = before.submittedTxHashes + txHash, uncertainRecipients = emptyList())
                bulkQuarantineStore.put(after)
                durableRecord = after
                entered = emptyList()
                _uiState.update {
                    it.copy(
                        bulkCurrentBatch = index + 1,
                        bulkCompletedRecipients = it.bulkCompletedRecipients + batch.size,
                        bulkSubmittedTxHashes = it.bulkSubmittedTxHashes + txHash,
                        statusMessage = "Submitted batch ${index + 1} of ${batches.size}.",
                    )
                }
            }

            // All batches have returned public hashes. Retire this provisional record only now.
            bulkQuarantineStore.remove(recordId)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    bulkRecipientsText = "",
                    bulkValidRecipients = emptyList(),
                    bulkInvalidEntries = emptyList(),
                    bulkDuplicateCount = 0,
                    bulkBatchCount = 0,
                    bulkTotalAmountShannons = 0L,
                    amountCkb = "",
                    estimatedFee = 0L,
                    transactionState = TransactionState.PENDING,
                    statusMessage = "Bulk send submitted for ${recipients.size} recipients across ${batches.size} transactions.",
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The entered batch may already be persisted or accepted by the node. Never put it
            // back into retry text without reconciliation, even if cancellation preceded signing.
            val remaining = recipients.drop(submitted.size + entered.size).map { it.address }
            _uiState.update { state ->
                val record = durableRecord
                state.copy(
                    isLoading = false,
                    bulkRecipientsText = remaining.joinToString("\n"),
                    interruptedBulkSends = state.interruptedBulkSends + listOfNotNull(record),
                    bulkFailureMessage = "Bulk send interrupted. Only ${remaining.size} recipients from batches that never started remain. Check interrupted batch details before sending any excluded recipient again.",
                ).refreshBulkPreview()
            }
            throw cancelled
        }
        catch (_: Exception) {
            // A failed checked write or broadcast cannot make the entered batch safe to retry.
            // The last durable record was committed before signing; never overwrite its uncertainty.
            bulkHistoryUnavailable = true
            _uiState.update { state -> state.copy(isLoading = false,
                interruptedBulkSends = state.interruptedBulkSends + listOfNotNull(durableRecord),
                bulkRecipientsText = recipients.drop(durableRecord?.let { it.submittedRecipients.size + it.uncertainRecipients.size } ?: 0).joinToString("\n") { it.address },
                transactionState = TransactionState.IDLE,
                bulkFailureMessage = "Bulk send stopped. Reconcile recorded batches; bulk sending is blocked until history is reloaded.",
                statusMessage = "Check transaction status before retrying.").refreshBulkPreview() }
        }
    }

    /**
     * Parse technical error messages into user-friendly descriptions
     */
    private fun parseErrorMessage(e: Exception): String = mapSendErrorMessage(e.message)

    /** Resume only status reads for a retained hash; never sign or broadcast again. */
    fun retryStatusCheck() {
        val watch = statusWatch ?: return
        if (_uiState.value.transactionState != TransactionState.TIMED_OUT ||
            _uiState.value.txHash != watch.hash || walletPreferencesActiveId() != watch.walletId ||
            !isCurrentSource(watch)) return
        startPollingTransactionStatus(watch.hash, watch.address, watch.walletId, watch.network)
    }

    private fun isCurrentSource(watch: StatusWatch): Boolean =
        walletPreferencesActiveId() == watch.walletId && repository.currentNetwork == watch.network &&
            _uiState.value.networkType == watch.network && repository.getCurrentAddress() == watch.address

    private fun startPollingTransactionStatus(txHash: String, address: String, walletId: String?, network: NetworkType) {
        pollingJob?.cancel()
        val epoch = ++pollingGeneration
        val watch = StatusWatch(txHash, address, walletId, network)
        statusWatch = watch
        _uiState.update { it.copy(transactionState = TransactionState.PENDING, confirmations = 0,
            statusMessage = SendStatusPoller.SUBMITTED) }
        pollingJob = viewModelScope.launch {
            var attempts = 0
            val previousBalance = _uiState.value.availableBalance
            suspend fun current(): Boolean {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                return epoch == pollingGeneration && _uiState.value.txHash == txHash &&
                    isCurrentSource(watch)
            }
            while (attempts < MAX_POLLING_ATTEMPTS) {
                delay(POLLING_INTERVAL_MS)
                if (!current()) return@launch
                attempts++
                val status = try {
                    repository.getTransactionStatus(txHash).getOrThrow()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    logger.w(TAG, "Status unavailable: ${e.message}")
                    null
                }
                if (!current()) return@launch
                if (status == null || status.txHash != txHash) continue
                if (status.status == "rejected") {
                    _uiState.update { it.copy(transactionState = TransactionState.FAILED,
                        confirmations = 0, statusMessage = SendStatusPoller.REJECTED) }
                    return@launch
                }
                updateTransactionStatus(status, txHash)
                if (status.hasSendInclusionFor(txHash) && (status.confirmations ?: 0) >= REQUIRED_CONFIRMATIONS) {
                    pollForBalanceUpdate(address, previousBalance, ::current)
                    if (!current()) return@launch
                    _uiState.update { it.copy(statusMessage = "Fully confirmed with ${status.confirmations} confirmations ✓") }
                    return@launch
                }
            }
            if (!current()) return@launch
            if (_uiState.value.transactionState != TransactionState.CONFIRMED) {
                _uiState.update { it.copy(transactionState = TransactionState.TIMED_OUT,
                    confirmations = 0, statusMessage = SendStatusPoller.TIMED_OUT) }
            }
        }
    }

    /**
     * Poll for balance update after a transaction.
     * The light client needs to sync the block containing the change output before
     * the new balance is visible. After evidenced inclusion, refresh until the
     * balance changes or the bounded wait ends; neither outcome supplies evidence.
     */
    private suspend fun pollForBalanceUpdate(address: String, previousBalance: Long, current: suspend () -> Boolean) {
        logger.d(TAG, "💰 Starting balance polling (previous: $previousBalance shannons)")

        var balanceAttempts = 0
        val maxBalanceAttempts = 30 // Try for ~90 seconds

        while (balanceAttempts < maxBalanceAttempts) {
            delay(POLLING_INTERVAL_MS)
            if (!current()) return
            balanceAttempts++

            val balanceResult = repository.refreshBalance(address)
            if (!current()) return
            balanceResult.onSuccess { balance ->
                val newBalance = balance.capacityAsLong()
                logger.d(TAG, "💰 Balance poll #$balanceAttempts: $newBalance shannons (${balance.capacityCkb} CKB)")

                // Balance updated when it differs from pre-send value
                // (for self-transfers, change is just the fee)
                if (newBalance != previousBalance) {
                    logger.d(TAG, "✅ Balance updated: $newBalance shannons")
                    return
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.w(TAG, "⚠️ Balance poll failed: ${e.message}")
            }
        }

        logger.w(TAG, "⏰ Balance polling timed out - balance may update later when light client syncs")
        // Do one final refresh attempt
        repository.refreshBalance(address).exceptionOrNull()?.let {
            if (it is kotlinx.coroutines.CancellationException) throw it
        }
        if (!current()) return
    }

    private fun updateTransactionStatus(status: TransactionStatusResponse, txHash: String) {
        val newState = when {
            status.hasSendInclusionFor(txHash) -> TransactionState.CONFIRMED
            status.status == "proposed" -> TransactionState.PROPOSED
            status.isPending() -> TransactionState.PENDING
            status.isUnknown() -> TransactionState.PENDING // Treat unknown as pending initially
            else -> TransactionState.PENDING
        }

        val confirmations = if (newState == TransactionState.CONFIRMED) status.confirmations ?: 0 else 0

        val message = when (newState) {
            TransactionState.PENDING -> if (status.isUnknown()) SendStatusPoller.AWAITING_NETWORK else SendStatusPoller.PENDING
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
        pollingGeneration++
        statusWatch = null
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
        invalidateDraft()
        sendJob?.cancel()
        _uiState.update { it.copy(requiresAuth = false, authMethod = null) }
    }

    fun clearError() {
        _uiState.update {
            it.copy(
                error = null,
                errorDetail = null,
                transactionState = if (it.txHash != null) it.transactionState else TransactionState.IDLE
            )
        }
    }

    /** Explicit acknowledgement in the reconciliation dialog; navigation never clears evidence. */
    fun acknowledgeBulkReconciliation(id: String) {
        if (sendJob?.isActive == true) return
        try {
            bulkQuarantineStore.remove(id)
            val remaining = bulkQuarantineStore.load()
            bulkHistoryUnavailable = false
            _uiState.update { it.copy(interruptedBulkSends = remaining, bulkFailureMessage = null,
                error = null).refreshBulkPreview() }
        } catch (_: Exception) {
            bulkHistoryUnavailable = true
            _uiState.update { it.copy(bulkFailureMessage = "Reconciliation could not be saved. Recipients remain blocked.") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        pollingJob?.cancel()
    }

    private fun SendUiState.refreshBulkPreview(amountShannonsOverride: Long? = null): SendUiState {
        if (sendMode != SendMode.BULK) return this
        val amountShannons = amountShannonsOverride ?: parseAmountShannons(amountCkb) ?: 0L
        val raw = parseBulkRecipients(bulkRecipientsText, networkType)
        // Durable reconciliation evidence is scoped to the stable sender wallet and network. An
        // edited or re-pasted list cannot silently turn submitted/uncertain recipients into retries.
        val excluded = interruptedBulkSends.filter { it.walletId == walletPreferencesActiveId() && it.network == networkType }.flatMap { it.submittedRecipients + it.uncertainRecipients }.toSet()
        val blocked = raw.validAddresses.count { it in excluded }
        val parsed = raw.copy(validAddresses = raw.validAddresses.filterNot { it in excluded },
            invalidEntries = raw.invalidEntries + if (blocked == 0) emptyList() else
                listOf("$blocked recipients excluded from an interrupted send. Review its submitted and uncertain batches first."))
        val batchCount = if (parsed.validAddresses.isEmpty()) 0 else (parsed.validAddresses.size + BULK_BATCH_SIZE - 1) / BULK_BATCH_SIZE
        // Item 3: estimate each batch's input count from the wallet's average
        // cell size (balance / cell count) instead of assuming 1. A fragmented
        // wallet gathers many inputs per batch, so the flat 1-input estimate
        // under-counted the fee and could let the insufficient-balance gate
        // pass a send that a late batch can't afford. Falls back to ~1 input
        // per recipient until the cell count loads (never under-gates).
        val avgCell = if (spendableCellCount > 0 && availableBalance > 0) availableBalance / spendableCellCount else 0L
        val estimatedFee = if (parsed.validAddresses.isEmpty() || amountShannons <= 0L) {
            0L
        } else {
            parsed.validAddresses.chunked(BULK_BATCH_SIZE).sumOf { batch ->
                val batchTotal = batch.size.toLong() * amountShannons
                val inputs = if (avgCell > 0) {
                    ((batchTotal + avgCell - 1) / avgCell).toInt().coerceIn(1, spendableCellCount)
                } else {
                    batch.size
                }
                transactionBuilder.estimateTransferFeeForDraft(inputs, batch, repository.getCurrentAddress(), true)
            }
        }
        return copy(
            bulkValidRecipients = parsed.validAddresses,
            bulkInvalidEntries = parsed.invalidEntries,
            bulkDuplicateCount = parsed.duplicateCount,
            bulkBatchCount = batchCount,
            bulkTotalAmountShannons = parsed.validAddresses.size * amountShannons,
            estimatedFee = estimatedFee,
            burnWarning = null,
        )
    }

    private fun parseBulkRecipients(text: String, network: NetworkType): BulkParseResult {
        val unique = LinkedHashSet<String>()
        val invalid = mutableListOf<String>()
        var duplicates = 0
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEachIndexed
            when {
                !AddressUtils.isValid(line) -> invalid += "Line ${index + 1}: invalid address"
                AddressUtils.getNetwork(line) != network -> invalid += "Line ${index + 1}: wrong network"
                !DestinationPolicy.assess(line, network).canSend -> invalid += "Line ${index + 1}: ${DestinationPolicy.assess(line, network).message}"
                !unique.add(line) -> duplicates++
            }
        }
        return BulkParseResult(
            validAddresses = unique.toList(),
            invalidEntries = invalid,
            duplicateCount = duplicates,
        )
    }

    private fun parseAmountShannons(amount: String): Long? = ckbToShannons(amount)
}

/**
 * Maps a raw send/broadcast error to a user-facing message. Pure + top-level
 * for testability. Order matters: the most specific, actionable causes come
 * first. The Rust JNI now returns the real rejection reason (via the
 * "Broadcast rejected: ..." exception), so we no longer blame the network for
 * what are actually local verification failures (Alex report).
 */
internal fun mapSendErrorMessage(raw: String?): String {
    val message = raw ?: "Unknown error"
    return when {
        // Cell/UTXO fetch
        message.contains("Failed to get cells", ignoreCase = true) ->
            "Could not fetch your available funds. Please ensure your wallet is synced and try again."
        message.contains("No cells available", ignoreCase = true) ||
            message.contains("Insufficient cells", ignoreCase = true) ->
            "Not enough funds available. Please wait for your wallet to fully sync."

        // Amount/build
        message.contains("Insufficient balance", ignoreCase = true) ->
            "Insufficient balance for this transaction."
        message.contains("minimum", ignoreCase = true) && message.contains("61", ignoreCase = true) ->
            "Minimum transfer amount is 61 CKB due to CKB's cell model."
        message.contains("Dust change refused", ignoreCase = true) ->
            "This exact amount would leave less than 61 CKB of change, which CKB cannot store as a separate output and the protocol would silently absorb into the transaction fee. Try sending a slightly different amount, or send your full balance minus the fee."

        // Broadcast rejected with a real reason (from the JNI). Specific,
        // actionable causes first.
        message.contains("Unknown(", ignoreCase = true) ->
            "This send depends on a previous transaction that hasn't confirmed yet. Wait for it to confirm, or reopen the app and try again."
        message.contains("Dead(", ignoreCase = true) ->
            "Some of the coins for this transaction were already spent. Reopen the app to refresh your balance, then try again."
        message.contains("light client not ready", ignoreCase = true) ->
            "The wallet is still starting up. Please wait a moment and try again."
        message.contains("verification failed", ignoreCase = true) ->
            "The network rejected this transaction. Reopen the app to refresh your wallet, then try again."
        // Any other broadcast rejection: surface a concise reason instead of a
        // network message, since these are usually deterministic and local.
        message.contains("Broadcast rejected", ignoreCase = true) ->
            "The network rejected this transaction. Please reopen the app and try again."
        message.contains("Send failed", ignoreCase = true) ||
            message.contains("broadcast", ignoreCase = true) ->
            "Could not send the transaction. Please reopen the app and try again."

        // Sync
        message.contains("not synced", ignoreCase = true) ||
            message.contains("sync", ignoreCase = true) ->
            "Wallet is still syncing. Please wait for sync to complete before sending."

        // Data/parsing (likely a bug)
        message.contains("json", ignoreCase = true) ||
            message.contains("parse", ignoreCase = true) ||
            message.contains("serial", ignoreCase = true) ||
            message.contains("missing", ignoreCase = true) ->
            "Internal error processing transaction data. Please try again or restart the app."

        else -> "Transaction failed: $message"
    }
}
