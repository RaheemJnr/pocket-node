package com.rjnr.pocketnode.data.wallet

/**
 * Where a restored discovery candidate should start scanning (#623).
 *
 * A candidate turns FOUND on its first matched transaction, which can happen
 * while its own scan cursor is far below the parent's saved height. Copying
 * the parent's height would skip the blocks in between for that script, so
 * the child starts at the lower of the two.
 */
object DiscoveredProgress {
    /**
     * @param parentSaved the parent's saved height, null when it has no progress row
     * @param cursor the candidate script's live light-client scan cursor, null when unavailable
     * @param registeredFromBlock the candidate row's registered-from block (0 = never registered)
     * @return the height to start the child at, or null when no progress should be copied
     */
    fun childStart(parentSaved: Long?, cursor: Long?, registeredFromBlock: Long): Long? {
        if (parentSaved == null) return null
        val own = cursor?.takeIf { it > 0 } ?: registeredFromBlock.takeIf { it > 0 } ?: return null
        return minOf(parentSaved, own)
    }
}

/**
 * Reads a script's live scan cursor from the light client (block number from
 * nativeGetScripts). Returns null when it cannot be read (node not running,
 * JNI error, script not registered); callers then fall back to the
 * candidate's registered-from block, never to the parent height.
 */
fun interface ScriptCursorProbe {
    suspend fun cursorFor(scriptArgs: String): Long?
}
