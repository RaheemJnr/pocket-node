package com.rjnr.pocketnode.parity

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.history.ElapsedUnit
import com.rjnr.pocketnode.data.history.TxDisplayState

/**
 * The recorded M3 answers, as data.
 *
 * Every row below is an input and the exact string, number or enum the shared
 * core answers with. [M3ParityTest] asserts the Kotlin functions reproduce the
 * table; `ios/PocketNodeTests/Parity/M3ParityTests.swift` holds a literal copy
 * of the same table and asserts the framework, and the Swift view models that
 * format through it, reproduce it too.
 *
 * ## The rule
 *
 * The two files change together or not at all. A value that has to be edited on
 * one side only is the bug this suite exists to catch: it means one platform
 * reformatted a number the other did not, and the two apps now disagree about
 * money. When one of these tests fails, the job is to find out which side moved,
 * never to copy the new value into the table.
 *
 * ## What is deliberately not here
 *
 * Anything calendar-relative (`ActivityFormat.dateGroup`, `blockTimestamp`) and
 * anything that reads a device clock. Those are platform code by design, have no
 * Kotlin counterpart to agree with, and their own tests cover them.
 */
object M3ParityFixtures {

    // ------------------------------------------------------------------
    // Sync: the block a wallet registers its filter script from
    // ------------------------------------------------------------------

    /** Pinned so a changed checkpoint fails here rather than silently moving every row. */
    const val MAINNET_CHECKPOINT: Long = 18_300_000L
    const val TESTNET_CHECKPOINT: Long = 0L

    /** One `SyncMode.toFromBlock(custom, tip, network)` answer. */
    data class StartBlockCase(
        val mode: SyncMode,
        val network: NetworkType,
        val tipHeight: Long,
        val customBlockHeight: Long?,
        /** The raw string `toFromBlock` returns, before any clamp. */
        val expected: String,
        val note: String,
    )

    val startBlocks: List<StartBlockCase> = listOf(
        StartBlockCase(
            SyncMode.NEW_WALLET, NetworkType.MAINNET, tipHeight = 0L, customBlockHeight = null,
            expected = "18300000",
            note = "no tip yet, so the mainnet checkpoint stands in for it",
        ),
        StartBlockCase(
            SyncMode.NEW_WALLET, NetworkType.MAINNET, tipHeight = 18_400_000L, customBlockHeight = null,
            expected = "18400000",
            note = "a real tip beats the checkpoint",
        ),
        StartBlockCase(
            SyncMode.NEW_WALLET, NetworkType.TESTNET, tipHeight = 0L, customBlockHeight = null,
            expected = "0",
            note = "testnet's checkpoint is genesis, so a missing tip is block 0",
        ),
        StartBlockCase(
            SyncMode.NEW_WALLET, NetworkType.TESTNET, tipHeight = 15_000_000L, customBlockHeight = null,
            expected = "15000000",
            note = "a new wallet starts at the tip",
        ),
        StartBlockCase(
            SyncMode.RECENT, NetworkType.MAINNET, tipHeight = 0L, customBlockHeight = null,
            expected = "18100000",
            note = "checkpoint less the 200,000-block month",
        ),
        StartBlockCase(
            SyncMode.RECENT, NetworkType.MAINNET, tipHeight = 18_400_000L, customBlockHeight = null,
            expected = "18200000",
            note = "tip less the 200,000-block month",
        ),
        StartBlockCase(
            SyncMode.RECENT, NetworkType.TESTNET, tipHeight = 0L, customBlockHeight = null,
            expected = "0",
            note = "a negative start floors at genesis",
        ),
        StartBlockCase(
            SyncMode.RECENT, NetworkType.TESTNET, tipHeight = 100_000L, customBlockHeight = null,
            expected = "0",
            note = "a chain younger than a month floors at genesis",
        ),
        StartBlockCase(
            SyncMode.RECENT, NetworkType.TESTNET, tipHeight = 15_000_000L, customBlockHeight = null,
            expected = "14800000",
            note = "tip less the 200,000-block month",
        ),
        StartBlockCase(
            SyncMode.FULL_HISTORY, NetworkType.MAINNET, tipHeight = 18_400_000L, customBlockHeight = null,
            expected = "0",
            note = "all history is genesis whatever the tip says",
        ),
        StartBlockCase(
            SyncMode.FULL_HISTORY, NetworkType.TESTNET, tipHeight = 0L, customBlockHeight = null,
            expected = "0",
            note = "all history is genesis on testnet too",
        ),
        StartBlockCase(
            SyncMode.CUSTOM, NetworkType.MAINNET, tipHeight = 18_400_000L, customBlockHeight = 12_000_000L,
            expected = "12000000",
            note = "the typed height, verbatim",
        ),
        StartBlockCase(
            SyncMode.CUSTOM, NetworkType.TESTNET, tipHeight = 15_000_000L, customBlockHeight = 15_500_000L,
            expected = "15500000",
            note = "past the tip here; the clamp table below is what catches it",
        ),
        StartBlockCase(
            SyncMode.CUSTOM, NetworkType.TESTNET, tipHeight = 15_000_000L, customBlockHeight = null,
            expected = "0",
            note = "custom with nothing typed is block 0 before the clamp",
        ),
    )

