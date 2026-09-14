package com.rjnr.pocketnode.data.gateway

import android.content.Context
import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.NetworkPreferences
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded-node lifecycle extracted from [GatewayRepository] (#460 part 1).
 *
 * Owns everything between the app and the Rust light client's process-level
 * state:
 *
 *  1. the on-disk layout: the one-time `data/` to `data/mainnet/` migration
 *     and the per-network TOML config copied out of `assets` into `filesDir`;
 *  2. the JNI bring-up: `nativeInit` / `nativeStart` with retry, plus the
 *     status callback that feeds [nodeStatus];
 *  3. the selected network ([network], [currentNetwork]) and the
 *     restart-based [switchNetwork].
 *
 * Network switching kills the process on purpose: the Rust bridge keeps its
 * storage, runtime and peer handles in `OnceLock`s, which expose no reset, so
 * stopping and re-initializing the client cannot work in one process
 * lifetime. Do not "fix" that here without a Rust-side refactor first.
 *
 * [GatewayRepository] delegates its node-lifecycle API to this class; it is a
 * plain `@Singleton` with no dependency back on the repository.
 */
@Singleton
class NodeLifecycle @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkPreferences: NetworkPreferences,
    private val cacheManager: CacheManager,
    private val daoSyncManager: DaoSyncManager,
    private val logger: Logger,
) {

    private val _nodeStatus = MutableStateFlow("Stopped")
    val nodeStatus: StateFlow<String> = _nodeStatus.asStateFlow()

    private val _network = MutableStateFlow(networkPreferences.getSelectedNetwork())
    val network: StateFlow<NetworkType> = _network.asStateFlow()
    val currentNetwork: NetworkType get() = _network.value

    private val _isSwitchingNetwork = MutableStateFlow(false)
    val isSwitchingNetwork: StateFlow<Boolean> = _isSwitchingNetwork.asStateFlow()

    private val _nodeReady = MutableStateFlow<Boolean?>(null)

    /**
     * Marks node init as failed from outside [initializeNode]: used when the
     * repository's startup sequence throws before it ever reaches node init,
     * so [awaitNodeReady] can't suspend forever.
     */
    fun markInitFailed() {
        _nodeReady.value = false
    }

    /**
     * Suspends until the node is ready. Returns true if init succeeded, false if it failed.
     */
    suspend fun awaitNodeReady(): Boolean {
        return _nodeReady.filterNotNull().first()
    }

    /**
     * One-time migration: moves old flat data/ layout (store.db, network/) into data/mainnet/.
     * Existing users upgrading from pre-testnet have data directly in data/ — this moves it
     * so each network gets its own isolated subdirectory.
     */
    fun migrateDataDirectoryIfNeeded() {
        val dataDir = File(context.filesDir, "data")
        val mainnetDir = File(dataDir, "mainnet")
        val storeDb = File(dataDir, "store.db")
        val networkDir = File(dataDir, "network")

        // If mainnet subdir already exists or there's nothing to migrate, skip
        if (mainnetDir.exists() || (!storeDb.exists() && !networkDir.exists())) return

        logger.d(TAG, "Migrating data directory to per-network layout...")
        if (!mainnetDir.mkdirs() && !mainnetDir.exists()) {
            logger.e(TAG, "Failed to create mainnet directory, skipping migration")
            return
        }

        var migrationOk = true
        if (storeDb.exists()) {
            if (storeDb.renameTo(File(mainnetDir, "store.db"))) {
                logger.d(TAG, "Moved store.db -> mainnet/store.db")
            } else {
                logger.e(TAG, "Failed to move store.db to mainnet/store.db")
                migrationOk = false
            }
        }
        if (networkDir.exists()) {
            if (networkDir.renameTo(File(mainnetDir, "network"))) {
                logger.d(TAG, "Moved network/ -> mainnet/network/")
            } else {
                logger.e(TAG, "Failed to move network/ to mainnet/network/")
                migrationOk = false
            }
        }
        if (!migrationOk) {
            logger.e(TAG, "Migration incomplete — manual intervention may be needed")
        }
    }

    /**
     * Copies the network's TOML config into `filesDir`, points it at the
     * per-network data directory and brings the embedded light client up via
     * `nativeInit` / `nativeStart` (3 attempts, 2s/4s/8s backoff).
     *
     * [onStarted] runs once, immediately after the node is marked ready and
     * before returning - it carries the repository-side work that follows a
     * successful start (orphan reconciliation, sync polling, background sync).
     */
    suspend fun initializeNode(targetNetwork: NetworkType, onStarted: suspend () -> Unit) {
        try {
            _nodeReady.value = null // Reset for re-initialization
            logger.d(TAG, "Initializing embedded node for ${targetNetwork.name}...")

            val configName = "${targetNetwork.name.lowercase()}.toml"
            val configFile = File(context.filesDir, configName)

            // Copy config from assets (deterministic, no retry needed)
            logger.d(TAG, "Copying config from assets: $configName")
            try {
                context.assets.open(configName).use { input ->
                    configFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "Failed to copy $configName from assets", e)
                _nodeReady.value = false
                return
            }

            // Update paths in config — each network gets its own data subdirectory
            val configContent = configFile.readText()
            val dataDir = File(context.filesDir, "data/${targetNetwork.name.lowercase()}")
            if (!dataDir.exists()) {
                logger.d(TAG, "Creating data directory: ${dataDir.absolutePath}")
                if (!dataDir.mkdirs()) {
                    logger.e(TAG, "Failed to create data directory")
                    _nodeReady.value = false
                    return
                }
            }

            val newConfig = configContent
                .replace("path = \"data/store\"", "path = \"${File(dataDir, "store.db").absolutePath}\"")
                .replace("path = \"data/network\"", "path = \"${File(dataDir, "network").absolutePath}\"")
            configFile.writeText(newConfig)
            logger.d(TAG, "Config updated with absolute paths for ${targetNetwork.name}")

            // Init and start JNI with retry (transient failures can occur)
            val maxRetries = 3
            val backoffMs = longArrayOf(2_000, 4_000, 8_000)

            for (attempt in 1..maxRetries) {
                logger.d(TAG, "JNI init attempt $attempt/$maxRetries...")

                val initResult = LightClientNative.nativeInit(
                    configFile.absolutePath,
                    object : LightClientNative.StatusCallback {
                        override fun onStatusChange(status: String, data: String) {
                            logger.d(TAG, "Native Status Change: $status")
                            _nodeStatus.value = status
                        }
                    }
                )

                if (!initResult) {
                    logger.e(TAG, "nativeInit returned false (attempt $attempt)")
                    if (attempt < maxRetries) {
                        delay(backoffMs[attempt - 1])
                        continue
                    }
                    _nodeReady.value = false
                    return
                }

                val startResult = LightClientNative.nativeStart()
                if (startResult) {
                    logger.d(TAG, "Node started successfully on ${targetNetwork.name} (attempt $attempt)")
                    _nodeReady.value = true

                    onStarted()
                    return
                }

                logger.e(TAG, "nativeStart returned false (attempt $attempt)")
                if (attempt < maxRetries) {
                    delay(backoffMs[attempt - 1])
                } else {
                    _nodeReady.value = false
                }
            }

        } catch (e: Exception) {
            logger.e(TAG, "Setup error during node initialization", e)
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
     * network from NetworkPreferences. Data directories are isolated per network.
     */
    suspend fun switchNetwork(target: NetworkType): Result<Unit> = runCatching {
        if (target == currentNetwork) return@runCatching
        if (_isSwitchingNetwork.value) throw Exception("Network switch already in progress")

        _isSwitchingNetwork.value = true
        try {
            logger.d(TAG, "Switching network: ${currentNetwork.name} -> ${target.name}")

            // The JNI light client does not support re-initialization in the same process lifetime:
            // nativeStop() blocks indefinitely (peer disconnection loop) and nativeInit() rejects
            // calls while already initialized ("Already initialized!"). The only reliable path is
            // to persist the selection and restart the process — Android will relaunch the app and
            // initializeNode() will pick up the new network from NetworkPreferences.

            // Clear Room caches before process restart
            cacheManager.clearAll()
            daoSyncManager.clearAll()

            networkPreferences.setSelectedNetwork(target) // uses commit() — synchronous flush
            logger.d(TAG, "Persisted ${target.name}, restarting process for clean JNI init")

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

    companion object {
        private const val TAG = "NodeLifecycle"
    }
}
