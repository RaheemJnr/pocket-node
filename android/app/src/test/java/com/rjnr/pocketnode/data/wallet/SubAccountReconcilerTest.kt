package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * State machine under test: PENDING -> FOUND on history, PENDING -> EMPTY
 * only when scanned near tip with no history, PENDING preserved when the
 * scan is incomplete or the probe can't answer. Mis-declaring EMPTY
 * mid-scan would permanently hide a real sub-account.
 */
@RunWith(RobolectricTestRunner::class)
class SubAccountReconcilerTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SubAccountCandidateDao

    private val parent = "parent-1"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.subAccountCandidateDao()
    }

    @After
    fun teardown() = db.close()

    private suspend fun seed(index: Int, args: String) {
        dao.insertAll(
            listOf(
                SubAccountCandidateEntity(
                    parentWalletId = parent,
                    accountIndex = index,
                    scriptArgs = args,
                    createdAt = 1L,
                )
            )
        )
    }

    private suspend fun stateOf(index: Int): String =
        dao.getForParent(parent).first { it.accountIndex == index }.state

    @Test
    fun `history flips PENDING to FOUND`() = runTest {
        seed(1, "0xaa")
        val r = SubAccountReconciler(dao) { true }
        r.reconcileNow(mapOf("0xaa" to 500L), tipHeight = 10_000L)
        assertEquals(SubAccountCandidateEntity.STATE_FOUND, stateOf(1))
    }

    @Test
    fun `no history near tip retires candidate as EMPTY`() = runTest {
        seed(1, "0xaa")
        val r = SubAccountReconciler(dao) { false }
        r.reconcileNow(mapOf("0xaa" to 9_500L), tipHeight = 10_000L)
        assertEquals(SubAccountCandidateEntity.STATE_EMPTY, stateOf(1))
    }

    @Test
    fun `no history mid-scan stays PENDING`() = runTest {
        seed(1, "0xaa")
        val r = SubAccountReconciler(dao) { false }
        r.reconcileNow(mapOf("0xaa" to 2_000L), tipHeight = 10_000L)
        assertEquals(SubAccountCandidateEntity.STATE_PENDING, stateOf(1))
    }

    @Test
    fun `unknown tip never declares EMPTY`() = runTest {
        seed(1, "0xaa")
        val r = SubAccountReconciler(dao) { false }
        r.reconcileNow(mapOf("0xaa" to 9_999_999L), tipHeight = 0L)
        assertEquals(SubAccountCandidateEntity.STATE_PENDING, stateOf(1))
    }

    @Test
    fun `unregistered script or indeterminate probe stays PENDING`() = runTest {
        seed(1, "0xaa") // not in scannedByArgs
        seed(2, "0xbb") // probe returns null
        val r = SubAccountReconciler(dao) { args -> if (args == "0xbb") null else true }
        r.reconcileNow(mapOf("0xbb" to 9_500L), tipHeight = 10_000L)
        assertEquals(SubAccountCandidateEntity.STATE_PENDING, stateOf(1))
        assertEquals(SubAccountCandidateEntity.STATE_PENDING, stateOf(2))
    }

    @Test
    fun `FOUND survives later empty-looking passes`() = runTest {
        seed(1, "0xaa")
        val r = SubAccountReconciler(dao) { true }
        r.reconcileNow(mapOf("0xaa" to 9_500L), tipHeight = 10_000L)
        // Second pass with a probe that now says no history (e.g. transient
        // light-client hiccup) must not demote FOUND — only PENDING is judged.
        val r2 = SubAccountReconciler(dao) { false }
        r2.reconcileNow(mapOf("0xaa" to 9_900L), tipHeight = 10_000L)
        assertEquals(SubAccountCandidateEntity.STATE_FOUND, stateOf(1))
    }
}
