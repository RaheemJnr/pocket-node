package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.storage.HistoryRecoveryReader
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HistoryRecoveryPresentationTest {
    private lateinit var db: AppDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    @Test fun `display reads are bounded in SQL and scoped to wallet and network`() = runTest {
        repeat(105) { index ->
            db.openHelper.writableDatabase.execSQL("INSERT INTO history_archive VALUES ('w1','MAINNET',?,NULL,NULL,NULL,NULL,NULL)", arrayOf("hash$index"))
            db.openHelper.writableDatabase.execSQL("INSERT INTO dao_history_archive VALUES ('w1','MAINNET',?,'0',NULL,NULL,NULL,NULL)", arrayOf("dao$index"))
        }
        db.openHelper.writableDatabase.execSQL("INSERT INTO history_archive VALUES ('w2','MAINNET','other',NULL,NULL,NULL,NULL,NULL)")
        val reader = HistoryRecoveryReader(db.historyRecovery())
        assertEquals(100, reader.archive("w1", "MAINNET").size)
        assertEquals(20, reader.daoHints("w1", "MAINNET").size)
        assertTrue(reader.archive("w1", "TESTNET").isEmpty())
        assertEquals("other", reader.archive("w2", "MAINNET").single().txHash)
    }
    @Test fun `current local and canonical identities suppress historical display without promoting hints`() = runTest {
        db.openHelper.writableDatabase.execSQL("INSERT INTO history_archive VALUES ('w','MAINNET','hash',NULL,NULL,NULL,'out',NULL)")
        val reader = HistoryRecoveryReader(db.historyRecovery())
        val retained = reader.observePresentation("w", "MAINNET").first().transactions.single()
        assertTrue(retained.details.contains("Amount unknown"))
        val local = TransactionEntity("hash", "0x0", "", 1, "0x1", "out", "0x0", 0, null, "MAINNET", "PENDING", true, 1, "w")
        db.transactionDao().insert(local)
        assertTrue(reader.observePresentation("w", "MAINNET").first().transactions.isEmpty())
        db.transactionDao().insert(local.copy(status = "CONFIRMED", isLocal = false, confirmations = 5))
        assertTrue(reader.archive("w", "MAINNET").isEmpty())
        db.transactionDao().deleteByHash("hash")
        assertEquals(retained, reader.observePresentation("w", "MAINNET").first().transactions.single())
    }

}
