package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.SyncMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #knmo B (Option 2): "Apply" should re-sync only when the wallet is not
 * actually registered at the requested sync setting. The old guard compared
 * against the displayed preference, which could disagree with what was really
 * applied (issue C wrote the pref but registered RECENT), so re-tapping Apply
 * silently no-opped and the restore point only took effect after a restart.
 *
 * This compares the request against the APPLIED setting (the per-wallet pref,
 * written at registration) plus whether scripts are actually registered. It
 * compares the chosen start intent, not sync progress, so it is not fooled by
 * the synced block advancing.
 */
class SyncSettingAppliedTest {

    @Test
    fun `not applied when not registered, even if pref matches`() {
        assertFalse(
            syncSettingAlreadyApplied(
                requestedMode = SyncMode.CUSTOM, requestedHeight = 19_100_000,
                appliedMode = SyncMode.CUSTOM, appliedHeight = 19_100_000,
                isRegistered = false,
            )
        )
    }

    @Test
    fun `applied when registered and custom height matches`() {
        assertTrue(
            syncSettingAlreadyApplied(
                requestedMode = SyncMode.CUSTOM, requestedHeight = 19_100_000,
                appliedMode = SyncMode.CUSTOM, appliedHeight = 19_100_000,
                isRegistered = true,
            )
        )
    }

    @Test
    fun `not applied when custom height differs`() {
        assertFalse(
            syncSettingAlreadyApplied(
                requestedMode = SyncMode.CUSTOM, requestedHeight = 19_100_000,
                appliedMode = SyncMode.CUSTOM, appliedHeight = 19_284_000,
                isRegistered = true,
            )
        )
    }

    @Test
    fun `not applied when mode differs`() {
        assertFalse(
            syncSettingAlreadyApplied(
                requestedMode = SyncMode.CUSTOM, requestedHeight = 19_100_000,
                appliedMode = SyncMode.RECENT, appliedHeight = null,
                isRegistered = true,
            )
        )
    }

    @Test
    fun `non-custom mode ignores height`() {
        assertTrue(
            syncSettingAlreadyApplied(
                requestedMode = SyncMode.RECENT, requestedHeight = null,
                appliedMode = SyncMode.RECENT, appliedHeight = 12345,
                isRegistered = true,
            )
        )
    }
}
