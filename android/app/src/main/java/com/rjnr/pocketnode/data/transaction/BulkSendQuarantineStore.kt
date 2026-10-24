package com.rjnr.pocketnode.data.transaction

import android.content.Context
import android.content.SharedPreferences
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Public evidence only. Written before a batch may sign, so process death cannot make it retryable. */
data class InterruptedBulkSend(
    val id: String,
    val walletId: String,
    val fromAddress: String,
    val network: NetworkType,
    val submittedRecipients: List<String>,
    val submittedTxHashes: List<String>,
    val uncertainRecipients: List<String>,
)

/** Checked synchronous commits are the durability boundary before any bulk signature. */
@Singleton
class BulkSendQuarantineStore internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("bulk_send_quarantine", Context.MODE_PRIVATE))

    private var writeFailed = false

    @Synchronized fun load(): List<InterruptedBulkSend> {
        check(!writeFailed) { "Bulk send history could not be saved. Restart and reconcile before retrying." }
        val raw = prefs.getString("records", null) ?: return emptyList()
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Invalid bulk send history" }
        val root = JSONObject(raw)
        require(root.get("version") == 1)
        val entries = root.getJSONArray("records")
        require(entries.length() <= 128)
        val records = (0 until entries.length()).map { i ->
            val row = entries.getJSONObject(i)
            fun text(key: String): String = (row.get(key) as? String) ?: error("Invalid bulk send history")
            fun strings(key: String): List<String> {
                val values = row.getJSONArray(key)
                require(values.length() <= 10_000)
                return (0 until values.length()).map { (values.get(it) as? String ?: error("Invalid bulk send history")).also(::validateText) }
            }
            InterruptedBulkSend(text("id"), text("walletId"), text("fromAddress"),
                NetworkType.valueOf(text("network")), strings("submittedRecipients"),
                strings("submittedTxHashes"), strings("uncertainRecipients")).also(::validate)
        }
        require(records.map { it.id }.distinct().size == records.size)
        return records
    }

    @Synchronized fun put(record: InterruptedBulkSend) {
        validate(record)
        save(load().filterNot { it.id == record.id } + record)
    }

    /** Called only after explicit reconciliation acknowledgement or complete successful submission. */
    @Synchronized fun remove(id: String) { save(load().filterNot { it.id == id }) }

    private fun save(records: List<InterruptedBulkSend>) {
        require(records.size <= 128)
        val rows = JSONArray()
        records.forEach { r -> rows.put(JSONObject().put("id", r.id).put("walletId", r.walletId)
            .put("fromAddress", r.fromAddress).put("network", r.network.name)
            .put("submittedRecipients", JSONArray(r.submittedRecipients))
            .put("submittedTxHashes", JSONArray(r.submittedTxHashes))
            .put("uncertainRecipients", JSONArray(r.uncertainRecipients))) }
        val raw = JSONObject().put("version", 1).put("records", rows).toString()
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val previous = prefs.getString("records", null)
        val committed = try { prefs.edit().putString("records", raw).commit() } catch (_: Exception) { false }
        if (!committed) {
            // SharedPreferences changes memory even on failed disk commit. Restore the conservative
            // prior record in memory too, so another reader cannot mistake a failed removal for success.
            writeFailed = true
            try { prefs.edit().putString("records", previous).commit() } catch (_: Exception) { /* Remain failed closed. */ }
            error("Bulk send history could not be saved. No further batch is authorized.")
        }
    }

    private fun validate(record: InterruptedBulkSend) {
        listOf(record.id, record.walletId, record.fromAddress).forEach(::validateText)
        require(record.submittedRecipients.size + record.uncertainRecipients.size <= 10_000)
        require(record.submittedTxHashes.size <= 400)
        (record.submittedRecipients + record.uncertainRecipients + record.submittedTxHashes).forEach(::validateText)
        require(record.submittedRecipients.distinct().size == record.submittedRecipients.size)
        require(record.uncertainRecipients.distinct().size == record.uncertainRecipients.size)
        require(record.submittedRecipients.intersect(record.uncertainRecipients.toSet()).isEmpty())
    }
    private fun validateText(value: String) { require(value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }) }
    private companion object {
        const val MAX_BYTES = 1_048_576
    }
}
