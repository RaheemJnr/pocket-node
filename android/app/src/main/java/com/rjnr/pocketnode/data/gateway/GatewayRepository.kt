package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.BuildConfig
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.sync.SyncEngine
import com.rjnr.pocketnode.data.sync.contract.SyncServiceCommands
import com.rjnr.pocketnode.data.migration.WalletMigrationHelper
import com.rjnr.pocketnode.data.transaction.PrivateKeySigner
import com.rjnr.pocketnode.data.send.SendContext
import com.rjnr.pocketnode.data.send.SendPipeline
import com.rjnr.pocketnode.data.transaction.RecipientOutput
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.transaction.TransferPlan
import com.rjnr.pocketnode.data.wallet.GapLimitStatus
import com.rjnr.pocketnode.data.wallet.GapLimitSweepPreview
import com.rjnr.pocketnode.data.wallet.KeyManager
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
import kotlinx.coroutines.withContext
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
    private val cacheManager: CacheManager,
    private val walletMigrationHelper: WalletMigrationHelper,
    private val walletDao: WalletDao,
    private val appDatabase: AppDatabase,
    private val syncProgressDao: SyncProgressDao,
    // The shared send path (M3 #5): preview, build, reserve, broadcast, retry.
    private val sendPipeline: SendPipeline,
    private val syncCoordinator: SyncCoordinator,
    // The DAO surface (#460): deposits, rescan, deposit/withdraw/unlock.
    private val daoGateway: DaoGateway,
    // Gap-limit recovery (#382): banner, deep scan, sweep.
    private val gapLimitGateway: GapLimitGateway,
    private val lightClient: LightClientReadOnly,
    // The shared read path (M3 #4): balance, cells, history, tx status.
    private val ledgerReader: LedgerReader,
    private val subAccountReconciler: com.rjnr.pocketnode.data.wallet.SubAccountReconciler,
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
            SyncStrategy.ALL_WALLETS, SyncStrategy.BALANCED -> registerAllWalletsQuietlyIfSuperseded()
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
                registerAllWalletsQuietlyIfSuperseded()
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
        forceResync: Boolean = false,
        // The wallet the caller meant to register. When set and the active
        // wallet changed while this call waited for the node, fail instead
        // of registering (and saving the caller's mode onto) another wallet.
        expectedWalletId: String? = null,
    ): Result<Unit> = runCatching {
        // Force IO dispatcher — see registerAllWalletScripts above for the same
        // reasoning. ACTIVE_ONLY callers also block Main without this. (#109)
        withContext(Dispatchers.IO) {
        // Wait for node to be ready
        if (!awaitNodeReady()) {
             throw Exception("Node initialization failed")
        }

        // Read the wallet id and its script together (no suspension between
        // them) and use this pair for the rest of the call, so a wallet
        // switch mid-registration cannot mix two wallets' state.
        val walletId = activeWalletId
        val info = _walletInfo.value ?: throw Exception("Wallet not initialized")
        if (expectedWalletId != null && walletId != expectedWalletId) {
            throw ActiveWalletChangedException()
        }

        val tipStr = lightClient.getTipHeader()
        val tipHeight = if (tipStr != null) {
            val tip = json.decodeFromString<JniHeaderView>(tipStr)
            tip.number.removePrefix("0x").toLongOrNull(16) ?: 0L
        } else 0L

        // Check for existing sync progress to resume from (per-wallet)
        val savedBlock = getWalletSyncBlock(walletId)
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
            syncCoordinator.earliestCachedTxBlock(walletId, currentNetwork.name),
            tipHeight,
        ).toString(16)
        val candidateRegistrations = syncCoordinator.pendingCandidateStatuses(
            walletId, info.script, candidateHex
        )
        val result = setScriptsAndRecord(
            scriptStatuses + candidateRegistrations.map { it.status },
            listOf(walletId) + candidateRegistrations.map { "" },
            LightClientNative.CMD_SET_SCRIPTS_ALL,
            // Re-checked under the registration mutex, atomically with the
            // set: a switch after the check above must not be overwritten.
            beforeSet = {
                if (expectedWalletId != null && activeWalletId != expectedWalletId) {
                    throw ActiveWalletChangedException()
                }
            },
        )
        if (!result) throw Exception("Failed to set scripts")

        // #382: record each candidate's scan-from block — the reconciler's
        // EMPTY coverage gate stays inert while registeredFromBlock is 0.
        syncCoordinator.recordCandidateRegistrations(candidateRegistrations)

        _isRegistered.value = true
        if (savePreference) {
            val wId = walletId.ifEmpty { null }
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
        // The wallet this resync is for, read once: every step below can
        // suspend (registration may wait seconds for the tip), and the user
        // can switch wallets meanwhile (Settings closes its sheet at once).
        // Reading activeWalletId again later would reset, register or roll
        // back whichever wallet happens to be active by then.
        val walletId = activeWalletId
        _isRegistered.value = false
        // Snapshot for the rollbacks below.
        val previousSyncBlock = getWalletSyncBlock(walletId)
        // Re-arm the zero-cell rescue rescan: an explicit resync is the user
        // deliberately asking us to look again (knmo).
        walletPreferences.clearZeroCellRescanDone(walletId)
        balanceRescanAttempted.remove(walletId)
        return when (walletPreferences.getSyncStrategy()) {
            // registerAccount issues CMD_SET_SCRIPTS_ALL with only the active
            // wallet's scripts, which under ALL_WALLETS / BALANCED unregistered
            // every other wallet on each sync-mode change (#431). Re-register
            // the whole set instead.
            SyncStrategy.ALL_WALLETS, SyncStrategy.BALANCED -> {
                val wId = walletId.ifEmpty { null }
                val previousMode = walletPreferences.getSyncModeOrNull(walletId = wId)
                val previousHeight = walletPreferences.getCustomBlockHeight(walletId = wId)
                val previousInitialSync = walletPreferences.hasCompletedInitialSync(walletId = wId)
                // Nothing about this wallet is persisted until the set is
                // certain to land: a concurrent registration (a wallet
                // switch) must still see its saved progress and old mode, or
                // it registers the wallet from the new start and a stale
                // abort here leaves the light client rewound while the prefs
                // say otherwise. The new mode reaches the start-block
                // computation through the context instead, and forceResync
                // for this wallet only reads its progress as 0; other
                // wallets resume.
                var persisted = false
                runCatching {
                    syncCoordinator.registerAllWalletScripts(
                        ctx = makeSyncContext().copy(
                            getWalletSyncBlock = { id ->
                                if (id == walletId) 0L else getWalletSyncBlock(id)
                            },
                            syncModeOverride = { id ->
                                if (id == walletId) syncMode to customBlockHeight else null
                            },
                            // Under the registration mutex, right before the
                            // set. If the user switched wallets while this
                            // waited, the switch's own registration stands:
                            // abort with nothing written. Otherwise persist
                            // the choice atomically with the set.
                            beforeSetScripts = {
                                if (activeWalletId != walletId) throw ActiveWalletChangedException()
                                setWalletSyncBlock(walletId, 0L)
                                walletPreferences.setSyncMode(syncMode, walletId = wId)
                                if (syncMode == SyncMode.CUSTOM) {
                                    walletPreferences.setCustomBlockHeight(customBlockHeight, walletId = wId)
                                }
                                walletPreferences.setInitialSyncCompleted(true, walletId = wId)
                                persisted = true
                            },
                        ),
                    )
                }.onFailure {
                    // Only a set that failed AFTER the writes needs undoing;
                    // an abort before them left everything as it was.
                    if (persisted) {
                        if (previousMode != null) {
                            walletPreferences.setSyncMode(previousMode, walletId = wId)
                        } else {
                            walletPreferences.clearSyncMode(walletId = wId)
                        }
                        walletPreferences.setCustomBlockHeight(previousHeight, walletId = wId)
                        walletPreferences.setInitialSyncCompleted(previousInitialSync, walletId = wId)
                        setWalletSyncBlock(walletId, previousSyncBlock)
                    }
                }
            }
            SyncStrategy.ACTIVE_ONLY -> {
                // Clear saved sync progress when explicitly resyncing (per-wallet)
                setWalletSyncBlock(walletId, 0L)
                registerAccount(
                    syncMode, customBlockHeight, savePreference = true, forceResync = true,
                    expectedWalletId = walletId,
                ).onFailure {
                    // Nothing was registered: put the progress back, unless
                    // the sync poll has already re-saved a newer block.
                    if (getWalletSyncBlock(walletId) == 0L) {
                        setWalletSyncBlock(walletId, previousSyncBlock)
                    }
                }
            }
        }
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

    // ========================================
    // Gap-limit recovery (delegated to [GapLimitGateway], #382 / #460)
    // ========================================

    private fun gapLimitContext(): GapLimitGateway.GapLimitContext = GapLimitGateway.GapLimitContext(
        network = { currentNetwork },
        walletId = { activeWalletId },
        activeScript = { _walletInfo.value?.script },
        savedSyncMode = { getSavedSyncMode() },
        savedCustomBlockHeight = { getSavedCustomBlockHeight() },
        getMnemonic = { getMnemonic() },
        sendContext = { sendContext() },
        registerAccountWithStrategy = { syncMode, customBlockHeight, savePreference ->
            registerAccountWithStrategy(syncMode, customBlockHeight, savePreference)
        },
    )

    /** @see GapLimitGateway.isGapLimitBannerVisible */
    fun isGapLimitBannerVisible(): Boolean =
        gapLimitGateway.isGapLimitBannerVisible(gapLimitContext())

    /** @see GapLimitGateway.dismissGapLimitBanner */
    fun dismissGapLimitBanner() =
        gapLimitGateway.dismissGapLimitBanner(gapLimitContext())

    /** @see GapLimitGateway.getGapLimitStatus */
    suspend fun getGapLimitStatus(): GapLimitStatus =
        gapLimitGateway.getGapLimitStatus(gapLimitContext())

    /** @see GapLimitGateway.runGapLimitScan */
    suspend fun runGapLimitScan(): Result<Int> =
        gapLimitGateway.runGapLimitScan(gapLimitContext())

    /** @see GapLimitGateway.runGapLimitScan */
    suspend fun runGapLimitScan(words: List<String>): Result<Int> =
        gapLimitGateway.runGapLimitScan(gapLimitContext(), words)

    /** @see GapLimitGateway.prepareGapLimitSweep */
    suspend fun prepareGapLimitSweep(): Result<GapLimitSweepPreview> =
        gapLimitGateway.prepareGapLimitSweep(gapLimitContext())

    /** @see GapLimitGateway.sweepGapLimitFunds */
    suspend fun sweepGapLimitFunds(): Result<String> =
        gapLimitGateway.sweepGapLimitFunds(gapLimitContext())

    /** @see GapLimitGateway.sweepGapLimitFunds */
    suspend fun sweepGapLimitFunds(words: List<String>): Result<String> =
        gapLimitGateway.sweepGapLimitFunds(gapLimitContext(), words)


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
        pendingDirection: String = "out",
    ): Result<String> = sendPipeline.sendTransaction(
        ctx = sendContext(),
        transaction = transaction,
        expectedWalletId = expectedWalletId,
        pendingFeeShannons = pendingFeeShannons,
        pendingDirection = pendingDirection,
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

    suspend fun getTransactions(limit: Int = 50, cursor: String? = null): Result<TransactionsResponse> {
        val walletId = activeWalletId
        val network = currentNetwork
        return ledgerReader.getTransactions(
            activeScript = _walletInfo.value?.script,
            activeWalletId = walletId,
            network = network,
            limit = limit,
            cursor = cursor,
            // Public #538: a gap-limit sweep's own hash and fee, recorded at
            // send time (GapLimitGateway), so its confirmed row reads "self"
            // with the fee rather than "Received".
            sweepFeeShannons = { hash -> walletPreferences.sweepFeeShannons(walletId, network.name, hash) },
        )
    }

    /**
     * @see LedgerReader.selfWalletLockArgsFor. Exposed for the wiring test,
     * as public main's GatewayRepository does (#538).
     */
    internal suspend fun selfWalletLockArgsFor(mainScriptArgs: String, walletId: String): Set<String> =
        ledgerReader.selfWalletLockArgsFor(mainScriptArgs, walletId, currentNetwork)

    suspend fun getTransactionStatus(txHash: String): Result<TransactionStatusResponse> =
        ledgerReader.getTransactionStatus(txHash)

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


    // ========================================
    // DAO Operations (delegated to [DaoGateway], #460)
    // ========================================
    //
    // The DAO bodies live on [DaoGateway]; what stays here is the context it
    // reads the repository's mutable state through, plus a forward per public
    // member so the DAO screen's API is unchanged.

    private fun daoContext(): DaoGateway.DaoContext = DaoGateway.DaoContext(
        network = { currentNetwork },
        walletId = { activeWalletId },
        walletInfo = { _walletInfo.value },
        currentAddress = { getCurrentAddress() },
        existingScriptBlock = { getExistingScriptBlock() },
        privateKey = { getPrivateKey() },
        sendContext = { sendContext() },
        transactionStatus = { hash -> getTransactionStatus(hash) },
        setScriptsAndRecord = { statuses, walletIds, cmd, allowRewind ->
            setScriptsAndRecord(statuses, walletIds, cmd, allowRewind)
        },
    )

    /** @see DaoGateway.getDaoDeposits */
    suspend fun getDaoDeposits(): Result<List<DaoDeposit>> =
        daoGateway.getDaoDeposits(daoContext())

    /** @see DaoGateway.getInFlightWithdrawOutPoints */
    suspend fun getInFlightWithdrawOutPoints(): List<OutPoint> =
        daoGateway.getInFlightWithdrawOutPoints(daoContext())

    /** @see DaoGateway.rescanForOlderDaoDeposits */
    suspend fun rescanForOlderDaoDeposits(): Result<Long> =
        daoGateway.rescanForOlderDaoDeposits(daoContext())

    /** @see DaoGateway.depositToDao */
    suspend fun depositToDao(amountShannons: Long): Result<String> =
        daoGateway.depositToDao(daoContext(), amountShannons)

    /** @see DaoGateway.depositToDao */
    suspend fun depositToDao(amountShannons: Long, privateKey: ByteArray): Result<String> =
        daoGateway.depositToDao(daoContext(), amountShannons, privateKey)

    /** @see DaoGateway.withdrawFromDao */
    suspend fun withdrawFromDao(depositOutPoint: OutPoint): Result<String> =
        daoGateway.withdrawFromDao(daoContext(), depositOutPoint)

    /** @see DaoGateway.withdrawFromDao */
    suspend fun withdrawFromDao(depositOutPoint: OutPoint, privateKey: ByteArray): Result<String> =
        daoGateway.withdrawFromDao(daoContext(), depositOutPoint, privateKey)

    /** @see DaoGateway.unlockPreflight */
    suspend fun unlockPreflight(withdrawingOutPoint: OutPoint): Result<Unit> =
        daoGateway.unlockPreflight(daoContext(), withdrawingOutPoint)

    /** @see DaoGateway.unlockDao */
    suspend fun unlockDao(withdrawingOutPoint: OutPoint): Result<String> =
        daoGateway.unlockDao(daoContext(), withdrawingOutPoint)

    /** @see DaoGateway.unlockDao */
    suspend fun unlockDao(withdrawingOutPoint: OutPoint, privateKey: ByteArray): Result<String> =
        daoGateway.unlockDao(daoContext(), withdrawingOutPoint, privateKey)


    // Sync registration + BALANCED filter delegated to [SyncCoordinator] (#106).
    private fun makeSyncContext(): SyncCoordinator.SyncContext = SyncCoordinator.SyncContext(
        network = currentNetwork,
        activeWalletId = activeWalletId,
        awaitNodeReady = ::awaitNodeReady,
        getWalletSyncBlock = { walletId -> getWalletSyncBlock(walletId) },
        onScriptsRegistered = { _isRegistered.value = true },
        liveActiveWalletId = { activeWalletId },
    )

    private suspend fun setScriptsAndRecord(
        statuses: List<JniScriptStatus>,
        walletIds: List<String>,
        cmd: Int,
        allowRewind: Boolean = false,
        beforeSet: suspend () -> Unit = {},
    ): Boolean = syncCoordinator.setScriptsAndRecord(statuses, walletIds, cmd, currentNetwork, allowRewind, beforeSet)

    private suspend fun maybeReregisterBalanced() {
        syncCoordinator.maybeReregisterBalanced(makeSyncContext())
    }

    /**
     * [registerAllWalletScripts] for callers that only refresh the set (a
     * wallet switch, strategy registration, the gap-limit scan): if the user
     * switched wallets before this set landed, the switch's own registration
     * supersedes it, so the abort is a quiet no-op, not an error (#431).
     */
    private suspend fun registerAllWalletsQuietlyIfSuperseded() {
        try {
            registerAllWalletScripts()
        } catch (e: ActiveWalletChangedException) {
            logger.d(TAG, "Registration superseded by a wallet switch: ${e.message}")
        }
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

        // BROADCAST_ERROR_PREFIX moved to SendPipeline with the send path (M3 #5).

        // MAX_CONCURRENT_WALLET_SCRIPTS + BALANCED_LAG_THRESHOLD moved to
        // SyncCoordinator (#106). Tests now import SyncCoordinator.BALANCED_LAG_THRESHOLD
        // directly.
    }
}
