package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.withTransaction
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.storage.VerificationEpoch
import dagger.hilt.android.qualifiers.ApplicationContext
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
    internal var writeGeneration: (String, String) -> Boolean = { network, generation ->
        applied.edit().putString(network, generation).commit()
    }
    private val _readyNetworks = MutableStateFlow<Set<String>>(emptySet())
    val readyNetworks: StateFlow<Set<String>> = _readyNetworks
    fun isReady(network: String): Boolean = network in _readyNetworks.value

    private var revision = 0L

    @Synchronized
    fun currentRevision(): Long = revision

    @Synchronized
    fun close() {
        revision += 1
        _readyNetworks.value = emptySet()
    }

    suspend fun reconcile(client: LightClientApi, network: NetworkType) {
        val ticket = synchronized(this) { close(); revision }
        val generation = VerificationEpoch.requireGeneration(client.callRpc("get_verification_epoch"))
        if (applied.getString(network.name, null) != generation) {
            database.withTransaction {
                val db = database.openHelper.writableDatabase
                for (table in listOf("sync_progress", "balance_cache", "header_cache")) {
                    db.execSQL("DELETE FROM $table WHERE network = ?", arrayOf(network.name))
                }
                // Pending DAO broadcasts are separate durable tables and survive.
                // A pending deposit cell is locally created; retain it for reconciliation.
                db.execSQL("DELETE FROM dao_cells WHERE network = ? AND NOT (status = 'PENDING' AND (txHash IN (SELECT txHash FROM transactions WHERE isLocal = 1 AND network = dao_cells.network) OR txHash IN (SELECT txHash FROM pending_broadcasts WHERE network = dao_cells.network)))", arrayOf(network.name))
                db.execSQL(
                    "UPDATE transactions SET status = 'UNVERIFIED', confirmations = 0, " +
                        "blockNumber = '0x0', blockHash = '', blockTimestampHex = NULL, " +
                        "balanceChange = '0', fee = '', fee_shannons = NULL " +
                        "WHERE network = ? AND NOT (isLocal = 1 AND status IN ('PENDING', 'FAILED'))",
                    arrayOf(network.name),
                )
            }
            // A crash between SQL commit and this commit repeats the idempotent cleanup.
            check(writeGeneration(network.name, generation)) { "Cannot record verification epoch" }
        }
        synchronized(this) {
            check(ticket == revision) { "Verification superseded by a network change" }
            _readyNetworks.value = setOf(network.name)
        }
    }
}
