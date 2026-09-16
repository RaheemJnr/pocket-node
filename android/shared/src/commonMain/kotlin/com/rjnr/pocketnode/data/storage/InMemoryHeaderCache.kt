package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [HeaderCache] held in memory, bounded, for the single-wallet iOS profile.
 *
 * The cache exists to stop a history page costing one `get_header` round trip
 * per transaction when the same headers were resolved seconds earlier. That
 * saving is entirely within a session: a header's timestamp never changes, so
 * losing the map at launch costs one extra walk and nothing else. Android
 * persists its copy in a `header_cache` table because it has one; iOS does not
 * yet, and adding a fourth table for data that is free to re-fetch would be
 * paying storage for no behaviour.
 *
 * Bounded at [MAX_ENTRIES] because a full-history wallet resolves a header per
 * transaction and an unbounded map would grow with the wallet forever.
 * Eviction is least-recently-WRITTEN, not least-recently-read: [put] removes
 * the key before re-inserting it, so re-caching a header moves it to the back
 * of the queue, while [get] leaves the order alone. For a `desc` history walk
 * that evicts the newest blocks first, which are the ones the next walk
 * resolves again anyway.
 *
 * The [Mutex] rather than a plain map: the history walk resolves headers from
 * whichever dispatcher its caller is on, and `LinkedHashMap` is not safe to
 * mutate concurrently.
 */
class InMemoryHeaderCache(
    private val maxEntries: Int = MAX_ENTRIES,
) : HeaderCache {

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, JniHeaderView>()

    override suspend fun get(blockHash: String): JniHeaderView? = mutex.withLock {
        entries[blockHash]
    }

    /**
     * [network] is accepted and ignored: iOS opens one database and one cache
     * per network already (`SyncService` builds both under the network's own
     * data directory), so the scoping the Android table needs is structural
     * here. Keeping the parameter means the seam stays one interface.
     */
    override suspend fun put(header: JniHeaderView, network: String) {
        val key = header.hash
        if (key.isBlank()) return
        mutex.withLock {
            entries.remove(key)
            entries[key] = header
            while (entries.size > maxEntries) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    companion object {
        /**
         * Roughly a wallet with a few thousand transactions' worth of distinct
         * blocks. A `JniHeaderView` is five short strings, so the ceiling is
         * hundreds of kilobytes, not megabytes.
         */
        const val MAX_ENTRIES = 4_000
    }
}
