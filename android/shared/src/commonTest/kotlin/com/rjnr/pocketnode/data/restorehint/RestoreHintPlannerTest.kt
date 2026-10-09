package com.rjnr.pocketnode.data.restorehint

import com.rjnr.pocketnode.data.gateway.models.SyncMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #559: a hint may only make a restore start earlier, never later than the source covered. */
class RestoreHintPlannerTest {

    private fun hint(
        vararg accounts: RestoreHintAccount,
        kind: String = RestoreHintKind.MNEMONIC,
        found: List<Int> = emptyList(),
    ) = RestoreHintPayload(
        network = "TESTNET",
        createdAtMs = 0L,
        tipHeight = 20_000_000L,
        tipHash = "0x00",
        kind = kind,
        accounts = accounts.toList(),
        discovery = RestoreHintDiscovery(found = found, highestScanned = 10),
    )

    private fun account(index: Int, coverage: Long, first: Long?) =
        RestoreHintAccount(index = index, coverageStart = coverage, firstActivity = first, syncMode = "RECENT")

    @Test
    fun startIsTheEarlierOfCoverageAndFirstActivityMinusTheMargin() {
        assertEquals(17_999_000L, RestoreHintPlanner.accountStart(account(0, 18_000_000L, 18_500_000L)))
        assertEquals(16_999_000L, RestoreHintPlanner.accountStart(account(0, 18_000_000L, 17_000_000L)))
        assertEquals(17_999_000L, RestoreHintPlanner.accountStart(account(0, 18_000_000L, null)))
    }

    @Test
    fun startFloorsAtZero() {
        assertEquals(0L, RestoreHintPlanner.accountStart(account(0, 500L, null)))
        assertEquals(0L, RestoreHintPlanner.accountStart(account(0, 5_000L, 10L)))
    }

    @Test
    fun startIsNeverLaterThanTheSourceCoverage() {
        val cases = listOf(
            account(0, 0L, null), account(0, 1L, 0L), account(0, 1_000L, 2_000L),
            account(0, 18_000_000L, 19_000_000L), account(0, 18_000_000L, 1L),
            account(0, Long.MAX_VALUE / 2, null),
        )
        for (a in cases) {
            val start = RestoreHintPlanner.accountStart(a)
            assertTrue(start <= a.coverageStart, "start $start > coverage ${a.coverageStart}")
            a.firstActivity?.let { assertTrue(start <= it, "start $start > first activity $it") }
        }
    }

    @Test
    fun theRestoredWalletStartsAtTheEarliestAccount() {
        val plan = RestoreHintPlanner.plan(
            hint(account(0, 18_000_000L, 18_200_000L), account(2, 17_500_000L, 17_600_000L)),
            RestoreHintKind.MNEMONIC,
        )!!
        assertEquals(
            listOf(RestoreHintAccountPlan(0, 17_999_000L), RestoreHintAccountPlan(2, 17_499_000L)),
            plan.accounts,
        )
        assertEquals(17_499_000L, plan.startBlock)
        assertEquals(17_500_000L, plan.sourceCoverageStart)
        assertEquals(SyncMode.CUSTOM, plan.syncMode)
        assertEquals(17_499_000L, plan.customHeight)
    }

    @Test
    fun aZeroStartIsFullHistoryNotCustomZero() {
        val plan = RestoreHintPlanner.plan(hint(account(0, 300L, null)), RestoreHintKind.MNEMONIC)!!
        assertEquals(0L, plan.startBlock)
        assertEquals(SyncMode.FULL_HISTORY, plan.syncMode)
        assertNull(plan.customHeight)
    }

    @Test
    fun aRawKeyRestoreUsesOnlyIndexZero() {
        val plan = RestoreHintPlanner.plan(
            hint(account(0, 18_000_000L, null), account(1, 100L, 100L), kind = RestoreHintKind.RAW_KEY, found = listOf(1)),
            RestoreHintKind.RAW_KEY,
        )!!
        assertEquals(listOf(RestoreHintAccountPlan(0, 17_999_000L)), plan.accounts)
        assertEquals(17_999_000L, plan.startBlock)
        assertEquals(emptyList(), plan.seedIndices)
    }

    @Test
    fun noMainAccountMeansNoPlan() {
        assertNull(RestoreHintPlanner.plan(hint(account(1, 18_000_000L, null)), RestoreHintKind.MNEMONIC))
        assertNull(
            RestoreHintPlanner.plan(hint(account(3, 1L, null), kind = RestoreHintKind.RAW_KEY), RestoreHintKind.RAW_KEY)
        )
    }

    @Test
    fun aKindMismatchMeansNoPlan() {
        assertNull(RestoreHintPlanner.plan(hint(account(0, 1L, null)), RestoreHintKind.RAW_KEY))
    }

    @Test
    fun seedIndicesComeFromSubAccountsAndDiscoveryWithinTheCap() {
        val plan = RestoreHintPlanner.plan(
            hint(
                account(0, 18_000_000L, null),
                account(4, 18_100_000L, null),
                found = listOf(1, 4, 12, 0, RestoreHintPlanner.MAX_SEED_INDEX + 1),
            ),
            RestoreHintKind.MNEMONIC,
        )!!
        assertEquals(listOf(1, 4, 12), plan.seedIndices)
    }

    @Test
    fun duplicateIndicesTakeTheEarliestStart() {
        val plan = RestoreHintPlanner.plan(
            hint(account(0, 18_000_000L, null), account(0, 16_000_000L, null)),
            RestoreHintKind.MNEMONIC,
        )!!
        assertEquals(listOf(RestoreHintAccountPlan(0, 15_999_000L)), plan.accounts)
    }
}
