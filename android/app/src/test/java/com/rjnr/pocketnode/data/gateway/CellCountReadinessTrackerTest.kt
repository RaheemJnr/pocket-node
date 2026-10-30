package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.sync.ChainSyncState
import com.rjnr.pocketnode.data.wallet.WalletInfo
import org.junit.Assert.*
import org.junit.Test

class CellCountReadinessTrackerTest {
    private val script = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "aa".repeat(20))
    private val info = WalletInfo("", script, "test-address", "main-address")
    private fun chain(tip: Long, cursor: Long, kind: String = "lock", s: Script = script) =
        ChainSyncState(tip, listOf(JniScriptStatus(s, kind, "0x${cursor.toString(16)}")))

    @Test fun `completion needs fresh registered epoch positive coverage and exact lock script`() {
        val tracker = CellCountReadinessTracker()
        tracker.activate("a", NetworkType.TESTNET, info)
        assertNotNull("activation publishes a waiting source", tracker.readiness.value)
        val waiting = tracker.readiness.value!!
        tracker.observe(waiting, chain(100, 100))
        assertEquals(CellCountStage.WAITING, tracker.readiness.value!!.stage)
        tracker.registered(waiting)
        val registered = tracker.readiness.value!!
        assertEquals(CellCountStage.REGISTERED, registered.stage)
        tracker.observe(waiting, chain(100, 100)) // Poll started before registration.
        tracker.observe(registered, chain(0, 0))
        tracker.observe(registered, chain(100, 99)) // Within UI's +/-10 window is insufficient.
        tracker.observe(registered, chain(100, 100, kind = "type"))
        tracker.observe(registered, chain(100, 100, s = script.copy(codeHash = "0x" + "11".repeat(32))))
        assertEquals(CellCountStage.REGISTERED, tracker.readiness.value!!.stage)
        tracker.observe(registered, chain(100, 100)) // No false-to-true syncing edge required.
        assertEquals(CellCountStage.CAUGHT_UP, tracker.readiness.value!!.stage)
        val complete = tracker.readiness.value
        tracker.registered(waiting)
        tracker.observe(registered, chain(101, 100))
        repeat(3) { tracker.observe(registered, chain(101L + it, 101L + it)) }
        tracker.activate("a", NetworkType.TESTNET, info)
        assertEquals(complete, tracker.readiness.value)
    }

    @Test fun `landed resync replaces an epoch once and rejects duplicate stale and ABA callbacks`() {
        val tracker = CellCountReadinessTracker()
        tracker.activate("a", NetworkType.TESTNET, info)
        tracker.registered(tracker.readiness.value)
        tracker.observe(tracker.readiness.value, chain(100, 100))
        val original = tracker.readiness.value!!
        tracker.resyncLanded(original)
        val first = tracker.readiness.value!!
        assertEquals(original.epoch + 1, first.epoch)
        assertEquals(original.activationEpoch, first.activationEpoch)
        assertEquals(CellCountStage.REGISTERED, first.stage)
        tracker.resyncLanded(original)
        tracker.observe(original, chain(100, 100))
        assertEquals(first, tracker.readiness.value)
        tracker.resyncLanded(first) // A second serialized set binds the first set's new epoch.
        assertEquals(first.epoch + 1, tracker.readiness.value!!.epoch)
        val stale = tracker.readiness.value!!
        tracker.activate("b", NetworkType.TESTNET, info.copy(testnetAddress = "other"))
        val other = tracker.readiness.value
        tracker.resyncLanded(stale)
        assertEquals(other, tracker.readiness.value)
        tracker.activate("a", NetworkType.TESTNET, info)
        val returned = tracker.readiness.value
        assertNotEquals(original.activationEpoch, returned!!.activationEpoch)
        tracker.resyncLanded(stale)
        assertEquals(returned, tracker.readiness.value)
    }

    @Test fun `old source registration and completion cannot advance new activation including ABA`() {
        val tracker = CellCountReadinessTracker()
        tracker.activate("a", NetworkType.TESTNET, info)
        assertNotNull("activation publishes a waiting source", tracker.readiness.value)
        val old = tracker.readiness.value!!
        tracker.registered(old)
        val oldRegistered = tracker.readiness.value!!
        tracker.activate("b", NetworkType.TESTNET, info.copy(testnetAddress = "other"))
        val current = tracker.readiness.value
        tracker.registered(old)
        tracker.observe(oldRegistered, chain(100, 100))
        assertEquals(current, tracker.readiness.value)
        tracker.activate("a", NetworkType.TESTNET, info)
        val again = tracker.readiness.value!!
        assertTrue(again.epoch > old.epoch)
        tracker.registered(old)
        tracker.observe(oldRegistered, chain(100, 100))
        assertEquals(again, tracker.readiness.value)
    }
}