    /** One `clampStartBlock` answer: the two guards `registerWallet` applies. */
    data class ClampCase(
        val calculated: Long,
        val mode: SyncMode,
        val tipHeight: Long,
        val network: NetworkType,
        val expected: Long,
        val note: String,
    )

    val clamps: List<ClampCase> = listOf(
        ClampCase(
            calculated = 15_500_000L, mode = SyncMode.CUSTOM,
            tipHeight = 15_000_000L, network = NetworkType.TESTNET,
            expected = 14_800_000L,
            note = "a height past the tip falls back to the last 200,000 blocks",
        ),
        ClampCase(
            calculated = 500_000L, mode = SyncMode.CUSTOM,
            tipHeight = 100_000L, network = NetworkType.TESTNET,
            expected = 0L,
            note = "the same fallback floors at genesis on a young chain",
        ),
        ClampCase(
            calculated = 0L, mode = SyncMode.CUSTOM,
            tipHeight = 18_400_000L, network = NetworkType.MAINNET,
            expected = 18_300_000L,
            note = "zero on a mode that did not ask for genesis means the checkpoint",
        ),
        ClampCase(
            calculated = 0L, mode = SyncMode.FULL_HISTORY,
            tipHeight = 18_400_000L, network = NetworkType.MAINNET,
            expected = 0L,
            note = "all history asked for genesis, so genesis it is",
        ),
        ClampCase(
            calculated = 0L, mode = SyncMode.CUSTOM,
            tipHeight = 15_000_000L, network = NetworkType.TESTNET,
            expected = 0L,
            note = "testnet has no checkpoint to fall back to",
        ),
        ClampCase(
            calculated = 14_800_000L, mode = SyncMode.RECENT,
            tipHeight = 15_000_000L, network = NetworkType.TESTNET,
            expected = 14_800_000L,
            note = "a height inside the chain is left alone",
        ),
    )

    // ------------------------------------------------------------------
    // Sync: the snapshot Home draws from a chain read
    // ------------------------------------------------------------------

    /** The wallet whose script the snapshot cases look for. */
    const val ACTIVE_SCRIPT_ARGS: String = "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1"

    /** A second registered script, so the lookup has something to skip past. */
    const val OTHER_SCRIPT_ARGS: String = "0x0000000000000000000000000000000000000001"

    /**
     * One `SyncEngine.computeStatus` answer.
     *
     * [expectedProgress] is 0.0 in every row on purpose: the percentage comes
     * off the poller's sample window, and a freshly built engine has no samples
     * and no seeded start. What these rows are about is the plus-or-minus 10
     * block rule behind `isSynced`.
     */
    data class SyncSnapshotCase(
        val tipNumber: Long,
        /** The active script's block, as the bridge reports it. */
        val scriptBlockHex: String,
        val expectedScriptBlockNumber: Long,
        val expectedIsSynced: Boolean,
        val expectedProgress: Double,
        val note: String,
    )

    val syncSnapshots: List<SyncSnapshotCase> = listOf(
        SyncSnapshotCase(
            tipNumber = 1_000L, scriptBlockHex = "0x3e2",
            expectedScriptBlockNumber = 994L, expectedIsSynced = true, expectedProgress = 0.0,
            note = "six blocks behind, inside the window",
        ),
        SyncSnapshotCase(
            tipNumber = 1_000L, scriptBlockHex = "0x3de",
            expectedScriptBlockNumber = 990L, expectedIsSynced = true, expectedProgress = 0.0,
            note = "exactly ten behind, the edge that still counts as synced",
        ),
        SyncSnapshotCase(
            tipNumber = 1_000L, scriptBlockHex = "0x3dd",
            expectedScriptBlockNumber = 989L, expectedIsSynced = false, expectedProgress = 0.0,
            note = "eleven behind, outside the window",
        ),
    )

