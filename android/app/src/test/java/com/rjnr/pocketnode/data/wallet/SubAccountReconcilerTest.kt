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

    private suspend fun seed(index: Int, args: String, registeredFrom: Long = 1_000L) {
        dao.insertAll(
            listOf(
                SubAccountCandidateEntity(
                    parentWalletId = parent,
                    derivationPath = SubAccountDiscovery.accountPath(index),
                    accountIndex = index,
                    scriptArgs = args,
                    createdAt = 1L,
                    registeredFromBlock = registeredFrom,
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
    fun `no history near tip with real coverage retires candidate as EMPTY`() = runTest {
        seed(1, "0xaa", registeredFrom = 100_000L)
        val r = SubAccountReconciler(dao) { false }
        r.reconcileNow(mapOf("0xaa" to 199_500L), tipHeight = 200_000L)
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

    /**
     * Regression: a candidate first registered AT the tip has scanned≈tip
     * instantly while covering zero history. Judging that as EMPTY retired
     * every candidate seconds after import (device-test 2026-07). EMPTY needs
     * real coverage.
     */
    @Test
    fun `no history at tip with zero coverage stays PENDING`() = runTest {
        seed(1, "0xaa", registeredFrom = 9_990L) // registered basically at tip
        seed(2, "0xbb", registeredFrom = 0L)     // never recorded a start
        val r = SubAccountReconciler(dao) { false }
        r.reconcileNow(mapOf("0xaa" to 10_000L, "0xbb" to 10_000L), tipHeight = 10_000L)
        assertEquals(SubAccountCandidateEntity.STATE_PENDING, stateOf(1))
        assertEquals(SubAccountCandidateEntity.STATE_PENDING, stateOf(2))
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

    /**
     * #382 explicit re-scan: EMPTY chain slots (accountIndex 0) re-arm to
     * PENDING; account-axis EMPTY stays retired (still gates the restore
     * banner). Without re-arm, "Scan Other Addresses" was a silent no-op
     * after any completed pass (device-verification, 2026-07).
     */
    @Test
    fun `explicit re-scan re-arms EMPTY chain slots only`() = runTest {
        dao.insertAll(
            listOf(
                SubAccountCandidateEntity(
                    parentWalletId = parent,
                    derivationPath = "m/44'/309'/0'/1/3",
                    accountIndex = 0,
                    scriptArgs = "0xchain",
                    state = SubAccountCandidateEntity.STATE_EMPTY,
                    createdAt = 1L,
                ),
                SubAccountCandidateEntity(
                    parentWalletId = parent,
                    derivationPath = SubAccountDiscovery.accountPath(2),
                    accountIndex = 2,
                    scriptArgs = "0xacct",
                    state = SubAccountCandidateEntity.STATE_EMPTY,
                    createdAt = 1L,
                ),
            )
        )
        val reArmed = dao.reArmEmptyChainSlots(parent)
        assertEquals(1, reArmed)
        val rows = dao.getForParent(parent)
        assertEquals(
            SubAccountCandidateEntity.STATE_PENDING,
            rows.first { it.accountIndex == 0 }.state,
        )
        assertEquals(
            SubAccountCandidateEntity.STATE_EMPTY,
            rows.first { it.accountIndex == 2 }.state,
        )
    }

    @Test
    fun `FOUND survives later empty-looking passes`() = runTest {
        seed(1, "0xaa", registeredFrom = 100_000L)
        val r = SubAccountReconciler(dao) { true }
        r.reconcileNow(mapOf("0xaa" to 199_500L), tipHeight = 200_000L)
        // Second pass with a probe that now says no history (e.g. transient
        // light-client hiccup) must not demote FOUND — only PENDING is judged.
        val r2 = SubAccountReconciler(dao) { false }
        r2.reconcileNow(mapOf("0xaa" to 199_900L), tipHeight = 200_000L)
        assertEquals(SubAccountCandidateEntity.STATE_FOUND, stateOf(1))
    }
}
