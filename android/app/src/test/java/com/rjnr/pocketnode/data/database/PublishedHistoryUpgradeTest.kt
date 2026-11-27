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
class PublishedHistoryUpgradeTest {
    @Test fun `published 18 chain preserves pre18M history and security metadata across interruption`() = runBlocking { exercise(false) }
    @Test fun `already erased 1_8_7 repairs unknown hints without inventing coverage`() = runBlocking { exercise(true) }

    private suspend fun exercise(erased: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "published-history-upgrade-$erased.db"
        val version = if (erased) 20 else 18
        val prefs = context.getSharedPreferences("synthetic-upgrade-security", Context.MODE_PRIVATE)
        prefs.edit().putString("pin-marker", "synthetic-only").putString("activeWallet", "active").commit()
        context.deleteDatabase(name)
        context.getSharedPreferences("verified_chain_cache", Context.MODE_PRIVATE).edit().clear().commit()
        val schema = JSONObject(File("schemas/com.rjnr.pocketnode.data.database.AppDatabase/$version.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    for (wallet in listOf("active", "inactive", "empty")) db.execSQL("INSERT INTO wallets VALUES ('$wallet', '$wallet', 'HD', NULL, NULL, 0, 'main-$wallet', 'test-$wallet', 0, 1, 1, 0)")
                    db.execSQL("INSERT INTO wallet_vault VALUES ('inactive', 4, 2, X'010203', 0)")
                    db.execSQL("INSERT INTO wallet_passphrase_profile VALUES (1, X'040506')")
                    db.execSQL("INSERT INTO key_material VALUES ('inactive', X'070809', NULL, X'01', 'HD', 1, 1, 2)")
                    db.execSQL("INSERT INTO sync_progress VALUES ('inactive', 'MAINNET', 12000000, 17000000, 1)")
                    db.execSQL("INSERT INTO sync_progress VALUES ('inactive', 'TESTNET', 100, 200, 1)")
                    db.execSQL("INSERT INTO transactions VALUES ('historic', '0xf42400', 'old', 123, '42', 'out', '1', 10, '0x7b', 'MAINNET', 'CONFIRMED', 0, 1, 'inactive', 100000)")
                    db.execSQL("INSERT INTO transactions VALUES ('erased', '0x0', '', 0, '0', 'in', '', 0, NULL, 'MAINNET', 'UNVERIFIED', 0, 1, 'inactive', NULL)")
                    if (erased) db.execSQL("UPDATE transactions SET blockNumber='0x0', balanceChange='0', fee='', fee_shannons=NULL, status='UNVERIFIED'")
                    db.execSQL("INSERT INTO transactions VALUES ('local-pending', '0x0', '', 456, '-61', 'out', '1', 0, NULL, 'MAINNET', 'PENDING', 1, 1, 'inactive', 1)")
                    db.execSQL("INSERT INTO balance_cache VALUES ('inactive', 'MAINNET', 'main-inactive', '100', '1', '0xf42400', 1)")
                    for ((hash, status) in listOf("dao-old" to "LOCKED", "local-pending" to "DEPOSITING")) db.execSQL("INSERT INTO dao_cells VALUES ('$hash', '0x0', 100000000000, '$status', 16000000, 'old', NULL, 16000100, 'withdraw', NULL, 42, NULL, 1, 'MAINNET', 1, 'inactive')")
                    db.execSQL("INSERT INTO pending_broadcasts VALUES ('local-pending', 'inactive', 'MAINNET', '{}', '[]', 'BROADCASTING', 17000000, 0, 1, 1)")
                    db.execSQL("INSERT INTO pending_dao_withdraws VALUES ('dao-old', '0x0', 'withdraw', 'inactive', 'MAINNET', 1)")
                    db.execSQL("INSERT INTO pending_dao_unlocks VALUES ('withdraw', '0x0', 'unlock', 'inactive', 'MAINNET', 1)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase; helper.close()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21).build()
        val client = mockk<LightClientApi> { every { callRpc("get_verification_epoch") } returns """{"result":{"version":1,"generation":"${"a".repeat(32)}"}}""" }
        try {
            var database = open()
            try {
                val archive = database.historyRecovery().archive("inactive", "MAINNET").associateBy { it.txHash }
                // Authoritative receipt still exists, so only the already-erased identity is shown.
                assertEquals(if (erased) setOf("erased", "historic") else setOf("erased"), archive.keys)
                assertNull(archive.getValue("erased").blockNumberHint)
                assertEquals(12_000_000L, database.historyRecovery().job("inactive", "MAINNET")!!.previousLightStart)
                assertEquals(17_000_000L, database.historyRecovery().job("inactive", "MAINNET")!!.previousSavedBlock)
                assertNotNull(database.historyRecovery().job("empty", "MAINNET"))
                val gate = VerificationCacheGate(context, database, com.rjnr.pocketnode.data.gateway.DaoRecoveryLifetime())
                gate.writeGeneration = { _, _ -> false }
                try { gate.reconcile(client, NetworkType.MAINNET); fail("accepted failed marker") } catch (_: IllegalStateException) { }
                assertFalse(gate.isReady("MAINNET"))
            } finally { database.close() }
            database = open()
            try {
                VerificationCacheGate(context, database, com.rjnr.pocketnode.data.gateway.DaoRecoveryLifetime()).reconcile(client, NetworkType.MAINNET)
                val archive = database.historyRecovery().archive("inactive", "MAINNET").associateBy { it.txHash }
                assertEquals(if (erased) null else "0xf42400", archive.getValue("historic").blockNumberHint)
                assertEquals(if (erased) null else "42", archive.getValue("historic").balanceChangeHint)
                assertEquals(123L, archive.getValue("historic").timestampHint)
                assertEquals("out", archive.getValue("historic").directionHint)
                assertEquals(if (erased) null else "1", archive.getValue("historic").feeHint)
                assertNull(archive.getValue("erased").timestampHint)
                assertEquals(0L, database.historyRecovery().job("inactive", "MAINNET")!!.startBlock)
                assertEquals(200L, database.syncProgressDao().get("inactive", "TESTNET")!!.localSavedBlockNumber)
                val sql = database.openHelper.readableDatabase
                fun scalar(query: String) = sql.query(query).use { c -> check(c.moveToFirst()); c.getString(0) }
                assertEquals("0", scalar("SELECT COUNT(*) FROM balance_cache WHERE network='MAINNET'"))
                assertEquals("0", scalar("SELECT COUNT(*) FROM dao_cells WHERE status='LOCKED'"))
                assertEquals("1", scalar("SELECT COUNT(*) FROM dao_cells WHERE status='DEPOSITING'"))
                for (table in listOf("pending_broadcasts", "pending_dao_withdraws", "pending_dao_unlocks")) assertEquals("1", scalar("SELECT COUNT(*) FROM $table"))
                val daoHints = database.historyRecovery().daoHints("inactive", "MAINNET").associateBy { it.txHash }
                assertEquals(setOf("dao-old", "local-pending"), daoHints.keys)
                val oldDao = daoHints.getValue("dao-old")
                assertEquals("inactive", oldDao.walletId)
                assertEquals("MAINNET", oldDao.network)
                assertEquals("0x0", oldDao.outputIndex)
                assertEquals(16_000_000L, oldDao.depositBlockHint)
                assertEquals(16_000_100L, oldDao.withdrawBlockHint)
                assertEquals(100_000_000_000L, oldDao.capacityHint)
                assertEquals("LOCKED", oldDao.statusHint)
                assertTrue(database.historyRecovery().daoHints("active", "MAINNET").isEmpty())
                assertEquals("inactive|MAINNET|{}|[]|BROADCASTING", scalar("SELECT walletId || '|' || network || '|' || signedTxJson || '|' || reservedInputs || '|' || state FROM pending_broadcasts WHERE txHash='local-pending'"))
                assertEquals("inactive|MAINNET|0x0|withdraw", scalar("SELECT walletId || '|' || network || '|' || depositIndex || '|' || withdrawTxHash FROM pending_dao_withdraws WHERE depositTxHash='dao-old'"))
                assertEquals("inactive|MAINNET|0x0|unlock", scalar("SELECT walletId || '|' || network || '|' || withdrawingIndex || '|' || unlockTxHash FROM pending_dao_unlocks WHERE withdrawingTxHash='withdraw'"))
                val local = database.transactionDao().getByTxHash("local-pending")!!
                assertTrue(local.isLocal)
                assertEquals("-61", local.balanceChange)
                assertEquals("inactive", local.walletId)
                assertEquals("PENDING", database.transactionDao().getByTxHash("local-pending")!!.status)
                assertEquals("synthetic-only", prefs.getString("pin-marker", null))
                assertEquals("active", prefs.getString("activeWallet", null))
                assertEquals("010203", scalar("SELECT hex(envelope) FROM wallet_vault"))
                assertEquals("040506", scalar("SELECT hex(envelope) FROM wallet_passphrase_profile"))
                assertEquals("070809", scalar("SELECT hex(encryptedPrivateKey) FROM key_material"))
                assertEquals("inactive", scalar("SELECT walletId FROM wallet_vault"))
                assertTrue(database.historyRecovery().archive("active", "MAINNET").isEmpty())
                assertEquals("PENDING", database.historyRecovery().job("inactive", "MAINNET")!!.status)
                assertEquals("UNVERIFIED", database.transactionDao().getByTxHash("historic")!!.status)
                database.historyRecovery().registered("inactive", "MAINNET", "a".repeat(32), 23_000_000, "owned-script")
            } finally { database.close() }
            database = open()
            try {
                val freshGate = VerificationCacheGate(context, database, com.rjnr.pocketnode.data.gateway.DaoRecoveryLifetime())
                assertFalse(freshGate.isReady("MAINNET"))
                freshGate.reconcile(client, NetworkType.MAINNET)
                assertEquals("REGISTERED", database.historyRecovery().job("inactive", "MAINNET")!!.status)
                assertEquals(23_000_000L, database.historyRecovery().job("inactive", "MAINNET")!!.targetBlock)
                assertEquals(2, database.historyRecovery().archive("inactive", "MAINNET").size)
            } finally { database.close() }
        } finally { context.deleteDatabase(name); prefs.edit().clear().commit() }
    }
}