    // ------------------------------------------------------------------
    // Activity: which lifecycle state a row is displayed in
    // ------------------------------------------------------------------

    /** One `displayStateOf(record, broadcast)` answer. */
    data class DisplayStateCase(
        val recordStatus: String,
        val confirmations: Int,
        /** The `pending_broadcasts` state, or null when no broadcast row survives. */
        val broadcastState: String?,
        val expected: TxDisplayState,
        val note: String,
    )

    val displayStates: List<DisplayStateCase> = listOf(
        DisplayStateCase(
            recordStatus = "PENDING", confirmations = 0, broadcastState = null,
            expected = TxDisplayState.PENDING,
            note = "in the pool with no broadcast row left",
        ),
        DisplayStateCase(
            recordStatus = "PENDING", confirmations = 0, broadcastState = "BROADCASTING",
            expected = TxDisplayState.BROADCASTING,
            note = "the only thing that tells sending from waiting",
        ),
        DisplayStateCase(
            recordStatus = "PENDING", confirmations = 0, broadcastState = "BROADCAST",
            expected = TxDisplayState.PENDING,
            note = "handed over and acknowledged, which is indistinguishable from waiting",
        ),
        DisplayStateCase(
            recordStatus = "PENDING", confirmations = 3, broadcastState = "BROADCASTING",
            expected = TxDisplayState.CONFIRMED,
            note = "a confirmation beats a stale broadcast row",
        ),
        DisplayStateCase(
            recordStatus = "CONFIRMED", confirmations = 0, broadcastState = null,
            expected = TxDisplayState.CONFIRMED,
            note = "the ledger status alone is enough",
        ),
        DisplayStateCase(
            recordStatus = "PENDING", confirmations = 5, broadcastState = "FAILED",
            expected = TxDisplayState.FAILED,
            note = "a terminal failure on either table wins, confirmations included",
        ),
    )

    // ------------------------------------------------------------------
    // Activity: the elapsed badge
    // ------------------------------------------------------------------

    /** One `elapsedBucket(millis)` answer, plus the words the platform renders it as. */
    data class ElapsedCase(
        val elapsedMillis: Long,
        val expectedUnit: ElapsedUnit,
        val expectedValue: Int?,
        val expectedText: String,
        val note: String,
    )

    val elapsed: List<ElapsedCase> = listOf(
        ElapsedCase(0L, ElapsedUnit.UNDER_MINUTE, null, "<1 min", "just sent"),
        ElapsedCase(59_999L, ElapsedUnit.UNDER_MINUTE, null, "<1 min", "one millisecond short of a minute"),
        ElapsedCase(120_000L, ElapsedUnit.MINUTES, 2, "2 min", "two minutes"),
        ElapsedCase(3_600_000L, ElapsedUnit.HOURS, 1, "1 hr", "the minute-to-hour boundary"),
        ElapsedCase(10_800_000L, ElapsedUnit.HOURS, 3, "3 hr", "three hours"),
        ElapsedCase(172_800_000L, ElapsedUnit.DAYS, 2, "2 d", "two days"),
        ElapsedCase(-5_000L, ElapsedUnit.UNDER_MINUTE, null, "<1 min", "the device clock moved backwards"),
    )

    // ------------------------------------------------------------------
    // Send: the sweep warning
    // ------------------------------------------------------------------

    const val CKB: Long = 100_000_000L

    /** One `sweepWarning(balance, amount, fee)` answer and the sentence it becomes. */
    data class SweepCase(
        val balanceShannons: Long,
        val amountShannons: Long,
        val feeShannons: Long,
        val expectsWarning: Boolean,
        val expectedRemainingShannons: Long,
        val expectedBelowMinCell: Boolean,
        /** The sentence the iOS review sheet shows; null when there is no warning. */
        val expectedMessage: String?,
        val note: String,
    )

