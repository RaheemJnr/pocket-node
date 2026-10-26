package com.rjnr.pocketnode.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.entity.WalletPendingCleanupEntity
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
class WalletPendingCleanupMigrationTest {
    @Test fun `v18 migration preserves source rows and pending deletion survives database reopen`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "pending-cleanup-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.rjnr.pocketnode.data.database.AppDatabase/18.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(18) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("INSERT INTO wallets VALUES ('kept', 'kept name', 'raw_key', NULL, NULL, 0, 'main', 'test', 0, 11, 22, 3)")
                    db.execSQL("INSERT INTO wallet_vault VALUES ('kept', 4, 2, X'010203', 1)")
                    db.execSQL("INSERT INTO wallet_vault_legacy VALUES ('kept', X'0405', NULL, X'0607', 'raw_key', 0, 123, 2)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase; helper.close()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_18_19).build()
        try {
            val upgraded = open()
            try {
                assertTrue(upgraded.walletPendingCleanupDao().all().isEmpty())
                assertEquals("kept name", upgraded.walletDao().getById("kept")!!.name)
                assertArrayEquals(byteArrayOf(1, 2, 3), upgraded.walletVaultDao().get("kept")!!.envelope)
                assertTrue(upgraded.walletVaultDao().get("kept")!!.cleanupPending)
                assertArrayEquals(byteArrayOf(4, 5), upgraded.walletVaultDao().legacy("kept")!!.encryptedPrivateKey)
                upgraded.walletPendingCleanupDao().insert(WalletPendingCleanupEntity("removed", true))
            } finally { upgraded.close() }
            val reopened = open()
            try { assertEquals(WalletPendingCleanupEntity("removed", true), reopened.walletPendingCleanupDao().get("removed")) }
            finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
