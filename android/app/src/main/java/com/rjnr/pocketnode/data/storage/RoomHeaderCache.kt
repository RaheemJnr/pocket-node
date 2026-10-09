package com.rjnr.pocketnode.data.storage

import com.rjnr.pocketnode.data.database.dao.HeaderCacheDao
import com.rjnr.pocketnode.data.database.entity.HeaderCacheEntity
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room binding for the shared [HeaderCache] seam.
 *
 * [put] is deliberately unguarded: every call site in the shared read path
 * already wraps the write in `runCatching`, exactly as the repository code it
 * replaces did, so adding a catch here would change nothing but would hide
 * where the tolerance actually lives.
 */
@Singleton
class RoomHeaderCache @Inject constructor(
    private val dao: HeaderCacheDao,
) : HeaderCache {

    override suspend fun get(blockHash: String): JniHeaderView? =
        dao.getByBlockHash(blockHash)?.toJniHeaderView()

    override suspend fun put(header: JniHeaderView, network: String) =
        dao.upsert(HeaderCacheEntity.from(header, network))
}