    val sweeps: List<SweepCase> = listOf(
        SweepCase(
            balanceShannons = 100L * CKB,
            amountShannons = 95L * CKB,
            feeShannons = 100_000L,
            expectsWarning = true,
            expectedRemainingShannons = 499_900_000L,
            expectedBelowMinCell = true,
            expectedMessage = "This sends nearly your whole balance. You will have 4.999 CKB left, " +
                "which is not enough for another transaction.",
            note = "under one minimal cell left, so the wallet cannot fund another send",
        ),
        SweepCase(
            balanceShannons = 1_000_000L * CKB,
            amountShannons = 99_992_999_900_000L,
            feeShannons = 100_000L,
            expectsWarning = true,
            expectedRemainingShannons = 7_000_000_000L,
            expectedBelowMinCell = false,
            expectedMessage = "This sends nearly your whole balance. You will have 70.00 CKB left.",
            note = "70 CKB left is over a cell but under the 1% line on a large balance",
        ),
        SweepCase(
            balanceShannons = 100L * CKB,
            amountShannons = 3_899_900_000L,
            feeShannons = 100_000L,
            expectsWarning = false,
            expectedRemainingShannons = 6_100_000_000L,
            expectedBelowMinCell = false,
            expectedMessage = null,
            note = "exactly one minimal cell left is exactly enough, so nothing is said",
        ),
        SweepCase(
            balanceShannons = 1_000L * CKB,
            amountShannons = 100L * CKB,
            feeShannons = 100_000L,
            expectsWarning = false,
            expectedRemainingShannons = 89_999_900_000L,
            expectedBelowMinCell = false,
            expectedMessage = null,
            note = "an ordinary send nowhere near the threshold",
        ),
    )

    // ------------------------------------------------------------------
    // Send: what a rejection reads as
    // ------------------------------------------------------------------

    /** One `mapSendErrorMessage(raw)` answer. */
    data class SendErrorCase(val raw: String, val expected: String, val note: String)

    /**
     * One row per branch of `mapSendErrorMessage`, in the order the function
     * tries them. The order is load-bearing: several of these inputs match more
     * than one branch, and moving a branch changes which message a user reads.
     */
    val sendErrors: List<SendErrorCase> = listOf(
        SendErrorCase(
            raw = "Failed to get cells from the node",
            expected = "Could not fetch your available funds. Please ensure your wallet is synced and try again.",
            note = "the cell fetch itself failed",
        ),
        SendErrorCase(
            raw = "No cells available for this wallet",
            expected = "Not enough funds available. Please wait for your wallet to fully sync.",
            note = "nothing indexed yet",
        ),
        SendErrorCase(
            raw = "Insufficient balance for the requested amount",
            expected = "Insufficient balance for this transaction.",
            note = "matched before the cell branches could claim the word Insufficient",
        ),
        SendErrorCase(
            raw = "Output is below the minimum of 61 CKB",
            expected = "Minimum transfer amount is 61 CKB due to CKB's cell model.",
            note = "needs both the word minimum and the number 61",
        ),
        SendErrorCase(
            raw = "Dust change refused: 3.5 CKB",
            expected = "This exact amount would leave less than 61 CKB of change, which CKB cannot " +
                "store as a separate output and the protocol would silently absorb into the " +
                "transaction fee. Try sending a slightly different amount, or send your full " +
                "balance minus the fee.",
            note = "the one refusal that comes with its own remedy",
        ),
        SendErrorCase(
            raw = "Broadcast rejected: Resolve failed Unknown(OutPoint(0xabc))",
            expected = "This send depends on a previous transaction that hasn't confirmed yet. " +
                "Wait for it to confirm, or reopen the app and try again.",
            note = "an unconfirmed parent, named by the bridge",
        ),
        SendErrorCase(
            raw = "Broadcast rejected: Resolve failed Dead(OutPoint(0xabc))",
            expected = "Some of the coins for this transaction were already spent. " +
                "Reopen the app to refresh your balance, then try again.",
            note = "already-spent inputs, named by the bridge",
        ),
        SendErrorCase(
            raw = "light client not ready",
            expected = "The wallet is still starting up. Please wait a moment and try again.",
            note = "the node has not come up yet",
        ),
        SendErrorCase(
            raw = "Broadcast rejected: script verification failed",
            expected = "The network rejected this transaction. Reopen the app to refresh your wallet, then try again.",
            note = "a local verification failure, ahead of the generic rejection",
        ),
        SendErrorCase(
            raw = "Broadcast rejected: TransactionFailedToResolve",
            expected = "The network rejected this transaction. Please reopen the app and try again.",
            note = "a rejection with no cause this mapping recognises",
        ),
        SendErrorCase(
            raw = "Send failed - native returned null",
            expected = "Could not send the transaction. Please reopen the app and try again.",
            note = "the bridge answered nothing at all",
        ),
        SendErrorCase(
            raw = "Wallet is not synced yet",
            expected = "Wallet is still syncing. Please wait for sync to complete before sending.",
            note = "reached only after every broadcast branch has declined it",
        ),
        SendErrorCase(
            raw = "Malformed json in the response",
            expected = "Internal error processing transaction data. Please try again or restart the app.",
            note = "a parsing failure, which is a bug rather than a user problem",
        ),
        SendErrorCase(
            raw = "kaboom",
            expected = "Transaction failed: kaboom",
            note = "an unrecognised reason is carried through rather than swallowed",
        ),
    )

