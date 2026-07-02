package com.rjnr.pocketnode.data.gateway

/**
 * Read-path null classification (#365 follow-up).
 *
 * The light client returns `null` from a query for two very different
 * reasons, and the caller can't tell them apart from the null alone:
 *
 *  1. It hasn't reported a tip header yet — cold start, still starting up,
 *     or mid-restart. Any read is expected to be empty; the user just needs
 *     to wait.
 *  2. It IS up (a tip is present) but this one read came back empty — a
 *     transient RPC hiccup worth a retry.
 *
 * Before this, both threw the same "native returned null" text, which
 * `mapSendErrorMessage` fell through to the reopen-the-app copy — actionable,
 * but wrong when the node was merely still syncing. This produces a message
 * whose wording routes correctly:
 *  - not ready -> contains "light client not ready" -> "still starting up"
 *  - ready     -> names the [operation] so the next bug report is diagnosable.
 *
 * Pure so it's unit-testable without JNI; the readiness probe stays at the
 * call site.
 */
internal fun readPathNullMessage(operation: String, lightClientReady: Boolean): String =
    if (!lightClientReady) {
        "light client not ready (no tip yet) while trying to $operation"
    } else {
        "Failed to $operation - light client returned no data (transient); please try again"
    }
