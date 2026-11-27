package com.rjnr.pocketnode.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.gateway.LightClientApi
import com.rjnr.pocketnode.data.gateway.VerificationCacheGate
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HistoryRecoveryMigrationTest {
    @Test fun `v20 capture precedes erasure and interrupted handoff survives reopen`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-recovery-migration.db"
        context.deleteDatabase(name)
        context.getSharedPreferences("verified_chain_cache", Context.MODE_PRIVATE).edit().clear().commit()
        val schema = JSONObject(File("schemas/com.rjnr.pocketnode.data.database.AppDatabase/20.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(20) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("INSERT INTO sync_progress VALUES ('inactive', 'MAINNET', 12000000, 17000000, 1)")
                    db.execSQL("INSERT INTO sync_progress VALUES ('inactive', 'TESTNET', 100, 200, 1)")
                    db.execSQL("INSERT INTO transactions VALUES ('historic', '0xf42400', 'old', 123, '42', 'out', '1', 10, '0x7b', 'MAINNET', 'CONFIRMED', 0, 1, 'inactive', 100000)")
                    db.execSQL("INSERT INTO transactions VALUES ('erased', '0x0', '', 0, '0', 'in', '', 0, NULL, 'MAINNET', 'UNVERIFIED', 0, 1, 'inactive', NULL)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase; helper.close()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_20_21).build()
        val client = mockk<LightClientApi> { every { callRpc("get_verification_epoch") } returns """{"result":{"version":1,"generation":"${"a".repeat(32)}"}}""" }
        try {
            var database = open()
            try {
                val archive = database.historyRecovery().archive("inactive", "MAINNET").associateBy { it.txHash }
                // Authoritative receipt still exists, so only the already-erased identity is shown.
                assertEquals(setOf("erased"), archive.keys)
                assertNull(archive.getValue("erased").blockNumberHint)
                assertEquals(17_000_000L, database.historyRecovery().job("inactive", "MAINNET")!!.previousSavedBlock)
                val gate = VerificationCacheGate(context, database, com.rjnr.pocketnode.data.gateway.DaoRecoveryLifetime())
                gate.writeGeneration = { _, _ -> false }
                try { gate.reconcile(client, NetworkType.MAINNET); fail("accepted failed marker") } catch (_: IllegalStateException) { }
                assertFalse(gate.isReady("MAINNET"))
            } finally { database.close() }
            database = open()
            try {
                VerificationCacheGate(context, database, com.rjnr.pocketnode.data.gateway.DaoRecoveryLifetime()).reconcile(client, NetworkType.MAINNET)
                val archive = database.historyRecovery().archive("inactive", "MAINNET").associateBy { it.txHash }
                assertEquals("0xf42400", archive.getValue("historic").blockNumberHint)
                assertEquals("42", archive.getValue("historic").balanceChangeHint)
                assertNull(archive.getValue("erased").timestampHint)
                assertEquals(0L, database.historyRecovery().job("inactive", "MAINNET")!!.startBlock)
                assertEquals(200L, database.syncProgressDao().get("inactive", "TESTNET")!!.localSavedBlockNumber)
                assertEquals("UNVERIFIED", database.transactionDao().getByTxHash("historic")!!.status)
            } finally { database.close() }
        } finally { context.deleteDatabase(name) }
    }
}
