import PocketNodeCore
import XCTest

@testable import PocketNode

// The Swift half of the M3 parity suite.
//
// Every value in `M3Parity` below is a literal copy of
// `android/shared/src/commonTest/kotlin/com/rjnr/pocketnode/parity/M3ParityFixtures.kt`.
// The two files change together or not at all: a row that has to be edited on
// one side only means one platform reformatted a number the other did not, and
// the two apps now disagree about money. When one of these tests fails, find
// out which side moved. Never adjust the table to match the new answer.
//
// What the assertions below add over the Kotlin half is the Swift layer: the
// same rows are checked through `SendViewModel`, `HomeViewModel` and
// `ActivityCopy`, which is what proves the app formats through the shared core
// rather than through a second copy of it.

// MARK: - The table

enum M3Parity {

    static let mainnetCheckpoint: Int64 = 18_300_000
    static let testnetCheckpoint: Int64 = 0

    struct StartBlockCase {
        let mode: SyncMode
        let network: NetworkType
        let tipHeight: Int64
        let customBlockHeight: Int64?
        let expected: String
        let note: String
    }

    static var startBlocks: [StartBlockCase] {
        [
            StartBlockCase(
                mode: .theNewWallet, network: .mainnet, tipHeight: 0, customBlockHeight: nil,
                expected: "18300000",
                note: "no tip yet, so the mainnet checkpoint stands in for it"
            ),
            StartBlockCase(
                mode: .theNewWallet, network: .mainnet, tipHeight: 18_400_000, customBlockHeight: nil,
                expected: "18400000",
                note: "a real tip beats the checkpoint"
            ),
            StartBlockCase(
                mode: .theNewWallet, network: .testnet, tipHeight: 0, customBlockHeight: nil,
                expected: "0",
                note: "testnet's checkpoint is genesis, so a missing tip is block 0"
            ),
            StartBlockCase(
                mode: .theNewWallet, network: .testnet, tipHeight: 15_000_000, customBlockHeight: nil,
                expected: "15000000",
                note: "a new wallet starts at the tip"
            ),
            StartBlockCase(
                mode: .recent, network: .mainnet, tipHeight: 0, customBlockHeight: nil,
                expected: "18100000",
                note: "checkpoint less the 200,000-block month"
            ),
            StartBlockCase(
                mode: .recent, network: .mainnet, tipHeight: 18_400_000, customBlockHeight: nil,
                expected: "18200000",
                note: "tip less the 200,000-block month"
            ),
            StartBlockCase(
                mode: .recent, network: .testnet, tipHeight: 0, customBlockHeight: nil,
                expected: "0",
                note: "a negative start floors at genesis"
            ),
            StartBlockCase(
                mode: .recent, network: .testnet, tipHeight: 100_000, customBlockHeight: nil,
                expected: "0",
                note: "a chain younger than a month floors at genesis"
            ),
            StartBlockCase(
                mode: .recent, network: .testnet, tipHeight: 15_000_000, customBlockHeight: nil,
                expected: "14800000",
                note: "tip less the 200,000-block month"
            ),
            StartBlockCase(
                mode: .fullHistory, network: .mainnet, tipHeight: 18_400_000, customBlockHeight: nil,
                expected: "0",
                note: "all history is genesis whatever the tip says"
            ),
            StartBlockCase(
                mode: .fullHistory, network: .testnet, tipHeight: 0, customBlockHeight: nil,
                expected: "0",
                note: "all history is genesis on testnet too"
            ),
            StartBlockCase(
                mode: .custom, network: .mainnet, tipHeight: 18_400_000, customBlockHeight: 12_000_000,
                expected: "12000000",
                note: "the typed height, verbatim"
            ),
            StartBlockCase(
                mode: .custom, network: .testnet, tipHeight: 15_000_000, customBlockHeight: 15_500_000,
                expected: "15500000",
                note: "past the tip here; the clamp table below is what catches it"
            ),
            StartBlockCase(
                mode: .custom, network: .testnet, tipHeight: 15_000_000, customBlockHeight: nil,
                expected: "0",
                note: "custom with nothing typed is block 0 before the clamp"
            ),
        ]
    }

