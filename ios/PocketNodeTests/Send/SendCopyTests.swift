import PocketNodeCore
import XCTest

@testable import PocketNode

/// The copy the Send screens draw, and where it comes from.
///
/// The error messages and the sweep rule are the Kotlin core's, reached
/// through SKIE: these tests exist to prove that the Swift side is using that
/// mapping rather than a second copy of it, which is the whole point of
/// putting it in `commonMain`. The exhaustive branch coverage lives in
/// `SendCopyTest.kt`.
final class SendCopyTests: XCTestCase {

    private let ckb: Int64 = 100_000_000

    // MARK: - Error mapping

    func testTheSharedMappingNamesTheRealCauseOfARejection() {
        XCTAssertEqual(
            mapSendErrorMessage(message: "Broadcast rejected: Resolve failed Unknown(OutPoint(0xabc))"),
            "This send depends on a previous transaction that hasn't confirmed yet. "
                + "Wait for it to confirm, or reopen the app and try again."
        )
        XCTAssertEqual(
            mapSendErrorMessage(message: "Broadcast rejected: Resolve failed Dead(OutPoint(0xabc))"),
            "Some of the coins for this transaction were already spent. "
                + "Reopen the app to refresh your balance, then try again."
        )
    }

    /// The rejection-reason path end to end at the copy level: the bridge now hands the
    /// pipeline a real reason, the pipeline raises it as "Broadcast rejected",
    /// and this is what the user reads instead of "native returned null".
    func testABareNullAndARealReasonReadDifferently() {
        XCTAssertEqual(
            mapSendErrorMessage(message: "Send failed - native returned null"),
            "Could not send the transaction. Please reopen the app and try again."
        )
        XCTAssertEqual(
            mapSendErrorMessage(message: "Broadcast rejected: script verification failed"),
            "The network rejected this transaction. Reopen the app to refresh your wallet, then try again."
        )
    }

    func testAnUnrecognisedReasonIsCarriedThroughRatherThanSwallowed() {
        XCTAssertEqual(mapSendErrorMessage(message: "kaboom"), "Transaction failed: kaboom")
    }

    // MARK: - Alert presentation

    func testTheRawReasonIsCappedBeforeItReachesTheDialog() {
        let long = String(repeating: "x", count: 500)
        let alert = SendAlert(message: "Transaction failed: x", detail: long, canRetry: false)

        XCTAssertEqual(alert.truncatedDetail?.count, SendAlert.detailLimit)
    }

    /// A detail that only repeats the message adds nothing and is dropped, the
    /// same rule the Android dialog applies.
    func testADetailThatRepeatsTheMessageIsNotShownTwice() {
        let alert = SendAlert(message: "Insufficient balance", detail: "Insufficient balance", canRetry: false)

        XCTAssertNil(alert.truncatedDetail)
    }

    func testTheTipsFollowTheMessageTheUserIsLookingAt() {
        XCTAssertEqual(
            SendAlert(message: "Insufficient balance", detail: nil, canRetry: false).tips,
            ["Make sure you have enough CKB to cover the amount plus network fees."]
        )
        XCTAssertEqual(
            SendAlert(message: "Minimum transfer is 61 CKB", detail: nil, canRetry: false).tips,
            ["CKB requires a minimum of 61 CKB per transaction output."]
        )
        XCTAssertTrue(
            SendAlert(message: "Transaction failed: kaboom", detail: nil, canRetry: false).tips.isEmpty
        )
    }

    // MARK: - Sweep rule

    func testTheSweepRuleComesFromTheSharedCore() {
        // 1% of the balance, which is above one minimal cell here.
        XCTAssertEqual(sweepThreshold(balanceShannons: 10_000 * ckb), 100 * ckb)
        // The floor, for a wallet where 1% is smaller.
        XCTAssertEqual(sweepThreshold(balanceShannons: 100 * ckb), 61 * ckb)

        XCTAssertNil(
            sweepWarning(balanceShannons: 10_000 * ckb, amountShannons: 100 * ckb, feeShannons: 1_000)
        )
        let warning = sweepWarning(
            balanceShannons: 200 * ckb,
            amountShannons: 150 * ckb,
            feeShannons: 1_000
        )
        XCTAssertEqual(warning?.remainingShannons, 50 * ckb - 1_000)
        XCTAssertEqual(warning?.belowMinCell, true)
    }

