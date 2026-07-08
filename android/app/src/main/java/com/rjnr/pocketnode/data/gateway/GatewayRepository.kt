package com.rjnr.pocketnode.data.gateway

import android.content.Context
import android.util.Log
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil
import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.dao.PendingBroadcastDao
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.sync.SyncForegroundService
import com.rjnr.pocketnode.data.sync.SyncProgressTracker
import com.rjnr.pocketnode.data.migration.WalletMigrationHelper
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.transaction.SweepInput
import com.rjnr.pocketnode.data.wallet.GapLimitResolution
import com.rjnr.pocketnode.data.wallet.GapLimitStatus
import com.rjnr.pocketnode.data.wallet.GapLimitSweepPreview
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.gapLimitResolution
import com.rjnr.pocketnode.data.wallet.nextScanWindow
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.SyncStrategy
import com.nervosnetwork.ckblightclient.LightClientNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.rjnr.pocketnode.util.redactAddress

data class SyncProgress(
    val isSyncing: Boolean = false,
    val syncedToBlock: Long = 0L,
    val tipBlockNumber: Long = 0L,
    val percentage: Double = 0.0,
    val etaDisplay: String = "",
    val justReachedTip: Boolean = false,
    val firstCatchingUpAtMs: Long? = null
)

/**
 * Edge-trigger first-time tracking for the "catching up" state.
 *
 * - When `catching` flips false→true, returns `nowMs` (start of the run).
 * - While `catching` stays true, returns `prev` unchanged.
 * - When `catching` is false, returns null.
 */
fun computeFirstCatchingUpAtMs(prev: Long?, catching: Boolean, nowMs: Long): Long? = when {
    !catching -> null
    prev == null -> nowMs
    else -> prev
}

/**
 * Narrow seam over [GatewayRepository] so [com.rjnr.pocketnode.data.sync.BroadcastWatchdog]
 * can be unit-tested without instantiating a full Repository (whose
 * constructor surface is wide). [GatewayRepository] implements this; tests
 * use a small fake.
 */
interface TipSource {
    /** Monotonic light-client tip stream. Initial value 0L until first publish. */
    val tipFlow: kotlinx.coroutines.flow.StateFlow<Long>

    /** Pull a fresh tip via JNI and publish to [tipFlow] if higher. Returns the tip read (or 0L). */
    suspend fun fetchAndPublishTip(): Long

    /** (walletId, networkName) of the active wallet, or null if no active wallet. */
    fun activeWalletAndNetworkOrNull(): Pair<String, String>?
}