    struct ClampCase {
        let calculated: Int64
        let mode: SyncMode
        let tipHeight: Int64
        let network: NetworkType
        let expected: Int64
        let note: String
    }

    static var clamps: [ClampCase] {
        [
            ClampCase(
                calculated: 15_500_000, mode: .custom, tipHeight: 15_000_000, network: .testnet,
                expected: 14_800_000,
                note: "a height past the tip falls back to the last 200,000 blocks"
            ),
            ClampCase(
                calculated: 500_000, mode: .custom, tipHeight: 100_000, network: .testnet,
                expected: 0,
                note: "the same fallback floors at genesis on a young chain"
            ),
            ClampCase(
                calculated: 0, mode: .custom, tipHeight: 18_400_000, network: .mainnet,
                expected: 18_300_000,
                note: "zero on a mode that did not ask for genesis means the checkpoint"
            ),
            ClampCase(
                calculated: 0, mode: .fullHistory, tipHeight: 18_400_000, network: .mainnet,
                expected: 0,
                note: "all history asked for genesis, so genesis it is"
            ),
            ClampCase(
                calculated: 0, mode: .custom, tipHeight: 15_000_000, network: .testnet,
                expected: 0,
                note: "testnet has no checkpoint to fall back to"
            ),
            ClampCase(
                calculated: 14_800_000, mode: .recent, tipHeight: 15_000_000, network: .testnet,
                expected: 14_800_000,
                note: "a height inside the chain is left alone"
            ),
        ]
    }

    static let activeScriptArgs = "0xda648442dbb7347e467d1d09da13e5cd3a0ef0e1"
    static let otherScriptArgs = "0x0000000000000000000000000000000000000001"

    struct SyncSnapshotCase {
        let tipNumber: Int64
        let scriptBlockHex: String
        let expectedScriptBlockNumber: Int64
        let expectedIsSynced: Bool
        let expectedProgress: Double
        let note: String
    }

    static let syncSnapshots: [SyncSnapshotCase] = [
        SyncSnapshotCase(
            tipNumber: 1_000, scriptBlockHex: "0x3e2",
            expectedScriptBlockNumber: 994, expectedIsSynced: true, expectedProgress: 0,
            note: "six blocks behind, inside the window"
        ),
        SyncSnapshotCase(
            tipNumber: 1_000, scriptBlockHex: "0x3de",
            expectedScriptBlockNumber: 990, expectedIsSynced: true, expectedProgress: 0,
            note: "exactly ten behind, the edge that still counts as synced"
        ),
        SyncSnapshotCase(
            tipNumber: 1_000, scriptBlockHex: "0x3dd",
            expectedScriptBlockNumber: 989, expectedIsSynced: false, expectedProgress: 0,
            note: "eleven behind, outside the window"
        ),
    ]

    struct DisplayStateCase {
        let recordStatus: String
        let confirmations: Int32
        let broadcastState: String?
        let expected: TxDisplayState
        let note: String
    }

    static var displayStates: [DisplayStateCase] {
        [
            DisplayStateCase(
                recordStatus: "PENDING", confirmations: 0, broadcastState: nil,
                expected: .pending,
                note: "in the pool with no broadcast row left"
            ),
            DisplayStateCase(
                recordStatus: "PENDING", confirmations: 0, broadcastState: "BROADCASTING",
                expected: .broadcasting,
                note: "the only thing that tells sending from waiting"
            ),
            DisplayStateCase(
                recordStatus: "PENDING", confirmations: 0, broadcastState: "BROADCAST",
                expected: .pending,
                note: "handed over and acknowledged, which is indistinguishable from waiting"
            ),
            DisplayStateCase(
                recordStatus: "PENDING", confirmations: 3, broadcastState: "BROADCASTING",
                expected: .confirmed,
                note: "a confirmation beats a stale broadcast row"
            ),
            DisplayStateCase(
                recordStatus: "CONFIRMED", confirmations: 0, broadcastState: nil,
                expected: .confirmed,
                note: "the ledger status alone is enough"
            ),
            DisplayStateCase(
                recordStatus: "PENDING", confirmations: 5, broadcastState: "FAILED",
                expected: .failed,
                note: "a terminal failure on either table wins, confirmations included"
            ),
        ]
    }

