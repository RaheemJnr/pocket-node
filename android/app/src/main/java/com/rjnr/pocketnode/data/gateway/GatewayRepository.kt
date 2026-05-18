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
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import com.rjnr.pocketnode.data.wallet.SyncStrategy
import com.nervosnetwork.ckblightclient.LightClientNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
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
 * Prefill data extracted from a FAILED `pending_broadcasts` row, used to
 * pre-populate `SendScreen` when the user taps the Failed chip's retry CTA.
 */
data class FailedTxPrefill(
    val recipientAddress: String,
    val amountShannons: Long
)

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

/**
 * Pure BALANCED filter algorithm — no I/O. Extracted from GatewayRepository
 * so unit tests exercise the production implementation directly without
 * having to construct a full GatewayRepository instance.
 *
 * Returns Pair(kept, dropped). Active wallet always lands in `kept`.
 */
internal fun balancedFilterAlgorithm(
    wallets: List<WalletEntity>,
    progressByWalletId: Map<String, Long>,
    activeId: String,
    threshold: Long
): Pair<List<WalletEntity>, List<WalletEntity>> {
    if (wallets.size <= 1) return wallets to emptyList()
    val maxProgress = progressByWalletId.values.maxOrNull() ?: 0L
    return wallets.partition { wallet ->
        val lag = maxProgress - (progressByWalletId[wallet.walletId] ?: 0L)
        wallet.walletId == activeId || lag <= threshold
    }
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
    private val broadcastClient: BroadcastClient
) : TipSource {
    private val sendMutex = Mutex()

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

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _nodeReady = MutableStateFlow<Boolean?>(null)
    private var activeWalletId: String = walletPreferences.getActiveWalletId() ?: ""
    private var activeWalletType: String = KeyManager.WALLET_TYPE_MNEMONIC

    /**
     * Cache of the last BALANCED-eligible wallet ID set, so the periodic poll can
     * cheaply detect whether the eligible set has changed and only re-issue
     * nativeSetScripts when it actually has.
     *
     * @Volatile so concurrent reads (poll path) and writes (wallet switch) don't tear.
     * Worst-case race = one extra nativeSetScripts call, which is benign.
     *
     * MUTATED ONLY BY registerAllWalletScripts. applyBalancedFilter stays pure.
     */
    @Volatile
    private var lastBalancedEligibleSet: Set<String> = emptySet()

    /**
     * Reverse mapping: lock-script `args` → walletId, populated by setScriptsAndRecord.
     *
     * Lets the sync poll fan progress out to every registered wallet, not just the
     * active one. Without this, non-active wallets' localSavedBlockNumber rows go
     * stale under BALANCED — the filter then mis-classifies them as laggards based
     * on stale data, drops them, and they actually fall behind.
     *
     * @Volatile because reads (sync poll) and writes (registration) happen on
     * different coroutine dispatchers. Worst-case torn read = one poll cycle's
     * progress for a newly-registered wallet is dropped — corrected on next poll.
     */
    @Volatile
    private var scriptArgsToWalletId: Map<String, String> = emptyMap()

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
        val privateKey = keyManager.getPrivateKeyForWallet(wallet.walletId)
            ?: throw Exception("No key for wallet ${wallet.walletId}")
        val info = keyManager.deriveWalletInfo(privateKey)
        _walletInfo.value = info
        _balance.value = null  // Clear old wallet's balance immediately
        _isRegistered.value = false
        // Drop the previous wallet's sync samples so the new wallet's progress
        // starts from its own baseline. Otherwise ACTIVE_ONLY switches can spuriously
        // report progress / ETA / justReachedTip from the old wallet's syncing window.
        syncProgressTracker.reset()
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
            throw IllegalStateException("upgrade-smoke negative control")

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
            // Use wallet-scoped keys for the active wallet, not global legacy prefs
            val info = if (activeWalletId.isNotEmpty()) {
                val privateKey = keyManager.getPrivateKeyForWallet(activeWalletId)
                    ?: throw Exception("No key for active wallet $activeWalletId")
                // Set activeWalletType from Room entity
                val activeWallet = walletDao.getActive()
                activeWalletType = activeWallet?.type ?: KeyManager.WALLET_TYPE_MNEMONIC
                keyManager.deriveWalletInfo(privateKey)
            } else {
                keyManager.getWalletInfo() // fallback for legacy single-wallet
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
     * Returns true if the current wallet is a mnemonic wallet that hasn't completed backup verification.
     * Used by MainActivity to gate access to the dashboard until backup is done.
     */
    suspend fun needsMnemonicBackup(): Boolean {
        return activeWalletType == KeyManager.WALLET_TYPE_MNEMONIC
            && !hasMnemonicBackupForActiveWallet()
    }

    fun wasResetDueToCorruption(): Boolean = keyManager.wasResetDueToCorruption()

    /**
     * Create a brand new wallet and register with optimized sync
     */
    suspend fun createNewWallet(): Result<WalletInfo> = runCatching {
        Log.d(TAG, "🆕 Creating brand new wallet...")
        val info = keyManager.generateWallet()
        _walletInfo.value = info
        
        // For new wallets, use NEW_WALLET sync mode (starts from checkpoint)
        registerAccount(syncMode = SyncMode.NEW_WALLET)
        info
    }

    /**
     * Import an existing wallet
     */
    suspend fun importExistingWallet(privateKeyHex: String, syncMode: SyncMode = SyncMode.RECENT): Result<WalletInfo> = runCatching {
        Log.d(TAG, "📥 Importing existing wallet...")
        val info = keyManager.importWallet(privateKeyHex)
        _walletInfo.value = info
        _isRegistered.value = false
        
        // For imported wallets, we use the provided sync mode (default RECENT)
        registerAccount(syncMode = syncMode)
        info
    }

    suspend fun importWallet(privateKeyHex: String): Result<WalletInfo> = runCatching {
        importExistingWallet(privateKeyHex).getOrThrow()
    }

    /**
     * Create a new wallet with BIP39 mnemonic.
     * Returns wallet info and the 12/24 mnemonic words for backup.
     */
    suspend fun createWalletWithMnemonic(): Result<Pair<WalletInfo, List<String>>> = runCatching {
        Log.d(TAG, "Creating mnemonic wallet...")
        val (info, words) = keyManager.generateWalletWithMnemonic()
        _walletInfo.value = info
        registerAccount(syncMode = SyncMode.NEW_WALLET)
        Pair(info, words)
    }

    /**
     * Import wallet from BIP39 mnemonic words.
     */
    suspend fun importFromMnemonic(
        words: List<String>,
        passphrase: String = "",
        syncMode: SyncMode = SyncMode.RECENT
    ): Result<WalletInfo> = runCatching {
        Log.d(TAG, "Importing wallet from mnemonic...")
        val info = keyManager.importWalletFromMnemonic(words, passphrase)
        _walletInfo.value = info
        _isRegistered.value = false
        registerAccount(syncMode = syncMode)
        info
    }

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
                registerAllWalletScripts()
                if (savePreference) {
                    val wId = activeWalletId.ifEmpty { null }
                    walletPreferences.setSyncMode(syncMode, walletId = wId)
                    if (syncMode == SyncMode.CUSTOM) {
                        walletPreferences.setCustomBlockHeight(customBlockHeight, walletId = wId)
                    }
                    walletPreferences.setInitialSyncCompleted(true, walletId = wId)
                }
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
            tip.number.removePrefix("0x").toLong(16)
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

        val result = setScriptsAndRecord(scriptStatuses, listOf(activeWalletId), LightClientNative.CMD_SET_SCRIPTS_ALL)
        if (!result) throw Exception("Failed to set scripts")

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
        return registerAccount(syncMode, customBlockHeight, savePreference = true, forceResync = true)
    }

    fun hasCompletedInitialSync(): Boolean = walletPreferences.hasCompletedInitialSync(walletId = activeWalletId.ifEmpty { null })
    
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
        Log.d(TAG, "🔍 Fetching balance for script: ${json.encodeToString(searchKey)}")

        val responseJson = LightClientNative.nativeGetCellsCapacity(json.encodeToString(searchKey))
            ?: throw Exception("Failed to get capacity - null response")

        Log.d(TAG, "📊 Raw capacity response: $responseJson")

        val cap = json.decodeFromString<JniCellsCapacity>(responseJson)

        // Convert to balance response
        var capacityVal = cap.capacity.removePrefix("0x").toLong(16)

        // The light client's nativeGetCellsCapacity may include spent cells
        // We need to calculate the true balance by getting live cells only
        Log.d(TAG, "🔍 Calculating true balance by filtering out spent cells...")

        try {
            // Get all transactions to find spent outpoints (inputs)
            val txJson = LightClientNative.nativeGetTransactions(
                json.encodeToString(searchKey),
                "desc",
                100,
                null
            )

            // Build a set of spent outpoints (cells used as inputs)
            val spentOutpoints = mutableSetOf<String>()
            if (txJson != null) {
                val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
                txPag.objects.forEach { txWithCell ->
                    // For each transaction, collect all inputs as spent outpoints
                    txWithCell.transaction.inputs.forEach { input ->
                        val outpointKey = "${input.previousOutput.txHash}:${input.previousOutput.index}"
                        spentOutpoints.add(outpointKey)
                    }
                }
                Log.d(TAG, "📋 Found ${spentOutpoints.size} spent outpoints from ${txPag.objects.size} transactions")
            }

            // Get all cells and filter out spent ones
            val cellsJson = LightClientNative.nativeGetCells(
                json.encodeToString(searchKey),
                "desc",
                100,
                null
            )

            if (cellsJson != null) {
                val cellsPag = json.decodeFromString<JniPagination<JniCell>>(cellsJson)
                var liveCapacity = 0L
                var liveCellCount = 0

                cellsPag.objects.forEach { cell ->
                    val outpointKey = "${cell.outPoint.txHash}:${cell.outPoint.index}"
                    if (outpointKey !in spentOutpoints) {
                        // Exclude cells with type scripts (DAO cells, etc.) from available balance
                        // Like Neuron: typeHash IS NULL AND hasData = false
                        if (cell.output.type != null) {
                            val cellCapacity = cell.output.capacity.removePrefix("0x").toLong(16)
                            Log.d(TAG, "🔒 DAO/typed cell excluded from balance: $outpointKey = $cellCapacity shannons")
                        } else {
                            val cellCapacity = cell.output.capacity.removePrefix("0x").toLong(16)
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

                // If we have 0 live cells but transactions exist, trigger rescan
                if (liveCapacity == 0L && txJson != null) {
                    val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
                    if (txPag.objects.isNotEmpty()) {
                        Log.w(TAG, "🔄 Have ${txPag.objects.size} transactions but 0 live cells - triggering rescan")
                        val earliestBlock = txPag.objects.minOfOrNull {
                            it.blockNumber.removePrefix("0x").toLong(16)
                        } ?: 0L
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
                        setScriptsAndRecord(scriptStatuses, listOf(activeWalletId), LightClientNative.CMD_SET_SCRIPTS_PARTIAL)
                        setWalletSyncBlock(activeWalletId, rescanFrom)
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
            tip.number.removePrefix("0x").toLong(16)
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
        val mapping = scriptArgsToWalletId
        var anyUpdated = false
        scripts.forEach { script ->
            val walletId = mapping[script.script.args] ?: return@forEach
            val block = script.blockNumber.removePrefix("0x").toLong(16)
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

        // Active wallet's block for the sync-progress display below.
        val activeArgs = _walletInfo.value?.script?.args
        val scriptBlockNumber = if (activeArgs != null) {
            scripts.find { it.script.args == activeArgs }
                ?.blockNumber?.removePrefix("0x")?.toLong(16) ?: 0L
        } else {
            scripts.firstOrNull()?.blockNumber?.removePrefix("0x")?.toLong(16) ?: 0L
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

        Log.d(TAG, "🔍 getCells: Fetching cells for script: ${json.encodeToString(searchKey)}")

        // First, get all transactions to find spent outpoints
        val txJson = LightClientNative.nativeGetTransactions(
            json.encodeToString(searchKey),
            "desc",
            100,
            null
        )

        val spentOutpoints = mutableSetOf<String>()
        if (txJson != null) {
            val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
            txPag.objects.forEach { txWithCell ->
                txWithCell.transaction.inputs.forEach { input ->
                    val outpointKey = "${input.previousOutput.txHash}:${input.previousOutput.index}"
                    spentOutpoints.add(outpointKey)
                }
            }
            Log.d(TAG, "📋 getCells: Found ${spentOutpoints.size} spent outpoints")
        }

        val resultJson = LightClientNative.nativeGetCells(
            json.encodeToString(searchKey),
            "desc",
            limit,
            cursor
        ) ?: throw Exception("Failed to get cells - native returned null")

        Log.d(TAG, "📦 getCells: Raw response length: ${resultJson.length}")

        // Parse as JniCell and filter out spent cells
        val pag = json.decodeFromString<JniPagination<JniCell>>(resultJson)
        val liveCells = pag.objects.filter { cell ->
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

        Log.d(TAG, "✅ getCells: ${liveCells.size} live cells (filtered from ${pag.objects.size} total)")
        liveCells.forEachIndexed { index, cell ->
            Log.d(TAG, "  Cell[$index]: capacity=${cell.capacity}, outPoint=${cell.outPoint.txHash.take(20)}...")
        }

        CellsResponse(liveCells, pag.lastCursor)
    }

    private suspend fun currentTipNumberOrZero(): Long = try {
        val tipStr = LightClientNative.nativeGetTipHeader() ?: return 0L
        val tip = json.decodeFromString<JniHeaderView>(tipStr)
        tip.number.removePrefix("0x").toLong(16)
    } catch (e: Exception) {
        Log.w(TAG, "currentTipNumberOrZero failed: ${e.message}")
        0L
    }

    /**
     * Single mutex-guarded prepare-and-send. Runs cell-fetch, reservation
     * filter, build, sign, and pre-broadcast persistence all inside
     * [sendMutex] — closing the read-filter-insert race that would
     * otherwise let two rapid sends pick the same input cells (#115).
     *
     * The JNI broadcast call happens AFTER the mutex is released —
     * locking that would needlessly serialize all sends. [sendTransaction]
     * is idempotent on the pre-inserted hash, so it skips the duplicate
     * insert and just performs the broadcast + post-broadcast CAS.
     */
    suspend fun prepareAndSend(
        fromAddress: String,
        toAddress: String,
        amountShannons: Long,
        privateKey: ByteArray
    ): Result<String> = runCatching {
        // Snapshot every piece of sender state at function entry. The user can
        // switch wallet/network mid-send (rare, but possible — Settings is one
        // tap away); we must not let live `_walletInfo.value` / `currentNetwork`
        // reads inside the mutex retarget the send to the new wallet while we
        // persist rows under the old walletId. fromAddress is the authoritative
        // sender identity here — it was captured by SendViewModel before this
        // call and we trust it over live repository globals.
        val senderNetwork = currentNetwork
        val walletId = activeWalletId
        val network = senderNetwork.name
        val tipNumber = currentTipNumberOrZero()
        publishTip(tipNumber)

        val signedTx = sendMutex.withLock {
            // getCells(fromAddress) decodes the address to a script — honors the
            // snapshot rather than reading _walletInfo.value live.
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
            val pendingChange: List<Cell> = pending.flatMap { row ->
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
            val filtered = liveFiltered + pendingChange
            Log.d(
                TAG,
                "prepareAndSend: ${cellsResult.items.size} live, ${reserved.size} reserved, " +
                    "${pendingChange.size} synthetic-change, ${filtered.size} available"
            )

            val signed = transactionBuilder.buildTransfer(
                fromAddress = fromAddress,
                toAddress = toAddress,
                amountShannons = amountShannons,
                availableCells = filtered,
                privateKey = privateKey,
                network = senderNetwork
            )

            val txHash = transactionBuilder.computeTxHash(signed)
            val txJson = json.encodeToString(signed)
            val reservedJson = json.encodeToString(signed.cellInputs.map { it.previousOutput })
            val now = System.currentTimeMillis()

            // Outgoing amount for activity-row balanceChange. Stored as POSITIVE
            // hex per existing convention; `direction = "out"` carries the sign
            // for the UI. (Prior code used "-0x..." which broke capacityAsLong's
            // hex parser and rendered as 0.)
            val outgoingAmount = signed.cellOutputs
                .minOfOrNull { it.capacity.removePrefix("0x").toLong(16) }
                ?: amountShannons
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
        sendTransaction(signedTx).getOrThrow()
    }

    /**
     * Loads a FAILED `pending_broadcasts` row, decodes the recipient/amount
     * from its signed tx, and removes the failed-state rows so the retry
     * doesn't see itself as a reservation. Caller (HomeViewModel) navigates
     * to SendScreen with the returned prefill.
     *
     * Heuristic: smallest-capacity output is the recipient (matches what
     * `sendTransaction` uses for `balanceChange`). For "send all" txs there's
     * only one output and the heuristic still resolves correctly.
     */
    suspend fun loadFailedForRetry(txHash: String): Result<FailedTxPrefill> = runCatching {
        val row = pendingBroadcastDao.getFailedRow(txHash)
            ?: error("This transaction is too old to retry automatically. Please send a new one.")
        val tx = json.decodeFromString<Transaction>(row.signedTxJson)
        val recipientOutput = tx.cellOutputs.minByOrNull {
            it.capacity.removePrefix("0x").toLong(16)
        } ?: error("Tx has no outputs")
        val recipientAmount = recipientOutput.capacity.removePrefix("0x").toLong(16)
        val recipientAddress = AddressUtils.encode(recipientOutput.lock, currentNetwork)
        pendingBroadcastDao.delete(txHash)
        cacheManager.deleteTransaction(txHash)
        FailedTxPrefill(recipientAddress, recipientAmount)
    }

    suspend fun sendTransaction(transaction: Transaction): Result<String> = runCatching {
        Log.d(TAG, "📤 sendTransaction: building JSON")
        Log.d(TAG, "  Inputs: ${transaction.cellInputs.size}, Outputs: ${transaction.cellOutputs.size}")

        // Pre-flight checks (defense-in-depth, TransactionBuilder also validates)
        require(transaction.cellInputs.isNotEmpty()) { "Transaction has no inputs" }
        require(transaction.cellOutputs.isNotEmpty()) { "Transaction has no outputs" }
        for (output in transaction.cellOutputs) {
            val capacity = output.capacity.removePrefix("0x").toLong(16)
            require(capacity >= TransactionBuilder.MIN_CELL_CAPACITY) {
                "Output capacity ${capacity / 100_000_000.0} CKB is below minimum 61 CKB"
            }
        }

        // Snapshot at entry — pin to whichever wallet/network the user was on.
        val walletId = activeWalletId
        val network = currentNetwork.name
        val tipNumber = currentTipNumberOrZero()
        publishTip(tipNumber)
        val txJson = json.encodeToString(transaction)
        val txHash = transactionBuilder.computeTxHash(transaction)
        val reservedJson = json.encodeToString(
            transaction.cellInputs.map { it.previousOutput }
        )

        // Compute balanceChange = -(smallest output) for the activity row.
        // For a normal transfer the smallest output is the recipient; for a
        // "send all" there's only one output. Either way: smallest by capacity.
        val outgoingAmount = transaction.cellOutputs
            .minOfOrNull { it.capacity.removePrefix("0x").toLong(16) }
            ?: 0L
        // Positive hex per existing convention; `direction = "out"` carries sign.
        val balanceChangeHex = "0x${outgoingAmount.toString(16)}"
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

        val returnedHash = rawResult.trim('"')
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
                val tipStr = LightClientNative.nativeGetTipHeader()
                if (tipStr != null && senderInfo != null) {
                    val tip = json.decodeFromString<JniHeaderView>(tipStr)
                    val tipNumber = tip.number.removePrefix("0x").toLong(16)
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

        val resultJson = LightClientNative.nativeGetTransactions(
            json.encodeToString(searchKey),
            "desc",
            limit,
            cursor
        ) ?: throw Exception("Failed to get transactions")

        Log.d(TAG, "📡 getTransactions raw JSON length: ${resultJson.length}")

        val pag = json.decodeFromString<JniPagination<JniTxWithCell>>(resultJson)

        // Group by transaction hash to show a clean "one entry per transaction" UI
        val groupedTransactions = pag.objects.groupBy { it.transaction.hash }

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
                val cap = interaction.ioCapacity.removePrefix("0x").toLong(16)
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
                    .capacity.removePrefix("0x").toLong(16)
                if (tx.headerDeps.isEmpty()) {
                    "dao_deposit" to daoOutputCapacity
                } else {
                    "dao_withdraw" to daoOutputCapacity
                }
            } else if (tx.headerDeps.size >= 2) {
                // Unlock: show total CKB returned (deposit + compensation)
                val totalOutput = cellInteractions
                    .filter { it.ioType == "output" }
                    .sumOf { it.ioCapacity.removePrefix("0x").toLong(16) }
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

        // --- Cache write: upsert JNI results into Room ---
        cacheManager.cacheTransactions(items, currentNetwork.name, walletId = activeWalletId)

        // Merge: include pending local txs not yet returned by JNI
        val jniTxHashes = items.map { it.txHash }.toSet()
        val pendingLocal = cacheManager.getPendingNotIn(currentNetwork.name, jniTxHashes, walletId = activeWalletId)
        val mergedItems = pendingLocal + items

        TransactionsResponse(mergedItems, pag.lastCursor)
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
                val tipNumber = tip.number.removePrefix("0x").toLong(16)

                // Get tx's block header to compute real confirmation depth
                val txBlockJson = LightClientNative.nativeGetHeader(txWithStatus.txStatus.blockHash!!)
                if (txBlockJson != null) {
                    val txBlock = json.decodeFromString<JniHeaderView>(txBlockJson)
                    val txBlockNumber = txBlock.number.removePrefix("0x").toLong(16)
                    val realConfirmations = (tipNumber - txBlockNumber + 1).coerceAtLeast(1).toInt()
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
            match?.blockNumber?.removePrefix("0x")?.toLong(16) ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get existing script block: ${e.message}")
            0L
        }
    }

    // Status UI methods
    suspend fun getPeers(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetPeers()
    }

    suspend fun getTipHeader(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetTipHeader()
    }

    suspend fun getScripts(): String? = withContext(Dispatchers.IO) {
        LightClientNative.nativeGetScripts()
    }

    suspend fun callRpc(method: String): String? = withContext(Dispatchers.IO) {
        LightClientNative.callRpc(method)
    }

    // ========================================
    // DAO Operations
    // ========================================

    suspend fun getCurrentEpoch(): Result<EpochInfo> = runCatching {
        val headerJson = LightClientNative.nativeGetTipHeader()
            ?: throw Exception("Failed to get tip header")
        val header = json.decodeFromString<JniHeaderView>(headerJson)
        EpochInfo.fromHex(header.epoch)
    }

    /**
     * Helper: get block hash for a cell by looking up its transaction.
     * The light client's get_transaction often returns null block_hash,
     * so we fall back to fetch_transaction which requests from peers.
     */
    private suspend fun getBlockHashForCell(txHash: String): String? {
        // Try local cache first
        val txJson = LightClientNative.nativeGetTransaction(txHash)
        if (txJson != null) {
            val txWithStatus = json.decodeFromString<JniTransactionWithStatus>(txJson)
            if (txWithStatus.txStatus.blockHash != null) {
                return txWithStatus.txStatus.blockHash
            }
            Log.d(TAG, "  get_transaction found tx but no block_hash, trying fetch_transaction...")
        } else {
            Log.d(TAG, "  get_transaction returned null, trying fetch_transaction...")
        }

        // Fallback: fetch from peers (may need retries as it's async)
        for (attempt in 1..3) {
            val fetchJson = LightClientNative.nativeFetchTransaction(txHash)
            if (fetchJson == null) {
                Log.w(TAG, "  fetch_transaction returned null on attempt $attempt")
                break
            }
            val fetchResp = json.decodeFromString<JniFetchTransactionResponse>(fetchJson)
            Log.d(TAG, "  fetch_transaction attempt $attempt: status=${fetchResp.status}")
            if (fetchResp.status == "fetched" && fetchResp.data != null) {
                return fetchResp.data.txStatus.blockHash
            }
            // "fetching" or "added" — wait and retry
            if (fetchResp.status == "fetching" || fetchResp.status == "added") {
                delay(2000)
            } else {
                break // unknown status, don't retry
            }
        }
        return null
    }

    /**
     * Fetch a block header from peers via nativeFetchHeader with retries.
     * The light client only stores headers for blocks it has processed locally;
     * for older blocks we need to request them from the peer network.
     */
    private suspend fun fetchHeaderWithRetry(blockHash: String): JniHeaderView? {
        for (attempt in 1..3) {
            val fetchJson = LightClientNative.nativeFetchHeader(blockHash)
            if (fetchJson == null) {
                Log.w(TAG, "  fetch_header returned null on attempt $attempt")
                break
            }
            val fetchResp = json.decodeFromString<JniFetchHeaderResponse>(fetchJson)
            Log.d(TAG, "  fetch_header attempt $attempt: status=${fetchResp.status}")
            if (fetchResp.status == "fetched" && fetchResp.data != null) {
                return fetchResp.data
            }
            if (fetchResp.status == "fetching" || fetchResp.status == "added") {
                delay(2000)
            } else {
                break
            }
        }
        return null
    }

    /**
     * Cache-first header lookup: Room DB → local JNI → peer fetch.
     * Block headers are immutable, so cached results are always valid.
     */
    private suspend fun getOrFetchHeader(blockHash: String): JniHeaderView? {
        // 1. Check Room cache
        val cached = daoSyncManager.getCachedHeader(blockHash)
        if (cached != null) {
            return cached.toJniHeaderView()
        }

        // 2. Try local JNI (light client may have it in memory)
        val localJson = LightClientNative.nativeGetHeader(blockHash)
        if (localJson != null) {
            val header = json.decodeFromString<JniHeaderView>(localJson)
            daoSyncManager.cacheHeader(header, currentNetwork.name)
            return header
        }

        // 3. Fetch from peers
        val fetched = fetchHeaderWithRetry(blockHash)
        if (fetched != null) {
            daoSyncManager.cacheHeader(fetched, currentNetwork.name)
        }
        return fetched
    }

    suspend fun getDaoDeposits(): Result<List<DaoDeposit>> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")

        // Query ALL cells by lock script (like Neuron), then filter locally for DAO type
        val searchKey = JniSearchKey(
            script = info.script,
            scriptType = "lock",
            withData = true
        )
        val searchKeyJson = json.encodeToString(searchKey)

        // Paginate all cells by lock script
        val allCellObjects = mutableListOf<JniCell>()
        var cellsCursor: String? = null
        do {
            val pageJson = LightClientNative.nativeGetCells(searchKeyJson, "desc", 100, cellsCursor)
                ?: break
            val page = json.decodeFromString<JniPagination<JniCell>>(pageJson)
            allCellObjects += page.objects
            cellsCursor = page.lastCursor?.takeUnless { it == cellsCursor }
        } while (cellsCursor != null)

        // Filter locally: only cells whose type script matches DAO code hash
        val daoCells = allCellObjects.filter { cell ->
            cell.output.type?.codeHash == DaoConstants.DAO_CODE_HASH
        }
        Log.d(TAG, "📋 getDaoDeposits: ${daoCells.size} DAO cells out of ${allCellObjects.size} total")

        // Paginate transactions to find spent outpoints
        val spentOutpoints = mutableSetOf<String>()
        var txCursor: String? = null
        do {
            val txJson = LightClientNative.nativeGetTransactions(searchKeyJson, "desc", 100, txCursor)
                ?: break
            val txPag = json.decodeFromString<JniPagination<JniTxWithCell>>(txJson)
            txPag.objects.forEach { txWithCell ->
                txWithCell.transaction.inputs.forEach { input ->
                    spentOutpoints.add("${input.previousOutput.txHash}:${input.previousOutput.index}")
                }
            }
            txCursor = txPag.lastCursor?.takeUnless { it == txCursor }
        } while (txCursor != null)

        val liveDaoCells = daoCells.filter { cell ->
            val key = "${cell.outPoint.txHash}:${cell.outPoint.index}"
            key !in spentOutpoints
        }
        Log.d(TAG, "📋 getDaoDeposits: ${liveDaoCells.size} live DAO cells")

        val pagination = JniPagination(objects = liveDaoCells, lastCursor = null)

        val currentEpoch = getCurrentEpoch().getOrNull()

        val deposits = mutableListOf<DaoDeposit>()
        for (jniCell in pagination.objects) {
            val cell = jniCell.toCell()
            val data = cell.data.removePrefix("0x")
            val cellId = "${cell.outPoint.txHash.take(20)}...:${cell.outPoint.index}"
            val capacityShannons = cell.capacity.removePrefix("0x").toLong(16)

            // Determine if deposit or withdrawing cell
            val isWithdrawing = data.length == 16 && data != "0000000000000000"
            Log.d(TAG, "🏦 Processing DAO cell: $cellId, data=$data, isWithdrawing=$isWithdrawing, capacity=$capacityShannons")

            var depositBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
            var depositBlockHash = "" // may remain empty if header unavailable
            var depositEpoch: EpochInfo? = null
            var withdrawBlockNumber: Long? = null
            var withdrawBlockHash: String? = null
            var withdrawEpoch: EpochInfo? = null
            var compensation = 0L
            var unlockEpoch: EpochInfo? = null
            var lockRemainingHours: Int? = null
            var depositTimestampMs = 0L
            var apc = 0.0

            // Get block header for compensation & epoch data (cache-first).
            val blockHash = getBlockHashForCell(cell.outPoint.txHash)
            val cellBlockHeader = if (blockHash != null) {
                getOrFetchHeader(blockHash)
            } else null

            if (cellBlockHeader != null) {
                val cellBlockEpoch = EpochInfo.fromHex(cellBlockHeader.epoch)
                depositBlockHash = cellBlockHeader.hash
                depositEpoch = cellBlockEpoch
                // Default timestamp from this cell's block; overridden for withdrawing cells below
                depositTimestampMs = cellBlockHeader.timestamp.removePrefix("0x").toLong(16)

                if (isWithdrawing) {
                    // Cell data contains deposit block number as 8-byte LE
                    val depositBlockNum = data.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                        .let { bytes ->
                            var num = 0L
                            for (i in bytes.indices) {
                                num = num or ((bytes[i].toLong() and 0xFF) shl (i * 8))
                            }
                            num
                        }

                    withdrawBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
                    withdrawBlockHash = cellBlockHeader.hash
                    withdrawEpoch = cellBlockEpoch
                    depositBlockNumber = depositBlockNum

                    // Get original deposit header for compensation (cache-first)
                    val withdrawTxJson = LightClientNative.nativeGetTransaction(cell.outPoint.txHash)
                    val withdrawTx = withdrawTxJson?.let { json.decodeFromString<JniTransactionWithStatus>(it) }
                    val origDepositBlockHash = withdrawTx?.transaction?.headerDeps?.firstOrNull()
                    // Preserve the hash even if full header fetch fails
                    if (origDepositBlockHash != null) {
                        depositBlockHash = origDepositBlockHash
                    }
                    val origDepositHeader = origDepositBlockHash?.let { h ->
                        getOrFetchHeader(h)
                    }

                    if (origDepositHeader != null) {
                        depositBlockHash = origDepositHeader.hash
                        depositEpoch = EpochInfo.fromHex(origDepositHeader.epoch)
                        depositTimestampMs = origDepositHeader.timestamp.removePrefix("0x").toLong(16)
                        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
                            origDepositHeader.dao, cellBlockHeader.dao, capacityShannons, 61_00000000L
                        )
                        if (maxWithdraw >= 0) compensation = maxWithdraw - capacityShannons
                        val sinceHex = LightClientNative.nativeCalculateUnlockEpoch(
                            origDepositHeader.epoch, cellBlockHeader.epoch
                        )
                        if (sinceHex != null) {
                            val epochVal = sinceHex.removePrefix("0x").toLong(16) and 0x00FF_FFFF_FFFF_FFFFL
                            unlockEpoch = EpochInfo.fromHex("0x${epochVal.toString(16)}")
                            if (currentEpoch != null && unlockEpoch!!.value > currentEpoch.value) {
                                lockRemainingHours = ((unlockEpoch!!.value - currentEpoch.value) * DaoConstants.HOURS_PER_EPOCH).toInt()
                            }
                        }
                    }
                } else {
                    // Deposited cell — calculate compensation using current tip header
                    val tipJson = LightClientNative.nativeGetTipHeader()
                    if (tipJson != null) {
                        val tipHeader = json.decodeFromString<JniHeaderView>(tipJson)
                        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
                            cellBlockHeader.dao, tipHeader.dao, capacityShannons, 61_00000000L
                        )
                        if (maxWithdraw >= 0) compensation = maxWithdraw - capacityShannons
                    }
                }
            } else {
                Log.d(TAG, "  No header available for $cellId — showing deposit with basic info")
                if (isWithdrawing) {
                    val depositBlockNum = data.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                        .let { bytes ->
                            var num = 0L
                            for (i in bytes.indices) {
                                num = num or ((bytes[i].toLong() and 0xFF) shl (i * 8))
                            }
                            num
                        }
                    withdrawBlockNumber = cell.blockNumber.removePrefix("0x").toLong(16)
                    depositBlockNumber = depositBlockNum
                }
            }

            // Compute per-deposit APC when enough time has elapsed
            if (compensation > 0 && depositTimestampMs > 0 && capacityShannons > 0) {
                val elapsedDays = (System.currentTimeMillis() - depositTimestampMs) / 86_400_000.0
                if (elapsedDays >= 1.0) {
                    apc = (compensation.toDouble() / capacityShannons) / (elapsedDays / 365.25) * 100.0
                }
            }

            // Calculate cycle progress (best-effort with available epoch data)
            val depositedEpochs = if (currentEpoch != null && depositEpoch != null) {
                (currentEpoch.value - depositEpoch.value).coerceAtLeast(0.0)
            } else 0.0
            val cycleProgress = ((depositedEpochs % DaoConstants.WITHDRAW_EPOCHS) / DaoConstants.WITHDRAW_EPOCHS).toFloat()

            val status = determineDaoStatus(
                isWithdrawingCell = isWithdrawing,
                hasPendingWithdraw = false,
                hasPendingUnlock = false,
                hasPendingDeposit = false,
                currentEpoch = currentEpoch,
                unlockEpoch = unlockEpoch
            )

            deposits.add(DaoDeposit(
                outPoint = cell.outPoint,
                capacity = capacityShannons,
                status = status,
                depositBlockNumber = depositBlockNumber,
                depositBlockHash = depositBlockHash,
                depositEpoch = depositEpoch,
                withdrawBlockNumber = withdrawBlockNumber,
                withdrawBlockHash = withdrawBlockHash,
                withdrawEpoch = withdrawEpoch,
                compensation = compensation.coerceAtLeast(0L),
                unlockEpoch = unlockEpoch,
                lockRemainingHours = lockRemainingHours,
                compensationCycleProgress = cycleProgress,
                cyclePhase = cyclePhaseFromProgress(cycleProgress),
                depositTimestamp = depositTimestampMs,
                apc = apc
            ))
            Log.d(TAG, "✅ DAO deposit added: $cellId, status=$status, capacity=$capacityShannons")
        }
        deposits
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

    suspend fun depositToDao(amountShannons: Long): Result<String> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val net = _network.value

        require(amountShannons >= DaoConstants.MIN_DEPOSIT_SHANNONS) {
            "Minimum deposit is ${DaoConstants.MIN_DEPOSIT_SHANNONS / 100_000_000} CKB"
        }

        val cellsResponse = getCells().getOrThrow()
        val privateKey = getPrivateKey()

        val tx = transactionBuilder.buildDaoDeposit(
            amountShannons = amountShannons,
            availableCells = cellsResponse.items,
            senderScript = info.script,
            privateKey = privateKey,
            network = net
        )

        val txHash = sendTransaction(tx).getOrThrow()
        Log.d(TAG, "DAO deposit sent: $txHash")

        // Track pending deposit in Room so UI shows it before JNI confirms
        daoSyncManager.insertPendingDeposit(txHash, amountShannons, net.name, walletId = activeWalletId)

        txHash
    }

    suspend fun withdrawFromDao(depositOutPoint: OutPoint): Result<String> = runCatching {
        val info = _walletInfo.value ?: throw Exception("No wallet")
        val net = _network.value
        val address = getCurrentAddress() ?: throw Exception("No address")

        // Find the deposit cell
        val deposits = getDaoDeposits().getOrThrow()
        val deposit = deposits.find { it.outPoint == depositOutPoint }
            ?: throw Exception("Deposit not found")

        val privateKey = getPrivateKey()

        require(deposit.depositBlockHash.isNotBlank()) {
            "Deposit block hash unavailable. Please retry after sync."
        }

        // Fetch normal cells to cover the fee — DAO Phase 1 preserves the
        // deposit capacity exactly so a fee input cell is mandatory (#119).
        // getCells already excludes typed (DAO) cells, so this list is only
        // regular CKB cells safe to spend as fee.
        val availableCells = getCells(address).getOrThrow().items

        // Build a Cell from the deposit for the transaction builder
        val depositCell = Cell(
            outPoint = deposit.outPoint,
            capacity = "0x${deposit.capacity.toString(16)}",
            blockNumber = "0x${deposit.depositBlockNumber.toString(16)}",
            lock = info.script,
            type = DaoConstants.DAO_TYPE_SCRIPT,
            data = "0x" + DaoConstants.DAO_DEPOSIT_DATA.joinToString("") { "%02x".format(it) }
        )

        val tx = transactionBuilder.buildDaoWithdraw(
            depositCell = depositCell,
            depositBlockNumber = deposit.depositBlockNumber,
            depositBlockHash = deposit.depositBlockHash,
            senderScript = info.script,
            privateKey = privateKey,
            network = net,
            availableCells = availableCells
        )

        val txHash = sendTransaction(tx).getOrThrow()
        Log.d(TAG, "DAO withdraw (phase 1) sent: $txHash")
        txHash
    }

    suspend fun unlockDao(
        withdrawingOutPoint: OutPoint
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

        val privateKey = getPrivateKey()

        // Get headers for max withdraw calculation (cache-first)
        val depositHeader = getOrFetchHeader(depositBlockHash)
            ?: throw Exception("Failed to get deposit header")

        val withdrawHeader = getOrFetchHeader(withdrawBlockHash)
            ?: throw Exception("Failed to get withdraw header")

        val maxWithdraw = LightClientNative.nativeCalculateMaxWithdraw(
            depositHeader.dao,
            withdrawHeader.dao,
            deposit.capacity,
            61_00000000L
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

        val txHash = sendTransaction(tx).getOrThrow()
        Log.d(TAG, "DAO unlock (phase 2) sent: $txHash")
        txHash
    }

    /**
     * Wraps every nativeSetScripts call so lightStartBlockNumber is recorded atomically
     * with the registration. Preserves existing localSavedBlockNumber when the row exists
     * (we're updating registration metadata, not progress).
     *
     * walletIds must be parallel to statuses — same length, same order. For single-wallet
     * PARTIAL paths, pass listOf(activeWalletId).
     */
    private suspend fun setScriptsAndRecord(
        statuses: List<JniScriptStatus>,
        walletIds: List<String>,
        cmd: Int
    ): Boolean {
        require(statuses.size == walletIds.size) {
            "setScriptsAndRecord: statuses (${statuses.size}) and walletIds (${walletIds.size}) must be parallel"
        }
        val jsonStr = json.encodeToString(statuses)
        val ok = LightClientNative.nativeSetScripts(jsonStr, cmd)
        if (!ok) return false

        val now = System.currentTimeMillis()
        val newMapping = mutableMapOf<String, String>()
        statuses.zip(walletIds).forEach { (status, walletId) ->
            if (walletId.isEmpty()) return@forEach
            newMapping[status.script.args] = walletId
            val startBlock = status.blockNumber.removePrefix("0x").toLong(16)
            // Atomic UPDATE preserves localSavedBlockNumber under concurrent writes
            // from the sync poll's setWalletSyncBlock. Falls through to upsert only
            // when no row exists yet (no race possible — nothing to overwrite).
            val rowsUpdated = syncProgressDao.updateLightStart(
                walletId, currentNetwork.name, startBlock, now
            )
            if (rowsUpdated == 0) {
                syncProgressDao.upsert(
                    SyncProgressEntity(
                        walletId = walletId,
                        network = currentNetwork.name,
                        lightStartBlockNumber = startBlock,
                        localSavedBlockNumber = startBlock,
                        updatedAt = now
                    )
                )
            }
        }
        // ALL replaces the entire registered set; PARTIAL adds to it.
        scriptArgsToWalletId = if (cmd == LightClientNative.CMD_SET_SCRIPTS_ALL) {
            newMapping
        } else {
            scriptArgsToWalletId + newMapping
        }
        return true
    }

    /**
     * BALANCED strategy filter: drop wallets whose localSavedBlockNumber lags the
     * max-progress wallet by more than BALANCED_LAG_THRESHOLD blocks. Active wallet
     * always passes regardless of its own lag (otherwise the user's current view stalls).
     *
     * Reference = max localSavedBlockNumber across the candidate set, NOT the active
     * wallet's progress (Q3=B in design): survives wallet-switch correctly.
     *
     * Returns input unchanged when wallets.size <= 1.
     *
     * MUST stay pure — no cache writes. The lastBalancedEligibleSet cache is owned
     * by registerAllWalletScripts (Task 14).
     *
     * I/O wrapper — bulk-reads progress rows, delegates the partition logic to
     * the top-level pure `balancedFilterAlgorithm` so tests can exercise it
     * without constructing a full GatewayRepository.
     */
    private suspend fun applyBalancedFilter(wallets: List<WalletEntity>): List<WalletEntity> {
        if (wallets.size <= 1) return wallets

        // Bulk read: one round-trip instead of N gets.
        val rows = syncProgressDao.getAllForNetwork(currentNetwork.name)
            .associateBy { it.walletId }
        val progress = wallets.associate { wallet ->
            wallet.walletId to (rows[wallet.walletId]?.localSavedBlockNumber ?: 0L)
        }

        val (kept, dropped) = balancedFilterAlgorithm(
            wallets, progress, activeWalletId, BALANCED_LAG_THRESHOLD
        )

        if (dropped.isNotEmpty()) {
            val maxProgress = progress.values.maxOrNull() ?: 0L
            Log.i(TAG, "BALANCED: dropped ${dropped.size} laggards: " +
                dropped.map { "${it.walletId}(lag=${maxProgress - (progress[it.walletId] ?: 0L)})" })
        }
        return kept
    }

    /**
     * Cheap BALANCED re-evaluation: compute the eligible set, compare to the cached
     * lastBalancedEligibleSet; only re-issue setScripts when the set changed.
     * Caller must already be on a coroutine context.
     */
    private suspend fun maybeReregisterBalanced() {
        val allWallets = walletDao.getAll().sortedByDescending { it.lastActiveAt }
        val filtered = applyBalancedFilter(allWallets)
        val newSet = filtered.map { it.walletId }.toSet()

        if (newSet == lastBalancedEligibleSet) return

        Log.i(TAG, "BALANCED set changed (was=$lastBalancedEligibleSet, now=$newSet): re-registering")
        // Pass through the snapshot we just computed so registerAllWalletScripts
        // doesn't re-fetch + re-filter (avoids double I/O and a snapshot race
        // where wallet add/delete between calls would update the cache against
        // a different set than the comparison was made on).
        registerAllWalletScripts(preFetchedWallets = allWallets, preFilteredCandidates = filtered)
    }

    /**
     * Register lock scripts for ALL wallets with the light client simultaneously.
     * Used when SyncStrategy is ALL_WALLETS — enables balance/transaction tracking
     * across every wallet without requiring a wallet switch.
     *
     * Capped at the 3 most-recently-active wallets to bound resource usage.
     *
     * @param preFetchedWallets if non-null, skip the walletDao.getAll() round-trip.
     * @param preFilteredCandidates if non-null, skip applyBalancedFilter and use this list as the post-filter candidate set.
     */
    private suspend fun registerAllWalletScripts(
        preFetchedWallets: List<WalletEntity>? = null,
        preFilteredCandidates: List<WalletEntity>? = null
    ) = withContext(Dispatchers.IO) {
        // Force IO dispatcher for the whole body — JNI calls (nativeGetTipHeader,
        // nativeSetScripts via setScriptsAndRecord) block the UI thread otherwise.
        // Symptom #109: adding the 3rd wallet (which triggers a re-registration
        // of all scripts) flashed the screen white because the caller chain ran
        // on viewModelScope.launch (Main) and the JNI round-trip blocked Main
        // long enough for Android to render a blank surface.
        if (!awaitNodeReady()) {
            throw Exception("Node initialization failed")
        }

        val allWallets = preFetchedWallets
            ?: walletDao.getAll().sortedByDescending { it.lastActiveAt }
        val strategy = walletPreferences.getSyncStrategy()

        // Step 1: BALANCED filter runs BEFORE the cap (Q2=A in design).
        val candidateWallets = preFilteredCandidates ?: when (strategy) {
            SyncStrategy.BALANCED -> applyBalancedFilter(allWallets)
            else -> allWallets
        }
        if (strategy == SyncStrategy.BALANCED) {
            lastBalancedEligibleSet = candidateWallets.map { it.walletId }.toSet()
        }

        // Step 2: Cap (unchanged behavior for ALL_WALLETS).
        val wallets = candidateWallets.take(MAX_CONCURRENT_WALLET_SCRIPTS)
        if (candidateWallets.size > wallets.size) {
            val droppedIds = candidateWallets.drop(wallets.size).map { it.walletId }
            Log.i(
                TAG,
                "${strategy.name}: syncing top-${wallets.size} of ${candidateWallets.size} wallets " +
                    "(dropped: $droppedIds)"
            )
        }

        val tipStr = LightClientNative.nativeGetTipHeader()
        val tipHeight = if (tipStr != null) {
            val tip = json.decodeFromString<JniHeaderView>(tipStr)
            tip.number.removePrefix("0x").toLong(16)
        } else 0L

        // Per-wallet derivation is independent CPU+IO work (Room read for the
        // private key, blake2b hash for the lock script). Run them concurrently
        // so a 5-wallet ALL_WALLETS sync doesn't pay the cost serially.
        val pairs = coroutineScope {
            wallets.map { wallet ->
                async(Dispatchers.IO) {
                    val privateKey = keyManager.getPrivateKeyForWallet(wallet.walletId) ?: run {
                        Log.w(TAG, "No key for wallet ${wallet.walletId}, skipping script registration")
                        return@async null
                    }
                    val publicKey = keyManager.derivePublicKey(privateKey)
                    val lockScript = keyManager.deriveLockScript(publicKey)

                    // Resume from saved per-wallet progress, or calculate from sync mode if first sync
                    val savedBlock = getWalletSyncBlock(wallet.walletId)
                    val blockNum: String
                    if (savedBlock > 0) {
                        blockNum = savedBlock.toString()
                    } else {
                        val syncMode = walletPreferences.getSyncMode(walletId = wallet.walletId)
                        val customHeight = walletPreferences.getCustomBlockHeight(walletId = wallet.walletId)
                        val calculated = syncMode.toFromBlock(
                            if (syncMode == SyncMode.CUSTOM) customHeight else null,
                            tipHeight,
                            currentNetwork
                        )
                        val calculatedLong = calculated.toLongOrNull() ?: 0L
                        // Safety: don't start from block 0 — use checkpoint if available
                        val checkpoint = getCheckpoint(currentNetwork)
                        blockNum = if (calculatedLong == 0L && syncMode != SyncMode.FULL_HISTORY && checkpoint > 0) {
                            checkpoint.toString()
                        } else {
                            calculated
                        }
                    }
                    val blockNumberHex = "0x${blockNum.toLongOrNull()?.toString(16) ?: "0"}"

                    wallet.walletId to JniScriptStatus(
                        script = lockScript,
                        scriptType = "lock",
                        blockNumber = blockNumberHex
                    )
                }
            }.awaitAll().filterNotNull()
        }

        if (pairs.isEmpty()) {
            Log.w(TAG, "registerAllWalletScripts: no scripts to register")
            return@withContext
        }

        val scriptStatuses = pairs.map { it.second }
        val walletIds = pairs.map { it.first }
        Log.d(TAG, "Registering ${scriptStatuses.size} wallet scripts with light client")
        val result = setScriptsAndRecord(scriptStatuses, walletIds, LightClientNative.CMD_SET_SCRIPTS_ALL)
        if (!result) throw Exception("Failed to set scripts for all wallets")

        _isRegistered.value = true
    }

    // ========================================
    // Sync Progress Polling
    // ========================================

    /**
     * Start centralized sync polling. Idempotent — does nothing if already running.
     * Polls getAccountStatus(), records samples, calculates progress, and emits to syncProgress flow.
     */
    fun startSyncPolling() {
        if (syncPollingJob?.isActive == true) return

        val generation = ++syncPollingGeneration

        syncPollingJob = scope.launch {
            Log.d(TAG, "Starting centralized sync polling")
            while (true) {
                // Skip the iteration when no wallet is loaded into repo state.
                // Happens during normal lifecycle windows: lock screen (PIN not
                // entered), brief startup race before wallet decryption, after
                // session clear on background. Without this guard, getAccountStatus
                // throws "No wallet" on every poll and floods logcat with stack
                // traces that look like real errors.
                if (_walletInfo.value == null) {
                    delay(5_000L)
                    continue
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

                        syncProgressTracker.recordSample(syncedBlock, System.currentTimeMillis())
                        val info = syncProgressTracker.calculate(tipBlock)

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

                val delayMs = if (_syncProgress.value.isSyncing) 5_000L else 30_000L
                delay(delayMs)
            }
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
        // Upper bound for wallets synced simultaneously under ALL_WALLETS. Wallets
        // beyond this are dropped by lastActiveAt descending; the dropped ids are
        // logged so support can diagnose "why isn't wallet X syncing".
        private const val MAX_CONCURRENT_WALLET_SCRIPTS = 3

        /**
         * Source: Neuron's THRESHOLD_BLOCK_NUMBER_IN_DIFF_WALLET, validated in production for years.
         * Wallets lagging the max-progress wallet by more than this are dropped from the registered
         * script set (BALANCED strategy) until the leader's tail catches up.
         * https://github.com/nervosnetwork/neuron/blob/develop/packages/neuron-wallet/src/block-sync-renderer/sync/light-synchronizer.ts#L22
         */
        const val BALANCED_LAG_THRESHOLD = 100_000L
    }
}
