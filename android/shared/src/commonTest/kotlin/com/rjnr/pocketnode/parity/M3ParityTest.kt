package com.rjnr.pocketnode.parity

import com.rjnr.pocketnode.core.format.ckbToShannons
import com.rjnr.pocketnode.core.format.formatCkbAmount
import com.rjnr.pocketnode.core.format.formatCkbBalance
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.TransactionRecord
import com.rjnr.pocketnode.data.gateway.models.getCheckpoint
import com.rjnr.pocketnode.data.gateway.models.toFromBlock
import com.rjnr.pocketnode.data.history.displayStateOf
import com.rjnr.pocketnode.data.history.elapsedBucket
import com.rjnr.pocketnode.data.send.sweepWarning
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import com.rjnr.pocketnode.data.sync.ChainSyncState
import com.rjnr.pocketnode.data.sync.SyncEngine
import com.rjnr.pocketnode.data.sync.clampStartBlock
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Kotlin half of the M3 parity suite: the shared core reproduces every row
 * of [M3ParityFixtures].
 *
 * The Swift half is `ios/PocketNodeTests/Parity/M3ParityTests.swift`, which
 * holds the same table as literals and asserts it through the framework and
 * through the view models that format with it. Read the header of
 * [M3ParityFixtures] before changing a value here: the two files move together
 * or the mismatch is the finding.
 */
