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
 * Reads live scan cursors from the light client (block numbers from one
 * nativeGetScripts call). A script that cannot be read (node not running, JNI
 * error, script not registered) is absent from the map; the restore path then
 * copies no progress and the child registers from its copied sync mode.
 */
fun interface ScriptCursorProbe {
    suspend fun cursorsFor(scriptArgs: Collection<String>): Map<String, Long>
}

/**
 * Candidate cursors read at one moment, with the network they were read on.
 * A batch restore takes one snapshot before the first child is activated,
 * because activating a child replaces the light client's script set and drops
 * the other candidates' cursors. A cursor only moves forward, so an older
 * snapshot can only start a child lower (more rescan), never skip blocks.
 */
data class CursorSnapshot(
    val network: com.rjnr.pocketnode.data.gateway.models.NetworkType,
    val cursors: Map<String, Long>,
)