    struct ElapsedCase {
        let elapsedMillis: Int64
        let expectedUnit: ElapsedUnit
        let expectedValue: Int?
        let expectedText: String
        let note: String
    }

    static var elapsed: [ElapsedCase] {
        [
            ElapsedCase(elapsedMillis: 0, expectedUnit: .underMinute, expectedValue: nil, expectedText: "<1 min", note: "just sent"),
            ElapsedCase(elapsedMillis: 59_999, expectedUnit: .underMinute, expectedValue: nil, expectedText: "<1 min", note: "one millisecond short of a minute"),
            ElapsedCase(elapsedMillis: 120_000, expectedUnit: .minutes, expectedValue: 2, expectedText: "2 min", note: "two minutes"),
            ElapsedCase(elapsedMillis: 3_600_000, expectedUnit: .hours, expectedValue: 1, expectedText: "1 hr", note: "the minute-to-hour boundary"),
            ElapsedCase(elapsedMillis: 10_800_000, expectedUnit: .hours, expectedValue: 3, expectedText: "3 hr", note: "three hours"),
            ElapsedCase(elapsedMillis: 172_800_000, expectedUnit: .days, expectedValue: 2, expectedText: "2 d", note: "two days"),
            ElapsedCase(elapsedMillis: -5_000, expectedUnit: .underMinute, expectedValue: nil, expectedText: "<1 min", note: "the device clock moved backwards"),
        ]
    }

    static let ckb: Int64 = 100_000_000

    struct SweepCase {
        let balanceShannons: Int64
        let amountShannons: Int64
        let feeShannons: Int64
        let expectsWarning: Bool
        let expectedRemainingShannons: Int64
        let expectedBelowMinCell: Bool
        let expectedMessage: String?
        let note: String
    }

    static let sweeps: [SweepCase] = [
        SweepCase(
            balanceShannons: 100 * ckb,
            amountShannons: 95 * ckb,
            feeShannons: 100_000,
            expectsWarning: true,
            expectedRemainingShannons: 499_900_000,
            expectedBelowMinCell: true,
            expectedMessage: "This sends nearly your whole balance. You will have 4.999 CKB left, "
                + "which is not enough for another transaction.",
            note: "under one minimal cell left, so the wallet cannot fund another send"
        ),
        SweepCase(
            balanceShannons: 1_000_000 * ckb,
            amountShannons: 99_992_999_900_000,
            feeShannons: 100_000,
            expectsWarning: true,
            expectedRemainingShannons: 7_000_000_000,
            expectedBelowMinCell: false,
            expectedMessage: "This sends nearly your whole balance. You will have 70.00 CKB left.",
            note: "70 CKB left is over a cell but under the 1% line on a large balance"
        ),
        SweepCase(
            balanceShannons: 100 * ckb,
            amountShannons: 3_899_900_000,
            feeShannons: 100_000,
            expectsWarning: false,
            expectedRemainingShannons: 6_100_000_000,
            expectedBelowMinCell: false,
            expectedMessage: nil,
            note: "exactly one minimal cell left is exactly enough, so nothing is said"
        ),
        SweepCase(
            balanceShannons: 1_000 * ckb,
            amountShannons: 100 * ckb,
            feeShannons: 100_000,
            expectsWarning: false,
            expectedRemainingShannons: 89_999_900_000,
            expectedBelowMinCell: false,
            expectedMessage: nil,
            note: "an ordinary send nowhere near the threshold"
        ),
    ]

    struct SendErrorCase {
        let raw: String
        let expected: String
        let note: String
    }