    /// A zero balance means "not read yet" far more often than "empty", so it
    /// must not fire the warning on every send made before the first tick.
    func testAnUnreadBalanceRaisesNoSweepWarning() {
        XCTAssertNil(sweepWarning(balanceShannons: 0, amountShannons: 61 * ckb, feeShannons: 0))
    }

    // MARK: - Status sheet copy

    func testTheStatusSheetTitlesMatchAndroidsExactly() {
        XCTAssertEqual(SendStatusSheet.title(for: .sending), "Sending...")
        XCTAssertEqual(SendStatusSheet.title(for: .pending), "Waiting for Confirmation")
        XCTAssertEqual(SendStatusSheet.title(for: .proposed), "Processing...")
        XCTAssertEqual(SendStatusSheet.title(for: .confirmed), "Transaction Confirmed!")
        XCTAssertEqual(SendStatusSheet.title(for: .failed), "Transaction Failed")
        XCTAssertEqual(SendStatusSheet.title(for: .idle), "Transaction Submitted")
        // iOS only: Android's own loop has no timed-out state.
        XCTAssertEqual(SendStatusSheet.title(for: .timedOut), "Not Confirmed Yet")
    }

    /// Only "Hide" leaves anything running, which is why the other two are
    /// worded as endings.
    func testTheDismissButtonSaysWhatItDoes() {
        XCTAssertEqual(SendStatusSheet.dismissLabel(for: .confirmed), "Done")
        XCTAssertEqual(SendStatusSheet.dismissLabel(for: .failed), "Close")
        XCTAssertEqual(SendStatusSheet.dismissLabel(for: .timedOut), "Close")
        XCTAssertEqual(SendStatusSheet.dismissLabel(for: .pending), "Hide")
        XCTAssertEqual(SendStatusSheet.dismissLabel(for: .sending), "Hide")
    }

    /// The sheet is swipe-dismissible only once there is an outcome.
    func testOnlyASettledSendCanBeDismissed() {
        XCTAssertFalse(SendStatus(phase: .sending).isSettled)
        XCTAssertFalse(SendStatus(phase: .pending).isSettled)
        XCTAssertFalse(SendStatus(phase: .proposed).isSettled)
        XCTAssertTrue(SendStatus(phase: .confirmed).isSettled)
        XCTAssertTrue(SendStatus(phase: .failed).isSettled)
        // The poll gave up: nothing is watching it here any more, so the
        // sheet must not be stuck on Hide over a poll that has stopped.
        XCTAssertTrue(SendStatus(phase: .timedOut).isSettled)
    }

    // MARK: - The Kotlin bridge

    func testTheSwiftPhaseMirrorsEveryKotlinState() {
        XCTAssertEqual(SendPhase(SendState.idle), .idle)
        XCTAssertEqual(SendPhase(SendState.sending), .sending)
        XCTAssertEqual(SendPhase(SendState.pending), .pending)
        XCTAssertEqual(SendPhase(SendState.proposed), .proposed)
        XCTAssertEqual(SendPhase(SendState.confirmed), .confirmed)
        XCTAssertEqual(SendPhase(SendState.failed), .failed)
        XCTAssertEqual(SendPhase(SendState.timedOut), .timedOut)
    }

    func testAKotlinProgressBecomesASwiftStatus() {
        let progress = SendProgress(
            state: SendState.pending,
            statusMessage: SendStatusPoller.companion.SUBMITTED,
            confirmations: 0,
            txHash: SendFixtures.txHash
        )

        let status = SendStatus(progress)

        XCTAssertEqual(status.phase, .pending)
        XCTAssertEqual(status.message, "Transaction submitted. Waiting for confirmation...")
        XCTAssertEqual(status.txHash, SendFixtures.txHash)
        XCTAssertTrue(status.isActive)
    }

    // MARK: - Review sheet

    func testTheReviewTruncatesTheRecipientTheWayTheActivityListDoes() {
        XCTAssertEqual(
            SendReviewSheet.truncate(SendFixtures.testnetAddress),
            "\(SendFixtures.testnetAddress.prefix(10))...\(SendFixtures.testnetAddress.suffix(6))"
        )
        XCTAssertEqual(SendReviewSheet.truncate("ckt1short"), "ckt1short")
    }
}
