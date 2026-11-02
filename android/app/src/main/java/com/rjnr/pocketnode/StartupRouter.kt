package com.rjnr.pocketnode

/**
 * The cold-start route, as a pure function of the startup inputs, so every
 * case can be unit tested. Used by [MainActivity].
 *
 * Recovery is shown while recovery is incomplete:
 *  - the in-memory corruption flag is up, or the persistent "recovery
 *    incomplete" marker is set, or
 *  - wallet rows exist and at least one lacks verified keys, even without
 *    the flag (key rows lost some other way),
 * unless [completeIfDone] confirms there is nothing left to recover. The
 * marker is set when a wallet row lacks keys, or when the flag is up and
 * the wallet rows cannot be read. Healthy users (every row has keys, no
 * flag, no marker) never reach Recovery and [completeIfDone] is not called.
 * A read that throws counts as "unknown": with the flag or marker it keeps
 * Recovery, without them it never sends a healthy user there.
 */
object StartupRouter {

    enum class Route { RESET_RETRY, RECOVERY, ONBOARDING, PIN_SETUP, AUTH }

    data class Decision(val route: Route, val recoveryPending: Boolean, val markedIncomplete: Boolean)

    suspend fun route(
        resetPending: Boolean = false,
        corruptionFlag: Boolean,
        markerSet: Boolean,
        hasWallet: Boolean,
        hasPin: Boolean,
        walletRowCount: suspend () -> Int,
        missingWalletCount: suspend () -> Int,
        markIncomplete: () -> Unit,
        completeIfDone: suspend () -> Boolean,
    ): Decision {
        if (resetPending) return Decision(Route.RESET_RETRY, recoveryPending = false, markedIncomplete = false)
        val rows = runCatching { walletRowCount() }.getOrNull()
        val missing = runCatching { missingWalletCount() }.getOrNull()
        val lostKeys = rows != null && rows > 0 && missing != null && missing > 0
        val unknown = rows == null || missing == null

        val mark = lostKeys || (corruptionFlag && unknown)
        if (mark) runCatching { markIncomplete() }

        val candidate = corruptionFlag || markerSet || lostKeys
        val recoveryPending = candidate && !runCatching { completeIfDone() }.getOrDefault(false)

        val route = when {
            recoveryPending -> Route.RECOVERY
            !hasWallet -> Route.ONBOARDING
            !hasPin -> Route.PIN_SETUP
            else -> Route.AUTH
        }
        return Decision(route, recoveryPending, mark)
    }
}
