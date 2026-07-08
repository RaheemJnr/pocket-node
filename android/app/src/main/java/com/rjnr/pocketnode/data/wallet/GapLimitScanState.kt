package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity

/**
 * What the chain-axis candidate set means for the active wallet (#382 Tier 2).
 * Drives the Home surface: NOT_SCANNED offers "Scan now" on the Tier 1 banner
 * (wallet imported before Tier 2 shipped), SCANNING shows progress copy,
 * FOUND switches to the found-funds card, CLEAR retires the Tier 1 signal.
 */
enum class GapLimitResolution { NOT_SCANNED, SCANNING, FOUND, CLEAR }

/** Resolution plus the live capacity found on chain-axis slots (FOUND only). */
data class GapLimitStatus(
    val resolution: GapLimitResolution,
    val foundAddressCount: Int,
    val foundShannons: Long,
)

/** #382 Tier 3: what the sweep confirm dialog shows — key-free preview. */
data class GapLimitSweepPreview(
    val totalShannons: Long,
    val feeShannons: Long,
    val addressCount: Int,
)

fun gapLimitResolution(chainCandidates: List<SubAccountCandidateEntity>): GapLimitResolution = when {
    chainCandidates.isEmpty() -> GapLimitResolution.NOT_SCANNED
    chainCandidates.any { it.state == SubAccountCandidateEntity.STATE_PENDING } -> GapLimitResolution.SCANNING
    chainCandidates.any { it.state == SubAccountCandidateEntity.STATE_FOUND } -> GapLimitResolution.FOUND
    else -> GapLimitResolution.CLEAR
}

/**
 * The gap-limit window a new scan should derive. A used slot AT the current
 * boundary means the other wallet may have rotated past it, so extend by 20;
 * capped at 60 (three windows — beyond that is not a realistic BIP44 wallet
 * and every slot costs light-client sync time).
 */
fun nextScanWindow(chainCandidates: List<SubAccountCandidateEntity>): Int {
    if (chainCandidates.isEmpty()) return SubAccountDiscovery.CHAIN_GAP_WINDOW
    val boundary = chainCandidates.maxOf { addressIndexOf(it.derivationPath) }
    val boundaryActive = chainCandidates.any {
        it.state == SubAccountCandidateEntity.STATE_FOUND && addressIndexOf(it.derivationPath) == boundary
    }
    return if (boundaryActive) minOf(boundary + 20, MAX_CHAIN_GAP_WINDOW) else boundary
}

/** Last path segment of m/44'/309'/0'/{chain}/{index}; -1 if malformed. */
private fun addressIndexOf(derivationPath: String): Int =
    derivationPath.substringAfterLast('/').toIntOrNull() ?: -1

/**
 * (chain, addressIndex) of a CHAIN-AXIS path — m/44'/309'/0'/{chain}/{index}
 * only. Account-axis and malformed paths return null: the Tier 3 sweep
 * re-derives signing keys from these paths, and a silently wrong parse would
 * derive the wrong key and produce an unverifiable signature.
 */
fun chainAndIndexFromPath(derivationPath: String): Pair<Int, Int>? {
    val prefix = "m/44'/309'/0'/"
    if (!derivationPath.startsWith(prefix)) return null
    val rest = derivationPath.removePrefix(prefix).split('/')
    if (rest.size != 2) return null
    val chain = rest[0].toIntOrNull() ?: return null
    val index = rest[1].toIntOrNull() ?: return null
    if (chain < 0 || index < 0) return null
    return chain to index
}

const val MAX_CHAIN_GAP_WINDOW = 60
