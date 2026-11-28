package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.storage.VerificationEpoch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local gate: persisted caches cannot be displayed before the native handshake. */
@Singleton
class VerificationCacheGate @Inject constructor(
    @ApplicationContext context: Context,
    private val database: AppDatabase,
    private val recoveryLifetime: DaoRecoveryLifetime = DaoRecoveryLifetime(),
    // 1.8.7.1: the wallet's sync mode decides its own start on a handoff.
    // Null (tests only) reads every wallet as NEW_WALLET.
    private val syncPreferences: SyncPreferences? = null,
) {
    private val upgradeFlags = UpgradeRescanFlags(context)
    private val applied = context.getSharedPreferences("verified_chain_cache", Context.MODE_PRIVATE)
    internal var writeGeneration: suspend (String, String) -> Boolean = { network, generation ->
        applied.edit().putString(network, generation).commit()
    }
    private val _readyNetworks = MutableStateFlow<Set<String>>(emptySet())
    val readyNetworks: StateFlow<Set<String>> = _readyNetworks
    fun isReady(network: String): Boolean = network in _readyNetworks.value

    private var revision = 0L
    private var lifecycle = 0L
    private var generations = emptyMap<String, String>()

    @Synchronized fun lifecycleRevision(): Long = lifecycle
    @Synchronized fun generation(network: String): String? = generations[network]
    // Lock order is gate state, then Room transaction. Never acquire state from
    // inside an existing Room transaction. Native/read work holds neither lock.
    private val state = Mutex()
    private val reconciliation = Mutex()
    // SharedPreferences can publish a value in memory even when commit fails.
    // Only a checked successful write clears that network/generation's debt.
    private val dirtyGenerations = mutableSetOf<Pair<String, String>>()

    @Synchronized
    fun currentRevision(): Long = revision

    suspend fun close() = state.withLock {
        recoveryLifetime.invalidate()
        synchronized(this) {
            revision += 1
            lifecycle += 1
            generations = emptyMap()
            _readyNetworks.value = emptySet()
            revision
        }
    }

    @Synchronized
    fun requireReadyRevision(network: String): Long {
        check(isReady(network)) { "Chain cache verification is not ready" }
        return revision
    }

    @Synchronized
    fun requireCurrent(network: String, ticket: Long) {
        check(revision == ticket && isReady(network)) { "Chain cache verification changed" }
    }

    /** Apply a read/stage operation only while its authorization is current.
     * close() cannot pass this boundary until SQL commits or rolls back. */
    suspend fun commitIfCurrent(network: String, ticket: Long, mutations: suspend () -> Unit) = state.withLock {
        currentCoroutineContext().ensureActive()
        requireCurrent(network, ticket)
        database.withTransaction { mutations() }
    }

    /** SQL commits first. Staged refresh tickets become stale before state is unlocked. */
    suspend fun commitRecoveryIfCurrent(network: String, ticket: Long, mutations: suspend () -> Unit): Long = state.withLock {
        currentCoroutineContext().ensureActive()
        requireCurrent(network, ticket)
        // A committed Room transaction must publish its fence even if cancellation
        // races the dispatcher return. This section contains SQL only, no native IO.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            database.withTransaction { mutations() }
            synchronized(this@VerificationCacheGate) { revision += 1; revision }
        }
    }

    suspend fun reconcile(client: LightClientApi, network: NetworkType) = reconciliation.withLock {
        val ticket = close()
        val generation = VerificationEpoch.requireGeneration(client.callRpc("get_verification_epoch"))
        val identity = network.name to generation
        if (identity in dirtyGenerations || applied.getString(network.name, null) != generation) {
            state.withLock {
                check(ticket == currentRevision()) { "Verification superseded by a network change" }
                val seeded = database.withTransaction {
                    val db = database.openHelper.writableDatabase
                    // 1.8.7.1: read each wallet's own start BEFORE the rows
                    // below lose their block numbers. 1.8.7 dropped
                    // sync_progress here, so registration restarted from the
                    // mode start (the tip for NEW_WALLET) and older funds never
                    // came back. The native cells are still gone (that is the
                    // trust boundary); only the position to rescan from is kept.
                    val seeds = ownStarts(db, network)
                    for (table in listOf("sync_progress", "balance_cache", "header_cache")) {
                        db.execSQL("DELETE FROM $table WHERE network = ?", arrayOf(network.name))
                    }
                    val now = System.currentTimeMillis()
                    for (seed in seeds) {
                        val block = seed.savedBlock ?: continue
                        db.execSQL(
                            "INSERT OR REPLACE INTO sync_progress (walletId, network, lightStartBlockNumber, localSavedBlockNumber, updatedAt) VALUES (?, ?, ?, ?, ?)",
                            arrayOf<Any?>(seed.walletId, network.name, block, block, now),
                        )
                    }
                    // Pending DAO broadcasts are separate durable tables and survive.
                    // A pending deposit cell is locally created; retain it for reconciliation.
                    db.execSQL("DELETE FROM dao_cells WHERE network = ? AND NOT (status = 'DEPOSITING' AND (txHash IN (SELECT txHash FROM transactions WHERE isLocal = 1 AND network = dao_cells.network) OR txHash IN (SELECT txHash FROM pending_broadcasts WHERE network = dao_cells.network)))", arrayOf(network.name))
                    db.execSQL(
                        "UPDATE transactions SET status = 'UNVERIFIED', confirmations = 0, " +
                            "blockNumber = '0x0', blockHash = '', blockTimestampHex = NULL, " +
                            "balanceChange = '0', fee = '', fee_shannons = NULL " +
                            "WHERE network = ? AND NOT (isLocal = 1 AND status IN ('PENDING', 'FAILED'))",
                        arrayOf(network.name),
                    )
                    seeds
                }
                // A wallet re-verified from its own start out of clean
                // (never quarantined) history needs no upgrade-scan banner.
                for (seed in seeded) if (seed.clean) upgradeFlags.markDone(seed.walletId, network.name)
            }
            // A crash or failed durable marker leaves cleanup retryable even if
            // SharedPreferences already exposed the generation in its memory map.
            dirtyGenerations.add(identity)
            check(writeGeneration(network.name, generation)) { "Cannot record verification epoch" }
            dirtyGenerations.remove(identity)
        }
        state.withLock {
            synchronized(this) {
                check(ticket == revision) { "Verification superseded by a network change" }
                generations = mapOf(network.name to generation)
                _readyNetworks.value = setOf(network.name)
            }
        }
    }

    private class OwnStartSeed(val walletId: String, val savedBlock: Long?, val clean: Boolean)

    /** Part B of the 1.8.7.1 hotfix; see [upgradeOwnStart]. Runs inside the handoff transaction. */
    private fun ownStarts(db: SupportSQLiteDatabase, network: NetworkType): List<OwnStartSeed> {
        val net = network.name
        val previous = mutableMapOf<String, Long>()
        db.query("SELECT walletId, lightStartBlockNumber, localSavedBlockNumber FROM sync_progress WHERE network = ?", arrayOf<Any?>(net)).use { c ->
            while (c.moveToNext()) previous[c.getString(0)] = minOf(c.getLong(1), c.getLong(2)).coerceAtLeast(0L)
        }
        val walletIds = linkedSetOf<String>()
        db.query("SELECT walletId FROM wallets").use { c -> while (c.moveToNext()) walletIds += c.getString(0) }
        walletIds += previous.keys
        return walletIds.filter { it.isNotEmpty() }.map { walletId ->
            var earliestTx: Long? = null
            var quarantined = false
            db.query("SELECT blockNumber, status, isLocal FROM transactions WHERE walletId = ? AND network = ?", arrayOf<Any?>(walletId, net)).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == "UNVERIFIED" && c.getInt(2) == 0) quarantined = true
                    val block = c.getString(0)?.removePrefix("0x")?.toLongOrNull(16)?.takeIf { it > 0L } ?: continue
                    earliestTx = minOf(earliestTx ?: block, block)
                }
            }
            val earliestDao = db.query("SELECT MIN(depositBlockNumber) FROM dao_cells WHERE walletId = ? AND network = ? AND depositBlockNumber > 0", arrayOf<Any?>(walletId, net)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
            val mode = syncPreferences?.getSyncMode(network, walletId) ?: SyncMode.NEW_WALLET
            val customHeight = syncPreferences?.getCustomBlockHeight(network, walletId)
            val own = upgradeOwnStart(previous[walletId], earliestTx, earliestDao, mode, customHeight)
            OwnStartSeed(walletId, own?.let { ownStartSavedBlock(it, mode) }, clean = !quarantined)
        }
    }
}
