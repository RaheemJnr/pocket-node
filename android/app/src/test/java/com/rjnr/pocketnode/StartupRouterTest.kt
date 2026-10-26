package com.rjnr.pocketnode

import com.rjnr.pocketnode.StartupRouter.Route
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per startup case of [StartupRouter]. Pure JVM, no Android. */
class StartupRouterTest {

    private var marked = 0
    private var completeCalls = 0

    private suspend fun route(
        flag: Boolean = false,
        marker: Boolean = false,
        hasWallet: Boolean = true,
        hasPin: Boolean = true,
        rows: () -> Int = { 2 },
        missing: () -> Int = { 0 },
        complete: () -> Boolean = { true },
    ): StartupRouter.Decision = StartupRouter.route(
        corruptionFlag = flag,
        markerSet = marker,
        hasWallet = hasWallet,
        hasPin = hasPin,
        walletRowCount = { rows() },
        missingWalletCount = { missing() },
        markIncomplete = { marked++ },
        completeIfDone = { completeCalls++; complete() },
    )

    @Test
    fun `pending reset routes to retry before any legacy recovery reads`() = runTest {
        val result = StartupRouter.route(resetPending = true, corruptionFlag = false, markerSet = false,
            hasWallet = false, hasPin = true,
            walletRowCount = { error("must not probe") }, missingWalletCount = { error("must not probe") },
            markIncomplete = { error("must not mutate recovery") }, completeIfDone = { error("must not complete recovery") })
        assertEquals(Route.RESET_RETRY, result.route)
    }

    @Test
    fun `healthy with no flag, no marker and every row with keys`() = runTest {
        val d = route()
        assertEquals(Route.AUTH, d.route)
        assertEquals(0, marked)
        assertEquals(0, completeCalls)
    }

    @Test
    fun `healthy without a PIN goes to PIN setup`() = runTest {
        assertEquals(Route.PIN_SETUP, route(hasPin = false).route)
    }

    @Test
    fun `stale marker with every wallet restored routes normally`() = runTest {
        val d = route(marker = true, complete = { true })
        assertEquals(Route.AUTH, d.route)
        assertEquals(0, marked)
        assertEquals(1, completeCalls)
    }

    @Test
    fun `flag up but every key present routes normally without marking`() = runTest {
        val d = route(flag = true, missing = { 0 }, complete = { true })
        assertEquals(Route.AUTH, d.route)
        assertEquals(0, marked)
    }

    @Test
    fun `flag up with wallets missing keys marks and opens Recovery`() = runTest {
        val d = route(flag = true, hasWallet = false, missing = { 2 }, complete = { false })
        assertEquals(Route.RECOVERY, d.route)
        assertEquals(1, marked)
    }

    @Test
    fun `marker set with a wallet still missing and the flag down opens Recovery`() = runTest {
        val d = route(marker = true, missing = { 1 }, complete = { false })
        assertEquals(Route.RECOVERY, d.route)
        assertTrue(d.recoveryPending)
    }

    @Test
    fun `nothing at all with a marker routes to onboarding`() = runTest {
        val d = route(marker = true, hasWallet = false, rows = { 0 }, missing = { 0 }, complete = { true })
        assertEquals(Route.ONBOARDING, d.route)
        assertEquals(0, marked)
    }

    @Test
    fun `no wallet rows but backups or stray keys left keeps Recovery`() = runTest {
        val d = route(flag = true, hasWallet = false, rows = { 0 }, missing = { 0 }, complete = { false })
        assertEquals(Route.RECOVERY, d.route)
        assertEquals(0, marked)
    }

    @Test
    fun `Room throwing never traps a healthy user`() = runTest {
        val d = route(rows = { error("db") }, missing = { error("db") }, complete = { error("db") })
        assertEquals(Route.AUTH, d.route)
        assertEquals(0, marked)
        assertEquals(0, completeCalls)
    }

    @Test
    fun `Room throwing with the flag up keeps Recovery and marks`() = runTest {
        val d = route(flag = true, rows = { error("db") }, missing = { error("db") }, complete = { error("db") })
        assertEquals(Route.RECOVERY, d.route)
        assertEquals(1, marked)
    }

    @Test
    fun `lost key rows without the flag or marker open Recovery and mark`() = runTest {
        val d = route(rows = { 3 }, missing = { 1 }, complete = { false })
        assertEquals(Route.RECOVERY, d.route)
        assertEquals(1, marked)
        assertTrue(d.markedIncomplete)
    }

    @Test
    fun `no wallets and nothing else is plain onboarding`() = runTest {
        val d = route(hasWallet = false, rows = { 0 }, missing = { 0 })
        assertEquals(Route.ONBOARDING, d.route)
        assertFalse(d.recoveryPending)
        assertEquals(0, completeCalls)
    }

    @Test
    fun `legacy-only keys count as present, so an upgrading user routes normally`() = runTest {
        // missingWallets() does not count a row whose keys are still in the
        // legacy per-wallet store (RecoveredKeyRestorerTest covers that), so
        // the router sees rows with nothing missing.
        val d = route(rows = { 2 }, missing = { 0 })
        assertEquals(Route.AUTH, d.route)
        assertEquals(0, marked)
        assertEquals(0, completeCalls)
    }
}
