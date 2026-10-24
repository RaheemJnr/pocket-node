package com.rjnr.pocketnode.data.transaction

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BulkSendQuarantineStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("public_bulk_quarantine_test", 0)
    private fun record() = InterruptedBulkSend("public-operation", "wallet-A", "ckt-public-source",
        NetworkType.TESTNET, listOf("ckt-submitted"), listOf("public-hash"), listOf("ckt-uncertain"))

    @Test fun `committed public record survives store reconstruction until explicit reconciliation`() {
        val record = record()
        BulkSendQuarantineStore(prefs).put(record)
        assertEquals(listOf(record), BulkSendQuarantineStore(prefs).load())
        BulkSendQuarantineStore(prefs).remove(record.id)
        assertTrue(BulkSendQuarantineStore(prefs).load().isEmpty())
    }

    @Test fun `malformed unknown or oversized history fails closed and stays intact`() {
        for (raw in listOf("broken", "{\"version\":2,\"records\":[]}", "x".repeat(1_048_577),
            "{\"version\":1,\"records\":[{}]}")) {
            prefs.edit().putString("records", raw).commit()
            assertTrue(runCatching { BulkSendQuarantineStore(prefs).load() }.isFailure)
            assertEquals(raw, prefs.getString("records", null))
        }
    }

    @Test fun `failed commit refuses progress and restores conservative record after failed removal`() {
        val record = record(); BulkSendQuarantineStore(prefs).put(record)
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val store = BulkSendQuarantineStore(failing)
        assertTrue(runCatching { store.remove(record.id) }.isFailure)
        assertTrue(runCatching { store.load() }.isFailure)
        assertEquals(listOf(record), BulkSendQuarantineStore(prefs).load())
    }

    @Test fun `duplicate recipients conflicting states and excessive records are refused`() {
        val store = BulkSendQuarantineStore(prefs)
        assertTrue(runCatching { store.put(record().copy(uncertainRecipients = record().submittedRecipients)) }.isFailure)
        assertTrue(runCatching { store.put(record().copy(walletId = "")) }.isFailure)
        assertTrue(store.load().isEmpty())
    }
}