@Singleton
class GatewayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val walletPreferences: WalletPreferences,
    private val json: Json,
    private val transactionBuilder: TransactionBuilder,
    private val cacheManager: CacheManager,
    private val daoSyncManager: DaoSyncManager,
    private val walletMigrationHelper: WalletMigrationHelper,
    private val walletDao: WalletDao,
    private val appDatabase: AppDatabase,
    private val headerCacheDao: HeaderCacheDao,
    private val syncProgressDao: SyncProgressDao,
    private val pendingBroadcastDao: PendingBroadcastDao,
    private val broadcastClient: BroadcastClient,
    private val syncCoordinator: SyncCoordinator,
    private val daoHeaderResolver: DaoHeaderResolver,
    private val daoDepositReader: DaoDepositReader,
    private val lightClient: LightClientReadOnly,
    private val subAccountReconciler: com.rjnr.pocketnode.data.wallet.SubAccountReconciler,
    private val subAccountDiscovery: com.rjnr.pocketnode.data.wallet.SubAccountDiscovery,
) : TipSource {
    private val sendMutex = Mutex()

    // #382: single-flight for the explicit gap-limit scan (Home banner and
    // Settings both trigger it).
    private val gapLimitScanMutex = Mutex()

    private val _tipFlow = MutableStateFlow(0L)
    override val tipFlow: StateFlow<Long> = _tipFlow.asStateFlow()

    /**
     * Publish a fresh tip to [tipFlow]. Monotonic — older tips are ignored
     * (light-client tip events can interleave). Public-by-package so the
     * sync polling path and send path can both keep the flow warm without
     * exposing a setter to outside callers.
     */
    internal fun publishTip(n: Long) {
        if (n > _tipFlow.value) _tipFlow.value = n
    }

    override suspend fun fetchAndPublishTip(): Long {
        val n = currentTipNumberOrZero()
        if (n > 0) publishTip(n)
        return n
    }

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

    private val _nodeStatus = MutableStateFlow("Stopped")
    val nodeStatus: StateFlow<String> = _nodeStatus.asStateFlow()

    private val _network = MutableStateFlow(walletPreferences.getSelectedNetwork())
    val network: StateFlow<NetworkType> = _network.asStateFlow()
    val currentNetwork: NetworkType get() = _network.value

    private val _isSwitchingNetwork = MutableStateFlow(false)
    val isSwitchingNetwork: StateFlow<Boolean> = _isSwitchingNetwork.asStateFlow()

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
        Log.e(TAG, "Uncaught exception in GatewayRepository scope; suppressed to avoid process crash", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private val _nodeReady = MutableStateFlow<Boolean?>(null)
    private var activeWalletId: String = walletPreferences.getActiveWalletId() ?: ""
    private var activeWalletType: String = KeyManager.WALLET_TYPE_MNEMONIC

    // BALANCED filter cache + `scriptArgsToWalletId` mapping live on
    // [SyncCoordinator] now (#106). Read through `syncCoordinator.getWalletIdForScript`.

    // --- Sync progress tracking ---
    private val syncProgressTracker = SyncProgressTracker()
    private var syncPollingJob: Job? = null

    // Generation token bumped on every start/stop of sync polling. In-flight
    // getAccountStatus().onSuccess lambdas capture the generation at the start
    // of each iteration and refuse to write `firstCatchingUpAtMs` /
    // `_syncProgress` if it's stale — coroutine cancellation is cooperative,
    // so without this gate a successful HTTP response that returned just
    // before stopSyncPolling() could resurrect the cleared state. (#90)
    @Volatile
    private var syncPollingGeneration: Long = 0L
    private var wasSyncing = false
    // Process-lifetime edge-tracker for the first time the wallet entered
    // "catching up" (actively downloading blocks). Used by the HomeViewModel
    // coachmark grace timer (#90). Null whenever we are not catching up.
    private var firstCatchingUpAtMs: Long? = null
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    init {
        // Migrate old flat data/ directory to data/mainnet/ on first run
        migrateDataDirectoryIfNeeded()

        // Initialize the embedded node for the persisted network. Any unhandled
        // failure in the startup sequence must flip _nodeReady to false so
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
                        Log.d(TAG, "Periodic VACUUM completed")
                    }
                }.onFailure { Log.w(TAG, "Periodic VACUUM failed (non-fatal)", it) }

                initializeNode(currentNetwork)
            } catch (e: Exception) {
                Log.e(TAG, "Startup sequence failed before node init", e)
                _nodeReady.value = false
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
        syncProgressTracker.reset()
        // Seed the percentage baseline with the registered light-client start
        // block so the first sample doesn't anchor the math to a transient
        // syncedToBlock=0 reading during peer warm-up (#150).
        val lightStart = syncProgressDao.get(wallet.walletId, currentNetwork.name)
            ?.lightStartBlockNumber ?: 0L
        if (lightStart > 0) {
            syncProgressTracker.seedStartHeight(lightStart)
        }
        wasSyncing = false
        firstCatchingUpAtMs = null
        _syncProgress.value = SyncProgress()

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
     * Suspends until the node is ready. Returns true if init succeeded, false if it failed.
     */
    private suspend fun awaitNodeReady(): Boolean {
        return _nodeReady.filterNotNull().first()
    }

    /**
     * One-time migration: moves old flat data/ layout (store.db, network/) into data/mainnet/.
     * Existing users upgrading from pre-testnet have data directly in data/ — this moves it
     * so each network gets its own isolated subdirectory.
     */
    private fun migrateDataDirectoryIfNeeded() {
        val dataDir = File(context.filesDir, "data")
        val mainnetDir = File(dataDir, "mainnet")
        val storeDb = File(dataDir, "store.db")
        val networkDir = File(dataDir, "network")

        // If mainnet subdir already exists or there's nothing to migrate, skip
        if (mainnetDir.exists() || (!storeDb.exists() && !networkDir.exists())) return

        Log.d(TAG, "Migrating data directory to per-network layout...")
        if (!mainnetDir.mkdirs() && !mainnetDir.exists()) {
            Log.e(TAG, "Failed to create mainnet directory, skipping migration")
            return
        }

        var migrationOk = true
        if (storeDb.exists()) {
            if (storeDb.renameTo(File(mainnetDir, "store.db"))) {
                Log.d(TAG, "Moved store.db -> mainnet/store.db")
            } else {
                Log.e(TAG, "Failed to move store.db to mainnet/store.db")
                migrationOk = false
            }
        }
        if (networkDir.exists()) {
            if (networkDir.renameTo(File(mainnetDir, "network"))) {
                Log.d(TAG, "Moved network/ -> mainnet/network/")
            } else {
                Log.e(TAG, "Failed to move network/ to mainnet/network/")
                migrationOk = false
            }
        }
        if (!migrationOk) {
            Log.e(TAG, "Migration incomplete — manual intervention may be needed")
        }
    }

    private suspend fun initializeNode(targetNetwork: NetworkType) {
        try {
            _nodeReady.value = null // Reset for re-initialization
            Log.d(TAG, "Initializing embedded node for ${targetNetwork.name}...")

            val configName = "${targetNetwork.name.lowercase()}.toml"
            val configFile = File(context.filesDir, configName)

            // Copy config from assets (deterministic, no retry needed)
            Log.d(TAG, "Copying config from assets: $configName")
            try {
                context.assets.open(configName).use { input ->
                    configFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $configName from assets", e)
                _nodeReady.value = false
                return
            }

            // Update paths in config — each network gets its own data subdirectory
            val configContent = configFile.readText()
            val dataDir = File(context.filesDir, "data/${targetNetwork.name.lowercase()}")
            if (!dataDir.exists()) {
                Log.d(TAG, "Creating data directory: ${dataDir.absolutePath}")
                if (!dataDir.mkdirs()) {
                    Log.e(TAG, "Failed to create data directory")
                    _nodeReady.value = false
                    return
                }
            }

            val newConfig = configContent
                .replace("path = \"data/store\"", "path = \"${File(dataDir, "store.db").absolutePath}\"")
                .replace("path = \"data/network\"", "path = \"${File(dataDir, "network").absolutePath}\"")
            configFile.writeText(newConfig)
            Log.d(TAG, "Config updated with absolute paths for ${targetNetwork.name}")

            // Init and start JNI with retry (transient failures can occur)
            val maxRetries = 3
            val backoffMs = longArrayOf(2_000, 4_000, 8_000)

            for (attempt in 1..maxRetries) {
                Log.d(TAG, "JNI init attempt $attempt/$maxRetries...")

                val initResult = LightClientNative.nativeInit(
                    configFile.absolutePath,
                    object : LightClientNative.StatusCallback {
                        override fun onStatusChange(status: String, data: String) {
                            Log.d(TAG, "Native Status Change: $status")
                            _nodeStatus.value = status
                        }
                    }
                )

                if (!initResult) {
                    Log.e(TAG, "nativeInit returned false (attempt $attempt)")
                    if (attempt < maxRetries) {
                        delay(backoffMs[attempt - 1])
                        continue
                    }
                    _nodeReady.value = false
                    return
                }

                val startResult = LightClientNative.nativeStart()
                if (startResult) {
                    Log.d(TAG, "Node started successfully on ${targetNetwork.name} (attempt $attempt)")
                    _nodeReady.value = true

                    // Cold-start recovery: surface any BROADCASTING orphan rows for the
                    // active network so the watchdog can resolve them on the next tip.
                    // Network-scoped — LightClientNative is per-network; querying for a
                    // hash on a network whose light client isn't running would return null
                    // spuriously and drive valid orphans to a false FAILED. (#115 §5)
                    runCatching {
                        val orphans = pendingBroadcastDao.getActive(activeWalletId, currentNetwork.name)
                        val broadcasting = orphans.count { it.state == "BROADCASTING" }
                        if (broadcasting > 0) {
                            Log.w(
                                TAG,
                                "Cold-start: $broadcasting BROADCASTING orphan(s) on ${currentNetwork.name}; watchdog will resolve"
                            )
                        }
                    }

                    // Legacy reconciliation: PENDING `transactions` rows that predate
                    // pending_broadcasts have no broadcast row, so the watchdog can't
                    // see them. Query the light client directly: on chain → CONFIRMED,
                    // not found → FAILED, in pool → leave alone (the natural pending state).
                    // (#115 — addresses the user's "old ghosts still showing pending" case.)
                    runCatching {
                        val orphanHashes = cacheManager.getOrphanPendingHashes(activeWalletId, currentNetwork.name)
                        if (orphanHashes.isNotEmpty()) {
                            Log.w(TAG, "Legacy reconcile: ${orphanHashes.size} orphan PENDING tx(s) on ${currentNetwork.name}")
                            scope.launch {
                                delay(15_000) // give light client time to be ready
                                for (hash in orphanHashes) {
                                    val result = getTransactionStatus(hash)
                                    // Distinguish transient lookup failure (Result.failure) from
                                    // a successful "unknown" response. Only the latter means the
                                    // light client knows it doesn't have the tx; the former is a
                                    // JNI/RPC hiccup and must NOT permanently mark the row FAILED.
                                    val resp = result.getOrNull()
                                    val newStatus = when {
                                        result.isFailure -> null      // transient — retry next init
                                        resp == null -> null           // defensive
                                        resp.status == "unknown" -> "FAILED"
                                        resp.blockHash != null -> "CONFIRMED"
                                        else -> null  // still in pool — leave PENDING
                                    }
                                    if (newStatus != null) {
                                        cacheManager.updateTransactionStatus(hash, newStatus)
                                        Log.d(TAG, "Legacy reconcile: $hash → $newStatus")
                                    }
                                }
                            }
                        }
                    }

                    startSyncPolling()
                    startBackgroundSync()
                    return
                }

                Log.e(TAG, "nativeStart returned false (attempt $attempt)")
                if (attempt < maxRetries) {
                    delay(backoffMs[attempt - 1])
                } else {
                    _nodeReady.value = false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Setup error during node initialization", e)
            _nodeReady.value = false
        }
    }

    /**
     * Switches to a different network by persisting the selection and restarting the process.
     *
     * The JNI light client does not support in-process re-initialization: nativeStop() blocks
     * indefinitely while peers are connected, and nativeInit() rejects calls when already
     * initialized. Restarting the process gives a clean JNI state at zero engineering cost.
     *
     * Process death safety: setSelectedNetwork() uses commit() (synchronous) so the preference
     * is guaranteed on disk before killProcess(). On restart, initializeNode() reads the new
     * network from WalletPreferences. Data directories are isolated per network.
     */
    suspend fun switchNetwork(target: NetworkType): Result<Unit> = runCatching {
        if (target == currentNetwork) return@runCatching
        if (_isSwitchingNetwork.value) throw Exception("Network switch already in progress")

        _isSwitchingNetwork.value = true
        try {
            Log.d(TAG, "Switching network: ${currentNetwork.name} -> ${target.name}")

            // The JNI light client does not support re-initialization in the same process lifetime:
            // nativeStop() blocks indefinitely (peer disconnection loop) and nativeInit() rejects
            // calls while already initialized ("Already initialized!"). The only reliable path is
            // to persist the selection and restart the process — Android will relaunch the app and
            // initializeNode() will pick up the new network from WalletPreferences.

            // Clear Room caches before process restart
            cacheManager.clearAll()
            daoSyncManager.clearAll()

            walletPreferences.setSelectedNetwork(target) // uses commit() — synchronous flush
            Log.d(TAG, "Persisted ${target.name}, restarting process for clean JNI init")

            // ProcessPhoenix-style restart: launch fresh activity before killing process.
            // This ensures the app visibly restarts on all devices/launchers.
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            _isSwitchingNetwork.value = false
            throw e
        }
    }

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
                Log.d(TAG, "Force resync requested, recalculating from sync mode")
                syncMode.toFromBlock(customBlockHeight, tipHeight, currentNetwork)
            }
            // Resume from saved progress if available (use the higher value)
            savedBlock > 0 || existingScriptBlock > 0 -> {
                val resumeBlock = maxOf(savedBlock, existingScriptBlock)
                Log.d(TAG, "Resuming sync from saved block: $resumeBlock (saved=$savedBlock, existing=$existingScriptBlock)")
                resumeBlock.toString()
            }
            // First time: calculate based on sync mode
            else -> {
                Log.d(TAG, "First time sync, calculating from mode: $syncMode")
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
            Log.w(TAG, "Detected future block number ($blockNumLong > $tipHeight). " +
                    "Resetting to RECENT height: $recentBlock")
            finalBlockNum = recentBlock.toString()
        } else if (blockNumLong == 0L && syncMode != SyncMode.FULL_HISTORY && checkpoint > 0) {
            // If it resolved to 0 but we aren't doing full history, use checkpoint
            Log.d(TAG, "Block resolved to 0 but mode is $syncMode. Using checkpoint $checkpoint")
            finalBlockNum = checkpoint.toString()
        }

        Log.d(TAG, "🔄 Sync mode $syncMode: tip=$tipHeight, targetBlock=$finalBlockNum")

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
            Log.w(TAG, "getGapLimitStatus: candidate read failed, treating as not scanned: ${it.message}")
        }.getOrDefault(emptyList())
        val resolution = gapLimitResolution(chain)
        if (resolution == GapLimitResolution.CLEAR &&
            walletPreferences.isGapLimitSignalDetected(currentNetwork, wId)
        ) {
            Log.i(TAG, "gap-limit scan completed clean — retiring Tier 1 signal for $wId")
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
            }.onFailure { Log.w(TAG, "gap-limit capacity read failed for ${cand.derivationPath}: ${it.message}") }
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
    suspend fun runGapLimitScan(): Result<Int> = runCatching {
        // Single-flight: the Home banner and Settings both trigger this, and
        // a second concurrent pass would double the derivation work and
        // interleave two CMD_SET_SCRIPTS_ALL registrations.
        if (!gapLimitScanMutex.tryLock()) throw Exception("A scan is already running")
        try {
            val wId = activeWalletId
            if (wId.isEmpty()) throw Exception("No active wallet")
            val words = getMnemonic() ?: throw Exception("Recovery phrase unavailable for this wallet")
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
            if (reArmed > 0) Log.i(TAG, "gap-limit scan: re-armed $reArmed retired slot(s)")
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
        }.onFailure { Log.w(TAG, "liveUntypedCellsFor: reservation read failed: ${it.message}") }
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
    suspend fun sweepGapLimitFunds(): Result<String> = runCatching {
        if (!gapLimitScanMutex.tryLock()) throw Exception("A scan or sweep is already running")
        try {
            val wId = activeWalletId
            if (wId.isEmpty()) throw Exception("No active wallet")
            val myScript = _walletInfo.value?.script ?: throw Exception("Wallet not initialized")
            val words = getMnemonic() ?: throw Exception("Recovery phrase unavailable for this wallet")

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
                Log.i(TAG, "gap-limit sweep broadcast: ${plan.inputLockArgs.size} inputs, ${keys.size} groups")
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
        Log.w(TAG, "Forcing sync reset...")
        // Only clear sync-related preferences for the active wallet, not all preferences
        setWalletSyncBlock(activeWalletId, 0L)
        walletPreferences.setInitialSyncCompleted(false, walletId = activeWalletId.ifEmpty { null })
        _isRegistered.value = false
        _balance.value = null
        registerAccount(SyncMode.RECENT)
        Log.i(TAG, "Sync reset complete. Registered as RECENT.")
    }

    suspend fun refreshBalance(address: String? = null): Result<BalanceResponse> = runCatching {
        val addr = address ?: getCurrentAddress() ?: throw Exception("Wallet not initialized")
        val info = _walletInfo.value ?: throw Exception("No wallet")

        // --- Cache-first: emit cached balance immediately ---
        cacheManager.getCachedBalance(currentNetwork.name, walletId = activeWalletId)?.let {
            _balance.value = it
        }

        val searchKey = JniSearchKey(script = info.script)
        Log.d(TAG, "🔍 Fetching balance for script args ${searchKey.script.args.redactAddress()}")

        val responseJson = LightClientNative.nativeGetCellsCapacity(json.encodeToString(searchKey))
            ?: throw Exception(readPathNullMessage("get balance", lightClientReadyForRead()))

        Log.d(TAG, "📊 Raw capacity response: $responseJson")

        val cap = json.decodeFromString<JniCellsCapacity>(responseJson)

        // Convert to balance response
        var capacityVal = cap.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L

        // The light client's nativeGetCellsCapacity may include spent cells
        // We need to calculate the true balance by getting live cells only
        Log.d(TAG, "🔍 Calculating true balance by filtering out spent cells...")

        try {
            // ALL spent outpoints, cursor-walked (see fetchAllSpentOutpoints
            // KDoc — the old single limit=100 page under/over-counted balances
            // for wallets with >100 transactions).
            val spentOutpoints = fetchAllSpentOutpoints(json.encodeToString(searchKey))
            Log.d(TAG, "📋 Found ${spentOutpoints.size} spent outpoints")

            // Walk ALL cells the same way — one page hid everything past the
            // first 100 cells from the balance.
            val allBalanceCells = mutableListOf<JniCell>()
            run {
                var c: String? = null
                var pages = 0
                while (pages < MAX_CELL_PAGES) {
                    val pageJson = LightClientNative.nativeGetCells(
                        json.encodeToString(searchKey), "desc", 100, c
                    ) ?: break
                    val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
                    allBalanceCells.addAll(page.objects)
                    pages++
                    if (page.objects.isEmpty() || page.objects.size < 100 ||
                        page.lastCursor.isNullOrEmpty()
                    ) break
                    c = page.lastCursor
                }
            }

            if (allBalanceCells.isNotEmpty()) {
                var liveCapacity = 0L
                var liveCellCount = 0
                var typedCellCount = 0

                allBalanceCells.forEach { cell ->
                    val outpointKey = "${cell.outPoint.txHash}:${cell.outPoint.index}"
                    if (outpointKey !in spentOutpoints) {
                        // Exclude cells with type scripts (DAO cells, etc.) from available balance
                        // Like Neuron: typeHash IS NULL AND hasData = false
                        // Skip a record with an unparseable capacity rather than
                        // letting one bad value throw and poison the whole balance
                        // page (#321). toLongOrNull also guards u64 > Long.MAX.
                        val cellCapacity = cell.output.capacity.removePrefix("0x").toLongOrNull(16)
                        if (cellCapacity == null) {
                            Log.w(TAG, "Skipping cell $outpointKey with unparseable capacity ${cell.output.capacity}")
                        } else if (cell.output.type != null) {
                            typedCellCount++
                            Log.d(TAG, "🔒 DAO/typed cell excluded from balance: $outpointKey = $cellCapacity shannons")
                        } else {
                            liveCapacity += cellCapacity
                            liveCellCount++
                            Log.d(TAG, "✅ Live cell: $outpointKey = $cellCapacity shannons")
                        }
                    } else {
                        Log.d(TAG, "❌ Spent cell: $outpointKey (filtered out)")
                    }
                }

                Log.d(TAG, "💰 Live balance: $liveCellCount cells, $liveCapacity shannons")
                capacityVal = liveCapacity

                // Rescue rescan (#332): only for a wallet that is GENUINELY
                // empty (no spendable AND no typed/DAO cells) yet has history,
                // at most once per wallet per process, and never while sync is
                // still catching up. The old `liveCapacity == 0` trigger
                // counted DAO deposits as nothing and re-fired after every
                // refresh — rewinding the light client to the wallet's
                // earliest transaction in an infinite loop.
                // Ascending order: the first page holds the OLDEST txs, which
                // is exactly what the earliest-block rewind wants (the old
                // desc-order page could miss the true earliest on >100-tx
                // wallets).
                val ascTxJson = LightClientNative.nativeGetTransactions(
                    json.encodeToString(searchKey), "asc", 100, null
                )
                if (ascTxJson != null) {
                    val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(ascTxJson)
                    if (shouldAttemptZeroCellRescan(
                            spendableCapacity = liveCapacity,
                            typedCellCount = typedCellCount,
                            hasTransactions = txPag.objects.isNotEmpty(),
                            // Persisted across launches (knmo): without this the
                            // in-memory set re-armed every cold start and a
                            // permanently-empty primary rewound on every launch.
                            alreadyAttempted = activeWalletId in balanceRescanAttempted ||
                                walletPreferences.isZeroCellRescanDone(activeWalletId),
                            isSyncing = _syncProgress.value.isSyncing,
                        )
                    ) {
                        balanceRescanAttempted.add(activeWalletId)
                        walletPreferences.setZeroCellRescanDone(activeWalletId)
                        Log.w(TAG, "🔄 Have ${txPag.objects.size} transactions but 0 live cells - triggering rescan")
                        val earliestBlock = txPag.objects
                            .mapNotNull { it.blockNumber.removePrefix("0x").toLongOrNull(16) }
                            .minOrNull() ?: 0L
                        val rescanFrom = (earliestBlock - 100).coerceAtLeast(0L)
                        Log.d(TAG, "🔄 Rescan from block $rescanFrom (earliest tx at $earliestBlock)")

                        val blockNumberHex = "0x${rescanFrom.toString(16)}"
                        val scriptStatuses = listOf(
                            JniScriptStatus(
                                script = info.script,
                                scriptType = "lock",
                                blockNumber = blockNumberHex
                            )
                        )
                        setScriptsAndRecord(
                            scriptStatuses,
                            listOf(activeWalletId),
                            LightClientNative.CMD_SET_SCRIPTS_PARTIAL,
                            allowRewind = true, // rescue rescan IS the intentional rewind
                        )
                        // Deliberately NOT persisted via setWalletSyncBlock: the
                        // light client's own storage carries the rewind for this
                        // session, and the sync poll's monotonic write records
                        // progress as it advances. Persisting the regression made
                        // it survive restarts — re-registering from the rewound
                        // block forever (#332).
                        Log.d(TAG, "✅ Rescan triggered (partial) - balance should update on next refresh")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate live balance: ${e.message}")
            // Fall back to the raw capacity value if filtering fails
        }

        val ckbVal = capacityVal / 100_000_000.0

        Log.d(TAG, "💰 Final balance: $capacityVal shannons = $ckbVal CKB (at block ${cap.blockNumber})")

        val resp = BalanceResponse(
            address = addr,
            capacity = "0x${capacityVal.toString(16)}",
            capacityCkb = ckbVal.toString(),
            asOfBlock = cap.blockNumber
        )
        _balance.value = resp

        // --- Cache write ---
        cacheManager.cacheBalance(resp, currentNetwork.name, walletId = activeWalletId)

        resp
    }

    // Simplified Account Status - JNI doesn't give sync progress easily
    suspend fun getAccountStatus(): Result<AccountStatusResponse> = runCatching {
        val addr = getCurrentAddress() ?: throw Exception("No wallet")
        
        // Fetch tip header
        val tipJson = LightClientNative.nativeGetTipHeader()
        val tipNumber = if (tipJson != null) {
            val tip = json.decodeFromString<JniHeaderView>(tipJson)
            tip.number.removePrefix("0x").toLongOrNull(16) ?: 0L
        } else {
            0L
        }

        // Fetch script status for ALL registered scripts.
        val scriptsJson = LightClientNative.nativeGetScripts()
        val scripts = if (scriptsJson != null) {
            json.decodeFromString<List<JniScriptStatus>>(scriptsJson)
        } else {
            emptyList()
        }

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
                    Log.d(TAG, "💾 Saved sync progress: block $block (wallet=$walletId)")
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
        }.onFailure { Log.w(TAG, "Sub-account candidate reconcile failed (non-fatal)", it) }

        // Active wallet's block for the sync-progress display below.
        val activeArgs = _walletInfo.value?.script?.args
        val scriptBlockNumber = if (activeArgs != null) {
            scripts.find { it.script.args == activeArgs }
                ?.blockNumber?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
        } else {
            scripts.firstOrNull()?.blockNumber?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
        }

        // Log sync progress for debugging
        Log.d(TAG, "📈 SYNC STATUS: tip=$tipNumber, scriptBlock=$scriptBlockNumber, " +
                "behind=${tipNumber - scriptBlockNumber} blocks")

        // Calculate progress relative to sync start (not absolute tip ratio).
        // This gives meaningful feedback for small block ranges (e.g. 50-100 blocks).
        val trackerInfo = syncProgressTracker.calculate(tipNumber)
        val progress = if (tipNumber > 0) {
            (trackerInfo.percentage / 100.0).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        
        val isSynced = tipNumber > 0 && 
                scriptBlockNumber >= tipNumber - 10 && 
                scriptBlockNumber <= tipNumber + 10 // Handle slight mismatches safely
        
        Log.d(TAG, "📊 SYNC PROGRESS: ${(progress * 100).toInt()}% synced, isSynced=$isSynced")

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
        val searchKey = JniSearchKey(script = script)

        Log.d(TAG, "🔍 getCells: Fetching cells for script args ${searchKey.script.args.redactAddress()}")

        // ALL spent outpoints, cursor-walked to the end (see helper KDoc —
        // the old single limit=100 page broke wallets with >100 txs).
        val spentOutpoints = fetchAllSpentOutpoints(json.encodeToString(searchKey))
        Log.d(TAG, "📋 getCells: Found ${spentOutpoints.size} spent outpoints")

        // Walk the cell cursor too: a wallet holding >`limit` cells only ever
        // exposed its first page to coin selection and the balance math.
        val allCells = mutableListOf<JniCell>()
        var cellCursor: String? = cursor
        var lastCursorOut: String? = null
        var cellPages = 0
        while (cellPages < MAX_CELL_PAGES) {
            val pageJson = LightClientNative.nativeGetCells(
                json.encodeToString(searchKey), "desc", limit, cellCursor
            ) ?: if (cellPages == 0) {
                throw Exception(readPathNullMessage("get cells", lightClientReadyForRead()))
            } else break
            val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
            allCells.addAll(page.objects)
            lastCursorOut = page.lastCursor
            cellPages++
            if (page.objects.isEmpty() || page.objects.size < limit ||
                page.lastCursor.isNullOrEmpty()
            ) break
            cellCursor = page.lastCursor
        }
        if (cellPages >= MAX_CELL_PAGES) {
            Log.w(TAG, "getCells: hit $MAX_CELL_PAGES-page cap (${allCells.size} cells) — set may be incomplete")
        }

        val liveCells = allCells.filter { cell ->
            val outpointKey = "${cell.outPoint.txHash}:${cell.outPoint.index}"
            val isLive = outpointKey !in spentOutpoints
            if (!isLive) {
                Log.d(TAG, "❌ getCells: Filtering out spent cell: $outpointKey")
            }
            // Also exclude cells with type scripts (DAO cells) — they can't be spent as regular inputs
            val hasTypeScript = cell.output.type != null
            if (hasTypeScript && isLive) {
                Log.d(TAG, "🔒 getCells: Excluding typed cell (DAO): $outpointKey")
            }
            isLive && !hasTypeScript
        }.map { it.toCell() }

        Log.d(TAG, "✅ getCells: ${liveCells.size} live cells (filtered from ${allCells.size} total)")

        CellsResponse(liveCells, lastCursorOut)
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
    private fun fetchAllSpentOutpoints(searchKeyJson: String): MutableSet<String> {
        val spent = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_TX_PAGES) {
            val txJson = LightClientNative.nativeGetTransactions(searchKeyJson, "desc", 100, cursor)
                ?: break
            val page = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
            if (page.objects.isEmpty()) break
            page.objects.forEach { txWithCell ->
                txWithCell.transaction.inputs.forEach { input ->
                    spent.add("${input.previousOutput.txHash}:${input.previousOutput.index}")
                }
            }
            pages++
            cursor = page.lastCursor
            if (cursor.isNullOrEmpty()) break
        }
        if (pages >= MAX_TX_PAGES) {
            Log.w(TAG, "fetchAllSpentOutpoints: hit $MAX_TX_PAGES-page cap — spent set may be incomplete")
        }
        return spent
    }

    /**
     * Readiness probe for the read-path null classifier ([readPathNullMessage]).
     * A tip header means the light client is up and reporting chain state; its
     * absence means cold start / still starting up. Only ever called on an
     * already-failed read, so the extra JNI hop is off the hot path. Any
     * exception here is treated as "not ready" so we never upgrade a real
     * outage into a misleading transient message.
     */
    private suspend fun lightClientReadyForRead(): Boolean =
        runCatching { lightClient.getTipHeader() != null }.getOrDefault(false)

    /**
     * Single mutex-guarded prepare-and-send shared by plain transfers and DAO
     * operations (#115, #320). Runs cell-fetch, reservation filter, build,
     * sign, and pre-broadcast persistence all inside [sendMutex] — closing the
     * read-filter-insert race that would otherwise let two concurrent sends
     * (e.g. a transfer and a DAO deposit) pick the same input cells and produce
     * conflicting transactions.
     *
     * [build] receives the reservation-filtered spendable cells (live cells
     * minus inputs reserved by in-flight broadcasts, plus synthesized
     * change-outputs of pending sends) and the snapshot network, and returns
     * the signed transaction.
     *
     * The JNI broadcast happens AFTER the mutex is released — locking that would
     * needlessly serialize all sends. [sendTransaction] is idempotent on the
     * pre-inserted hash, so it skips the duplicate insert and just performs the
     * broadcast + post-broadcast CAS.
     */
    private suspend fun buildReserveAndSend(
        fromAddress: String,
        build: (availableCells: List<Cell>, network: NetworkType) -> Transaction
    ): String {
        // Snapshot every piece of sender state at function entry. The user can
        // switch wallet/network mid-send (rare, but possible — Settings is one
        // tap away); we must not let live reads inside the mutex retarget the
        // send to the new wallet while we persist rows under the old walletId.
        val senderNetwork = currentNetwork
        val walletId = activeWalletId
        val network = senderNetwork.name
        val tipNumber = currentTipNumberOrZero()
        publishTip(tipNumber)

        val signedTx = sendMutex.withLock {
            // getCells(fromAddress) decodes the address to a script — honors the
            // snapshot rather than reading _walletInfo.value live. It already
            // excludes typed (DAO/token) cells, so this is regular spendable CKB.
            val cellsResult = getCells(fromAddress).getOrThrow()
            val pending = pendingBroadcastDao.getActive(walletId, network)
            val reserved: Set<OutPoint> = pending
                .flatMap { json.decodeFromString<List<OutPoint>>(it.reservedInputs) }
                .toSet()
            val liveFiltered = cellsResult.items.filter { it.outPoint !in reserved }

            // Synthesize predicted change-output cells from in-flight broadcasts.
            // Without this, rapid sequential sends exhaust live cells before the
            // light client has synced the change outputs of prior sends — the
            // observed "Not enough funds available" failure mode.
            // We include each output of every active pending tx whose lock script
            // matches the sender's lock (= change output going back to us).
            // If a pending tx ultimately FAILs, downstream txs that consumed its
            // synthetic change will also fail and the watchdog times them out.
            //
            // ONLY session-broadcast rows: a synthetic input resolves only if
            // its creating tx is in the light client's in-memory pending pool,
            // which is wiped on restart. Persisted rows from a prior session are
            // not in the pool, so their change would be unresolvable and reject
            // every send after a reboot (Alex report). The reservation filter
            // above still uses ALL pending rows so reserved inputs are never
            // double-spent.
            val pendingChange: List<Cell> = pending
                .filter { it.txHash in broadcastedThisSession }
                .flatMap { row ->
                val pendingTx = try {
                    json.decodeFromString<Transaction>(row.signedTxJson)
                } catch (e: Exception) {
                    return@flatMap emptyList<Cell>()
                }
                pendingTx.cellOutputs.mapIndexedNotNull { idx, output ->
                    val outAddr = try {
                        AddressUtils.encode(output.lock, senderNetwork)
                    } catch (e: Exception) {
                        return@mapIndexedNotNull null
                    }
                    if (outAddr != fromAddress) return@mapIndexedNotNull null
                    Cell(
                        outPoint = OutPoint(row.txHash, "0x${idx.toString(16)}"),
                        capacity = output.capacity,
                        blockNumber = "0x0", // synthetic — not on chain yet
                        lock = output.lock,
                        type = output.type,
                        data = "0x"
                    )
                }
            }
            // Dedup by outpoint, preferring the real (on-chain) cell: once a
            // pending tx confirms, its change appears in both liveFiltered and
            // pendingChange for the brief window before the watchdog clears the
            // row. Selecting the same outpoint twice would build a tx with a
            // duplicate input and fail. liveFiltered is first, so distinctBy
            // keeps the real cell.
            val filtered = (liveFiltered + pendingChange)
                .distinctBy { "${it.outPoint.txHash}:${it.outPoint.index}" }
            Log.d(
                TAG,
                "buildReserveAndSend: ${cellsResult.items.size} live, ${reserved.size} reserved, " +
                    "${pendingChange.size} synthetic-change, ${filtered.size} available"
            )

            val signed = build(filtered, senderNetwork)

            val txHash = transactionBuilder.computeTxHash(signed)
            val txJson = json.encodeToString(signed)
            val reservedJson = json.encodeToString(signed.cellInputs.map { it.previousOutput })
            val now = System.currentTimeMillis()

            // Outgoing amount for activity-row balanceChange. Stored as POSITIVE
            // hex per existing convention; `direction = "out"` carries the sign
            // for the UI.
            //
            // Net debit = Σ(our input capacities) − Σ(our plain-change outputs),
            // matching the confirmed-row formula. The old code used
            // min(all outputs), which returned the CHANGE output whenever
            // change < amount sent — a 150,000 CKB send displayed as
            // "-17,950.29" until the light client synced and corrected it
            // (Alex, Telegram). A typed self-output (DAO deposit cell) is
            // capacity leaving spendable, so it is not counted as change.
            val inputCapacities = signed.cellInputs.mapNotNull { input ->
                filtered.find { it.outPoint == input.previousOutput }
                    ?.capacity?.removePrefix("0x")?.toLongOrNull(16)
            }
            val outgoingOutputs = signed.cellOutputs.map { output ->
                val isOurs = runCatching {
                    AddressUtils.encode(output.lock, senderNetwork)
                }.getOrNull() == fromAddress
                OutgoingOutput(
                    capacityShannons = output.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L,
                    isOurs = isOurs,
                    isTyped = output.type != null,
                )
            }
            val outgoingAmount = computeOutgoingShannons(inputCapacities, outgoingOutputs)
            val balanceChangeHex = "0x${outgoingAmount.toString(16)}"

            pendingBroadcastDao.insert(
                PendingBroadcastEntity(
                    txHash = txHash,
                    walletId = walletId,
                    network = network,
                    signedTxJson = txJson,
                    reservedInputs = reservedJson,
                    state = "BROADCASTING",
                    submittedAtTipBlock = tipNumber,
                    nullCount = 0,
                    createdAt = now,
                    lastCheckedAt = now
                )
            )
            cacheManager.insertPendingTransaction(
                txHash = txHash,
                network = network,
                walletId = walletId,
                balanceChange = balanceChangeHex,
                direction = "out",
                fee = "0x0"
            )
            signed
        }

        // sendTransaction owns the JNI call + post-broadcast CAS.
        // Its insert path is idempotent: it sees the row we just inserted
        // and skips re-insertion, then performs broadcast + state CAS.
        return sendTransaction(signedTx).getOrThrow()
    }

    /**
     * Plain secp256k1 transfer. fromAddress is the authoritative sender
     * identity (captured by SendViewModel before this call) and is trusted
     * over live repository globals.
     */
    suspend fun prepareAndSend(
        fromAddress: String,
        toAddress: String,
        amountShannons: Long,
        privateKey: ByteArray
    ): Result<String> = runCatching {
        buildReserveAndSend(fromAddress) { availableCells, net ->
            transactionBuilder.buildTransfer(
                fromAddress = fromAddress,
                toAddress = toAddress,
                amountShannons = amountShannons,
                availableCells = availableCells,
                privateKey = privateKey,
                network = net
            )
        }
    }

    /**
     * Retries a FAILED `pending_broadcasts` row by re-broadcasting its
     * ORIGINAL signed bytes (#316).
     *
     * The previous flow decoded a recipient/amount and prefilled a fresh
     * send, which re-ran cell selection. Two ways that lost funds:
     *
     *  1. Double-pay. A FAILED state is a *heuristic* (still in-pool past the
     *     commit window, or fetch returned unknown N times) — not proof the
     *     network rejected the tx. The original could still be alive in a
     *     remote mempool. If the prefilled retry selected *different* inputs
     *     and the original later committed, the recipient was paid twice.
     *  2. Wrong recipient. The prefill guessed the recipient via a
     *     "smallest-capacity output" heuristic, which is the sender's own
     *     change whenever change < amount — the retry then paid the sender.
     *
     * Re-broadcasting the identical signed tx reuses the exact same inputs, so
     * the original and the retry conflict and at most one can ever commit — no
     * double-pay possible — and the recipient is whatever the original tx
     * already encodes, with no heuristic. We drop the FAILED row first so
     * [sendTransaction] re-inserts a fresh BROADCASTING row for the same hash
     * and the watchdog re-tracks it.
     */
    suspend fun retryBroadcast(txHash: String): Result<String> = runCatching {
        val row = pendingBroadcastDao.getFailedRow(txHash)
            ?: error("This transaction is too old to retry automatically. Please send a new one.")
        val tx = json.decodeFromString<Transaction>(row.signedTxJson)
        pendingBroadcastDao.delete(txHash)
        cacheManager.deleteTransaction(txHash)
        sendTransaction(tx).getOrThrow()
    }

    suspend fun sendTransaction(
        transaction: Transaction,
        /**
         * When non-null, abort if the active wallet is no longer this one —
         * a transaction signed for wallet A must never persist its pending
         * row under wallet B's id/network (#382 Tier 3 review).
         */
        expectedWalletId: String? = null,
    ): Result<String> = runCatching {
        Log.d(TAG, "📤 sendTransaction: building JSON")
        Log.d(TAG, "  Inputs: ${transaction.cellInputs.size}, Outputs: ${transaction.cellOutputs.size}")

        // Pre-flight checks (defense-in-depth, TransactionBuilder also validates)
        require(transaction.cellInputs.isNotEmpty()) { "Transaction has no inputs" }
        require(transaction.cellOutputs.isNotEmpty()) { "Transaction has no outputs" }
        for (output in transaction.cellOutputs) {
            val capacity = requireNotNull(output.capacity.removePrefix("0x").toLongOrNull(16)) {
                "Malformed output capacity '${output.capacity}' in transaction"
            }
            require(capacity >= TransactionBuilder.MIN_CELL_CAPACITY) {
                "Output capacity ${capacity / 100_000_000.0} CKB is below minimum 61 CKB"
            }
        }

        // Snapshot at entry — pin to whichever wallet/network the user was on.
        val walletId = activeWalletId
        if (expectedWalletId != null && walletId != expectedWalletId) {
            throw Exception("Wallet changed before broadcast; transaction not sent")
        }
        val network = currentNetwork.name
        val tipNumber = currentTipNumberOrZero()
        publishTip(tipNumber)
        val txJson = json.encodeToString(transaction)
        val txHash = transactionBuilder.computeTxHash(transaction)
        val reservedJson = json.encodeToString(
            transaction.cellInputs.map { it.previousOutput }
        )

        // Outgoing amount for the activity row. This path (DAO unlock,
        // failed-send retry) has no input capacities, so it sums the outputs
        // NOT locked to us — the recipient amount. The old code used
        // min(all outputs), which returned the CHANGE output whenever
        // change < amount sent (same bug as buildReserveAndSend; see #350).
        // Transfers via buildReserveAndSend insert their own (more precise)
        // net-debit row first and skip the insert below, so this only drives
        // standalone sends. A self-only tx (DAO unlock) sums to 0 and is
        // reclassified by the synced row.
        val ourLock = _walletInfo.value?.script
        val outgoingOutputs = transaction.cellOutputs.map { output ->
            OutgoingOutput(
                capacityShannons = output.capacity.removePrefix("0x").toLongOrNull(16) ?: 0L,
                isOurs = ourLock != null && output.lock == ourLock,
                isTyped = output.type != null,
            )
        }
        // Positive hex per existing convention; `direction = "out"` carries sign.
        val balanceChangeHex = "0x${recipientOutgoingShannons(outgoingOutputs).toString(16)}"
        val now = System.currentTimeMillis()

        Log.d(TAG, "📤 sendTransaction: JSON length=${txJson.length}, preHash=$txHash")

        // Critical section: pre-broadcast inserts under sendMutex.
        // Idempotent: skip insert if a row already exists for this hash
        // (Task 3's prepareAndSend pre-inserts under its own mutex hold).
        sendMutex.withLock {
            val existing = pendingBroadcastDao.getActive(walletId, network)
                .firstOrNull { it.txHash == txHash }
            if (existing == null) {
                pendingBroadcastDao.insert(
                    PendingBroadcastEntity(
                        txHash = txHash,
                        walletId = walletId,
                        network = network,
                        signedTxJson = txJson,
                        reservedInputs = reservedJson,
                        state = "BROADCASTING",
                        submittedAtTipBlock = tipNumber,
                        nullCount = 0,
                        createdAt = now,
                        lastCheckedAt = now
                    )
                )
                cacheManager.insertPendingTransaction(
                    txHash = txHash,
                    network = network,
                    walletId = walletId,
                    balanceChange = balanceChangeHex,
                    direction = "out",
                    fee = "0x0"
                )
            } else {
                Log.d(TAG, "sendTransaction: row exists (state=${existing.state}) — skipping insert")
            }
        }

        // JNI broadcast — outside the mutex (long-running, no need to serialize).
        val rawResult = try {
            broadcastClient.sendRaw(txJson)
        } catch (e: Exception) {
            pendingBroadcastDao.delete(txHash)
            cacheManager.deleteTransaction(txHash)
            throw e
        }

        if (rawResult == null) {
            pendingBroadcastDao.delete(txHash)
            cacheManager.deleteTransaction(txHash)
            throw Exception("Send failed - native returned null")
        }

        // The JNI now returns the real rejection reason with a sentinel prefix
        // instead of null, so we can surface WHY a broadcast was rejected (e.g.
        // an unresolvable input from a stale pending tx) rather than blaming the
        // network. Clean up the row we inserted, same as the null path.
        if (rawResult.startsWith(BROADCAST_ERROR_PREFIX)) {
            val reason = rawResult.removePrefix(BROADCAST_ERROR_PREFIX)
            pendingBroadcastDao.delete(txHash)
            cacheManager.deleteTransaction(txHash)
            throw Exception("Broadcast rejected: $reason")
        }

        val returnedHash = rawResult.trim('"')
        // Broadcast accepted → the tx is now in the light client's in-memory
        // pending pool this session, so its change is safe to spend as synthetic
        // change until it confirms. Record both key variants (pre-hash and the
        // returned hash) since the pending_broadcasts row may be keyed by either.
        broadcastedThisSession.add(txHash)
        broadcastedThisSession.add(returnedHash)
        if (returnedHash.lowercase() != txHash.lowercase()) {
            // Step 0 verified equality on testnet; this branch should be unreachable.
            // If it fires in production, the tx WAS broadcast under returnedHash but
            // our pre-broadcast hash derivation disagrees. Re-key both rows so cleanup
            // paths align with what the network sees.
            Log.e(TAG, "❌ Hash mismatch! pre=$txHash returned=$returnedHash — re-keying rows")
            pendingBroadcastDao.delete(txHash)
            cacheManager.deleteTransaction(txHash)
            pendingBroadcastDao.insert(
                PendingBroadcastEntity(
                    txHash = returnedHash,
                    walletId = walletId,
                    network = network,
                    signedTxJson = txJson,
                    reservedInputs = reservedJson,
                    state = "BROADCAST",
                    submittedAtTipBlock = tipNumber,
                    nullCount = 0,
                    createdAt = now,
                    lastCheckedAt = System.currentTimeMillis()
                )
            )
            cacheManager.insertPendingTransaction(
                txHash = returnedHash,
                network = network,
                walletId = walletId,
                balanceChange = balanceChangeHex,
                direction = "out",
                fee = "0x0"
            )
        } else {
            val ok = pendingBroadcastDao.compareAndUpdateState(
                hash = txHash,
                expected = "BROADCASTING",
                next = "BROADCAST",
                now = System.currentTimeMillis()
            )
            if (ok != 1) {
                Log.w(TAG, "compareAndUpdateState saw row not in BROADCASTING (race?); proceeding")
            }
        }

        Log.d(TAG, "✅ sendTransaction: returnedHash=$returnedHash")

        // After sending, nudge the light client to rescan from a few blocks back
        // so it picks up the new change output when the tx confirms. Capture the
        // sender's wallet info up front — if the user switches wallets during the
        // 5s delay, we must still re-register the script that actually sent.
        val senderInfo = _walletInfo.value
        val senderWalletId = activeWalletId
        scope.launch {
            try {
                delay(5000) // Wait a bit for tx to propagate
                // #332: on a still-catching-up wallet, re-registering at
                // tip-10 JUMPS the script forward over unscanned history —
                // silent balance/history loss. The ongoing scan will find the
                // change output anyway; only fast-path when already synced.
                if (_syncProgress.value.isSyncing) {
                    Log.d(TAG, "Skipping post-send partial re-register: wallet still catching up")
                    return@launch
                }
                val tipStr = LightClientNative.nativeGetTipHeader()
                if (tipStr != null && senderInfo != null) {
                    val tip = json.decodeFromString<JniHeaderView>(tipStr)
                    val tipNumber = tip.number.removePrefix("0x").toLongOrNull(16) ?: 0L
                    val rescanFrom = (tipNumber - 10).coerceAtLeast(0L)
                    Log.d(TAG, "🔄 Partial re-register from block $rescanFrom to catch change output")

                    val blockNumberHex = "0x${rescanFrom.toString(16)}"
                    // Only register lock script (not DAO type) with PARTIAL mode
                    val scriptStatuses = listOf(
                        JniScriptStatus(
                            script = senderInfo.script,
                            scriptType = "lock",
                            blockNumber = blockNumberHex
                        )
                    )
                    setScriptsAndRecord(scriptStatuses, listOf(senderWalletId), LightClientNative.CMD_SET_SCRIPTS_PARTIAL)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-register script after send: ${e.message}")
            }
        }

        returnedHash
    }

    suspend fun getTransactions(limit: Int = 50, cursor: String? = null): Result<TransactionsResponse> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val searchKey = JniSearchKey(script = info.script)
        val myScript = info.script

        // Walk ALL interaction pages before grouping (same class of fix as
        // the #386 spent-set walk). Two bugs lived in the old single
        // limit-sized page:
        //  1. The Room cache below — which feeds the Activity tab's Paging
        //     source — only ever saw the newest page, so wallets with more
        //     history silently lost their older transactions from the list.
        //  2. `limit` counts CELL INTERACTIONS, not transactions, and the
        //     per-tx net amount is computed by grouping interactions. A tx
        //     straddling the page boundary was computed from PART of its
        //     interactions — wrong amount/direction written to the cache.
        // Walking to the end fixes both; grouping happens once over the
        // complete set. The page cap is a runaway guard, logged when hit.
        val allInteractions = mutableListOf<JniTxWithCell>()
        run {
            var c: String? = cursor
            var pages = 0
            while (pages < MAX_TX_PAGES) {
                val pageJson = LightClientNative.nativeGetTransactions(
                    json.encodeToString(searchKey), "desc", 100, c
                ) ?: if (pages == 0) throw Exception("Failed to get transactions") else break
                val page = json.decodeFromString<JniPagination<JniTxWithCell>>(pageJson)
                allInteractions.addAll(page.objects)
                pages++
                if (page.objects.isEmpty() || page.objects.size < 100 ||
                    page.lastCursor.isNullOrEmpty()
                ) break
                c = page.lastCursor
            }
            if (pages >= MAX_TX_PAGES) {
                Log.w(TAG, "getTransactions: hit $MAX_TX_PAGES-page cap (${allInteractions.size} interactions) — history may be incomplete")
            }
        }
        Log.d(TAG, "📡 getTransactions: ${allInteractions.size} interactions walked")

        // Group by transaction hash to show a clean "one entry per transaction" UI
        val groupedTransactions = allInteractions.groupBy { it.transaction.hash }

        // #382 gap-limit signature: every lock-script args we track — all
        // wallets (both networks share args; the address differs, the script
        // args don't) plus every sub-account candidate. Failure here must
        // never break the transaction list; an incomplete set only means the
        // banner may not arm this pass.
        val knownLockArgs: Set<String> = runCatching {
            buildSet {
                add(myScript.args)
                val addressPicker: (com.rjnr.pocketnode.data.database.entity.WalletEntity) -> String =
                    if (currentNetwork == NetworkType.MAINNET) { w -> w.mainnetAddress } else { w -> w.testnetAddress }
                walletDao.getAll().forEach { w ->
                    val addr = addressPicker(w)
                    if (addr.isNotBlank()) {
                        runCatching { keyManager.deriveLockScriptFromAddress(addr) }
                            .getOrNull()?.let { add(it.args) }
                    }
                }
                addAll(appDatabase.subAccountCandidateDao().getAllScriptArgs())
            }
        }.getOrElse {
            Log.w(TAG, "getTransactions: known-scripts set incomplete: ${it.message}")
            setOf(myScript.args)
        }
        var gapLimitSignal = false

        // Fetch tip height once for confirmation calculations (avoid per-tx JNI calls)
        val tipHeight = runCatching {
            LightClientNative.nativeGetTipHeader()
                ?.let { json.decodeFromString<JniHeaderView>(it) }
                ?.number?.removePrefix("0x")?.toLongOrNull(16)
        }.getOrNull() ?: 0L

        val items = groupedTransactions.map { (txHash, cellInteractions) ->
            val firstInteraction = cellInteractions.first()
            val tx = firstInteraction.transaction

            // Net balance change = Sum(Outputs to us) - Sum(Inputs from us)
            var netChangeShannons = 0L
            cellInteractions.forEach { interaction ->
                val cap = interaction.ioCapacity.removePrefix("0x").toLongOrNull(16) ?: 0L
                if (interaction.ioType == "output") {
                    netChangeShannons += cap
                } else {
                    netChangeShannons -= cap
                }
            }

            val direction = when {
                netChangeShannons > 0 -> "in"
                netChangeShannons < 0 -> "out"
                else -> "self"
            }

            // #382: outgoing tx whose change went to no script we know —
            // the seed was likely also used in a standard BIP44 wallet
            // (Neuron) whose change chain we don't derive yet.
            if (!gapLimitSignal &&
                isUnknownChangeSignature(netChangeShannons, tx.outputs, knownLockArgs)
            ) {
                gapLimitSignal = true
                Log.i(TAG, "getTransactions: gap-limit signature in $txHash (#382)")
            }

            // For display, we show the absolute value as the amount
            val amount = if (netChangeShannons < 0) -netChangeShannons else netChangeShannons

            // Attempt to fetch block header to get real timestamp and block hash.
            // For a 50-tx page this used to do 50 native_get_header round-trips
            // even when the same headers had been resolved seconds earlier.
            // header_cache (Room) is consulted first; JNI is only invoked on miss
            // and the result is persisted so the next page-load is free.
            data class HeaderInfo(val timestampHex: String?, val hash: String?)
            val headerInfo: HeaderInfo = runCatching {
                val txWithStatus = LightClientNative.nativeGetTransaction(txHash)
                    ?.let { json.decodeFromString<JniTransactionWithStatus>(it) }
                val blockHashFromStatus = txWithStatus?.txStatus?.blockHash
                if (blockHashFromStatus != null) {
                    val cached = headerCacheDao.getByBlockHash(blockHashFromStatus)
                    if (cached != null) {
                        HeaderInfo(timestampHex = cached.timestamp, hash = cached.blockHash)
                    } else {
                        // Try local JNI lookup first, then trigger a fetch if not cached
                        val headerJson = LightClientNative.nativeGetHeader(blockHashFromStatus)
                        val header = headerJson?.let { json.decodeFromString<JniHeaderView>(it) }
                        if (header != null) {
                            runCatching {
                                headerCacheDao.upsert(HeaderCacheEntity.from(header, currentNetwork.name))
                            }
                            HeaderInfo(timestampHex = header.timestamp, hash = header.hash)
                        } else {
                            // Header not cached locally — ask light client to fetch it
                            val fetchJson = LightClientNative.nativeFetchHeader(blockHashFromStatus)
                            val fetchResult = fetchJson?.let { json.decodeFromString<JniFetchHeaderResponse>(it) }
                            if (fetchResult?.status == "fetched" && fetchResult.data != null) {
                                runCatching {
                                    headerCacheDao.upsert(HeaderCacheEntity.from(fetchResult.data, currentNetwork.name))
                                }
                                HeaderInfo(timestampHex = fetchResult.data.timestamp, hash = fetchResult.data.hash)
                            } else {
                                HeaderInfo(null, null)
                            }
                        }
                    }
                } else HeaderInfo(null, null)
            }.onFailure { e ->
                Log.w(TAG, "getTransactions: failed to fetch header for $txHash: ${e.message}")
            }.getOrElse { HeaderInfo(null, null) }

            // Derive confirmations from tip block height vs transaction block number
            val txBlockNum = firstInteraction.blockNumber.removePrefix("0x")
                .toLongOrNull(16) ?: 0L
            val confirmations = if (tipHeight > 0L && txBlockNum > 0L) {
                (tipHeight - txBlockNum).coerceAtLeast(0L).toInt()
            } else {
                0  // unknown = treat as pending
            }

            // Detect DAO operation type from output type scripts and header deps:
            //   Deposit:  DAO output + no header deps
            //   Withdraw: DAO output + 1 header dep (deposit block)
            //   Unlock:   no DAO output + 2 header deps (deposit + withdraw blocks)
            val hasDaoOutput = tx.outputs.any { output ->
                output.type?.codeHash == DaoConstants.DAO_CODE_HASH
            }

            val (finalDirection, finalAmount) = if (hasDaoOutput) {
                val daoOutputCapacity = tx.outputs
                    .first { it.type?.codeHash == DaoConstants.DAO_CODE_HASH }
                    .capacity.removePrefix("0x").toLongOrNull(16) ?: 0L
                if (tx.headerDeps.isEmpty()) {
                    "dao_deposit" to daoOutputCapacity
                } else {
                    "dao_withdraw" to daoOutputCapacity
                }
            } else if (tx.headerDeps.size >= 2) {
                // Unlock: show total CKB returned (deposit + compensation)
                val totalOutput = cellInteractions
                    .filter { it.ioType == "output" }
                    .sumOf { it.ioCapacity.removePrefix("0x").toLongOrNull(16) ?: 0L }
                "dao_unlock" to totalOutput
            } else {
                direction to amount
            }

            TransactionRecord(
                txHash = txHash,
                blockNumber = firstInteraction.blockNumber,
                blockHash = headerInfo.hash ?: "0x0",
                timestamp = 0L,
                balanceChange = "0x${finalAmount.toString(16)}",
                direction = finalDirection,
                fee = "0x0",
                confirmations = confirmations,
                blockTimestampHex = headerInfo.timestampHex,
                isDaoRelated = hasDaoOutput || tx.headerDeps.size >= 2
            )
        }

        // --- Cache write: upsert the COMPLETE walked history into Room. The
        // Activity tab pages from Room, so completeness here is what makes
        // its "All" list actually mean all. ---
        cacheManager.cacheTransactions(items, currentNetwork.name, walletId = activeWalletId)

        // Sticky: once armed, only the Tier 2 deep scan clears it. Detection
        // is not re-evaluated downward — a later partial walk (runaway cap)
        // must not un-detect.
        if (gapLimitSignal && activeWalletId.isNotEmpty() &&
            !walletPreferences.isGapLimitSignalDetected(currentNetwork, activeWalletId)
        ) {
            walletPreferences.setGapLimitSignalDetected(true, currentNetwork, activeWalletId)
        }

        // Merge: include pending local txs not yet returned by JNI
        val jniTxHashes = items.map { it.txHash }.toSet()
        val pendingLocal = cacheManager.getPendingNotIn(currentNetwork.name, jniTxHashes, walletId = activeWalletId)
        // Return only the newest `limit` transactions — Home renders this list
        // directly and the full set lives in Room. Cursor is always null now:
        // no UI caller ever passed one (Room Paging does the scrolling), and
        // the full walk leaves nothing to continue from.
        val mergedItems = (pendingLocal + items).take(limit)

        TransactionsResponse(mergedItems, null)
    }

    suspend fun getTransactionStatus(txHash: String): Result<TransactionStatusResponse> = runCatching {
        Log.d(TAG, "🔍 getTransactionStatus: Checking status for $txHash")

        val resJson = LightClientNative.nativeGetTransaction(txHash)
        if (resJson == null) {
            Log.w(TAG, "⚠️ getTransactionStatus: Native returned null for $txHash")
            // Return unknown status instead of throwing - tx might still be in network mempool
            return@runCatching TransactionStatusResponse(
                txHash = txHash,
                status = "unknown",
                confirmations = 0,
                blockHash = null
            )
        }

        Log.d(TAG, "📦 getTransactionStatus: Response: ${resJson.take(500)}")
        val txWithStatus = json.decodeFromString<JniTransactionWithStatus>(resJson)

        val status = txWithStatus.txStatus.status
        Log.d(TAG, "📊 getTransactionStatus: Raw status='$status', blockHash=${txWithStatus.txStatus.blockHash}")

        // Calculate actual confirmations from tip - txBlock
        val confirmations = if (status == "committed" && txWithStatus.txStatus.blockHash != null) {
            val tipJson = LightClientNative.nativeGetTipHeader()
            if (tipJson != null) {
                val tip = json.decodeFromString<JniHeaderView>(tipJson)
                val tipNumber = tip.number.removePrefix("0x").toLongOrNull(16) ?: 0L

                // Get tx's block header to compute real confirmation depth
                val txBlockJson = LightClientNative.nativeGetHeader(txWithStatus.txStatus.blockHash!!)
                if (txBlockJson != null) {
                    val txBlock = json.decodeFromString<JniHeaderView>(txBlockJson)
                    val txBlockNumber = txBlock.number.removePrefix("0x").toLongOrNull(16)
                    val realConfirmations = if (txBlockNumber != null) {
                        (tipNumber - txBlockNumber + 1).coerceAtLeast(1).toInt()
                    } else {
                        1 // malformed tx-block number; committed means at least 1
                    }
                    Log.d(TAG, "📈 Tip: $tipNumber, txBlock: $txBlockNumber, confirmations: $realConfirmations")
                    realConfirmations
                } else {
                    // Can't get tx block header — committed means at least 1
                    Log.d(TAG, "📈 Tip: $tipNumber, txBlock header unavailable, using 1")
                    1
                }
            } else {
                1 // At least 1 confirmation if committed
            }
        } else {
            0
        }

        Log.d(TAG, "✅ getTransactionStatus: status=$status, confirmations=$confirmations")

        TransactionStatusResponse(
            txHash = txHash,
            status = status,
            confirmations = confirmations,
            blockHash = txWithStatus.txStatus.blockHash
        )
    }

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
    private fun getExistingScriptBlock(): Long {
        return try {
            val scriptsJson = LightClientNative.nativeGetScripts() ?: return 0L
            val scripts = json.decodeFromString<List<JniScriptStatus>>(scriptsJson)
            val activeArgs = _walletInfo.value?.script?.args
            val match = if (activeArgs != null) {
                scripts.find { it.script.args == activeArgs }
            } else {
                scripts.firstOrNull()
            }
            match?.blockNumber?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get existing script block: ${e.message}")
            0L
        }
    }

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
        }.onFailure { Log.w(TAG, "DAO write-through failed: ${it.message}") }

        val windowStart = getExistingScriptBlock()
        val liveKeys = live.map { "${it.outPoint.txHash}:${it.outPoint.index}" }.toSet()
        val cached = runCatching { daoSyncManager.getActiveDeposits(network, walletId) }
            .getOrDefault(emptyList())

        val outsideWindow = mutableListOf<DaoDeposit>()
        for (entity in cached) {
            val key = "${entity.txHash}:${entity.index}"
            if (key in liveKeys) continue
            // DEPOSITING rows are optimistic pre-confirmation inserts with
            // blockNumber 0 — not windowing victims; leave them alone.
            if (entity.status == DaoCellStatus.DEPOSITING.name) continue
            if (windowStart > 0 && entity.depositBlockNumber in 1 until windowStart) {
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
            Log.i(TAG, "DAO merge: ${outsideWindow.size} cached deposit(s) predate sync window (start=$windowStart)")
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
        Log.i(TAG, "DAO deep rescan: rewound script to block $target (oldest cached deposit at $oldest)")
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
        val txHash = buildReserveAndSend(address) { availableCells, net ->
            transactionBuilder.buildDaoDeposit(
                amountShannons = amountShannons,
                availableCells = availableCells,
                senderScript = info.script,
                privateKey = privateKey,
                network = net
            )
        }
        Log.d(TAG, "DAO deposit sent: $txHash")

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
        val txHash = buildReserveAndSend(address) { availableCells, net ->
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
        Log.d(TAG, "DAO withdraw (phase 1) sent: $txHash")

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
        }.onFailure { Log.w(TAG, "Failed to persist pending withdraw marker: ${it.message}") }

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
        val net = _network.value

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

        // Unlock consumes only the withdrawing DAO cell (typed; never returned by
        // getCells, so no transfer can select it) and pays the fee from that
        // cell's own capacity — it selects no regular cells, so the
        // reservation filter doesn't apply. sendTransaction still reserves this
        // input and serializes the pre-broadcast insert under sendMutex (#320).
        val txHash = sendTransaction(tx).getOrThrow()
        Log.d(TAG, "DAO unlock (phase 2) sent: $txHash")
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
        preFetchedWallets: List<WalletEntity>? = null,
        preFilteredCandidates: List<WalletEntity>? = null,
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

    /**
     * Start centralized sync polling. Idempotent — does nothing if already running.
     * Polls getAccountStatus(), records samples, calculates progress, and emits to syncProgress flow.
     */
    // Throttle for the lastSyncedAt pref write in the sync poll (#286).
    @Volatile
    private var lastSyncedAtWrittenMs = 0L

    // One rescue rescan per wallet per process (#332) — the rescan itself
    // takes hours on a long-history wallet; re-firing restarts it.
    private val balanceRescanAttempted =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Tx hashes broadcast in THIS process session. Only these are safe to spend
    // as synthetic change: a synthetic change cell resolves only if its creating
    // tx is in the light client's IN-MEMORY pending pool, which is wiped on every
    // app restart. A PERSISTED pending_broadcasts row from a prior session is not
    // in the pool, so feeding its change into a new send makes the light client
    // fail to resolve the input and reject the tx locally — surfacing as the
    // misleading "Could not broadcast" error that survived reboots (Alex report).
    private val broadcastedThisSession =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun startSyncPolling() {
        if (syncPollingJob?.isActive == true) return

        val generation = ++syncPollingGeneration

        syncPollingJob = scope.launch {
            Log.d(TAG, "Starting centralized sync polling")
            while (true) {
                // Wrap each poll iteration so an exception (JNI panic-returned-
                // null, state mutation race, notification update failure)
                // doesn't kill the polling loop. Without this, one bad cycle
                // produces a permanently-stuck "Syncing..." UI even though
                // the scope's SupervisorJob keeps the process alive. The
                // catch logs and waits for the next cycle.
                try {
                    pollSyncOnce(generation)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // honour structured concurrency
                } catch (e: Throwable) {
                    Log.e(TAG, "syncPoll iteration failed; continuing", e)
                }
                // Synced cadence is 10s (was 30s): balance only refreshes off
                // this poll, so a confirmed incoming tx could lag up to ~30s
                // after "Synced" (#355/#356). The poll is cheap local JNI
                // (tip header + account status); 10s bounds the lag without
                // meaningful battery cost. Catch-up stays at 5s.
                val delayMs = if (_syncProgress.value.isSyncing) 5_000L else 10_000L
                delay(delayMs)
            }
        }
    }

    /**
     * Single sync-poll iteration extracted from [startSyncPolling] so the
     * while loop can wrap each call in a try-catch without losing the
     * generation-guard semantics. Any throwable inside this function logs
     * and returns; the loop continues on the next tick.
     */
    private suspend fun pollSyncOnce(generation: Long) {
        // Skip the iteration when no wallet is loaded into repo state.
        // Happens during normal lifecycle windows: lock screen (PIN not
        // entered), brief startup race before wallet decryption, after
        // session clear on background. Without this guard, getAccountStatus
        // throws "No wallet" on every poll and floods logcat with stack
        // traces that look like real errors.
        if (_walletInfo.value == null) {
            return
        }

        getAccountStatus()
                    .onSuccess { status ->
                        // Generation gate: refuse to publish state if stopSyncPolling()
                        // (or a fresh start) has bumped the generation since this
                        // iteration began. Prevents in-flight responses that returned
                        // just before cancel() from resurrecting cleared state.
                        if (generation != syncPollingGeneration) return@onSuccess

                        val syncedBlock = status.syncedToBlock.toLongOrNull() ?: 0L
                        val tipBlock = status.tipNumber.toLongOrNull() ?: 0L

                        // Diagnostic for the production sync-stall reports (#150).
                        // Logged once every poll cycle so support can see the
                        // delta between syncedBlock and tipBlock in logcat
                        // without enabling verbose JNI logging.
                        Log.i(
                            TAG,
                            "syncPoll synced=$syncedBlock tip=$tipBlock " +
                                "delta=${tipBlock - syncedBlock} progress=${status.syncProgress}"
                        )

                        syncProgressTracker.recordSample(syncedBlock, System.currentTimeMillis())
                        val info = syncProgressTracker.calculate(tipBlock)

                        // Staleness pill input (#286): persist "last time we
                        // observed sync progress", throttled to ~1 write/min
                        // (the poll runs every 5-30s; pref churn is pointless).
                        val nowMs = System.currentTimeMillis()
                        if (syncedBlock > 0 && nowMs - lastSyncedAtWrittenMs > 60_000L) {
                            lastSyncedAtWrittenMs = nowMs
                            walletPreferences.setLastSyncedAt(nowMs)
                        }

                        val justReachedTip = wasSyncing && info.isSynced
                        wasSyncing = !info.isSynced

                        // Edge-track first time we entered "catching up" (actively
                        // downloading blocks) so HomeViewModel can apply a grace
                        // period before showing the sync coachmark (#90).
                        val catching = !info.isSynced && info.percentage < 100
                        firstCatchingUpAtMs = computeFirstCatchingUpAtMs(
                            firstCatchingUpAtMs,
                            catching,
                            System.currentTimeMillis()
                        )

                        _syncProgress.value = SyncProgress(
                            isSyncing = !info.isSynced,
                            syncedToBlock = syncedBlock,
                            tipBlockNumber = tipBlock,
                            percentage = info.percentage,
                            etaDisplay = info.etaDisplay,
                            justReachedTip = justReachedTip,
                            firstCatchingUpAtMs = firstCatchingUpAtMs
                        )
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Sync polling: failed to get account status", e)
                    }
    }

    /**
     * Stop centralized sync polling. Resets tracker state.
     */
    fun stopSyncPolling() {
        // Bump the generation FIRST so any in-flight onSuccess lambda sees a
        // mismatch and refuses to write before we clear state below.
        syncPollingGeneration++
        syncPollingJob?.cancel()
        syncPollingJob = null
        syncProgressTracker.reset()
        wasSyncing = false
        // Clear the coachmark grace tracker (#90) so a subsequent
        // startSyncPolling() restarts the 2s grace from a clean clock.
        // Also strip the timestamp from the last-emitted SyncProgress so
        // HomeViewModel's combine doesn't see stale state during the gap.
        firstCatchingUpAtMs = null
        _syncProgress.value = _syncProgress.value.copy(firstCatchingUpAtMs = null)
        Log.d(TAG, "Stopped centralized sync polling")
    }

    // ========================================
    // Background Sync Service
    // ========================================

    /**
     * Start the foreground sync service if background sync is enabled.
     */
    fun startBackgroundSync() {
        if (!walletPreferences.isBackgroundSyncEnabled()) {
            Log.d(TAG, "Background sync disabled, not starting service")
            return
        }
        Log.d(TAG, "Starting background sync service")
        SyncForegroundService.start(context)
    }

    /**
     * Stop the foreground sync service.
     */
    fun stopBackgroundSync() {
        Log.d(TAG, "Stopping background sync service")
        SyncForegroundService.stop(context)
    }

    companion object {
        private const val TAG = "GatewayRepository"

        /**
         * Cursor-walk caps — runaway guards far above real usage
         * (100 items/page → 20k txs, 5k cells). Hitting one is logged.
         */
        private const val MAX_TX_PAGES = 200
        private const val MAX_CELL_PAGES = 50

        // Matches SEND_ERROR_PREFIX in the Rust JNI (query.rs): nativeSendTransaction
        // returns "__SEND_ERROR__:<reason>" on a rejected broadcast instead of null.
        private const val BROADCAST_ERROR_PREFIX = "__SEND_ERROR__:"

        // MAX_CONCURRENT_WALLET_SCRIPTS + BALANCED_LAG_THRESHOLD moved to
        // SyncCoordinator (#106). Tests now import SyncCoordinator.BALANCED_LAG_THRESHOLD
        // directly.
    }
}
