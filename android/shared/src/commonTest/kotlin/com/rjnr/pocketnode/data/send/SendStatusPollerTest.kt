package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.prefs.FakeUiPreferences
import com.rjnr.pocketnode.core.time.FakeClock
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.LightClientFixtures
import com.rjnr.pocketnode.data.storage.FakeBalanceCache
import com.rjnr.pocketnode.data.storage.FakeHeaderCache
import com.rjnr.pocketnode.data.storage.FakeSubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.FakeTransactionStore
import com.rjnr.pocketnode.data.storage.FakeWalletRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Evidence-based post-broadcast status under deterministic virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendStatusPollerTest {

    private val txHash = "0xabc123"

    private fun ledgerOver(api: FakeLightClientApi) = LedgerReader(
        lightClient = api,
        balanceCache = FakeBalanceCache(),
        transactionStore = FakeTransactionStore(),
        headerCache = FakeHeaderCache(),
        walletRegistry = FakeWalletRegistry(),
        candidates = FakeSubAccountCandidateStore(),
        syncPreferences = FakeSyncPreferences(),
        uiPreferences = FakeUiPreferences(),
        json = Json { ignoreUnknownKeys = true },
        logger = NoopLogger,
    )

    private fun pollerOver(
        api: FakeLightClientApi,
        balanceChanged: () -> Boolean = { true },
    ) = SendStatusPoller(
        ledger = ledgerOver(api),
        balanceChangedSinceStart = balanceChanged,
        logger = NoopLogger,
        clock = FakeClock(),
    )

    /** `get_transaction` -> `TransactionWithStatus`, only the status arm matters here. */
    private fun statusJson(status: String, blockHash: String? = null): String {
        val hash = blockHash?.let { "\"$it\"" } ?: "null"
        return """{"transaction":null,"cycles":null,"tx_status":{"status":"$status","block_hash":$hash}}"""
    }

    /** [LightClientFixtures.HEADER_BLOCK_12] with its block number rewritten. */
    private fun headerJson(number: Long): String =
        LightClientFixtures.HEADER_BLOCK_12.replace("\"number\": \"0xc\"", "\"number\": \"0x${number.toString(16)}\"")

    /** One poll tick. */
    private val tick = SendStatusPoller.POLLING_INTERVAL_MS

    @Test
    fun startSeedsTheSubmittedStateBeforeTheFirstTick() = runTest {
        val poller = pollerOver(FakeLightClientApi())

        poller.start(txHash, this)
        runCurrent()

        val state = poller.state.value
        assertEquals(SendState.PENDING, state.state)
        assertEquals(SendStatusPoller.SUBMITTED, state.statusMessage)
        assertEquals(txHash, state.txHash)
        assertEquals(0, state.confirmations)

        poller.stop()
    }

    @Test
    fun aPendingAnswerIsReportedAsPending() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("pending"))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick + 1)

        assertEquals(SendState.PENDING, poller.state.value.state)
        assertEquals(SendStatusPoller.PENDING, poller.state.value.statusMessage)

        poller.stop()
    }

    @Test
    fun aProposedAnswerIsItsOwnState() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("proposed"))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick + 1)

        assertEquals(SendState.PROPOSED, poller.state.value.state)
        assertEquals(SendStatusPoller.PROPOSED, poller.state.value.statusMessage)

        poller.stop()
    }

    /**
     * One confirmation is committed but not finished: the state is CONFIRMED,
     * the copy still counts down, and the poll keeps running.
     */
    @Test
    fun aSingleConfirmationCountsDownToTheRequiredThree() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransaction", statusJson("committed", blockHash = "0x" + "bb".repeat(32)))
            .enqueue("getTipHeader", headerJson(100))
            .enqueue("getHeader", headerJson(100))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick + 1)

        assertEquals(SendState.CONFIRMED, poller.state.value.state)
        assertEquals(1, poller.state.value.confirmations)
        assertEquals("1 confirmation (waiting for 2 more)...", poller.state.value.statusMessage)

        poller.stop()
    }

    @Test
    fun twoConfirmationsStillCountDown() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransaction", statusJson("committed", blockHash = "0x" + "bb".repeat(32)))
            .enqueue("getTipHeader", headerJson(101))
            .enqueue("getHeader", headerJson(100))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick + 1)

        assertEquals(2, poller.state.value.confirmations)
        assertEquals("2 confirmations (waiting for 1 more)...", poller.state.value.statusMessage)

        poller.stop()
    }

    @Test
    fun threeConfirmationsFinishThePollAfterTheBalanceMoves() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransaction", statusJson("committed", blockHash = "0x" + "bb".repeat(32)))
            .enqueue("getTipHeader", headerJson(102))
            .enqueue("getHeader", headerJson(100))
        var balanceChecks = 0
        val poller = pollerOver(api) { balanceChecks++; true }

        poller.start(txHash, this)
        // One tick to see the status, one more for the balance wait's first check.
        advanceTimeBy(tick * 2 + 1)

        assertEquals(SendState.CONFIRMED, poller.state.value.state)
        assertEquals(3, poller.state.value.confirmations)
        assertEquals("Fully confirmed with 3 confirmations", poller.state.value.statusMessage)
        assertEquals(1, balanceChecks)

        // Finished: no further reads however long we wait.
        val reads = api.callsTo("getTransaction").size
        advanceTimeBy(tick * 10)
        assertEquals(reads, api.callsTo("getTransaction").size)
    }

    /**
     * The light client answers `unknown` for a transaction it has not synced
     * the block for, which is the normal case for the first minute after a
     * broadcast. It is pending, not failed.
     */
    @Test
    fun unknownAnswersAreReportedAsAwaitingTheNetwork() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", null)
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick + 1)

        assertEquals(SendState.PENDING, poller.state.value.state)
        assertEquals(SendStatusPoller.AWAITING_NETWORK, poller.state.value.statusMessage)

        poller.stop()
    }

    @Test
    fun moreThanTwentyUnknownsCannotConfirmEvenWhenBalanceChanges() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", null)
        var balanceChecks = 0
        val poller = pollerOver(api) { balanceChecks++; true }
        poller.start(txHash, this)
        advanceTimeBy(tick * 25 + 1)
        assertEquals(SendState.PENDING, poller.state.value.state)
        assertEquals(txHash, poller.state.value.txHash)
        assertEquals(0, poller.state.value.confirmations)
        assertEquals(25, api.callsTo("getTransaction").size)
        assertEquals(0, balanceChecks)
        poller.stop()
    }

    @Test
    fun delayedSyncNeedsRealInclusionBeforeConfirming() = runTest {
        val answers = List<String?>(25) { null } + statusJson("committed", "0x" + "bb".repeat(32))
        val api = FakeLightClientApi().enqueue("getTransaction", *answers.toTypedArray())
            .enqueue("getTipHeader", headerJson(102)).enqueue("getHeader", headerJson(100))
        val poller = pollerOver(api) { false }
        poller.start(txHash, this)
        advanceTimeBy(tick * 25 + 1)
        assertEquals(SendState.PENDING, poller.state.value.state)
        advanceTimeBy(tick)
        assertEquals(SendState.CONFIRMED, poller.state.value.state)
        assertEquals(3, poller.state.value.confirmations)
        assertEquals(txHash, poller.state.value.txHash)
        advanceTimeBy(tick * (SendStatusPoller.MAX_BALANCE_ATTEMPTS + 1))
        assertEquals(26, api.callsTo("getTransaction").size)
    }

    @Test
    fun committedWithoutInclusionBlockIsNotConfirmed() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("committed"))
        val poller = pollerOver(api)
        poller.start(txHash, this)
        advanceTimeBy(tick * 121)
        assertEquals(SendState.TIMED_OUT, poller.state.value.state)
        assertEquals(0, poller.state.value.confirmations)
        assertEquals(txHash, poller.state.value.txHash)
    }

    @Test
    fun explicitRejectionStopsWithHashRatherThanPendingOrSuccess() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("rejected"))
        val poller = pollerOver(api)
        poller.start(txHash, this)
        advanceTimeBy(tick * 130)
        assertEquals(SendState.FAILED, poller.state.value.state)
        assertEquals(txHash, poller.state.value.txHash)
        assertEquals(0, poller.state.value.confirmations)
        assertEquals(1, api.callsTo("getTransaction").size)
    }

    @Test
    fun unavailableThenUnknownTimesOutAndReadOnlyRetryCanFindInclusion() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", "not json", null)
        val poller = pollerOver(api)
        poller.start(txHash, this)
        advanceTimeBy(tick * 121)
        assertEquals(SendState.TIMED_OUT, poller.state.value.state)
        assertEquals(txHash, poller.state.value.txHash)
        api.reset()
        api.enqueue("getTransaction", statusJson("committed", "0x" + "bb".repeat(32)))
            .enqueue("getTipHeader", headerJson(102)).enqueue("getHeader", headerJson(100))
        poller.start(txHash, this)
        advanceTimeBy(tick * 3)
        assertEquals(SendState.CONFIRMED, poller.state.value.state)
        assertEquals(txHash, poller.state.value.txHash)
        assertEquals(0, api.callsTo("sendTransaction").size)
    }

    /**
     * Out of attempts with only a handful of unknowns: the honest answer is
     * that nothing is known, not that the send failed and not that it worked.
     */
    @Test
    fun runningOutOfAttemptsOnPendingAnswersReportsATimeout() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("pending"))
        var balanceChecks = 0
        val poller = pollerOver(api) { balanceChecks++; true }

        poller.start(txHash, this)
        advanceTimeBy(tick * (SendStatusPoller.MAX_POLLING_ATTEMPTS + 1))

        // Terminal for the sheet: a PENDING left here kept the sheet and the
        // form waiting on a poll that had already stopped.
        assertEquals(SendState.TIMED_OUT, poller.state.value.state)
        assertEquals(SendStatusPoller.TIMED_OUT, poller.state.value.statusMessage)
        assertEquals(txHash, poller.state.value.txHash)
        assertEquals(0, balanceChecks)
        assertEquals(SendStatusPoller.MAX_POLLING_ATTEMPTS, api.callsTo("getTransaction").size)

        // And it is the end of the poll: nothing reads after it.
        advanceTimeBy(tick * 10)
        assertEquals(SendStatusPoller.MAX_POLLING_ATTEMPTS, api.callsTo("getTransaction").size)
    }

    /** A proposal that never commits within the window is no more known than a pending one. */
    @Test
    fun runningOutOfAttemptsOnAProposalReportsATimeout() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("proposed"))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick * (SendStatusPoller.MAX_POLLING_ATTEMPTS + 1))

        assertEquals(SendState.TIMED_OUT, poller.state.value.state)
        assertEquals(SendStatusPoller.TIMED_OUT, poller.state.value.statusMessage)
    }

    /**
     * Every read failing is not an answer either: the poll still ends in the
     * terminal-but-uncertain state rather than spinning on PENDING.
     */
    @Test
    fun runningOutOfAttemptsOnFailedReadsReportsATimeout() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", "not json")
        var balanceChecks = 0
        val poller = pollerOver(api) { balanceChecks++; true }

        poller.start(txHash, this)
        advanceTimeBy(tick * (SendStatusPoller.MAX_POLLING_ATTEMPTS + 1))

        assertEquals(SendState.TIMED_OUT, poller.state.value.state)
        assertEquals(SendStatusPoller.TIMED_OUT, poller.state.value.statusMessage)
        assertEquals(0, balanceChecks)
    }

    /**
     * Committed but short of three confirmations when the window closes: it
     * is on chain, so the sheet keeps saying so instead of "could not confirm".
     */
    @Test
    fun runningOutOfAttemptsAfterACommitKeepsTheConfirmation() = runTest {
        val api = FakeLightClientApi()
            .enqueue("getTransaction", statusJson("committed", blockHash = "0x" + "bb".repeat(32)))
            .enqueue("getTipHeader", headerJson(100))
            .enqueue("getHeader", headerJson(100))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick * (SendStatusPoller.MAX_POLLING_ATTEMPTS + 1))

        assertEquals(SendState.CONFIRMED, poller.state.value.state)
        assertEquals(1, poller.state.value.confirmations)
        assertEquals("1 confirmation (waiting for 2 more)...", poller.state.value.statusMessage)
    }

    /**
     * Fifteen final unknowns exercise the former timeout-to-success branch.
     */
    @Test
    fun runningOutOfAttemptsAfterFifteenUnknownsRetainsUnverifiedHash() = runTest {
        // 105 pending answers, then 15 unknown: the run never reaches 21, so
        // only the timeout branch can fire.
        val scripted = buildList {
            repeat(105) { add(statusJson("pending")) }
            repeat(15) { add(null) }
        }
        val api = FakeLightClientApi().enqueue("getTransaction", *scripted.toTypedArray())
        var balanceChecks = 0
        val poller = pollerOver(api) { balanceChecks++; true }

        poller.start(txHash, this)
        advanceTimeBy(tick * (SendStatusPoller.MAX_POLLING_ATTEMPTS + 2))

        assertEquals(SendState.TIMED_OUT, poller.state.value.state)
        assertEquals(txHash, poller.state.value.txHash)
        assertEquals(0, poller.state.value.confirmations)
        assertEquals(0, balanceChecks)
    }

    @Test
    fun stopHaltsThePollAndLeavesTheLastStateOnScreen() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("pending"))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick + 1)
        val reads = api.callsTo("getTransaction").size
        poller.stop()
        advanceTimeBy(tick * 10)

        assertEquals(reads, api.callsTo("getTransaction").size)
        assertEquals(SendStatusPoller.PENDING, poller.state.value.statusMessage)
    }

    /** A second send must not be reported under the first one's hash. */
    @Test
    fun startingAgainReplacesThePollRatherThanRunningTwo() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("pending"))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick + 1)
        poller.start("0xdef456", this)
        runCurrent()

        assertEquals("0xdef456", poller.state.value.txHash)
        assertEquals(SendStatusPoller.SUBMITTED, poller.state.value.statusMessage)

        val before = api.callsTo("getTransaction").size
        advanceTimeBy(tick + 1)
        // One further read, from the one surviving poll.
        assertEquals(before + 1, api.callsTo("getTransaction").size)

        poller.stop()
    }

    @Test
    fun theMarkersCarryTheSharedPreBroadcastCopy() = runTest {
        val poller = pollerOver(FakeLightClientApi())

        poller.markBuilding()
        assertEquals(SendState.SENDING, poller.state.value.state)
        assertEquals(SendStatusPoller.BUILDING, poller.state.value.statusMessage)

        poller.markBroadcasting()
        assertEquals(SendStatusPoller.BROADCASTING, poller.state.value.statusMessage)

        poller.markFailed()
        assertEquals(SendState.FAILED, poller.state.value.state)
        assertEquals(SendStatusPoller.FAILED, poller.state.value.statusMessage)

        poller.reset()
        assertEquals(SendState.IDLE, poller.state.value.state)
        assertTrue(poller.state.value.statusMessage.isEmpty())
    }

    /**
     * A broadcast that was accepted and then failed still has a hash worth
     * looking up, so failing must not throw it away.
     */
    /**
     * Found in review: `finish()` cleared `job` unconditionally, so a poll that ended
     * after a newer one had started left the newer loop unstoppable.
     */
    @Test
    fun anOldPollFinishingDoesNotStrandTheOneThatReplacedIt() = runTest {
        // The first poll self-terminates on its third confirmation; the second
        // keeps running until it is stopped.
        val api = FakeLightClientApi()
            .enqueue("getTransaction", statusJson("committed", blockHash = "0x" + "bb".repeat(32)))
            .enqueue("getTipHeader", headerJson(102))
            .enqueue("getHeader", headerJson(100))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        poller.start("0xdef456", this)
        advanceTimeBy(tick * 3 + 1)

        // The first poll has long since finished. If it cleared the second
        // one's job on the way out, this stop is a no-op and the loop runs on.
        val before = api.callsTo("getTransaction").size
        poller.stop()
        advanceTimeBy(tick * 10)

        assertEquals(before, api.callsTo("getTransaction").size, "stop must still reach the live poll")
    }

    /** A new send must not be narrated by the previous one's loop. */
    @Test
    fun theMarkersStopAPollThatIsStillRunning() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("pending"))
        val poller = pollerOver(api)

        poller.start(txHash, this)
        advanceTimeBy(tick + 1)
        val before = api.callsTo("getTransaction").size

        poller.markBuilding()
        advanceTimeBy(tick * 10)

        assertEquals(before, api.callsTo("getTransaction").size)
        assertEquals(SendStatusPoller.BUILDING, poller.state.value.statusMessage)
    }

    @Test
    fun failingKeepsTheHashTheBroadcastReturned() = runTest {
        val poller = pollerOver(FakeLightClientApi())

        poller.start(txHash, this)
        runCurrent()
        poller.markFailed()

        assertEquals(txHash, poller.state.value.txHash)
    }
    @Test
    fun malformedBlockHashesNeverSupplyInclusionEvidence() = runTest {
        for (block in listOf("", "0x", "0xbb", "0x" + "gg".repeat(32))) {
            val api = FakeLightClientApi().enqueue("getTransaction", statusJson("committed", block))
            val poller = pollerOver(api)
            poller.start(txHash, this)
            advanceTimeBy(tick * 121)
            assertEquals(SendState.TIMED_OUT, poller.state.value.state, block)
            assertEquals(0, poller.state.value.confirmations)
        }
    }

    @Test
    fun cancellationFromTheBridgeStopsInsteadOfBecomingAnUnavailableRead() = runTest {
        var reads = 0
        val api = object : FakeLightClientApi() {
            override fun getTransaction(hash: String): String? {
                reads++
                throw kotlinx.coroutines.CancellationException("poll cancelled")
            }
        }
        val poller = pollerOver(api)
        poller.start(txHash, this)
        advanceTimeBy(tick * 125)
        assertEquals(1, reads)
        assertEquals(SendState.PENDING, poller.state.value.state)
        assertEquals(txHash, poller.state.value.txHash)
    }

    @Test
    fun replacedPollCannotPublishItsLateInclusionRead() = runTest {
        lateinit var poller: SendStatusPoller
        val scope = this
        val api = object : FakeLightClientApi() {
            override fun getTransaction(hash: String): String? {
                if (hash == txHash) {
                    poller.start("0xnew", scope)
                    return statusJson("committed", "0x" + "bb".repeat(32))
                }
                return null
            }
        }
        poller = pollerOver(api)
        poller.start(txHash, this)
        advanceTimeBy(tick + 1)
        assertEquals("0xnew", poller.state.value.txHash)
        assertEquals(SendState.PENDING, poller.state.value.state)
        assertEquals(0, poller.state.value.confirmations)
        poller.stop()
    }

    @Test
    fun cancelledBalanceWaitCannotOverwriteReplacementProgress() = runTest {
        val api = FakeLightClientApi().enqueue("getTransaction", statusJson("committed", "0x" + "bb".repeat(32)))
            .enqueue("getTipHeader", headerJson(102)).enqueue("getHeader", headerJson(100))
        lateinit var poller: SendStatusPoller
        val scope = this
        poller = pollerOver(api) { poller.start("0xnew", scope); true }
        poller.start(txHash, this)
        advanceTimeBy(tick * 2 + 1)
        assertEquals("0xnew", poller.state.value.txHash)
        assertEquals(SendState.PENDING, poller.state.value.state)
        assertEquals(0, poller.state.value.confirmations)
        assertEquals(SendStatusPoller.SUBMITTED, poller.state.value.statusMessage)
        poller.stop()
    }

}
