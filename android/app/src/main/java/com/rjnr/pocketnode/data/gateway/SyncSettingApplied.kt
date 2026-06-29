package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.SyncMode

/**
 * True when the wallet is already registered at the requested sync setting, so
 * tapping "Apply" should be a no-op rather than a resync (knmo B, Option 2).
 *
 * Compares the request against the APPLIED setting — the per-wallet preference
 * written at registration time — not the displayed UI state, which could lie
 * (issue C persisted CUSTOM while registering RECENT). Requires scripts to be
 * actually registered, so a never-applied setting always proceeds. Compares the
 * chosen start mode/height, not sync progress, so an advancing synced block
 * never makes a genuine re-apply look like a no-op or vice versa.
 */
fun syncSettingAlreadyApplied(
    requestedMode: SyncMode,
    requestedHeight: Long?,
    appliedMode: SyncMode,
    appliedHeight: Long?,
    isRegistered: Boolean,
): Boolean {
    if (!isRegistered) return false
    if (requestedMode != appliedMode) return false
    // Height only distinguishes CUSTOM; other modes derive their start block.
    if (requestedMode == SyncMode.CUSTOM && requestedHeight != appliedHeight) return false
    return true
}
