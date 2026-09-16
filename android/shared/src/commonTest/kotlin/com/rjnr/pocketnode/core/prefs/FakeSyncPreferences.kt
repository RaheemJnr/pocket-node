package com.rjnr.pocketnode.core.prefs

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode

/**
 * In-memory [SyncPreferences] for tests.
 *
 * Values are keyed the way the Android implementation keys them, by
 * network and wallet, with null meaning "the current one" and collapsing to a
 * single shared slot here (no test needs the real per-network namespacing).
 *
 * [lastSyncedAtWrites] records every [setLastSyncedAt] value in order, which is
 * what the poller's 60 s throttle is asserted against.
 */
class FakeSyncPreferences : SyncPreferences {

    private data class Key(val network: NetworkType?, val walletId: String?)

    private val syncModes = mutableMapOf<Key, SyncMode>()
    private val customHeights = mutableMapOf<Key, Long?>()
    private val initialSyncDone = mutableMapOf<Key, Boolean>()
    private val zeroCellRescans = mutableSetOf<Pair<String, NetworkType?>>()
    private val gapLimitSignals = mutableMapOf<Key, Boolean>()

    var defaultSyncMode: SyncMode = SyncMode.RECENT

    // Backing fields, deliberately not named after their accessors: a public
    // `var backgroundSyncEnabled` would compile to the same JVM signature as
    // the interface's own setBackgroundSyncEnabled.
    private var bgSyncEnabled: Boolean = false
    private var strategy: SyncStrategy = SyncStrategy.ACTIVE_ONLY

    /** Every value handed to [setLastSyncedAt], oldest first. */
    val lastSyncedAtWrites: MutableList<Long> = mutableListOf()

    override fun getSyncModeOrNull(network: NetworkType?, walletId: String?): SyncMode? =
        syncModes[Key(network, walletId)]

    override fun getSyncMode(network: NetworkType?, walletId: String?): SyncMode =
        getSyncModeOrNull(network, walletId) ?: defaultSyncMode

    override fun setSyncMode(mode: SyncMode, network: NetworkType?, walletId: String?) {
        syncModes[Key(network, walletId)] = mode
    }

    override fun getCustomBlockHeight(network: NetworkType?, walletId: String?): Long? =
        customHeights[Key(network, walletId)]

    override fun setCustomBlockHeight(height: Long?, network: NetworkType?, walletId: String?) {
        customHeights[Key(network, walletId)] = height
    }

    override fun hasCompletedInitialSync(network: NetworkType?, walletId: String?): Boolean =
        initialSyncDone[Key(network, walletId)] ?: false

    override fun setInitialSyncCompleted(
        completed: Boolean,
        network: NetworkType?,
        walletId: String?
    ) {
        initialSyncDone[Key(network, walletId)] = completed
    }

    override fun isZeroCellRescanDone(walletId: String, network: NetworkType?): Boolean =
        walletId to network in zeroCellRescans

    override fun setZeroCellRescanDone(walletId: String, network: NetworkType?) {
        zeroCellRescans += walletId to network
    }

    override fun clearZeroCellRescanDone(walletId: String, network: NetworkType?) {
        zeroCellRescans -= walletId to network
    }

    override fun isBackgroundSyncEnabled(): Boolean = bgSyncEnabled

    override fun setBackgroundSyncEnabled(enabled: Boolean) {
        bgSyncEnabled = enabled
    }

    override fun getLastSyncedAt(): Long = lastSyncedAtWrites.lastOrNull() ?: 0L

    override fun setLastSyncedAt(timestampMs: Long) {
        lastSyncedAtWrites += timestampMs
    }

    override fun getSyncStrategy(): SyncStrategy = strategy

    override fun setSyncStrategy(strategy: SyncStrategy) {
        this.strategy = strategy
    }

    override fun isGapLimitSignalDetected(network: NetworkType?, walletId: String?): Boolean =
        gapLimitSignals[Key(network, walletId)] ?: false

    override fun setGapLimitSignalDetected(
        detected: Boolean,
        network: NetworkType?,
        walletId: String?
    ) {
        gapLimitSignals[Key(network, walletId)] = detected
    }
}
