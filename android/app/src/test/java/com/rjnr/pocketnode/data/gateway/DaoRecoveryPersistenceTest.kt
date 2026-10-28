package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoRecoveryPersistenceTest {
    @Test fun `schema19 migration preserves rows and guards and cooldown survive reopen and cache deletion`() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "dao-recovery-migration.db"
        context.deleteDatabase(name)
        val schema = org.json.JSONObject(java.io.File("schemas/com.rjnr.pocketnode.data.database.AppDatabase/19.json").readText()).getJSONObject("database")
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                        db.execSQL("INSERT INTO wallets VALUES ('kept', 'Synthetic', 'raw_key', NULL, NULL, 0, '', '', 1, 11, 22, 0)")
                        db.execSQL("INSERT INTO pending_dao_unlocks VALUES ('0xsynthetic', '0x10', '0xpending', 'kept', 'TESTNET', 123)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        helper.writableDatabase; helper.close()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(com.rjnr.pocketnode.data.database.MIGRATION_19_20).build()
        val guard = com.rjnr.pocketnode.data.database.entity.DaoRecoveryEntity("kept", "TESTNET", "genesis", "hash", 16,
            1000, 100, "script", 200, "block", "store", "run", 1)
        val budget = com.rjnr.pocketnode.data.database.entity.DaoRecoveryRetryEntity("kept", "TESTNET", "genesis", "hash", 16,
            3, 1000, 1050, 601050)
        try {
            val migrated = open()
            try {
                assertEquals("Synthetic", migrated.walletDao().getById("kept")!!.name)
                assertEquals(1, migrated.pendingDaoUnlockDao().getByWalletAndNetwork("kept", "TESTNET").size)
                migrated.daoRecoveryDao().putGuard(guard)
                migrated.daoRecoveryDao().putBudget(budget)
                migrated.daoCellDao().deleteAll()
            } finally { migrated.close() }
            val reopened = open()
            try {
                assertEquals(listOf(guard), reopened.daoRecoveryDao().guards("TESTNET", "hash", 16))
                assertEquals(budget, reopened.daoRecoveryDao().budget("kept", "TESTNET", "genesis", "hash", 16))
                assertEquals(1, reopened.pendingDaoUnlockDao().getByWalletAndNetwork("kept", "TESTNET").size)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `scheduler debits survive actual Room reopen and restart ignores elapsed wall clock`() = kotlinx.coroutines.test.runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "dao-recovery-scheduler-restart.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        var elapsed = 0L
        var wall = 1_000_000L
        val clock = object : RecoveryClock { override fun elapsed() = elapsed; override fun wall() = wall }
        val session = RecoverySession("wallet", com.rjnr.pocketnode.data.gateway.models.NetworkType.TESTNET, "genesis",
            com.rjnr.pocketnode.data.gateway.models.Script("code", "type", "args"), 1)
        val key = RecoveryBudgetKey(session, RecoveryOutPoint("hash", 0))
        var database = open()
        try {
            database.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity("wallet", "Synthetic", "raw_key", null, null, isActive = true))
            val budget = DaoRecoveryBudget(database, clock)
            val first = budget.debit(key)!!; budget.finish(first)
            elapsed = 10_000; wall += 10_000
            val second = budget.debit(key)!!; budget.finish(second)
            elapsed = 40_000; wall += 30_000
            val third = budget.debit(key)!!; budget.finish(third)
            database.close()
            database = open()
            wall += 86_400_000 // Cannot jump over cooldown by changing device time.
            val restarted = DaoRecoveryBudget(database, clock)
            assertNull(restarted.debit(key))
            elapsed += 599_999
            wall = 1 // Nor can rollback produce an immediate retry loop.
            assertNull(restarted.debit(key))
            elapsed++
            assertEquals(1, restarted.debit(key)!!.record.attempts)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun `fresh database can retain recovery obligations and retry budgets separately from disposable cells`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val tables = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertTrue("recovery obligations must survive dao_cells deletion", "dao_recovery_guards" in tables)
            assertTrue("retry budget must survive process restart", "dao_recovery_retry_budget" in tables)
        } finally { db.close() }
    }
}
