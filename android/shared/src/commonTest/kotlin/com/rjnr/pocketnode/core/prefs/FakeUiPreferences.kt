package com.rjnr.pocketnode.core.prefs

import com.rjnr.pocketnode.data.gateway.models.NetworkType

/**
 * In-memory [UiPreferences] for tests.
 *
 * Everything round-trips, but only [bulkTxHashes] carries weight for the
 * shared readers: it is what the history read badges a bulk-airdrop batch
 * from. The rest is here because the interface declares it.
 */
open class FakeUiPreferences : UiPreferences {

    private data class Key(val network: NetworkType?, val walletId: String?)

    private var theme: ThemeMode = ThemeMode.SYSTEM
    private var coachmarkSeen: Boolean = false
    private var pillDismissed: Boolean = false
    private var bulkUnlocked: Boolean = false
    private val bannersDismissed = mutableSetOf<Key>()

    /** Tx hashes marked as bulk-airdrop batches. */
    val bulkTxHashes: MutableSet<String> = mutableSetOf()

    override fun getThemeMode(): ThemeMode = theme

    override fun setThemeMode(mode: ThemeMode) {
        theme = mode
    }

    override fun hasSeenSyncCoachmark(): Boolean = coachmarkSeen

    override fun markSyncCoachmarkSeen() {
        coachmarkSeen = true
    }

    override fun isBgSyncPillDismissed(): Boolean = pillDismissed

    override fun setBgSyncPillDismissed() {
        pillDismissed = true
    }

    override fun isGapLimitBannerDismissed(network: NetworkType?, walletId: String?): Boolean =
        Key(network, walletId) in bannersDismissed

    override fun setGapLimitBannerDismissed(network: NetworkType?, walletId: String?) {
        bannersDismissed += Key(network, walletId)
    }

    override fun isBulkSendUnlocked(): Boolean = bulkUnlocked

    override fun setBulkSendUnlocked(unlocked: Boolean) {
        bulkUnlocked = unlocked
    }

    override fun isBulkTxHash(hash: String): Boolean = hash in bulkTxHashes

    override fun addBulkTxHash(hash: String) {
        bulkTxHashes += hash
    }
}
