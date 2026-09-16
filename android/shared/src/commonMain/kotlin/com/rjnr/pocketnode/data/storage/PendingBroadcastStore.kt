package com.rjnr.pocketnode.data.storage

/**
 * An in-flight or recently-failed broadcast, as the shared read path sees it.
 *
 * Deliberately narrower than the Android `PendingBroadcastEntity`: only the
 * four columns a reader actually touches are here. The bookkeeping columns
 * (`submittedAtTipBlock`, `nullCount`, `createdAt`, `lastCheckedAt`) belong to
 * the watchdog's state machine, which stays on the app side.
 *
 * [state] carries the same strings the entity declares; the constants below
 * repeat them so shared code can compare without reaching into the app module.
 */
data class PendingBroadcastRecord(
    val txHash: String,
    val state: String,
    /** JSON-encoded `List<OutPoint>` reserved by this broadcast. */
    val reservedInputs: String,
    val signedTxJson: String,
) {
    companion object {
        const val STATE_BROADCASTING = "BROADCASTING"
        const val STATE_BROADCAST = "BROADCAST"
        const val STATE_CONFIRMED = "CONFIRMED"
        const val STATE_FAILED = "FAILED"
    }
}

/**
 * The `pending_broadcasts` read the shared read path performs (M3 #4),
 * narrowed to the single active-row snapshot it needs.
 *
 * Android binds it to `PendingBroadcastDao` through `RoomPendingBroadcastStore`.
 */
interface PendingBroadcastStore {

    /**
     * Snapshot of the BROADCASTING and BROADCAST rows for a wallet on a
     * network. Terminal rows (CONFIRMED, FAILED) are not returned.
     */
    suspend fun getActive(walletId: String, network: String): List<PendingBroadcastRecord>
}