class M3ParityTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // MARK: - Sync start block

    @Test
    fun `the checkpoints the table is pinned to`() {
        assertEquals(M3ParityFixtures.MAINNET_CHECKPOINT, getCheckpoint(NetworkType.MAINNET))
        assertEquals(M3ParityFixtures.TESTNET_CHECKPOINT, getCheckpoint(NetworkType.TESTNET))
    }

    @Test
    fun `every recorded start block is what toFromBlock answers`() {
        M3ParityFixtures.startBlocks.forEach { case ->
            assertEquals(
                case.expected,
                case.mode.toFromBlock(case.customBlockHeight, case.tipHeight, case.network),
                "${case.mode} on ${case.network} at tip ${case.tipHeight}: ${case.note}",
            )
        }
    }

    @Test
    fun `every recorded clamp is what registerWallet would apply`() {
        M3ParityFixtures.clamps.forEach { case ->
            assertEquals(
                case.expected,
                clampStartBlock(case.calculated, case.mode, case.tipHeight, case.network),
                "${case.calculated} for ${case.mode} at tip ${case.tipHeight}: ${case.note}",
            )
        }
    }

    // MARK: - Sync snapshot

    @Test
    fun `every recorded snapshot is what computeStatus derives`() {
        M3ParityFixtures.syncSnapshots.forEach { case ->
            // A fresh engine per row: the percentage comes off the poller's
            // sample window, and sharing one would carry a row's samples into
            // the next.
            val engine = SyncEngine(FakeLightClientApi(), FakeSyncPreferences(), json, NoopLogger)
            val state = ChainSyncState(
                tipNumber = case.tipNumber,
                scripts = listOf(
                    script(M3ParityFixtures.OTHER_SCRIPT_ARGS, "0x1"),
                    script(M3ParityFixtures.ACTIVE_SCRIPT_ARGS, case.scriptBlockHex),
                ),
            )

            val snapshot = engine.computeStatus(state, M3ParityFixtures.ACTIVE_SCRIPT_ARGS)

            assertEquals(case.expectedScriptBlockNumber, snapshot.scriptBlockNumber, case.note)
            assertEquals(case.expectedIsSynced, snapshot.isSynced, case.note)
            assertEquals(case.expectedProgress, snapshot.progress, absoluteTolerance = 1e-9)
        }
    }

    // MARK: - Activity display state

    @Test
    fun `every recorded display state is what displayStateOf resolves`() {
        M3ParityFixtures.displayStates.forEach { case ->
            val state = displayStateOf(
                record(
                    direction = "out",
                    balanceChangeHex = "0x5f5e100",
                    status = case.recordStatus,
                    confirmations = case.confirmations,
                ),
                case.broadcastState?.let { broadcast(it) },
            )
            assertEquals(case.expected, state, case.note)
        }
    }

    @Test
    fun `every recorded elapsed reading is what elapsedBucket buckets`() {
        M3ParityFixtures.elapsed.forEach { case ->
            val bucket = elapsedBucket(case.elapsedMillis)
            assertEquals(case.expectedUnit, bucket.unit, case.note)
            assertEquals(case.expectedValue, bucket.value, case.note)
        }
    }

    // MARK: - Send

    @Test
    fun `every recorded sweep is what sweepWarning decides`() {
        M3ParityFixtures.sweeps.forEach { case ->
            val warning = sweepWarning(
                balanceShannons = case.balanceShannons,
                amountShannons = case.amountShannons,
                feeShannons = case.feeShannons,
            )
            if (!case.expectsWarning) {
                assertNull(warning, case.note)
                return@forEach
            }
            assertNotNull(warning, case.note)
            assertEquals(case.expectedRemainingShannons, warning.remainingShannons, case.note)
            assertEquals(case.expectedBelowMinCell, warning.belowMinCell, case.note)
        }
    }

    /**
     * The remaining balance the warning's sentence is built from, checked
     * against the number the iOS copy quotes. iOS writes the sentence; this
     * pins the only part of it that comes from the core.
     */
    @Test
    fun `the remaining balance in each sweep sentence is the formatted remainder`() {
        M3ParityFixtures.sweeps.filter { it.expectsWarning }.forEach { case ->
            val remaining = formatCkbAmount(case.expectedRemainingShannons, ",", ".")
            val message = assertNotNull(case.expectedMessage, case.note)
            assertEquals(
                true,
                message.contains("$remaining CKB left"),
                "${case.note}: expected \"$remaining CKB left\" inside \"$message\"",
            )
        }
    }

    @Test
    fun `every recorded rejection reads as the mapping says`() {
        M3ParityFixtures.sendErrors.forEach { case ->
            assertEquals(
                case.expected,
                com.rjnr.pocketnode.data.send.mapSendErrorMessage(case.raw),
                "${case.raw}: ${case.note}",
            )
        }
    }

    // MARK: - Amounts

    @Test
    fun `every recorded amount and fee is what the record formats to`() {
        M3ParityFixtures.amounts.forEach { case ->
            val row = record(
                direction = case.direction,
                balanceChangeHex = case.balanceChangeHex,
                status = "CONFIRMED",
                confirmations = 4,
                feeShannons = case.feeShannons,
            )
            assertEquals(case.expectedAmount, row.formattedAmount(), case.note)
            assertEquals(case.expectedFee, row.formattedFee(), case.note)
            assertEquals(case.expectedPaysNetworkFee, row.paysNetworkFee(), case.note)
        }
    }

    @Test
    fun `every recorded value formats as a balance and as an amount`() {
        M3ParityFixtures.formats.forEach { case ->
            assertEquals(case.expectedBalance, formatCkbBalance(case.shannons, ",", "."), case.note)
            assertEquals(case.expectedAmount, formatCkbAmount(case.shannons, ",", "."), case.note)
        }
    }

    @Test
    fun `every recorded typed amount parses to the recorded shannons`() {
        M3ParityFixtures.parses.forEach { case ->
            assertEquals(case.expected, ckbToShannons(case.text), "${case.text}: ${case.note}")
        }
    }

    // MARK: - Builders

    private fun script(args: String, blockNumberHex: String) = JniScriptStatus(
        script = Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = args,
        ),
        blockNumber = blockNumberHex,
    )

    private fun record(
        direction: String,
        balanceChangeHex: String,
        status: String,
        confirmations: Int,
        feeShannons: Long? = null,
    ) = TransactionRecord(
        txHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
        blockNumber = "0x1170ea8",
        blockHash = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
        timestamp = 0L,
        balanceChange = balanceChangeHex,
        direction = direction,
        fee = "0x0",
        confirmations = confirmations,
        blockTimestampHex = "0x18c8d0a7a00",
        isDaoRelated = direction.startsWith("dao_"),
        isBulk = false,
        status = status,
        feeShannons = feeShannons,
    )

    private fun broadcast(state: String) = PendingBroadcastRecord(
        txHash = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
        state = state,
        reservedInputs = "[]",
        signedTxJson = "{}",
        walletId = "w1",
        network = "TESTNET",
        submittedAtTipBlock = 100L,
        nullCount = 0,
        createdAt = 0L,
        lastCheckedAt = 0L,
    )
}
