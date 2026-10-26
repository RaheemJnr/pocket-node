package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.withTransaction
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.NetworkType
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
) {
    private val applied = context.getSharedPreferences("verified_chain_cache", Context.MODE_PRIVATE)
    internal var writeGeneration: suspend (String, String) -> Boolean = { network, generation ->
        applied.edit().putString(network, generation).commit()
    }
    private val _readyNetworks = MutableStateFlow<Set<String>>(emptySet())
    val readyNetworks: StateFlow<Set<String>> = _readyNetworks
    fun isReady(network: String): Boolean = network in _readyNetworks.value

    private var revision = 0L
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
        synchronized(this) {
            revision += 1
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

    suspend fun reconcile(client: LightClientApi, network: NetworkType) = reconciliation.withLock {
        val ticket = close()
        val generation = VerificationEpoch.requireGeneration(client.callRpc("get_verification_epoch"))
        val identity = network.name to generation
        if (identity in dirtyGenerations || applied.getString(network.name, null) != generation) {
            state.withLock {
                check(ticket == currentRevision()) { "Verification superseded by a network change" }
                database.withTransaction {
                    val db = database.openHelper.writableDatabase
                    for (table in listOf("sync_progress", "balance_cache", "header_cache")) {
                        db.execSQL("DELETE FROM $table WHERE network = ?", arrayOf(network.name))
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
                }
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
                _readyNetworks.value = setOf(network.name)
            }
        }
    }
}