    static let sendErrors: [SendErrorCase] = [
        SendErrorCase(
            raw: "Failed to get cells from the node",
            expected: "Could not fetch your available funds. Please ensure your wallet is synced and try again.",
            note: "the cell fetch itself failed"
        ),
        SendErrorCase(
            raw: "No cells available for this wallet",
            expected: "Not enough funds available. Please wait for your wallet to fully sync.",
            note: "nothing indexed yet"
        ),
        SendErrorCase(
            raw: "Insufficient balance for the requested amount",
            expected: "Insufficient balance for this transaction.",
            note: "matched before the cell branches could claim the word Insufficient"
        ),
        SendErrorCase(
            raw: "Output is below the minimum of 61 CKB",
            expected: "Minimum transfer amount is 61 CKB due to CKB's cell model.",
            note: "needs both the word minimum and the number 61"
        ),
        SendErrorCase(
            raw: "Dust change refused: 3.5 CKB",
            expected: "This exact amount would leave less than 61 CKB of change, which CKB cannot "
                + "store as a separate output and the protocol would silently absorb into the "
                + "transaction fee. Try sending a slightly different amount, or send your full "
                + "balance minus the fee.",
            note: "the one refusal that comes with its own remedy"
        ),
        SendErrorCase(
            raw: "Broadcast rejected: Resolve failed Unknown(OutPoint(0xabc))",
            expected: "This send depends on a previous transaction that hasn't confirmed yet. "
                + "Wait for it to confirm, or reopen the app and try again.",
            note: "an unconfirmed parent, named by the bridge"
        ),
        SendErrorCase(
            raw: "Broadcast rejected: Resolve failed Dead(OutPoint(0xabc))",
            expected: "Some of the coins for this transaction were already spent. "
                + "Reopen the app to refresh your balance, then try again.",
            note: "already-spent inputs, named by the bridge"
        ),
        SendErrorCase(
            raw: "light client not ready",
            expected: "The wallet is still starting up. Please wait a moment and try again.",
            note: "the node has not come up yet"
        ),
        SendErrorCase(
            raw: "Broadcast rejected: script verification failed",
            expected: "The network rejected this transaction. Reopen the app to refresh your wallet, then try again.",
            note: "a local verification failure, ahead of the generic rejection"
        ),
        SendErrorCase(
            raw: "Broadcast rejected: TransactionFailedToResolve",
            expected: "The network rejected this transaction. Please reopen the app and try again.",
            note: "a rejection with no cause this mapping recognises"
        ),
        SendErrorCase(
            raw: "Send failed - native returned null",
            expected: "Could not send the transaction. Please reopen the app and try again.",
            note: "the bridge answered nothing at all"
        ),
        SendErrorCase(
            raw: "Wallet is not synced yet",
            expected: "Wallet is still syncing. Please wait for sync to complete before sending.",
            note: "reached only after every broadcast branch has declined it"
        ),
        SendErrorCase(
            raw: "Malformed json in the response",
            expected: "Internal error processing transaction data. Please try again or restart the app.",
            note: "a parsing failure, which is a bug rather than a user problem"
        ),
        SendErrorCase(
            raw: "kaboom",
            expected: "Transaction failed: kaboom",
            note: "an unrecognised reason is carried through rather than swallowed"
        ),
    ]

    struct AmountCase {
        let direction: String
        let balanceChangeHex: String
        let feeShannons: Int64?
        let expectedAmount: String
        let expectedFee: String?
        let expectedPaysNetworkFee: Bool
        let note: String
    }

    static let amounts: [AmountCase] = [
        AmountCase(
            direction: "in", balanceChangeHex: "0x5f5e100", feeShannons: nil,
            expectedAmount: "+1.00 CKB", expectedFee: nil, expectedPaysNetworkFee: false,
            note: "one whole CKB, and the sender paid the fee"
        ),
        AmountCase(
            direction: "out", balanceChangeHex: "0x2dfdc1c34", feeShannons: 100_000,
            expectedAmount: "-123.46 CKB", expectedFee: "0.001 CKB", expectedPaysNetworkFee: true,
            note: "123.456789 CKB rounds to two decimals half away from zero"
        ),
        AmountCase(
            direction: "dao_deposit", balanceChangeHex: "0xc350", feeShannons: 1_000,
            expectedAmount: "-0.0005 CKB", expectedFee: "0.00001 CKB", expectedPaysNetworkFee: true,
            note: "under 1 CKB and over 0.0001, so four decimals"
        ),
        AmountCase(
            direction: "dao_unlock", balanceChangeHex: "0x270f", feeShannons: 123_456,
            expectedAmount: "+0.00009999 CKB", expectedFee: "0.00123456 CKB", expectedPaysNetworkFee: true,
            note: "under 0.0001 CKB, so all eight decimals"
        ),
        AmountCase(
            direction: "dao_unlock", balanceChangeHex: "0x16b969d00", feeShannons: nil,
            expectedAmount: "+61.00 CKB", expectedFee: nil, expectedPaysNetworkFee: false,
            note: "an unlock whose fee this device never recorded shows no fee row at all"
        ),
    ]

