package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.Script
import kotlinx.serialization.Serializable

/** Native authority, not pagination provenance. Heights are decimal strings to preserve u64. */
@Serializable
data class CanonicalHistorySnapshot(
    val version: Int,
    val complete: Boolean,
    val network: String,
    val genesisHash: String,
    val storeGeneration: String,
    val script: Script,
    val registration: String,
    val startHeight: String,
    val tipHeight: String,
    val tipHash: String,
    val processedThrough: String,
    val hashes: Set<String>,
) {
    fun validFor(expectedScript: Script, expectedNetwork: String): Boolean {
        val start = startHeight.toLongOrNull() ?: return false
        val tip = tipHeight.toLongOrNull() ?: return false
        val through = processedThrough.toLongOrNull() ?: return false
        return version == 1 && complete && network == expectedNetwork && script == expectedScript &&
            start >= 0 && tip >= start && through >= tip &&
            hash(genesisHash) && hash(tipHash) && storeGeneration.isNotBlank() &&
            registration.length == 32 && registration.all { it in "0123456789abcdef" } &&
            hashes.size <= 20_000 && hashes.all(::hash)
    }

    /** The start block is an exclusive scan boundary; genesis is explicitly filtered. */
    fun provesAbsent(txHash: String, blockNumber: String): Boolean {
        val height = blockNumber.takeIf { it.startsWith("0x") }?.drop(2)?.toLongOrNull(16) ?: return false
        val start = startHeight.toLongOrNull() ?: return false
        return hash(txHash) && height >= 0 && (height > start || (start == 0L && height == 0L)) && txHash !in hashes
    }
    private fun hash(value: String) = value.length == 66 && value.startsWith("0x") && value.drop(2).all { it in "0123456789abcdef" }
}
