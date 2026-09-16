package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.gateway.models.JniHeaderView

/**
 * The block-header cache the shared history read consults before it asks the
 * light client (M3 #4).
 *
 * A 50-transaction page used to cost 50 `get_header` round trips even when the
 * same headers had been resolved seconds earlier; this is the table that makes
 * the next page free.
 *
 * Only five of a header's fields survive the round trip on Android (hash,
 * number, epoch, timestamp, dao), so a [get] answer carries empty strings
 * where the store keeps nothing. Callers read the timestamp and the hash,
 * which are both real.
 */
interface HeaderCache {

    /** Cached header for [blockHash], or null on a miss. */
    suspend fun get(blockHash: String): JniHeaderView?

    /** Insert or replace the cached header, scoped to [network]. */
    suspend fun put(header: JniHeaderView, network: String)
}