    struct FormatCase {
        let shannons: Int64
        let expectedBalance: String
        let expectedAmount: String
        let note: String
    }

    static let formats: [FormatCase] = [
        FormatCase(shannons: 0, expectedBalance: "0.00", expectedAmount: "0.00", note: "an empty wallet"),
        FormatCase(
            shannons: 1, expectedBalance: "0.00", expectedAmount: "0.00000001",
            note: "one shannon: rounded away on the balance, kept on the amount"
        ),
        FormatCase(
            shannons: 999_999_999, expectedBalance: "10.00", expectedAmount: "9.99999999",
            note: "the balance rounds up where the amount does not"
        ),
        FormatCase(shannons: 6_100_000_000, expectedBalance: "61.00", expectedAmount: "61.00", note: "one minimal cell"),
        FormatCase(
            shannons: 1_234_560_000_000, expectedBalance: "12,345.60", expectedAmount: "12,345.60",
            note: "grouped thousands"
        ),
        FormatCase(
            shannons: 2_100_000_000_000_000_000,
            expectedBalance: "21,000,000,000.00", expectedAmount: "21,000,000,000.00",
            note: "the whole CKB supply, well past where a Double stops counting shannons"
        ),
    ]

    struct ParseCase {
        let text: String
        let expected: Int64?
        let note: String
    }

    static let parses: [ParseCase] = [
        ParseCase(text: "61", expected: 6_100_000_000, note: "a whole number of CKB"),
        ParseCase(text: "0.00000001", expected: 1, note: "one shannon"),
        ParseCase(text: "12.345678901", expected: 1_234_567_890, note: "a ninth decimal is truncated, never rounded up"),
        ParseCase(text: "1234.5", expected: 123_450_000_000, note: "a single decimal"),
        ParseCase(text: "99999999999999999999", expected: nil, note: "too large for a Long, so it is refused rather than wrapped"),
    ]
}

// MARK: - The assertions

@MainActor
final class M3ParityTests: XCTestCase {

    private let suiteName = "com.rjnr.pocketnode.tests.parity.prefs"