    // ------------------------------------------------------------------
    // Activity: the amount and the fee on a row
    // ------------------------------------------------------------------

    /** One `TransactionRecord.formattedAmount()` / `formattedFee()` answer. */
    data class AmountCase(
        val direction: String,
        /** `balance_change`, exactly as the ledger stores it. */
        val balanceChangeHex: String,
        val feeShannons: Long?,
        val expectedAmount: String,
        val expectedFee: String?,
        val expectedPaysNetworkFee: Boolean,
        val note: String,
    )

    val amounts: List<AmountCase> = listOf(
        AmountCase(
            direction = "in", balanceChangeHex = "0x5f5e100", feeShannons = null,
            expectedAmount = "+1.00 CKB", expectedFee = null, expectedPaysNetworkFee = false,
            note = "one whole CKB, and the sender paid the fee",
        ),
        AmountCase(
            direction = "out", balanceChangeHex = "0x2dfdc1c34", feeShannons = 100_000L,
            expectedAmount = "-123.46 CKB", expectedFee = "0.001 CKB", expectedPaysNetworkFee = true,
            note = "123.456789 CKB rounds to two decimals half away from zero",
        ),
        AmountCase(
            direction = "dao_deposit", balanceChangeHex = "0xc350", feeShannons = 1_000L,
            expectedAmount = "-0.0005 CKB", expectedFee = "0.00001 CKB", expectedPaysNetworkFee = true,
            note = "under 1 CKB and over 0.0001, so four decimals",
        ),
        AmountCase(
            direction = "dao_unlock", balanceChangeHex = "0x270f", feeShannons = 123_456L,
            expectedAmount = "+0.00009999 CKB", expectedFee = "0.00123456 CKB", expectedPaysNetworkFee = true,
            note = "under 0.0001 CKB, so all eight decimals",
        ),
        AmountCase(
            direction = "dao_unlock", balanceChangeHex = "0x16b969d00", feeShannons = null,
            expectedAmount = "+61.00 CKB", expectedFee = null, expectedPaysNetworkFee = false,
            note = "an unlock whose fee this device never recorded shows no fee row at all",
        ),
    )

    // ------------------------------------------------------------------
    // Money: formatting and parsing
    // ------------------------------------------------------------------

    /** One shannon value and the two strings the core renders it as. */
    data class FormatCase(
        val shannons: Long,
        val expectedBalance: String,
        val expectedAmount: String,
        val note: String,
    )

    val formats: List<FormatCase> = listOf(
        FormatCase(0L, "0.00", "0.00", "an empty wallet"),
        FormatCase(1L, "0.00", "0.00000001", "one shannon: rounded away on the balance, kept on the amount"),
        FormatCase(999_999_999L, "10.00", "9.99999999", "the balance rounds up where the amount does not"),
        FormatCase(6_100_000_000L, "61.00", "61.00", "one minimal cell"),
        FormatCase(1_234_560_000_000L, "12,345.60", "12,345.60", "grouped thousands"),
        FormatCase(
            2_100_000_000_000_000_000L, "21,000,000,000.00", "21,000,000,000.00",
            "the whole CKB supply, well past where a Double stops counting shannons",
        ),
    )

    /** One typed amount and the shannons it parses to, or null when it is not an amount. */
    data class ParseCase(val text: String, val expected: Long?, val note: String)

    val parses: List<ParseCase> = listOf(
        ParseCase("61", 6_100_000_000L, "a whole number of CKB"),
        ParseCase("0.00000001", 1L, "one shannon"),
        ParseCase("12.345678901", 1_234_567_890L, "a ninth decimal is truncated, never rounded up"),
        ParseCase("1234.5", 123_450_000_000L, "a single decimal"),
        ParseCase("99999999999999999999", null, "too large for a Long, so it is refused rather than wrapped"),
    )
}
