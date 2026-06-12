package com.rjnr.pocketnode.data.gateway

/**
 * Decision for the 0-live-cells rescue rescan in
 * [GatewayRepository.refreshBalance] (#332). Pure so it is unit-testable
 * away from the JNI surface.
 *
 * History: the original trigger was `liveCapacity == 0 && transactions
 * exist`. `liveCapacity` excludes typed cells, so a wallet whose entire
 * balance sits in Nervos DAO satisfied it on EVERY refresh — each hit
 * rewound the light-client scripts to the wallet's earliest transaction and
 * restarted a multi-hour filter scan the moment the previous one finished.
 * "Sync restarts indefinitely."
 */
internal fun shouldAttemptZeroCellRescan(
    spendableCapacity: Long,
    typedCellCount: Int,
    hasTransactions: Boolean,
    alreadyAttempted: Boolean,
    isSyncing: Boolean,
): Boolean =
    spendableCapacity == 0L &&
        typedCellCount == 0 &&
        hasTransactions &&
        !alreadyAttempted &&
        !isSyncing