    private var directory: URL!
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!
    private var walletStore: WalletStore!

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.parity-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        preferences = UserDefaultsPreferences(defaults: defaults)
    }

    override func tearDown() async throws {
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: - Sync start block

    func testTheCheckpointsTheTableIsPinnedTo() {
        XCTAssertEqual(getCheckpoint(network: .mainnet), M3Parity.mainnetCheckpoint)
        XCTAssertEqual(getCheckpoint(network: .testnet), M3Parity.testnetCheckpoint)
    }

    func testEveryRecordedStartBlockIsWhatTheSharedCoreAnswers() {
        for row in M3Parity.startBlocks {
            let height = row.customBlockHeight.map { KotlinLong(longLong: $0) }
            XCTAssertEqual(
                row.mode.toFromBlock(customBlockHeight: height, tipHeight: row.tipHeight, network: row.network),
                row.expected,
                "\(row.mode.name) on \(row.network.name) at tip \(row.tipHeight): \(row.note)"
            )
        }
    }

    func testEveryRecordedClampIsWhatRegistrationWouldApply() {
        for row in M3Parity.clamps {
            XCTAssertEqual(
                clampStartBlock(
                    calculated: row.calculated,
                    mode: row.mode,
                    tipHeight: row.tipHeight,
                    network: row.network
                ),
                row.expected,
                "\(row.calculated) for \(row.mode.name) at tip \(row.tipHeight): \(row.note)"
            )
        }
    }

    // MARK: - Sync snapshot

    /// The same `SyncEngine.computeStatus` Home's card is drawn from, and then
    /// the `SyncStatus` the view actually reads, so the Swift struct is shown
    /// not to re-derive either number.
    func testEveryRecordedSnapshotIsWhatComputeStatusDerives() {
        for row in M3Parity.syncSnapshots {
            // A fresh engine per row: the percentage comes off the poller's
            // sample window, and sharing one would carry a row's samples into
            // the next.
            let engine = SyncEngine(
                lightClient: UniffiLightClientApi(),
                syncPreferences: preferences,
                json: SharedRuntime.shared.json,
                logger: NSLogLogger(),
                clock: SystemClock.shared,
                queryContext: SharedRuntime.shared.defaultContext
            )
            let state = ChainSyncState(
                tipNumber: row.tipNumber,
                scripts: [
                    Self.script(args: M3Parity.otherScriptArgs, blockNumberHex: "0x1"),
                    Self.script(args: M3Parity.activeScriptArgs, blockNumberHex: row.scriptBlockHex),
                ]
            )

            let snapshot = engine.computeStatus(state: state, activeScriptArgs: M3Parity.activeScriptArgs)

            XCTAssertEqual(snapshot.scriptBlockNumber, row.expectedScriptBlockNumber, row.note)
            XCTAssertEqual(snapshot.isSynced, row.expectedIsSynced, row.note)
            XCTAssertEqual(snapshot.progress, row.expectedProgress, accuracy: 1e-9, row.note)

            let status = SyncStatus(
                isSyncing: !snapshot.isSynced,
                syncedToBlock: snapshot.scriptBlockNumber,
                tipBlockNumber: row.tipNumber,
                percentage: snapshot.progress * 100
            )
            XCTAssertEqual(status.syncedToBlock, row.expectedScriptBlockNumber, row.note)
            XCTAssertEqual(status.tipBlockNumber, row.tipNumber, row.note)
            XCTAssertEqual(status.fraction, row.expectedProgress, accuracy: 1e-9, row.note)
        }
    }

    // MARK: - Activity

    func testEveryRecordedDisplayStateIsWhatTheSharedCoreResolves() {
        for row in M3Parity.displayStates {
            let record = ActivityFixtures.record(
                direction: "out",
                confirmations: row.confirmations,
                status: row.recordStatus
            )
            let broadcast = row.broadcastState.map { ActivityFixtures.broadcast(state: $0) }

            XCTAssertEqual(displayStateOf(record: record, broadcast: broadcast), row.expected, row.note)
        }
    }

    /// The bucket from the shared core, and the words `TransactionStatusChip`
    /// and the detail sheet render it as.
    func testEveryRecordedElapsedReadingBucketsAndReadsTheSame() {
        for row in M3Parity.elapsed {
            let bucket = elapsedBucket(elapsedMillis: row.elapsedMillis)

            let value: Int? = bucket.value.map { Int($0.intValue) }

            XCTAssertEqual(bucket.unit, row.expectedUnit, row.note)
            XCTAssertEqual(value, row.expectedValue, row.note)
            XCTAssertEqual(ActivityCopy.elapsedText(bucket), row.expectedText, row.note)
        }
    }

    /// The amount and the fee exactly as `ActivityRow` and
    /// `TransactionDetailSheet` draw them: both call these Kotlin methods
    /// directly rather than formatting anything in Swift.
    func testEveryRecordedAmountAndFeeIsWhatTheRowDraws() {
        for row in M3Parity.amounts {
            let record = ActivityFixtures.record(
                balanceChange: row.balanceChangeHex,
                direction: row.direction,
                feeShannons: row.feeShannons
            )

            XCTAssertEqual(record.formattedAmount(), row.expectedAmount, row.note)
            XCTAssertEqual(record.formattedFee(), row.expectedFee, row.note)
            XCTAssertEqual(record.paysNetworkFee(), row.expectedPaysNetworkFee, row.note)
        }
    }

    // MARK: - Send

    func testEveryRecordedSweepIsWhatTheSharedCoreDecides() {
        for row in M3Parity.sweeps {
            let warning = sweepWarning(
                balanceShannons: row.balanceShannons,
                amountShannons: row.amountShannons,
                feeShannons: row.feeShannons
            )
            guard row.expectsWarning else {
                XCTAssertNil(warning, row.note)
                continue
            }
            guard let warning else {
                XCTFail("expected a warning: \(row.note)")
                continue
            }
            XCTAssertEqual(warning.remainingShannons, row.expectedRemainingShannons, row.note)
            XCTAssertEqual(warning.belowMinCell, row.expectedBelowMinCell, row.note)
            XCTAssertEqual(SweepNotice(warning).message, row.expectedMessage, row.note)
        }
    }

    /// The same rows driven through the screen: type the amount, tap Send, and
    /// read the sweep notice off the review the sheet would show.
    ///
    /// The third row is skipped rather than special-cased: 38.99 CKB is below
    /// the 61 CKB minimum, so the form refuses it before a review exists. Its
    /// answer is still covered by the direct assertion above.
    func testTheRecordedSweepsSurviveTheSendScreen() async {
        for row in M3Parity.sweeps where row.amountShannons >= SendCopyKt.MIN_CELL_SHANNONS {
            let service = FakeSendService()
            service.availableShannons = row.balanceShannons
            service.previewFee = row.feeShannons
            let model = SendViewModel(service: service)

            model.updateRecipient(SendFixtures.testnetAddress)
            model.updateAmount(formatCkbTrimmed(shannons: row.amountShannons))
            await model.submit()

            guard let review = model.review else {
                XCTFail("no review for \(row.note): \(model.alert?.message ?? "no alert")")
                continue
            }
            XCTAssertEqual(review.amountShannons, row.amountShannons, row.note)
            XCTAssertEqual(review.sweep?.message, row.expectedMessage, row.note)
        }
    }

    func testEveryRecordedRejectionReadsAsTheMappingSays() {
        for row in M3Parity.sendErrors {
            XCTAssertEqual(mapSendErrorMessage(message: row.raw), row.expected, "\(row.raw): \(row.note)")
        }
    }

    // MARK: - Money

    func testEveryRecordedValueFormatsAsABalanceAndAsAnAmount() {
        for row in M3Parity.formats {
            XCTAssertEqual(
                formatCkbBalance(shannons: row.shannons, groupSeparator: ",", decimalSeparator: "."),
                row.expectedBalance,
                row.note
            )
            XCTAssertEqual(
                formatCkbAmount(shannons: row.shannons, groupSeparator: ",", decimalSeparator: "."),
                row.expectedAmount,
                row.note
            )
        }
    }

    /// The balance rows again, through the two screens that show them: Home's
    /// wallet card and the Send form's Available row.
    func testTheRecordedBalancesReachBothScreensUnchanged() throws {
        try walletStore.save(
            WalletRecord(
                id: "parity-wallet",
                name: "Parity Wallet",
                type: WalletCreator.typeMnemonic,
                derivationPath: WalletCreator.derivationPath,
                mainnetAddress: SendFixtures.mainnetAddress,
                testnetAddress: SendFixtures.testnetAddress,
                mnemonicBackedUp: true,
                createdAt: 0
            )
        )

        for row in M3Parity.formats {
            let sync = FakeSyncStatusProvider(
                balance: BalanceStatus(shannons: row.shannons, isCached: false, hasValue: true)
            )
            let home = HomeViewModel(walletStore: walletStore, preferences: preferences, sync: sync)
            XCTAssertEqual(home.balanceText, "\(row.expectedBalance) CKB", row.note)

            let service = FakeSendService()
            service.availableShannons = row.shannons
            XCTAssertEqual(SendViewModel(service: service).availableText, row.expectedBalance, row.note)
        }
    }

    func testEveryRecordedTypedAmountParsesToTheRecordedShannons() {
        for row in M3Parity.parses {
            XCTAssertEqual(
                ckbToShannons(text: row.text)?.int64Value,
                row.expected,
                "\(row.text): \(row.note)"
            )
        }
    }

    // MARK: - Builders

    private static func script(args: String, blockNumberHex: String) -> JniScriptStatus {
        JniScriptStatus(
            script: Script(
                codeHash: Script.companion.SECP256K1_CODE_HASH,
                hashType: "type",
                args: args
            ),
            scriptType: "lock",
            blockNumber: blockNumberHex
        )
    }
}
