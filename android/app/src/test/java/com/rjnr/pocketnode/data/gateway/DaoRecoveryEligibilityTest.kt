package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.database.entity.DaoCellEntity
import com.rjnr.pocketnode.data.gateway.models.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DaoRecoveryEligibilityTest {
    private val hash = "0x" + "aa".repeat(32)
    private val other = "0x" + "bb".repeat(32)
    private fun row(index: String = "0x10") = DaoCellEntity(hash, index, 1000, "LOCKED", 100,
        "", null, 200, hash, null, 0, null, 0, "TESTNET", 0, "wallet")
    private val canonical = JniHeaderView(hash = other, number = "0xc8", epoch = "0x70800000000001", timestamp = "0x1", dao = hash, parentHash = hash, transactionsRoot = hash, proposalsHash = hash, extraHash = hash, nonce = "0x0")
    @Test fun `ordinary missing headers and unresolved metadata never create contradiction`() {
        assertFalse(hasRecoveryContradiction(listOf(row()), null))
        assertFalse(hasRecoveryContradiction(listOf(row().copy(withdrawBlockHash = null)), canonical))
        assertFalse(hasRecoveryContradiction(listOf(row()), canonical.copy(hash = hash)))
        assertFalse(hasRecoveryContradiction(listOf(row()), canonical.copy(number = "0xc9")))
    }
    @Test fun `complete historical locator disagreement and normalized aliases qualify`() {
        assertTrue(hasRecoveryContradiction(listOf(row()), canonical))
        assertTrue(hasRecoveryContradiction(listOf(row(), row("00010").copy(withdrawBlockHash = other)), null))
        assertFalse(hasRecoveryContradiction(listOf(row(), row("00010").copy(withdrawBlockHash = hash.uppercase())), null))
    }
    @Test fun `pending completed and foreign group cannot be scheduled by locator mismatch`() {
        for (status in listOf("WITHDRAWING", "UNLOCKING", "COMPLETED", "DEPOSITING"))
            assertFalse(hasRecoveryContradiction(listOf(row().copy(status = status)), canonical))
    }
    @Test fun `only negotiated light client protocol with proved peer state is suitable in either direction`() {
        fun peers(direction: String) = """{"result":[{"is_outbound":$direction,"protocols":[{"id":"0x78","version":"3"}],"sync_state":{"proved_best_known_header":{"hash":"$hash","number":"0xc8"}}}]}"""
        assertTrue(hasSuitableRecoveryPeer(Json, peers("true")))
        assertTrue(hasSuitableRecoveryPeer(Json, peers("false")))
        for (raw in listOf(null, "bad", "[]", "{}", "{\"result\":[]}",
            peers("true").replace("0x78", "0x79"), peers("true").replace("\"proved_best_known_header\":{\"hash\":\"$hash\",\"number\":\"0xc8\"}", "\"proved_best_known_header\":null"),
            peers("true").replace("\"protocols\":[{\"id\":\"0x78\",\"version\":\"3\"}]", "\"protocols\":null")))
            assertFalse("must fail closed: $raw", hasSuitableRecoveryPeer(Json, raw))
    }
}
