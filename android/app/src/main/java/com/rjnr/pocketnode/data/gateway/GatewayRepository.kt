package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.sync.SyncEngine
import com.rjnr.pocketnode.data.sync.contract.SyncServiceCommands
import com.rjnr.pocketnode.data.migration.WalletMigrationHelper
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.transaction.PrivateKeySigner
import com.rjnr.pocketnode.data.send.SendContext
import com.rjnr.pocketnode.data.send.SendPipeline
import com.rjnr.pocketnode.data.transaction.RecipientOutput
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.transaction.SweepInput
import com.rjnr.pocketnode.data.transaction.TransferPlan
import com.rjnr.pocketnode.data.wallet.GapLimitResolution
import com.rjnr.pocketnode.data.wallet.GapLimitStatus
import com.rjnr.pocketnode.data.wallet.GapLimitSweepPreview
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.gapLimitResolution
import com.rjnr.pocketnode.data.wallet.nextScanWindow
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.nervosnetwork.ckblightclient.LightClientNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject
import javax.inject.Singleton
import com.rjnr.pocketnode.util.redactAddress

@Singleton
class GatewayRepository @Inject constructor(
    private val keyManager: KeyManager,
    // Concrete (#461): touches all four preference domains.
    private val walletPreferences: WalletPreferences,
    private val json: Json,
    private val transactionBuilder: TransactionBuilder,
    private val cacheManager: CacheManager,
    private val daoSyncManager: DaoSyncManager,
    private val walletMigrationHelper: WalletMigrationHelper,
    private val walletDao: WalletDao,
    private val appDatabase: AppDatabase,
    private val syncProgressDao: SyncProgressDao,
    private val pendingBroadcastDao: PendingBroadcastDao,
    // The shared send path (M3 #5): preview, build, reserve, broadcast, retry.
    private val sendPipeline: SendPipeline,
    private val syncCoordinator: SyncCoordinator,
    private val daoHeaderResolver: DaoHeaderResolver,
    private val daoDepositReader: DaoDepositReader,
    private val lightClient: LightClientReadOnly,
    // The shared read path (M3 #4): balance, cells, history, tx status.
    private val ledgerReader: LedgerReader,
    private val subAccountReconciler: com.rjnr.pocketnode.data.wallet.SubAccountReconciler,
    private val subAccountDiscovery: com.rjnr.pocketnode.data.wallet.SubAccountDiscovery,
    private val syncServiceCommands: SyncServiceCommands,
    private val nodeLifecycle: NodeLifecycle,
    private val syncEngine: SyncEngine,
    private val startupReconciler: StartupReconciler,
    private val logger: Logger,
) : TipSource, SyncPollSource {

    /**
     * Sender identity for one send, snapshotted here and handed to
     * [SendPipeline] so nothing inside the send mutex re-reads a field a
     * wallet switch could move underneath it (M3 #5).
     */
    private fun sendContext(): SendContext = SendContext(
        network = currentNetwork,
        walletId = activeWalletId,
        activeScript = _walletInfo.value?.script,
        isSyncing = { syncProgress.value.isSyncing },
        scope = scope,
    )

    // #382: single-flight for the explicit gap-limit scan (Home banner and
    // Settings both trigger it).
    private val gapLimitScanMutex = Mutex()

    // The tip stream and the whole sync-progress poll live on [SyncEngine]
    // (#460, moved to the shared module in M3). These forward so the
    // repository's public surface is unchanged.
    override val tipFlow: StateFlow<Long> get() = syncEngine.tipFlow

    /** @see SyncEngine.publishTip */
    internal fun publishTip(n: Long) = syncEngine.publishTip(n)

    override suspend fun fetchAndPublishTip(): Long {
        val n = currentTipNumberOrZero()
        if (n > 0) publishTip(n)
        return n
    }

    override fun hasWalletInfo(): Boolean = _walletInfo.value != null

    override fun activeWalletAndNetworkOrNull(): Pair<String, String>? {
        val id = activeWalletId
        if (id.isBlank()) return null
        return id to currentNetwork.name
    }

    private val _walletInfo = MutableStateFlow<WalletInfo?>(null)
    val walletInfo: StateFlow<WalletInfo?> = _walletInfo.asStateFlow()

    private val _balance = MutableStateFlow<BalanceResponse?>(null)
    val balance: StateFlow<BalanceResponse?> = _balance.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    // Node lifecycle (config copy, JNI init/start, status callback, network
    // selection and the restart-based switch) lives on [NodeLifecycle] (#460).
    // These forward so the repository's public surface is unchanged.
    val nodeStatus: StateFlow<String> get() = nodeLifecycle.nodeStatus
    val network: StateFlow<NetworkType> get() = nodeLifecycle.network
    val currentNetwork: NetworkType get() = nodeLifecycle.currentNetwork
    val isSwitchingNetwork: StateFlow<Boolean> get() = nodeLifecycle.isSwitchingNetwork

    // SupervisorJob: one child failure must not cancel siblings or the scope
    // itself. Without this, a thrown exception inside any background coroutine
    // (sync polling JNI calls, DAO header fetches, notification updates) would
    // cancel every other coroutine and propagate to the Thread default handler,
    // which in release builds crashes the process. Samsung devices reach this
    // path readily after long background periods because the OEM memory
    // manager forces the embedded light client into states that throw on
    // re-entry (matt, Telegram, 2026-05).
    //
    // CoroutineExceptionHandler: logs the throwable instead of letting it
    // bubble out of the scope. Pairs with the SupervisorJob — together they
    // turn what used to be a process crash into a single ERROR line in
    // logcat.
    private val coroutineExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        logger.e(TAG, "Uncaught exception in GatewayRepository scope; suppressed to avoid process crash", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private var activeWalletId: String = walletPreferences.getActiveWalletId() ?: ""
    private var activeWalletType: String = KeyManager.WALLET_TYPE_MNEMONIC

    // BALANCED filter cache + `scriptArgsToWalletId` mapping live on
    // [SyncCoordinator] now (#106). Read through `syncCoordinator.getWalletIdForScript`.

    /** @see SyncEngine.syncProgress */
    val syncProgress: StateFlow<SyncProgress> get() = syncEngine.syncProgress

    init {
        // Migrate old flat data/ directory to data/mainnet/ on first run
        nodeLifecycle.migrateDataDirectoryIfNeeded()

        // Initialize the embedded node for the persisted network. Any unhandled
        // failure in the startup sequence must mark node init as failed so
        // awaitNodeReady() can't suspend forever and callers see an error.
        scope.launch {
            try {
                // Migrate single-wallet to multi-wallet schema (idempotent, no-op if already done)
                walletMigrationHelper.migrateIfNeeded()
                // Copy per-wallet lastSyncedBlock from SharedPreferences to Room sync_progress (#105)
                walletMigrationHelper.migrateSyncProgressToRoomIfNeeded()
                // Migrate key material from ESP to Room (one-time, for upgrading users)
                keyManager.migrateEspToRoomIfNeeded(walletDao)
                // Delete ESP files after successful migration
                keyManager.deleteEspFilesIfSafe()
                activeWalletId = walletPreferences.getActiveWalletId() ?: ""

                // Periodic VACUUM (~monthly) to reclaim fragmented space from
                // tombstoned tx/cell rows. Throttled so it doesn't run on
                // every cold start.
                runCatching {
                    if (DatabaseMaintenanceUtil.vacuumIfDue(appDatabase, walletPreferences.getLastVacuumAt())) {
                        walletPreferences.setLastVacuumAt(System.currentTimeMillis())
                        logger.d(TAG, "Periodic VACUUM completed")
                    }
                }.onFailure { logger.w(TAG, "Periodic VACUUM failed (non-fatal)", it) }

                nodeLifecycle.initializeNode(currentNetwork, ::onNodeStarted)
            } catch (e: Exception) {
                logger.e(TAG, "Startup sequence failed before node init", e)
                nodeLifecycle.markInitFailed()
            }
        }
    }

    /**
     * Read the last fully-processed block for a wallet on a given network.
     * Returns 0L when no sync_progress row exists (wallet never synced).
     */
    suspend fun getWalletSyncBlock(walletId: String, network: NetworkType = currentNetwork): Long {
        if (walletId.isEmpty()) return 0L
        return syncProgressDao.get(walletId, network.name)?.localSavedBlockNumber ?: 0L
    }

    /**
     * Persist the last fully-processed block for a wallet on a given network.
     * If no sync_progress row exists, creates one (lightStartBlockNumber seeded to `block`).
     * If a row exists, updates only `localSavedBlockNumber` and `updatedAt`.
     */
    suspend fun setWalletSyncBlock(walletId: String, block: Long, network: NetworkType = currentNetwork) {
        if (walletId.isEmpty()) return
        val now = System.currentTimeMillis()
        // Atomic UPDATE first preserves any concurrently-written lightStartBlockNumber
        // (e.g. setScriptsAndRecord landing between get and upsert).
        val rowsUpdated = syncProgressDao.updateLocalSaved(walletId, network.name, block, now)
        if (rowsUpdated == 0) {
            syncProgressDao.upsert(
                SyncProgressEntity(
                    walletId = walletId,
                    network = network.name,
                    lightStartBlockNumber = block,
                    localSavedBlockNumber = block,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * Called when the user switches wallets. Updates internal state, derives the new
     * wallet's lock script, and re-registers with the light client according to
     * the configured sync strategy.
     */
    suspend fun onActiveWalletChanged(wallet: WalletEntity) {
        activeWalletId = wallet.walletId
        activeWalletType = wallet.type
        // Address-only derivation: avoids a BiometricPrompt for V2 wallets
        // on every wallet switch. Lock script + addresses round-trip from
        // the cached WalletEntity, no key material needed (#213 sub-PR 5).
        val info = keyManager.deriveWalletInfoFromEntity(wallet)
        _walletInfo.value = info
        _balance.value = null  // Clear old wallet's balance immediately
        _isRegistered.value = false
        // Drop the previous wallet's sync samples so the new wallet's progress
        // starts from its own baseline. Otherwise ACTIVE_ONLY switches can spuriously
        // report progress / ETA / justReachedTip from the old wallet's syncing window.
        syncEngine.resetTracker()
        // Seed the percentage baseline with the registered light-client start
        // block so the first sample doesn't anchor the math to a transient
        // syncedToBlock=0 reading during peer warm-up (#150).
        val lightStart = syncProgressDao.get(wallet.walletId, currentNetwork.name)
            ?.lightStartBlockNumber ?: 0L
        if (lightStart > 0) {
            syncEngine.seedStartHeight(lightStart)
        }
        syncEngine.resetProgressState()

        val walletSyncMode = walletPreferences.getSyncMode(walletId = wallet.walletId)
        val walletCustomHeight = if (walletSyncMode == SyncMode.CUSTOM) {
            walletPreferences.getCustomBlockHeight(walletId = wallet.walletId)
        } else null

        when (walletPreferences.getSyncStrategy()) {
            // BALANCED reads per-wallet syncMode/customBlockHeight inside the loop
            // (registerAllWalletScripts at L1749), so the locals above are unused here.
            SyncStrategy.ALL_WALLETS, SyncStrategy.BALANCED -> registerAllWalletScripts()
            SyncStrategy.ACTIVE_ONLY -> registerAccount(
                syncMode = walletSyncMode,
                customBlockHeight = walletCustomHeight,
                savePreference = false
            )
        }

        // Emit cached data immediately
        cacheManager.getCachedBalance(currentNetwork.name, walletId = activeWalletId)?.let {
            _balance.value = it
        }
    }

    /**
     * Repository-side work that runs immediately after the embedded node
     * starts, passed to [NodeLifecycle.initializeNode]. The cold-start
     * pending-tx reconciliation itself lives on [StartupReconciler] (#460);
     * this keeps the order: reconcile, poll, background sync.
     */
    private suspend fun onNodeStarted() {
        startupReconciler.reconcile(
            walletId = activeWalletId,
            networkName = currentNetwork.name,
            scope = scope,
            statusSource = { hash -> getTransactionStatus(hash) },
        )

        startSyncPolling()
        startBackgroundSync()
    }

    /** @see NodeLifecycle.awaitNodeReady */
    private suspend fun awaitNodeReady(): Boolean = nodeLifecycle.awaitNodeReady()

    /** @see NodeLifecycle.switchNetwork */
    suspend fun switchNetwork(target: NetworkType): Result<Unit> = nodeLifecycle.switchNetwork(target)

    /**
     * Initialize wallet by loading existing one. 
     * Does NOT auto-generate a new one anymore (Onboarding flow handles that).
     */
    suspend fun initializeWallet(): Result<WalletInfo> = runCatching {
        if (keyManager.hasWallet()) {
            // Use wallet-scoped addresses for the active wallet, not global legacy prefs.
            // Address-only derivation here (no key read) so V2 wallets boot without a
            // BiometricPrompt — the sign path will prompt when actually needed (#213).
            val info = if (activeWalletId.isNotEmpty()) {
                val activeWallet = walletDao.getActive()
                    ?: throw Exception("No key for active wallet $activeWalletId")
                activeWalletType = activeWallet.type
                keyManager.deriveWalletInfoFromEntity(activeWallet)
            } else {
                keyManager.getWalletInfo() // fallback for legacy single-wallet (V1 only)
            }
            _walletInfo.value = info
            info
        } else {
            throw Exception("No wallet found")
        }
    }

    /**
     * Checks if a wallet is already configured
     */
    suspend fun hasWallet(): Boolean = keyManager.hasWallet()

    /**
     * Resolve the active wallet type from durable state before making startup
     * routing decisions. During a cold start [activeWalletType] may still hold
     * its constructor default while repository initialization is running in the
     * background, so trusting only the in-memory value can misclassify raw-key
     * wallets as mnemonic wallets.
     */
    private suspend fun resolveActiveWalletType(): String {
        val activeWallet = walletDao.getActive()
            ?: activeWalletId.takeIf { it.isNotBlank() }?.let { walletDao.getById(it) }

        if (activeWallet != null) {
            activeWalletId = activeWallet.walletId
            activeWalletType = activeWallet.type
            return activeWallet.type
        }

        // Legacy single-wallet fallback for installs that have not been migrated
        // into Room yet. KeyManager defaults unknown legacy wallets to raw-key.
        return keyManager.getWalletType().also { activeWalletType = it }
    }

    /**
     * Returns true if the current wallet is a mnemonic wallet that hasn't completed backup verification.
     * Used by MainActivity to gate access to the dashboard until backup is done.
     */
    suspend fun needsMnemonicBackup(): Boolean {
        // Sub-accounts are derived from the parent's seed and have no
        // independent mnemonic to back up; they inherit the parent's backup
        // status. Never prompt them to back up. Besides being wrong, this used
        // to make the sub-account backup notice the nav START destination,
        // where its "Got it" button (popBackStack) no-oped on an empty back
        // stack and the user was stuck (#372).
        val activeWallet = walletDao.getActive()
            ?: activeWalletId.takeIf { it.isNotBlank() }?.let { walletDao.getById(it) }
        if (activeWallet?.parentWalletId != null) return false

        return resolveActiveWalletType() == KeyManager.WALLET_TYPE_MNEMONIC
            && !hasMnemonicBackupForActiveWallet()
    }

    // Retires together with KeyManager's ESP fallback — that path is still the
    // legacy-key read for un-migrated installs, so its removal needs its own
    // issue, not a warning sweep.
    @Suppress("DEPRECATION")
    fun wasResetDueToCorruption(): Boolean = keyManager.wasResetDueToCorruption()

    fun getWalletType(): String = activeWalletType
    suspend fun getMnemonic(): List<String>? {
        // Use wallet-scoped mnemonic — never fall back to global prefs
        // (raw_key wallets correctly return null here)
        val wId = activeWalletId
        return if (wId.isNotEmpty()) {
            keyManager.getMnemonicForWallet(wId)
        } else {
            keyManager.getMnemonic()
        }
    }
    suspend fun hasMnemonicBackup(): Boolean = hasMnemonicBackupForActiveWallet()

    /**
     * Check backup status for the active wallet specifically, not the global legacy flag.
     */
    suspend fun hasMnemonicBackupForActiveWallet(): Boolean {
        val wId = activeWalletId
        return if (wId.isNotEmpty()) {
            keyManager.hasMnemonicBackupForWallet(wId)
        } else {
            keyManager.hasMnemonicBackup()
        }
    }
    suspend fun setMnemonicBackedUp(backedUp: Boolean) {
        if (activeWalletId.isNotEmpty()) {
            keyManager.setMnemonicBackedUpForWallet(activeWalletId, backedUp)
        } else {
            keyManager.setMnemonicBackedUp(backedUp)
        }
    }

    fun getSavedSyncMode(): SyncMode = walletPreferences.getSyncMode(walletId = activeWalletId.ifEmpty { null })
    fun getSavedCustomBlockHeight(): Long? = walletPreferences.getCustomBlockHeight(walletId = activeWalletId.ifEmpty { null })

    /**
     * Register scripts according to the configured sync strategy.
     * If ALL_WALLETS, registers scripts for all wallets simultaneously.
     * Otherwise delegates to the single-wallet registerAccount().
     */
    suspend fun registerAccountWithStrategy(
        syncMode: SyncMode = SyncMode.RECENT,
        customBlockHeight: Long? = null,
        savePreference: Boolean = true
    ): Result<Unit> = runCatching {
        when (walletPreferences.getSyncStrategy()) {
            SyncStrategy.ALL_WALLETS, SyncStrategy.BALANCED -> {
                // Persist the chosen mode/height BEFORE registering: the
                // SyncCoordinator reads the per-wallet pref to compute each
                // script's start block, so writing it afterwards meant a
                // first-time CUSTOM selection registered from the stale default
                // (RECENT = tip-200k) and only took effect on the next launch
                // (knmo C).
                if (savePreference) {
                    val wId = activeWalletId.ifEmpty { null }
                    walletPreferences.setSyncMode(syncMode, walletId = wId)
                    if (syncMode == SyncMode.CUSTOM) {
                        walletPreferences.setCustomBlockHeight(customBlockHeight, walletId = wId)
                    }
                    walletPreferences.setInitialSyncCompleted(true, walletId = wId)
                }
                registerAllWalletScripts()
            }
            SyncStrategy.ACTIVE_ONLY -> {
                registerAccount(syncMode, customBlockHeight, savePreference).getOrThrow()
            }
        }
    }

    suspend fun registerAccount(
        syncMode: SyncMode = SyncMode.RECENT,
        customBlockHeight: Long? = null,
        savePreference: Boolean = true,
        forceResync: Boolean = false
    ): Result<Unit> = runCatching {
        // Force IO dispatcher — see registerAllWalletScripts above for the same
        // reasoning. ACTIVE_ONLY callers also block Main without this. (#109)
        withContext(Dispatchers.IO) {
        // Wait for node to be ready
        if (!awaitNodeReady()) {
             throw Exception("Node initialization failed")
        }

        val info = _walletInfo.value ?: throw Exception("Wallet not initialized")

        val tipStr = LightClientNative.nativeGetTipHeader()
        val tipHeight = if (tipStr != null) {
            val tip = json.decodeFromString<JniHeaderView>(tipStr)
            tip.number.removePrefix("0x").toLongOrNull(16) ?: 0L
        } else 0L

        // Check for existing sync progress to resume from (per-wallet)
        val savedBlock = getWalletSyncBlock(activeWalletId)
        val existingScriptBlock = getExistingScriptBlock()

        val blockNum: String = when {
            // If force resync requested, recalculate from sync mode
            forceResync -> {
                logger.d(TAG, "Force resync requested, recalculating from sync mode")
                syncMode.toFromBlock(customBlockHeight, tipHeight, currentNetwork)
            }
            // Resume from saved progress if available (use the higher value)
            savedBlock > 0 || existingScriptBlock > 0 -> {
                val resumeBlock = maxOf(savedBlock, existingScriptBlock)
                logger.d(TAG, "Resuming sync from saved block: $resumeBlock (saved=$savedBlock, existing=$existingScriptBlock)")
                resumeBlock.toString()
            }
            // First time: calculate based on sync mode
            else -> {
                logger.d(TAG, "First time sync, calculating from mode: $syncMode")
                syncMode.toFromBlock(customBlockHeight, tipHeight, currentNetwork)
            }
        }

        // Safety check: if blockNum is in the future, reset to a RECENT block height.
        // Use network-aware checkpoint as a fallback if tip is 0.
        val checkpoint = getCheckpoint(currentNetwork)
        var finalBlockNum = blockNum
        val blockNumLong = blockNum.toLongOrNull() ?: 0L

        if (blockNumLong > tipHeight && tipHeight > 0) {
            val recentBlock = (tipHeight - 200_000).coerceAtLeast(0L)
            logger.w(TAG, "Detected future block number ($blockNumLong > $tipHeight). " +
                    "Resetting to RECENT height: $recentBlock")
            finalBlockNum = recentBlock.toString()
        } else if (blockNumLong == 0L && syncMode != SyncMode.FULL_HISTORY && checkpoint > 0) {
            // If it resolved to 0 but we aren't doing full history, use checkpoint
            logger.d(TAG, "Block resolved to 0 but mode is $syncMode. Using checkpoint $checkpoint")
            finalBlockNum = checkpoint.toString()
        }

        logger.d(TAG, "🔄 Sync mode $syncMode: tip=$tipHeight, targetBlock=$finalBlockNum")

        val blockNumberHex = "0x${finalBlockNum.toLongOrNull()?.toString(16) ?: "0"}"
        val scriptStatuses = listOf(
            JniScriptStatus(
                script = info.script,
                scriptType = "lock",
                blockNumber = blockNumberHex
            )
        )

        // CMD_ALL replaces the entire registered set — carry the active
        // wallet's PENDING discovery candidates or they get silently
        // unregistered. The import flow's immediate resync hit exactly this:
        // candidates registered at import were wiped 40s later and discovery
        // never ran (#82, device-test 2026-07). Empty walletIds keep them out
        // of per-wallet progress, same contract as registerAllWalletScripts.
        // #382 P1: candidates register from their own HISTORICAL start, not
        // this wallet's resume height — a tip-synced wallet resuming from
        // ~tip would register candidates where a scan can find nothing.
        val candidateHex = "0x" + candidateScanStart(
            historicalStartBlock(syncMode, customBlockHeight, tipHeight, currentNetwork),
            syncCoordinator.earliestCachedTxBlock(activeWalletId, currentNetwork.name),
            tipHeight,
        ).toString(16)
        val candidateRegistrations = syncCoordinator.pendingCandidateStatuses(
            activeWalletId, info.script, candidateHex
        )
        val result = setScriptsAndRecord(
            scriptStatuses + candidateRegistrations.map { it.status },
            listOf(activeWalletId) + candidateRegistrations.map { "" },
            LightClientNative.CMD_SET_SCRIPTS_ALL
        )
        if (!result) throw Exception("Failed to set scripts")

        // #382: record each candidate's scan-from block — the reconciler's
        // EMPTY coverage gate stays inert while registeredFromBlock is 0.
        syncCoordinator.recordCandidateRegistrations(candidateRegistrations)

        _isRegistered.value = true
        if (savePreference) {
            val wId = activeWalletId.ifEmpty { null }
            walletPreferences.setSyncMode(syncMode, walletId = wId)
            if (syncMode == SyncMode.CUSTOM) {
                walletPreferences.setCustomBlockHeight(customBlockHeight, walletId = wId)
            }
            walletPreferences.setInitialSyncCompleted(true, walletId = wId)
        }
        }  // end withContext(Dispatchers.IO)
    }

    suspend fun resyncAccount(
        syncMode: SyncMode,
        customBlockHeight: Long? = null
    ): Result<Unit> {
        _isRegistered.value = false
        // Clear saved sync progress when explicitly resyncing (per-wallet)
        setWalletSyncBlock(activeWalletId, 0L)
        // Re-arm the zero-cell rescue rescan: an explicit resync is the user
        // deliberately asking us to look again (knmo).
        walletPreferences.clearZeroCellRescanDone(activeWalletId)
        balanceRescanAttempted.remove(activeWalletId)
        return registerAccount(syncMode, customBlockHeight, savePreference = true, forceResync = true)
    }

    /**
     * True when the wallet is already registered at this sync setting, so an
     * Apply tap should be a no-op (knmo B, Option 2). Compares the request
     * against the APPLIED per-wallet preference + actual registration, not the
     * UI's displayed value, which could disagree with what was really applied.
     */
    fun isSyncSettingApplied(syncMode: SyncMode, customBlockHeight: Long?): Boolean {
        val wId = activeWalletId.ifEmpty { null }
        return syncSettingAlreadyApplied(
            requestedMode = syncMode,
            requestedHeight = customBlockHeight,
            appliedMode = walletPreferences.getSyncMode(walletId = wId),
            appliedHeight = walletPreferences.getCustomBlockHeight(walletId = wId),
            isRegistered = _isRegistered.value,
        )
    }

    fun hasCompletedInitialSync(): Boolean = walletPreferences.hasCompletedInitialSync(walletId = activeWalletId.ifEmpty { null })

    /** #382: gap-limit banner is visible when the signature was detected for the active wallet and not dismissed. */
    fun isGapLimitBannerVisible(): Boolean {
        if (activeWalletId.isEmpty()) return false
        return walletPreferences.isGapLimitSignalDetected(currentNetwork, activeWalletId) &&
            !walletPreferences.isGapLimitBannerDismissed(currentNetwork, activeWalletId)
    }

    fun dismissGapLimitBanner() {
        if (activeWalletId.isEmpty()) return
        walletPreferences.setGapLimitBannerDismissed(currentNetwork, activeWalletId)
    }

    /**
     * #382 Tier 2: what the chain-axis candidate set means for the active
     * wallet, plus the live capacity sitting on FOUND slots. Side effect:
     * a CLEAR resolution (scan finished, nothing anywhere) retires the
     * Tier 1 signal so the banner stops firing on stale evidence.
     */
    suspend fun getGapLimitStatus(): GapLimitStatus {
        val wId = activeWalletId
        if (wId.isEmpty()) return GapLimitStatus(GapLimitResolution.NOT_SCANNED, 0, 0L)
        val chain = runCatching {
            appDatabase.subAccountCandidateDao().getForParent(wId).filter { it.accountIndex == 0 }
        }.onFailure {
            logger.w(TAG, "getGapLimitStatus: candidate read failed, treating as not scanned: ${it.message}")
        }.getOrDefault(emptyList())
        val resolution = gapLimitResolution(chain)
        if (resolution == GapLimitResolution.CLEAR &&
            walletPreferences.isGapLimitSignalDetected(currentNetwork, wId)
        ) {
            logger.i(TAG, "gap-limit scan completed clean — retiring Tier 1 signal for $wId")
            walletPreferences.setGapLimitSignalDetected(false, currentNetwork, wId)
        }
        if (resolution != GapLimitResolution.FOUND) return GapLimitStatus(resolution, 0, 0L)

        val myScript = _walletInfo.value?.script
            ?: return GapLimitStatus(resolution, chain.count { it.state == SubAccountCandidateEntity.STATE_FOUND }, 0L)
        var total = 0L
        var count = 0
        chain.filter { it.state == SubAccountCandidateEntity.STATE_FOUND }.forEach { cand ->
            runCatching {
                val cap = liveUntypedCapacityFor(JniSearchKey(script = myScript.copy(args = cand.scriptArgs)))
                if (cap > 0L) {
                    total += cap
                    count++
                }
            }.onFailure { logger.w(TAG, "gap-limit capacity read failed for ${cand.derivationPath}: ${it.message}") }
        }
        return GapLimitStatus(resolution, count, total)
    }

    /**
     * #382 Tier 2: explicit deep scan. Covers wallets imported before the
     * auto-scan shipped, and extends the window (20 -> 40 -> 60) when the
     * current boundary slot shows activity. Needs the mnemonic (session-auth
     * gated by [getMnemonic]); inserts are IGNORE so already-resolved slots
     * keep their state, then scripts re-register so the new ones enter the
     * light-client filter. Returns the window that is now covered.
     */
    /**
     * V1 / session-auth entry: reads the seed via [getMnemonic] (which has an
     * EncryptedSharedPreferences fallback). V2 (kdfVersion=2) wallets cannot
     * decrypt without an authenticated Cipher, so their ViewModels unlock the
     * seed via a BiometricPrompt and call [runGapLimitScan] with words (#408).
     */
    suspend fun runGapLimitScan(): Result<Int> = runCatching {
        val words = getMnemonic() ?: throw Exception("Recovery phrase unavailable for this wallet")
        runGapLimitScanInner(words)
    }

    /** V2 entry: [words] were already unlocked by the caller's BiometricPrompt (#408). */
    suspend fun runGapLimitScan(words: List<String>): Result<Int> = runCatching {
        runGapLimitScanInner(words)
    }

    private suspend fun runGapLimitScanInner(words: List<String>): Int {
        // Single-flight: the Home banner and Settings both trigger this, and
        // a second concurrent pass would double the derivation work and
        // interleave two CMD_SET_SCRIPTS_ALL registrations.
        if (!gapLimitScanMutex.tryLock()) throw Exception("A scan is already running")
        return try {
            val wId = activeWalletId
            if (wId.isEmpty()) throw Exception("No active wallet")
            val dao = appDatabase.subAccountCandidateDao()
            val existing = dao.getForParent(wId).filter { it.accountIndex == 0 }
            val window = nextScanWindow(existing)
            val now = System.currentTimeMillis()
            val candidates = subAccountDiscovery.deriveChainCandidates(words, window = window)
            // The mnemonic read and derivation are slow; if the user switched
            // wallets meanwhile, inserting rows for the OLD wallet and then
            // registering the NEW one would corrupt the scan. Abort instead.
            if (activeWalletId != wId) throw Exception("Wallet changed during the scan; try again")
            dao.insertAll(
                candidates.map {
                    SubAccountCandidateEntity(
                        parentWalletId = wId,
                        derivationPath = it.derivationPath,
                        accountIndex = it.accountIndex,
                        scriptArgs = it.scriptArgs,
                        createdAt = now,
                    )
                }
            )
            // An explicit scan means "look AGAIN": re-arm chain slots a past
            // pass retired as EMPTY. Without this the action was a silent
            // no-op after any completed scan — funds arriving later (or on a
            // different network than the pass that retired them) were
            // undiscoverable forever (device-verification, 2026-07). The
            // reconciler's probe re-judges them: activity -> FOUND, still
            // nothing -> EMPTY again shortly.
            val reArmed = dao.reArmEmptyChainSlots(wId)
            if (reArmed > 0) logger.i(TAG, "gap-limit scan: re-armed $reArmed retired slot(s)")
            // Fresh registration pass so new candidate scripts join the filter.
            registerAccountWithStrategy(
                getSavedSyncMode(), getSavedCustomBlockHeight(), savePreference = false
            ).getOrThrow()
            window
        } finally {
            gapLimitScanMutex.unlock()
        }
    }

    /**
     * Live spendable capacity for one script: cells walked to the end, spent
     * outpoints subtracted, typed cells excluded — the same read-path rules
     * as refreshBalance (nativeGetCellsCapacity alone can overstate; an
     * inflated "found funds" number would re-create the exact panic #382 is
     * meant to end).
     */
    private suspend fun liveUntypedCapacityFor(searchKey: JniSearchKey): Long =
        liveUntypedCellsFor(searchKey).sumOf {
            it.output.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L
        }

    /**
     * The live untyped cells behind [liveUntypedCapacityFor] — the Tier 3
     * sweep spends them. Outpoints reserved by ACTIVE pending broadcasts are
     * excluded: a just-broadcast sweep's inputs are spent-in-flight, and
     * counting them keeps the found-funds card at the old amount until the
     * chain index catches up (and would let a double-tapped sweep try to
     * respend them).
     */
    private suspend fun liveUntypedCellsFor(searchKey: JniSearchKey): List<JniCell> {
        val searchKeyJson = json.encodeToString(searchKey)
        val spent = fetchAllSpentOutpoints(searchKeyJson).toMutableSet()
        runCatching {
            pendingBroadcastDao.getActive(activeWalletId, currentNetwork.name)
                .flatMap { json.decodeFromString<List<OutPoint>>(it.reservedInputs) }
                .forEach { spent += "${it.txHash}:${it.index}" }
        }.onFailure { logger.w(TAG, "liveUntypedCellsFor: reservation read failed: ${it.message}") }
        val live = mutableListOf<JniCell>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_CELL_PAGES) {
            val pageJson = LightClientNative.nativeGetCells(searchKeyJson, "desc", 100, cursor) ?: break
            val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
            page.objects.forEach { cell ->
                val key = "${cell.outPoint.txHash}:${cell.outPoint.index}"
                if (key !in spent && cell.output.type == null &&
                    cell.output.capacity.removePrefix("0x").toLongOrNull(16) != null
                ) {
                    live += cell
                }
            }
            pages++
            if (page.objects.isEmpty() || page.objects.size < 100 || page.lastCursor.isNullOrEmpty()) break
            cursor = page.lastCursor
        }
        return live
    }

    /**
     * #382 Tier 3: gather every live untyped cell sitting on FOUND chain-axis
     * slots as sweep inputs, tagged with the lock args that identify their
     * signing group. Second value = how many distinct addresses hold funds.
     */
    private suspend fun gatherSweepInputs(walletId: String): Pair<List<SweepInput>, Int> {
        val myScript = _walletInfo.value?.script ?: throw Exception("Wallet not initialized")
        val found = appDatabase.subAccountCandidateDao().getForParent(walletId)
            .filter { it.accountIndex == 0 && it.state == SubAccountCandidateEntity.STATE_FOUND }
        val inputs = mutableListOf<SweepInput>()
        var addresses = 0
        found.forEach { cand ->
            val cells = liveUntypedCellsFor(JniSearchKey(script = myScript.copy(args = cand.scriptArgs)))
            if (cells.isNotEmpty()) addresses++
            cells.forEach { cell ->
                inputs += SweepInput(
                    outPoint = OutPoint(cell.outPoint.txHash, cell.outPoint.index),
                    capacityShannons = cell.output.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L,
                    lockArgs = cand.scriptArgs,
                )
            }
        }
        return inputs to addresses
    }

    /**
     * #382 Tier 3: the numbers for the sweep confirm dialog — total found,
     * exact fee, distinct addresses. Key-free: gathering and planning need
     * no mnemonic, only the confirm step does.
     */
    suspend fun prepareGapLimitSweep(): Result<GapLimitSweepPreview> = runCatching {
        val wId = activeWalletId
        if (wId.isEmpty()) throw Exception("No active wallet")
        val myScript = _walletInfo.value?.script ?: throw Exception("Wallet not initialized")
        val (inputs, addresses) = gatherSweepInputs(wId)
        val plan = transactionBuilder.buildSweep(inputs, myScript, currentNetwork).getOrThrow()
        GapLimitSweepPreview(
            totalShannons = plan.totalShannons,
            feeShannons = plan.feeShannons,
            addressCount = addresses,
        )
    }

    /**
     * #382 Tier 3: the sweep itself. Re-gathers cells (a preview can go
     * stale), derives each lock group's key from its candidate's derivation
     * path, VERIFIES each derived key reproduces the candidate's lock args
     * (a derivation mismatch must abort, never sign), signs one multi-group
     * transaction and hands it to the idempotent sendTransaction path
     * (pending row + watchdog). Keys and seed are zeroed after signing.
     */
    /**
     * V1 / session-auth entry: reads the seed via [getMnemonic]. V2 wallets
     * unlock the seed via a BiometricPrompt and call the words overload (#408).
     */
    suspend fun sweepGapLimitFunds(): Result<String> = runCatching {
        val words = getMnemonic() ?: throw Exception("Recovery phrase unavailable for this wallet")
        sweepGapLimitFundsInner(words)
    }

    /** V2 entry: [words] were already unlocked by the caller's BiometricPrompt (#408). */
    suspend fun sweepGapLimitFunds(words: List<String>): Result<String> = runCatching {
        sweepGapLimitFundsInner(words)
    }

    private suspend fun sweepGapLimitFundsInner(words: List<String>): String {
        if (!gapLimitScanMutex.tryLock()) throw Exception("A scan or sweep is already running")
        return try {
            val wId = activeWalletId
            if (wId.isEmpty()) throw Exception("No active wallet")
            val myScript = _walletInfo.value?.script ?: throw Exception("Wallet not initialized")

            val (inputs, _) = gatherSweepInputs(wId)
            val plan = transactionBuilder.buildSweep(inputs, myScript, currentNetwork).getOrThrow()

            val pathByArgs = appDatabase.subAccountCandidateDao().getForParent(wId)
                .filter { it.accountIndex == 0 && it.state == SubAccountCandidateEntity.STATE_FOUND }
                .associate { it.scriptArgs to it.derivationPath }

            // BIP39 passphrase: the import UI has no passphrase field, so
            // every wallet's candidates were derived with "". If that ever
            // changes, the derived-args verification below aborts the sweep
            // rather than signing with a mismatched key.
            val seed = keyManager.mnemonicToSeed(words)
            val keys = mutableMapOf<String, ByteArray>()
            try {
                plan.inputLockArgs.distinct().forEach { args ->
                    val path = pathByArgs[args]
                        ?: throw Exception("No derivation path recorded for a sweep input")
                    val (chain, index) = com.rjnr.pocketnode.data.wallet.chainAndIndexFromPath(path)
                        ?: throw Exception("Unparseable derivation path for a sweep input")
                    val key = keyManager.deriveChainKey(seed, chainIndex = chain, addressIndex = index)
                    val derivedArgs = keyManager.deriveLockScript(keyManager.derivePublicKey(key)).args
                    if (!derivedArgs.equals(args, ignoreCase = true)) {
                        key.fill(0)
                        throw Exception("Derived key does not match the recorded address; sweep aborted")
                    }
                    keys[args] = key
                }
                if (activeWalletId != wId) throw Exception("Wallet changed during the sweep; try again")
                val signed = transactionBuilder.signSweep(plan.transaction, plan.inputLockArgs, keys).getOrThrow()
                val txHash = sendTransaction(signed, expectedWalletId = wId).getOrThrow()
                logger.i(TAG, "gap-limit sweep broadcast: ${plan.inputLockArgs.size} inputs, ${keys.size} groups")
                txHash
            } finally {
                keys.values.forEach { it.fill(0) }
                seed.fill(0)
            }
        } finally {
            gapLimitScanMutex.unlock()
        }
    }
    
    suspend fun forceResetSync(): Result<Unit> = runCatching {
        logger.w(TAG, "Forcing sync reset...")
        // Only clear sync-related preferences for the active wallet, not all preferences
        setWalletSyncBlock(activeWalletId, 0L)
        walletPreferences.setInitialSyncCompleted(false, walletId = activeWalletId.ifEmpty { null })
        _isRegistered.value = false
        _balance.value = null
        registerAccount(SyncMode.RECENT)
        logger.i(TAG, "Sync reset complete. Registered as RECENT.")
    }

    suspend fun refreshBalance(address: String? = null): Result<BalanceResponse> = runCatching {
        val addr = address ?: getCurrentAddress() ?: throw Exception("Wallet not initialized")
        // One snapshot of the mutable wallet state for the whole read, so a
        // wallet switch mid-walk cannot cache the new wallet's id against the
        // old wallet's cells.
        val wId = activeWalletId
        val network = currentNetwork

        val resp = ledgerReader.readBalance(
            address = addr,
            script = _walletInfo.value?.script,
            network = network,
            walletId = wId,
            // Deliberately NOT snapshotted: the rescue rescan is decided after
            // the cursor walks, and the poll flips this every 5 to 10 s.
            isSyncing = { syncProgress.value.isSyncing },
            rescanAttempted = balanceRescanAttempted,
            // The cached balance paints the UI before the walk finishes; the
            // stream itself stays repository-owned.
            emitCached = { cached -> _balance.value = cached },
            requestPartialRescan = { statuses ->
                setScriptsAndRecord(
                    statuses,
                    listOf(wId),
                    LightClientNative.CMD_SET_SCRIPTS_PARTIAL,
                    allowRewind = true, // rescue rescan IS the intentional rewind
                )
            },
        ).getOrThrow()

        _balance.value = resp

        // --- Cache write ---
        cacheManager.cacheBalance(resp, network.name, walletId = wId)

        resp
    }

    /**
     * #435: recompute and cache the spendable balance for a wallet OTHER than
     * the active one, keyed under [walletId], from its [address].
     *
     * The account switcher renders each account's balance from the balance
     * cache. That cache was only written for a wallet while it was active (via
     * [refreshBalance]), so a confirmed transfer INTO a sub-account left the
     * sub-account's switcher balance stale until the user switched to it. All
     * wallets' lock scripts are registered with the light client
     * (SyncCoordinator), so the cell scan here sees the sub-account's cells
     * even while it is inactive.
     *
     * Unlike [refreshBalance] this deliberately does NOT mutate [_balance]
     * (that stream belongs to the active wallet) and does NOT run the zero-cell
     * rescue rescan (an active-wallet-only recovery that rewinds the script).
     */
    suspend fun refreshBalanceForWallet(walletId: String, address: String): Result<BalanceResponse> = runCatching {
        val resp = ledgerReader.readBalanceForWallet(walletId, address, currentNetwork).getOrThrow()
        cacheManager.cacheBalance(resp, currentNetwork.name, walletId = walletId)
        resp
    }

    // Simplified Account Status - JNI doesn't give sync progress easily
    override suspend fun getAccountStatus(): Result<AccountStatusResponse> = runCatching {
        val addr = getCurrentAddress() ?: throw Exception("No wallet")
        
        // Tip header plus the status of ALL registered scripts, read and
        // decoded by the shared engine (M3 #2). Same two bridge calls,
        // same hex parsing, same null-degrades-to-empty contract.
        val state = syncEngine.readChainSyncState()
        val tipNumber = state.tipNumber
        val scripts = state.scripts

        // Persist progress for EVERY registered wallet, not just the active one.
        // Under BALANCED with 3 wallets registered, the light client advances all
        // their scripts; if we only saved the active wallet's progress, the others'
        // localSavedBlockNumber rows would go stale and applyBalancedFilter would
        // mis-classify them as laggards based on stale data.
        var anyUpdated = false
        scripts.forEach { script ->
            val walletId = syncCoordinator.getWalletIdForScript(script.script.args) ?: return@forEach
            val block = script.blockNumber.removePrefix("0x").toLongOrNull(16) ?: return@forEach
            if (block > getWalletSyncBlock(walletId)) {
                setWalletSyncBlock(walletId, block)
                anyUpdated = true
                if (walletId == activeWalletId) {
                    logger.d(TAG, "💾 Saved sync progress: block $block (wallet=$walletId)")
                }
            }
        }

        // BALANCED: re-evaluate eligible set once after all updates landed.
        if (anyUpdated && walletPreferences.getSyncStrategy() == SyncStrategy.BALANCED) {
            maybeReregisterBalanced()
        }

        // #82 phase 2: resolve PENDING sub-account discovery candidates.
        // Their scripts ride along in `scripts` (registered with empty
        // walletId, so the wallet loop above skips them). Throttled
        // internally; never allowed to break the status poll.
        runCatching {
            subAccountReconciler.reconcile(
                scannedByArgs = scripts.associate { s ->
                    s.script.args to (s.blockNumber.removePrefix("0x").toLongOrNull(16) ?: 0L)
                },
                tipHeight = tipNumber,
            )
        }.onFailure { logger.w(TAG, "Sub-account candidate reconcile failed (non-fatal)", it) }

        // Active wallet's block, its progress percentage and the +-10-block
        // isSynced window, all derived by the shared engine (M3 #2). The
        // per-wallet bookkeeping above stays here: it is Android-only.
        val snapshot = syncEngine.computeStatus(state, _walletInfo.value?.script?.args)
        val scriptBlockNumber = snapshot.scriptBlockNumber
        val progress = snapshot.progress
        val isSynced = snapshot.isSynced

        AccountStatusResponse(
            address = addr,
            isRegistered = _isRegistered.value,
            tipNumber = tipNumber.toString(),
            syncedToBlock = scriptBlockNumber.toString(),
            syncProgress = progress.coerceIn(0.0, 1.0),
            isSynced = isSynced
        )
    }

    suspend fun getCells(address: String? = null, limit: Int = 100, cursor: String? = null): Result<CellsResponse> = runCatching {
        // If a caller passes an address, honor it — decode to script. This is what
        // mutex-guarded send paths rely on: the snapshot taken at the top of
        // prepareAndSend is authoritative even if _walletInfo.value mutates while
        // we're holding the mutex (wallet switch). Falls back to active wallet
        // for back-compat callers that don't pass address.
        val script = if (address != null) {
            AddressUtils.parseAddress(address)
                ?: throw Exception("Invalid address: $address")
        } else {
            _walletInfo.value?.script ?: throw Exception("No wallet")
        }
        ledgerReader.getCells(script, limit, cursor).getOrThrow()
    }

    private suspend fun currentTipNumberOrZero(): Long = lightClient.currentTipNumberOrZero()

    /**
     * ALL spent outpoints for a script, walking the transaction cursor to the
     * end. The previous single limit=100 page silently truncated the spent
     * set for wallets with >100 transactions: cell selection then picked
     * already-spent cells, every send failed local verification with a
     * "network rejected" error that survived reinstall (it re-derives from
     * the same chain data), and the balance math subtracted the wrong cells
     * (Alex, Telegram 2026-07, ~646k CKB of history). Page cap is a runaway
     * guard, far above real usage; truncation past it is logged, never silent.
     */
    private suspend fun fetchAllSpentOutpoints(searchKeyJson: String): MutableSet<String> =
        ledgerReader.fetchAllSpentOutpoints(searchKeyJson)

    // ========================================
    // Send (delegated to the shared [SendPipeline], M3 #5)
    // ========================================
    //
    // The pipeline owns the send mutex, the session-broadcast set, cell
    // reservation, the pre-broadcast rows and the broadcast itself. What stays
    // here is the sender snapshot: the network, the active wallet's id and lock
    // script, the sync flag and the repository scope, bundled by [sendContext].

    /** @see SendPipeline.previewTransfer */
    suspend fun previewTransfer(
        fromAddress: String,
        recipients: List<RecipientOutput>,
    ): TransferPlan = sendPipeline.previewTransfer(sendContext(), fromAddress, recipients)

    /**
     * Plain secp256k1 transfer. fromAddress is the authoritative sender
     * identity (captured by SendViewModel before this call) and is trusted
     * over live repository globals.
     *
     * The key is wrapped in a [PrivateKeySigner] and NOT wiped here: the
     * caller still owns it (SendViewModel zeroes it in a `finally`).
     */
    suspend fun prepareAndSend(
        fromAddress: String,
        toAddress: String,
        amountShannons: Long,
        privateKey: ByteArray,
        /** Fee the user confirmed on the review sheet; the send aborts if the build no longer matches it (#490). */
        expectedFeeShannons: Long? = null,
    ): Result<String> = sendPipeline.prepareAndSend(
        ctx = sendContext(),
        fromAddress = fromAddress,
        toAddress = toAddress,
        amountShannons = amountShannons,
        signer = PrivateKeySigner(privateKey),
        expectedFeeShannons = expectedFeeShannons,
    )

    /** @see SendPipeline.prepareAndSendBulk */
    suspend fun prepareAndSendBulk(
        fromAddress: String,
        recipients: List<RecipientOutput>,
        privateKey: ByteArray
    ): Result<String> = sendPipeline.prepareAndSendBulk(
        ctx = sendContext(),
        fromAddress = fromAddress,
        recipients = recipients,
        signer = PrivateKeySigner(privateKey),
    )

    /** @see SendPipeline.retryBroadcast */
    suspend fun retryBroadcast(txHash: String): Result<String> =
        sendPipeline.retryBroadcast(sendContext(), txHash)

    /** @see SendPipeline.sendTransaction */
    suspend fun sendTransaction(
        transaction: Transaction,
        expectedWalletId: String? = null,
        pendingFeeShannons: Long? = null,
    ): Result<String> = sendPipeline.sendTransaction(
        ctx = sendContext(),
        transaction = transaction,
        expectedWalletId = expectedWalletId,
        pendingFeeShannons = pendingFeeShannons,
    )

    /** @see SendPipeline.buildReserveAndSend */
    private suspend fun buildReserveAndSend(
        fromAddress: String,
        pendingDirection: String = "out",
        pendingAmountShannons: Long? = null,
        pendingFeeShannons: Long? = null,
        build: (availableCells: List<Cell>, network: NetworkType) -> Transaction
    ): String = sendPipeline.buildReserveAndSend(
        ctx = sendContext(),
        fromAddress = fromAddress,
        pendingDirection = pendingDirection,
        pendingAmountShannons = pendingAmountShannons,
        pendingFeeShannons = pendingFeeShannons,
        build = build,
    )

    suspend fun getTransactions(limit: Int = 50, cursor: String? = null): Result<TransactionsResponse> =
        ledgerReader.getTransactions(
            activeScript = _walletInfo.value?.script,
            activeWalletId = activeWalletId,
            network = currentNetwork,
            limit = limit,
            cursor = cursor,
        )

    suspend fun getTransactionStatus(txHash: String): Result<TransactionStatusResponse> =
        ledgerReader.getTransactionStatus(txHash)

    suspend fun getGatewayStatus(): Result<StatusResponse> = runCatching {
        StatusResponse(currentNetwork.name.lowercase(), "0x0", "0x0", 0, false, true)
    }

    fun getCurrentAddress(): String? {
        val info = _walletInfo.value ?: return null
        return when (currentNetwork) {
            NetworkType.TESTNET -> info.testnetAddress
            NetworkType.MAINNET -> info.mainnetAddress
        }
    }

    suspend fun getPrivateKey(): ByteArray {
        return if (activeWalletId.isNotEmpty()) {
            keyManager.getPrivateKeyForWallet(activeWalletId)
                ?: throw IllegalStateException("No key found for active wallet $activeWalletId")
        } else {
            keyManager.getPrivateKey() // legacy single-wallet only
        }
    }

    /**
     * Get the current block number from the registered script in the light client.
     * This represents how far the light client has synced for our wallet.
     * In multi-wallet mode, matches the active wallet's script by lock args.
     */
    private fun getExistingScriptBlock(): Long =
        ledgerReader.existingScriptBlock(_walletInfo.value?.script?.args)

    // Diagnostic / read-only JNI passthroughs moved to [LightClientReadOnly]
    // (#106 phase 4). Thin shims kept so NodeStatusViewModel and other
    // consumers don't need a constructor change.
    suspend fun getPeers(): String? = lightClient.getPeers()
    suspend fun getTipHeader(): String? = lightClient.getTipHeader()
    suspend fun getScripts(): String? = lightClient.getScripts()
    suspend fun callRpc(method: String): String? = lightClient.callRpc(method)

    // ========================================
    // DAO Operations
    // ========================================

    suspend fun getCurrentEpoch(): Result<EpochInfo> = lightClient.getCurrentEpoch()

    // DAO chain-state helpers moved to [DaoHeaderResolver] (#106 phase 2).
    // Thin shims kept so internal call sites stay unchanged. getOrFetchHeader's
    // network arg is supplied here; resolver itself is network-agnostic.
    private suspend fun getBlockHashForCell(txHash: String): String? =
        daoHeaderResolver.getBlockHashForCell(txHash)

    private suspend fun getOrFetchHeader(blockHash: String): JniHeaderView? =
        daoHeaderResolver.getOrFetchHeader(blockHash, currentNetwork)

    suspend fun getDaoDeposits(): Result<List<DaoDeposit>> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val currentEpoch = getCurrentEpoch().getOrNull()
        val live = daoDepositReader.list(info.script, currentEpoch, currentNetwork)
        // #357: drop a spent deposit's stale DEPOSITED entry that the light
        // client still lists alongside its new withdrawing cell, before the
        // pending-withdraw overlay would paint it a duplicate "Confirming…".
        val deduped = dedupeWithdrawnDeposits(live)
        applyPendingWithdrawOverlay(mergeWithCachedDaoDeposits(deduped))
    }

    /**
     * #347: overlay in-flight phase-1 withdraws onto the deposit list. The
     * deposit cell scans as DEPOSITED until the withdraw commits and is
     * indexed, so without this a just-withdrawn deposit looks withdrawable
     * again (double-withdraw) and the only "withdrawing" signal — the
     * in-memory banner — is lost on restart. The persisted marker carries the
     * state across process death; [resolvePendingWithdraw] decides per marker
     * whether to overlay WITHDRAWING, or retire it (committed / failed).
     */
    private suspend fun applyPendingWithdrawOverlay(deposits: List<DaoDeposit>): List<DaoDeposit> {
        val walletId = activeWalletId
        if (walletId.isEmpty()) return deposits
        val network = currentNetwork.name
        val withdrawDao = appDatabase.pendingDaoWithdrawDao()
        val pending = runCatching { withdrawDao.getByWalletAndNetwork(walletId, network) }
            .getOrDefault(emptyList())
        if (pending.isEmpty()) return deposits

        fun key(txHash: String, index: String) = "$txHash:$index"
        val depositedKeys = deposits
            .filter { it.status == DaoCellStatus.DEPOSITED }
            .map { key(it.outPoint.txHash, it.outPoint.index) }
            .toSet()

        val overlayKeys = mutableSetOf<String>()
        for (p in pending) {
            val k = key(p.depositTxHash, p.depositIndex)
            val stillDeposited = k in depositedKeys
            val txStatus = runCatching {
                appDatabase.transactionDao().getByTxHash(p.withdrawTxHash)?.status
            }.getOrNull()
            when (resolvePendingWithdraw(stillDeposited, txStatus)) {
                PendingWithdrawResolution.OVERLAY -> if (stillDeposited) overlayKeys.add(k)
                PendingWithdrawResolution.CLEAR_CONFIRMED,
                PendingWithdrawResolution.CLEAR_FAILED ->
                    runCatching { withdrawDao.deleteByDeposit(p.depositTxHash, p.depositIndex) }
            }
        }
        if (overlayKeys.isEmpty()) return deposits
        return deposits.map {
            if (key(it.outPoint.txHash, it.outPoint.index) in overlayKeys) {
                it.copy(status = DaoCellStatus.WITHDRAWING)
            } else it
        }
    }

    /**
     * Deposit outpoints with an in-flight withdraw (#347) — used by the DAO
     * screen to rehydrate the "Withdrawing from DAO…" banner after restart.
     */
    suspend fun getInFlightWithdrawOutPoints(): List<OutPoint> {
        val walletId = activeWalletId.takeIf { it.isNotEmpty() } ?: return emptyList()
        return runCatching {
            appDatabase.pendingDaoWithdrawDao()
                .getByWalletAndNetwork(walletId, currentNetwork.name)
                .map { OutPoint(it.depositTxHash, it.depositIndex) }
        }.getOrDefault(emptyList())
    }

    /**
     * #332 windowing recovery. The light client only indexes cells created
     * AFTER the script's registered start block, so a DAO deposit older than
     * the chosen sync window silently vanishes from both the deposit list and
     * the balance. Mitigation:
     *  1. write-through: every live scan persists its deposits to dao_cells;
     *  2. reconcile: cached active deposits INSIDE the window that the live
     *     scan no longer returns were spent/unlocked — mark COMPLETED;
     *  3. merge: cached active deposits from BEFORE the window are appended,
     *     flagged [DaoDeposit.outsideSyncWindow] so the UI can offer the
     *     deeper-rescan recovery instead of pretending they don't exist.
     */
    private suspend fun mergeWithCachedDaoDeposits(live: List<DaoDeposit>): List<DaoDeposit> {
        val walletId = activeWalletId
        if (walletId.isEmpty()) return live
        val network = currentNetwork.name
        val nowMs = System.currentTimeMillis()

        runCatching {
            daoSyncManager.upsertDaoCells(live.map { it.toDaoCellEntity(network, walletId, nowMs) })
        }.onFailure { logger.w(TAG, "DAO write-through failed: ${it.message}") }

        val windowStart = getExistingScriptBlock()
        val liveKeys = live.map { "${it.outPoint.txHash}:${it.outPoint.index}" }.toSet()

        // #434: outpoints consumed by a live withdrawing cell's phase-1 tx.
        // A cached deposit whose outpoint is here was spent by a withdraw and
        // MUST be retired — never resurfaced as an outside-window entry.
        // getExistingScriptBlock() returns the script's current sync head, not
        // its registration start, so a just-spent recent deposit (block now
        // behind the head) would otherwise fall into the `< windowStart` branch,
        // reappear under "made before this wallet's sync window", and double the
        // DAO total right after a withdraw confirms. Index formats are
        // normalized (hex vs decimal) so the outpoint match is reliable.
        fun normKey(txHash: String, index: String) =
            "${txHash.lowercase()}:${index.removePrefix("0x").toLongOrNull(16) ?: index}"
        val consumedByLive = live.flatMap { it.consumedDepositOutPoints }
            .map { normKey(it.txHash, it.index) }
            .toSet()

        val cached = runCatching { daoSyncManager.getActiveDeposits(network, walletId) }
            .getOrDefault(emptyList())

        val outsideWindow = mutableListOf<DaoDeposit>()
        for (entity in cached) {
            val key = "${entity.txHash}:${entity.index}"
            if (key in liveKeys) continue
            // DEPOSITING rows are optimistic pre-confirmation inserts with
            // blockNumber 0 — not windowing victims; leave them alone.
            if (entity.status == DaoCellStatus.DEPOSITING.name) continue
            if (normKey(entity.txHash, entity.index) in consumedByLive) {
                // Spent by a live withdraw — retire so it neither resurfaces as
                // an outside-window entry nor double-counts the DAO total (#434).
                runCatching {
                    daoSyncManager.updateStatus(entity.txHash, entity.index, DaoCellStatus.COMPLETED.name)
                }
            } else if (windowStart > 0 && entity.depositBlockNumber in 1 until windowStart) {
                outsideWindow += entity.toOutsideWindowDeposit()
            } else {
                // Inside the window yet absent from the live scan: the cell
                // was spent (withdrawn/unlocked) — retire the cached row so it
                // doesn't resurrect.
                runCatching {
                    daoSyncManager.updateStatus(entity.txHash, entity.index, DaoCellStatus.COMPLETED.name)
                }
            }
        }
        if (outsideWindow.isNotEmpty()) {
            logger.i(TAG, "DAO merge: ${outsideWindow.size} cached deposit(s) predate sync window (start=$windowStart)")
        }
        return live + outsideWindow
    }

    /**
     * User-confirmed deeper rescan to re-index DAO deposits that predate the
     * current sync window (#332). Rewinds the wallet's lock script to just
     * before the oldest cached out-of-window deposit. Multi-hour cost on
     * mainnet — callers must gate behind an explicit confirmation dialog.
     * Returns the rewind target block.
     */
    suspend fun rescanForOlderDaoDeposits(): Result<Long> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val walletId = activeWalletId.takeIf { it.isNotEmpty() } ?: throw Exception("No active wallet")
        val windowStart = getExistingScriptBlock()
        val oldest = daoSyncManager.getActiveDeposits(currentNetwork.name, walletId)
            .filter { it.status != DaoCellStatus.DEPOSITING.name }
            .filter { windowStart > 0 && it.depositBlockNumber in 1 until windowStart }
            .minOfOrNull { it.depositBlockNumber }
            ?: throw Exception("No deposits older than the current sync window")
        val target = (oldest - 100).coerceAtLeast(0L)
        val ok = setScriptsAndRecord(
            listOf(
                JniScriptStatus(
                    script = info.script,
                    scriptType = "lock",
                    blockNumber = "0x${target.toString(16)}"
                )
            ),
            listOf(walletId),
            LightClientNative.CMD_SET_SCRIPTS_PARTIAL,
            allowRewind = true, // explicitly user-initiated rewind
        )
        if (!ok) throw Exception("Light client refused script registration")
        logger.i(TAG, "DAO deep rescan: rewound script to block $target (oldest cached deposit at $oldest)")
        target
    }


    suspend fun getDaoOverview(): Result<DaoOverview> = runCatching {
        val deposits = getDaoDeposits().getOrThrow()
        val active = deposits.filter { it.status != DaoCellStatus.COMPLETED }
        val completed = deposits.filter { it.status == DaoCellStatus.COMPLETED }

        // Capacity-weighted average APC from deposits that have APC data
        val depositsWithApc = active.filter { it.apc > 0.0 }
        val weightedApc = if (depositsWithApc.isNotEmpty()) {
            val totalCap = depositsWithApc.sumOf { it.capacity }.toDouble()
            depositsWithApc.sumOf { it.apc * it.capacity } / totalCap
        } else 2.47 // fallback until headers are available

        DaoOverview(
            totalLocked = active.sumOf { it.capacity },
            totalCompensation = deposits.sumOf { it.compensation },
            currentApc = weightedApc,
            activeCount = active.size,
            completedCount = completed.size
        )
    }

    suspend fun depositToDao(amountShannons: Long): Result<String> =
        getPrivateKey().let { key ->
            try {
                depositToDao(amountShannons, privateKey = key)
            } finally {
                key.fill(0) // transient signing key — zero after use (#321)
            }
        }

    /**
     * V2-aware overload: caller supplies the private key already
     * unlocked via BiometricPrompt CryptoObject. Used by [DaoViewModel]
     * when the active wallet is on `kdfVersion=2` and requires explicit
     * user authentication per signing operation (#213 sub-PR 5).
     */
    suspend fun depositToDao(
        amountShannons: Long,
        privateKey: ByteArray,
    ): Result<String> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val address = getCurrentAddress() ?: throw Exception("No address")

        require(amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
            "Minimum deposit is ${DaoConstants.MIN_DEPOSIT_SHANNONS / 100_000_000} CKB"
        }

        // Route through the shared mutex + reservation filter (#320) so a deposit
        // can't select inputs already reserved by an in-flight transfer.
        val txHash = buildReserveAndSend(
            address,
            // #433: pending deposit reads "Dao Deposit <amount> CKB" (matching the
            // confirmed row) rather than a generic "Sent" carrying amount + fee.
            pendingDirection = "dao_deposit",
            pendingAmountShannons = amountShannons,
            // The builder prices the deposit from the tx it actually builds, so
            // the pending row quotes the same estimator for the common
            // one-input shape instead of the DEFAULT_FEE reservation, which
            // over-reported the fee 100x until the confirmed row replaced it
            // (#490). Corrected by the confirmed record either way.
            pendingFeeShannons = transactionBuilder.estimateTransferFee(
                inputCount = 1,
                outputCount = 2,
            ),
        ) { availableCells, net ->
            transactionBuilder.buildDaoDeposit(
                amountShannons = amountShannons,
                availableCells = availableCells,
                senderScript = info.script,
                privateKey = privateKey,
                network = net
            )
        }
        logger.d(TAG, "DAO deposit sent: $txHash")

        // Track pending deposit in Room so UI shows it before JNI confirms
        daoSyncManager.insertPendingDeposit(txHash, amountShannons, currentNetwork.name, walletId = activeWalletId)

        txHash
    }

    suspend fun withdrawFromDao(depositOutPoint: OutPoint): Result<String> =
        getPrivateKey().let { key ->
            try {
                withdrawFromDao(depositOutPoint, privateKey = key)
            } finally {
                key.fill(0) // transient signing key — zero after use (#321)
            }
        }

    /** V2-aware overload — see [depositToDao]. */
    suspend fun withdrawFromDao(
        depositOutPoint: OutPoint,
        privateKey: ByteArray,
    ): Result<String> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val address = getCurrentAddress() ?: throw Exception("No address")

        // Find the deposit cell
        val deposits = getDaoDeposits().getOrThrow()
        val deposit = deposits.find { it.outPoint == depositOutPoint }
            ?: throw Exception("Deposit not found")

        require(deposit.depositBlockHash.isNotBlank()) {
            "Deposit block hash unavailable. Please retry after sync."
        }

        // Build a Cell from the deposit for the transaction builder. The
        // deposit cell itself is a DAO (typed) cell and is NOT in getCells'
        // output, so it isn't subject to the regular-cell reservation filter.
        val depositCell = Cell(
            outPoint = deposit.outPoint,
            capacity = "0x${deposit.capacity.toString(16)}",
            blockNumber = "0x${deposit.depositBlockNumber.toString(16)}",
            lock = info.script,
            type = DaoConstants.DAO_TYPE_SCRIPT,
            data = "0x" + DaoConstants.DAO_DEPOSIT_DATA.joinToString("") { "%02x".format(it) }
        )

        // Route through the shared mutex + reservation filter (#320). DAO Phase 1
        // preserves the deposit capacity exactly, so a regular fee input cell is
        // mandatory (#119) — `availableCells` is the reservation-filtered regular
        // CKB set, ensuring the fee cell isn't one an in-flight transfer reserved.
        val txHash = buildReserveAndSend(
            address,
            // #433: show the pending withdraw as "Dao Withdraw <deposit> CKB"
            // with the fee on its own line, not as a "-0.001 Sent". Phase-1
            // preserves the deposit capacity exactly, so the fee is whatever
            // the fee cell pays: the same dynamic estimate the builder uses,
            // for the common deposit-plus-one-fee-cell shape (#490).
            pendingDirection = "dao_withdraw",
            pendingAmountShannons = deposit.capacity,
            pendingFeeShannons = transactionBuilder.estimateTransferFee(
                inputCount = 2,
                outputCount = 2,
            ),
        ) { availableCells, net ->
            transactionBuilder.buildDaoWithdraw(
                depositCell = depositCell,
                depositBlockNumber = deposit.depositBlockNumber,
                depositBlockHash = deposit.depositBlockHash,
                senderScript = info.script,
                privateKey = privateKey,
                network = net,
                availableCells = availableCells
            )
        }
        logger.d(TAG, "DAO withdraw (phase 1) sent: $txHash")

        // #347: persist the in-flight withdraw so the deposit renders as
        // WITHDRAWING ("Confirming…") across restart and can't be withdrawn
        // twice. Cleared by applyPendingWithdrawOverlay on commit/failure.
        runCatching {
            appDatabase.pendingDaoWithdrawDao().upsert(
                com.rjnr.pocketnode.data.database.entity.PendingDaoWithdrawEntity(
                    depositTxHash = depositOutPoint.txHash,
                    depositIndex = depositOutPoint.index,
                    withdrawTxHash = txHash,
                    walletId = activeWalletId,
                    network = currentNetwork.name,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { logger.w(TAG, "Failed to persist pending withdraw marker: ${it.message}") }

        txHash
    }

    suspend fun unlockDao(withdrawingOutPoint: OutPoint): Result<String> =
        getPrivateKey().let { key ->
            try {
                unlockDao(withdrawingOutPoint, privateKey = key)
            } finally {
                key.fill(0) // transient signing key — zero after use (#321)
            }
        }

    /** V2-aware overload — see [depositToDao]. */
    suspend fun unlockDao(
        withdrawingOutPoint: OutPoint,
        privateKey: ByteArray,
    ): Result<String> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val net = currentNetwork

        val deposits = getDaoDeposits().getOrThrow()
        val deposit = deposits.find { it.outPoint == withdrawingOutPoint }
            ?: throw Exception("Withdrawing cell not found")

        require(deposit.status == DaoCellStatus.UNLOCKABLE) {
            "Cell is not unlockable yet (status: ${deposit.status})"
        }

        // Use the deposit object's hashes — it is the single source of truth
        val depositBlockHash = deposit.depositBlockHash
        require(depositBlockHash.isNotBlank()) {
            "Deposit block hash unavailable. Please retry after sync."
        }
        val withdrawBlockHash = deposit.withdrawBlockHash
            ?: throw Exception("Withdraw block hash unavailable. Please retry after sync.")

        // Get headers for max withdraw calculation (cache-first)
        val depositHeader = getOrFetchHeader(depositBlockHash)
            ?: throw Exception("Failed to get deposit header")

        val withdrawHeader = getOrFetchHeader(withdrawBlockHash)
            ?: throw Exception("Failed to get withdraw header")

        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
            depositHeader.dao,
            withdrawHeader.dao,
            deposit.capacity,
            DaoConstants.DEPOSIT_OCCUPIED_SHANNONS
        )
        if (maxWithdraw < 0) throw Exception("Failed to calculate max withdraw capacity")

        val sinceValue = LightClientNative.nativeCalculateUnlockEpoch(
            depositHeader.epoch,
            withdrawHeader.epoch
        ) ?: throw Exception("Failed to calculate unlock epoch")

        val withdrawingCell = Cell(
            outPoint = deposit.outPoint,
            capacity = "0x${deposit.capacity.toString(16)}",
            blockNumber = "0x${(deposit.withdrawBlockNumber ?: throw Exception("No withdraw block")).toString(16)}",
            lock = info.script,
            type = DaoConstants.DAO_TYPE_SCRIPT
        )

        val tx = transactionBuilder.buildDaoUnlock(
            withdrawingCell = withdrawingCell,
            maxWithdraw = maxWithdraw,
            sinceValue = sinceValue,
            depositBlockHash = depositBlockHash,
            withdrawBlockHash = withdrawBlockHash,
            senderScript = info.script,
            privateKey = privateKey,
            network = net
        )

        // The unlock's fee is knowable exactly here and NOWHERE ELSE (#497).
        // On-chain the tx reads: one input whose declared capacity is the
        // original deposit, one output worth maxWithdraw − fee. Since
        // maxWithdraw = deposit + compensation, inputs − outputs comes out as
        // fee − compensation, i.e. negative, and the confirmed-path formula
        // can never score it. Recovering the compensation from the confirmed
        // transaction would take two header fetches plus a JNI
        // calculateMaxWithdraw per row, and would first have to decode the
        // witness input_type to learn WHICH header dep is the deposit (the
        // order is not load-bearing — see TransactionBuilder.buildDaoUnlock),
        // which we cannot rely on for a tx we did not build. So the planned
        // fee is persisted on the pending row and carried forward by
        // CacheManager; an unlock with no recorded fee hides the row rather
        // than promising a "Pending" that would never resolve.
        val plannedFeeShannons = daoUnlockFeeShannons(
            maxWithdraw = maxWithdraw,
            outputCapacities = tx.cellOutputs.map {
                it.capacity.removePrefix("0x").toLongOrNull(16)
            },
        )

        // Unlock consumes only the withdrawing DAO cell (typed; never returned by
        // getCells, so no transfer can select it) and pays the fee from that
        // cell's own capacity — it selects no regular cells, so the
        // reservation filter doesn't apply. sendTransaction still reserves this
        // input and serializes the pre-broadcast insert under sendMutex (#320).
        val txHash = sendTransaction(tx, pendingFeeShannons = plannedFeeShannons).getOrThrow()
        logger.d(TAG, "DAO unlock (phase 2) sent: $txHash")
        txHash
    }

    // Sync registration + BALANCED filter delegated to [SyncCoordinator] (#106).
    private fun makeSyncContext(): SyncCoordinator.SyncContext = SyncCoordinator.SyncContext(
        network = currentNetwork,
        activeWalletId = activeWalletId,
        awaitNodeReady = ::awaitNodeReady,
        getWalletSyncBlock = { walletId -> getWalletSyncBlock(walletId) },
        onScriptsRegistered = { _isRegistered.value = true },
    )

    private suspend fun setScriptsAndRecord(
        statuses: List<JniScriptStatus>,
        walletIds: List<String>,
        cmd: Int,
        allowRewind: Boolean = false,
    ): Boolean = syncCoordinator.setScriptsAndRecord(statuses, walletIds, cmd, currentNetwork, allowRewind)

    private suspend fun maybeReregisterBalanced() {
        syncCoordinator.maybeReregisterBalanced(makeSyncContext())
    }

    private suspend fun registerAllWalletScripts(
        preFetchedWallets: List<com.rjnr.pocketnode.data.storage.WalletRecord>? = null,
        preFilteredCandidates: List<com.rjnr.pocketnode.data.storage.WalletRecord>? = null,
    ) {
        syncCoordinator.registerAllWalletScripts(
            ctx = makeSyncContext(),
            preFetchedWallets = preFetchedWallets,
            preFilteredCandidates = preFilteredCandidates,
        )
    }

    // ========================================
    // Sync Progress Polling
    // ========================================

    // One rescue rescan per wallet per process (#332) — the rescan itself
    // takes hours on a long-history wallet; re-firing restarts it.
    private val balanceRescanAttempted =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // The session-broadcast set moved to SendPipeline with the send path (M3 #5).

    /** @see SyncEngine.start */
    fun startSyncPolling() = syncEngine.start(scope, this)

    /** @see SyncEngine.stop */
    fun stopSyncPolling() = syncEngine.stop()

    // ========================================
    // Background Sync Service
    // ========================================

    /**
     * Start the foreground sync service if background sync is enabled.
     */
    fun startBackgroundSync() {
        // Play build ships no foreground service (Google FGS policy rejected the
        // dataSync justification, #338). The flag is false only on `playRelease`;
        // the GitHub/website release keeps the FGS. Guard here, the single call
        // site, so the manifest overlay that strips FOREGROUND_SERVICE_* can never
        // be paired with a startForegroundService() that would then crash.
        if (!BuildConfig.BG_FGS_ENABLED) {
            logger.d(TAG, "FGS compiled out for this build (Play); sync stays foreground-first")
            return
        }
        if (!walletPreferences.isBackgroundSyncEnabled()) {
            logger.d(TAG, "Background sync disabled, not starting service")
            return
        }
        logger.d(TAG, "Starting background sync service")
        syncServiceCommands.start()
    }

    /**
     * Stop the foreground sync service.
     */
    fun stopBackgroundSync() {
        logger.d(TAG, "Stopping background sync service")
        syncServiceCommands.stop()
    }

    companion object {
        private const val TAG = "GatewayRepository"

        /**
         * Cell cursor-walk cap — a runaway guard far above real usage
         * (100 cells/page → 5k cells). Hitting it is logged. The transaction
         * counterpart moved to [LedgerReader] with the read path (M3 #4).
         */
        private const val MAX_CELL_PAGES = 50

        // BROADCAST_ERROR_PREFIX moved to SendPipeline with the send path (M3 #5).

        // MAX_CONCURRENT_WALLET_SCRIPTS + BALANCED_LAG_THRESHOLD moved to
        // SyncCoordinator (#106). Tests now import SyncCoordinator.BALANCED_LAG_THRESHOLD
        // directly.
    }
}
