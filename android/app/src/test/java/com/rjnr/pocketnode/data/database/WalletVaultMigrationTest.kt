package com.rjnr.pocketnode.data.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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
class WalletVaultMigrationTest {
    @Test fun `v17 upgrade preserves wallet metadata and both legacy encryption versions without activating vaults`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "vault-upgrade.db"
        ctx.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.rjnr.pocketnode.data.database.AppDatabase/17.json").readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(17) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("INSERT INTO wallets VALUES ('a', 'kept name', 'mnemonic', 'derivation', NULL, 0, 'main', 'test', 1, 11, 22, 3)")
                    for (version in 1..2) db.execSQL("INSERT INTO key_material VALUES (?, X'010203', X'0405', X'0607', 'mnemonic', 1, 123, ?)", arrayOf<Any>("legacy$version", version))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        try {
            val upgraded = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(MIGRATION_17_18).build()
            try {
                upgraded.openHelper.writableDatabase
                for (version in 1..2) {
                    val row = upgraded.keyMaterialDao().getByWalletId("legacy$version")!!
                    assertEquals(version, row.kdfVersion)
                    assertArrayEquals(byteArrayOf(1, 2, 3), row.encryptedPrivateKey)
                    assertArrayEquals(byteArrayOf(4, 5), row.encryptedMnemonic)
                    assertArrayEquals(byteArrayOf(6, 7), row.iv)
                    assertTrue(row.mnemonicBackedUp); assertEquals(123L, row.updatedAt)
                }
                upgraded.openHelper.readableDatabase.query("SELECT name, mainnetAddress FROM wallets WHERE walletId = 'a'").use {
                    assertTrue(it.moveToFirst()); assertEquals("kept name", it.getString(0)); assertEquals("main", it.getString(1))
                }
                assertTrue(upgraded.walletVaultDao().all().isEmpty())
                assertNull(upgraded.walletVaultDao().profile())
            } finally { upgraded.close() }
        } finally { ctx.deleteDatabase(name) }
    }
}
