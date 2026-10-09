import PocketNodeCore
import XCTest

@testable import PocketNode

/// The Send screen's decisions, none of which need a node.
///
/// The one invariant running through all of it: `FakeSendService.sendCalls`
/// stays empty until Confirm. Everything before that is validation, pricing
/// and copy, and none of it may reach a key.
@MainActor
final class SendViewModelTests: XCTestCase {

    private var service: FakeSendService!
    private var model: SendViewModel!

    private let ckb = SendFixtures.ckb

    override func setUp() async throws {
        service = FakeSendService()
        model = SendViewModel(service: service)
    }

    override func tearDown() async throws {
        service = nil
        model = nil
    }

    // MARK: - Address indicator

    func testNoIndicatorUntilSomethingIsTyped() {
        XCTAssertNil(model.addressIndicator)

        model.updateRecipient("   ")
        XCTAssertNil(model.addressIndicator)
    }

    func testAnUnparseableAddressReadsAsInvalid() {
        model.updateRecipient("not-an-address")

        XCTAssertEqual(model.addressIndicator, .invalid)
        XCTAssertEqual(model.addressIndicator?.message, "Invalid address format")
    }

    func testAnAddressOnTheSelectedNetworkReadsAsValid() {
        model.updateRecipient(SendFixtures.testnetAddress)

        XCTAssertEqual(model.addressIndicator?.message, "Valid CKB testnet address")
        XCTAssertEqual(model.addressIndicator?.isProblem, false)
    }

    /// A warning rather than a block, which is the point: the user may have
    /// switched networks on purpose and a disabled button would not say so.
    func testAnAddressOnTheOtherNetworkWarnsWithoutBlocking() {
        model.updateRecipient(SendFixtures.mainnetAddress)

        XCTAssertEqual(
            model.addressIndicator?.message,
            "This is a mainnet address on testnet"
        )
        model.updateAmount("100")
        XCTAssertTrue(model.canSubmit)
    }

    func testTheIndicatorFollowsTheSelectedNetwork() {
        service.network = NetworkType.mainnet
        model.updateRecipient(SendFixtures.testnetAddress)

        XCTAssertEqual(
            model.addressIndicator?.message,
            "This is a testnet address on mainnet"
        )
    }

    // MARK: - Amount entry

    /// Every keystroke goes through the shared `sanitizeAmount`, so a stray
    /// letter or a second decimal point is dropped rather than rejected and
    /// the field never fights the user mid-entry.
    func testAmountEntryRunsThroughTheSharedSanitiser() {
        model.updateAmount("12.34")
        XCTAssertEqual(model.amount, "12.34")

        // A second dot is refused outright: the field keeps what it had.
        model.updateAmount("12.34.5")
        XCTAssertEqual(model.amount, "12.34")

        // Letters likewise.
        model.updateAmount("12.3x")
        XCTAssertEqual(model.amount, "12.34")

        // A ninth decimal is truncated rather than refused.
        model.updateAmount("1.123456789")
        XCTAssertEqual(model.amount, "1.12345678")
    }

    func testTheAvailableRowIsFormattedByTheSharedCore() {
        service.availableShannons = 1_234_560_000_000
        model = SendViewModel(service: service)

        XCTAssertEqual(model.availableText, "12,345.60")
    }

    func testTheFeeLineReadsZeroUntilThereIsSomethingToPrice() {
        service.availableShannons = 0
        model = SendViewModel(service: service)

        // Two outputs at the fake's rate, so it is never actually zero here;
        // the zero branch is the one a real builder hits before a first
        // estimate. Pin the format instead.
        XCTAssertTrue(model.estimatedFeeText.hasPrefix("~"))
        XCTAssertTrue(model.estimatedFeeText.hasSuffix(" CKB"))
    }

    func testTheFeeLineShowsSixDecimals() {
        model.updateAmount("100")

        XCTAssertEqual(model.estimatedFeeText, "~0.001000 CKB")
    }

    // MARK: - Dust warning

    /// The change this send would leave is below a minimal cell, so the node
    /// would absorb it into the fee. Better to say so while the user can still
    /// change the number.
    func testAnAmountThatWouldLeaveDustChangeWarnsWhileTyping() {
        service.availableShannons = 200 * ckb
        model = SendViewModel(service: service)

        model.updateAmount("150")

        let warning = try? XCTUnwrap(model.dustWarning)
        XCTAssertNotNil(warning)
        XCTAssertTrue(model.dustWarning?.hasPrefix("Heads up: this would leave ") ?? false)
        XCTAssertTrue(
            model.dustWarning?.contains(
                "below the 61 CKB minimum cell size. The send will be refused."
            ) ?? false
        )
    }

    func testAnAmountThatLeavesAUsableCellDoesNotWarn() {
        service.availableShannons = 1_000 * ckb
        model = SendViewModel(service: service)

        model.updateAmount("100")

        XCTAssertNil(model.dustWarning)
    }

    func testSweepingTheWholeBalanceDoesNotRaiseTheDustWarning() {
        service.availableShannons = 200 * ckb
        model = SendViewModel(service: service)

        // Exactly the balance: the change is negative, not dust.
        model.updateAmount("200")

        XCTAssertNil(model.dustWarning)
    }

    // MARK: - Submit validation

    func testValidationRefusesABlankRecipientFirst() async {
        await model.submit()

        XCTAssertEqual(model.alert?.message, "Please enter recipient address")
        XCTAssertTrue(service.previewCalls.isEmpty)
    }

    func testValidationRefusesABlankAmountSecond() async {
        model.updateRecipient(SendFixtures.testnetAddress)

        await model.submit()

        XCTAssertEqual(model.alert?.message, "Please enter amount")
    }

    /// Reachable only through a value the sanitiser lets through but the
    /// parser cannot read, which is the lone dot.
    func testValidationRefusesAnUnparseableAmountThird() async {
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount(".")

        await model.submit()

        XCTAssertEqual(model.alert?.message, "Invalid amount")
    }

    func testValidationRefusesBelowTheMinimumCellFourth() async {
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount("60.99999999")

        await model.submit()

        XCTAssertEqual(model.alert?.message, "Minimum transfer is 61 CKB")
    }

    func testValidationRefusesMoreThanTheBalanceFifth() async {
        service.availableShannons = 100 * ckb
        model = SendViewModel(service: service)
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount("500")

        await model.submit()

        XCTAssertEqual(model.alert?.message, "Insufficient balance")
    }

    func testValidationRefusesWithNoWalletLast() async {
        service.fromAddress = nil
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount("100")

        await model.submit()

        XCTAssertEqual(model.alert?.message, "Wallet not initialized")
        XCTAssertTrue(service.previewCalls.isEmpty)
    }

    /// Order matters: a draft with neither field filled is told about the
    /// recipient, not the amount, so the user is walked down the form rather
    /// than back up it.
    func testTheFirstFailureIsTheOneReported() async {
        model.updateAmount("1")

        await model.submit()

        XCTAssertEqual(model.alert?.message, "Please enter recipient address")
    }

    // MARK: - Review

    func testAValidDraftPricesTheSendAndOpensTheReview() async {
        service.previewFee = 123_456
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount("100")

        await model.submit()

        let review = try? XCTUnwrap(model.review)
        XCTAssertEqual(review?.amountShannons, 100 * ckb)
        XCTAssertEqual(review?.feeShannons, 123_456)
        XCTAssertEqual(review?.totalShannons, 100 * ckb + 123_456)
        // The form's guess is replaced by the plan, so both surfaces agree.
        XCTAssertEqual(model.estimatedFeeShannons, 123_456)
        // And nothing has been signed.
        XCTAssertTrue(service.sendCalls.isEmpty)
    }

    func testAPreviewFailureShowsTheMappedMessageAndNotAReview() async {
        service.previewError = SendError(
            message: "Not enough funds available. Please wait for your wallet to fully sync.",
            detail: "No cells available"
        )
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount("100")

        await model.submit()

        XCTAssertNil(model.review)
        XCTAssertEqual(
            model.alert?.message,
            "Not enough funds available. Please wait for your wallet to fully sync."
        )
    }

    func testCancellingTheReviewLeavesTheDraftIntactAndSendsNothing() async {
        await openReview(amount: "100")

        model.cancelReview()

        XCTAssertNil(model.review)
        XCTAssertEqual(model.amount, "100")
        XCTAssertTrue(service.sendCalls.isEmpty)
    }

    // MARK: - Sweep warning

    func testASendThatLeavesPlentyBehindCarriesNoSweepWarning() async {
        service.availableShannons = 10_000 * ckb
        model = SendViewModel(service: service)

        await openReview(amount: "100")

        XCTAssertNil(model.review?.sweep)
        XCTAssertTrue(model.canConfirm)
    }

    func testASweepWarnsThatTheWalletCannotFundAnotherTransaction() async {
        service.availableShannons = 200 * ckb
        service.previewFee = 1_000
        model = SendViewModel(service: service)

        await openReview(amount: "150")

        let sweep = try? XCTUnwrap(model.review?.sweep)
        XCTAssertEqual(sweep?.belowMinCell, true)
        XCTAssertEqual(
            sweep?.message,
            "This sends nearly your whole balance. You will have 49.99999 CKB left, "
                + "which is not enough for another transaction."
        )
    }

    /// The 1%-of-balance arm: 61 CKB out of 50,000 is effectively everything
    /// gone, but the wallet can still fund a transaction, so the copy softens.
    func testABigWalletSweptToOneCellGetsTheSofterWording() async {
        service.availableShannons = 50_000 * ckb
        service.previewFee = 0
        model = SendViewModel(service: service)

        await openReview(amount: "49939")

        let sweep = try? XCTUnwrap(model.review?.sweep)
        XCTAssertEqual(sweep?.belowMinCell, false)
        XCTAssertEqual(
            sweep?.message,
            "This sends nearly your whole balance. You will have 61.00 CKB left."
        )
    }

    func testConfirmIsBlockedUntilTheSweepIsAcknowledged() async {
        service.availableShannons = 200 * ckb
        service.previewFee = 1_000
        model = SendViewModel(service: service)
        await openReview(amount: "150")

        XCTAssertFalse(model.canConfirm)
        await model.confirm()
        XCTAssertTrue(service.sendCalls.isEmpty, "a sweep must not send unacknowledged")

        model.sweepAcknowledged = true
        XCTAssertTrue(model.canConfirm)
        await model.confirm()
        XCTAssertEqual(service.sendCalls.count, 1)
    }

    func testAnAcknowledgementNeverCarriesOverToTheNextDraft() async {
        service.availableShannons = 200 * ckb
        service.previewFee = 1_000
        model = SendViewModel(service: service)
        await openReview(amount: "150")
        model.sweepAcknowledged = true

        model.cancelReview()
        XCTAssertFalse(model.sweepAcknowledged)

        await openReview(amount: "150")
        XCTAssertFalse(model.sweepAcknowledged, "a new review must be acknowledged again")
    }

    // MARK: - Confirm

    func testConfirmSendsTheReviewedAmountAndFee() async {
        service.previewFee = 77_777
        await openReview(amount: "100")

        await model.confirm()

        XCTAssertEqual(service.sendCalls.count, 1)
        XCTAssertEqual(service.sendCalls.first?.to, SendFixtures.testnetAddress)
        XCTAssertEqual(service.sendCalls.first?.amount, 100 * ckb)
        // The fee the user saw is handed back so the pipeline can refuse if a
        // re-plan disagrees (#490).
        XCTAssertEqual(service.sendCalls.first?.expectedFee, 77_777)
    }

    func testAnAcceptedBroadcastClearsTheDraft() async {
        await openReview(amount: "100")

        await model.confirm()

        XCTAssertEqual(model.recipient, "")
        XCTAssertEqual(model.amount, "")
        XCTAssertNil(model.review)
    }

    /// A dismissed Face ID or PIN prompt is an answer, not a failure: the user
    /// meant to stop, and an error dialog would say they did something wrong.
    func testACancelledAuthenticationSaysNothing() async {
        service.sendResult = .failure(.cancelled)
        await openReview(amount: "100")

        await model.confirm()

        XCTAssertNil(model.alert)
        // The draft survives, so the user can try again without retyping.
        XCTAssertEqual(model.amount, "100")
    }

    func testARefusedBroadcastShowsTheMessageAndTheRawReason() async {
        service.sendResult = .failure(
            SendError(
                message: "The network rejected this transaction. Please reopen the app and try again.",
                detail: "Broadcast rejected: PoolRejectedRBF"
            )
        )
        await openReview(amount: "100")

        await model.confirm()

        XCTAssertEqual(
            model.alert?.message,
            "The network rejected this transaction. Please reopen the app and try again."
        )
        XCTAssertEqual(model.alert?.truncatedDetail, "Broadcast rejected: PoolRejectedRBF")
    }

    /// Retry re-broadcasts the original signed bytes, so it is only offered
    /// when there is a hash to re-broadcast.
    func testRetryIsOfferedOnlyWhenThereIsSomethingToReBroadcast() async {
        service.status = SendStatus(phase: .failed, message: "...", txHash: SendFixtures.txHash)
        service.sendResult = .failure(SendError(message: "The network rejected this transaction."))
        await openReview(amount: "100")

        await model.confirm()

        XCTAssertEqual(model.alert?.canRetry, true)
    }

    /// A send that failed at authentication or at the key never reached a
    /// broadcast, so a Retry button there would do nothing.
    func testAFailureBeforeTheBroadcastOffersNoRetry() async {
        service.sendResult = .failure(SendError(message: "Could not read this wallet's keys."))
        await openReview(amount: "100")

        await model.confirm()

        XCTAssertEqual(model.alert?.canRetry, false)
    }

    // MARK: - Re-entrancy

    /// Two taps in the same runloop turn each start their own Task, and the
    /// disabled button only takes effect on the next render, so the second
    /// call has to be refused inside `submit` itself. The fake re-enters at
    /// exactly the point the first call is suspended in the preview.
    func testASecondSubmitWhileTheFirstIsRunningDoesNothing() async {
        service.onPreview = { [weak self] in await self?.model.submit() }
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount("100")

        await model.submit()

        XCTAssertEqual(service.previewCalls.count, 1)
    }

    /// The same for the button that actually spends: the review is cleared
    /// before the first `confirm` suspends, so the second finds nothing to
    /// confirm and two taps produce one transaction.
    func testTwoConfirmsProduceOneSend() async {
        await openReview(amount: "100")
        service.onSend = { [weak self] in await self?.model.confirm() }

        await model.confirm()

        XCTAssertEqual(service.sendCalls.count, 1)
    }

    /// The other arm of the same guard: a broadcast already in flight blocks a
    /// fresh preview too.
    func testSubmitIsRefusedWhileABroadcastIsInFlight() async {
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount("100")
        service.status = SendStatus(phase: .sending, message: "Broadcasting transaction...")

        await model.submit()

        XCTAssertTrue(service.previewCalls.isEmpty)
    }

    // MARK: - MAX

    /// Rendered with trailing zeros trimmed and a "." point, because it goes
    /// straight back into the field the sanitiser owns.
    func testMaxFillsTheFieldWithATrimmedAmount() async {
        // A balance above what MAX answers, so the clamp is not what is being
        // measured here; `testMaxNeverExceeds...` covers that.
        service.availableShannons = 200_000 * ckb
        service.maxSendable = 12_345_600_000_000
        model = SendViewModel(service: service)

        await model.setMaxAmount()

        XCTAssertEqual(model.amount, "123456")
    }

    func testMaxKeepsTheDecimalsItActuallyHas() async {
        service.maxSendable = 6_099_999_500

        await model.setMaxAmount()

        XCTAssertEqual(model.amount, "60.999995")
    }

    /// The pipeline prices MAX over the cells a send would select, which
    /// include the change in-flight broadcasts are about to create; the
    /// balance is the on-chain read that `validate()` measures against. When
    /// the first is the larger, MAX must not fill a number the screen would
    /// then refuse.
    func testMaxNeverExceedsTheBalanceValidationWillMeasureAgainst() async {
        service.availableShannons = 100 * ckb
        service.maxSendable = 500 * ckb
        model = SendViewModel(service: service)

        await model.setMaxAmount()

        XCTAssertEqual(model.amount, "100")
        await model.submit()
        XCTAssertNotEqual(model.alert?.message, "Insufficient balance")
    }

    func testMaxIsOfferedOnlyWhenThereIsABalance() {
        XCTAssertTrue(model.isMaxEnabled)

        service.availableShannons = 0
        model = SendViewModel(service: service)
        XCTAssertFalse(model.isMaxEnabled)
    }

    // MARK: - Retry

    func testRetryReBroadcastsTheFailedHashExactlyOnce() async {
        service.status = SendStatus(phase: .failed, message: "Transaction failed", txHash: SendFixtures.txHash)

        await model.retry()

        XCTAssertEqual(service.retryCalls, [SendFixtures.txHash])
        XCTAssertNil(model.alert, "a retry that was accepted clears the failure dialog")
    }

    func testRetryDoesNothingWithoutAHash() async {
        service.status = SendStatus(phase: .failed, message: "Transaction failed")

        await model.retry()

        XCTAssertTrue(service.retryCalls.isEmpty)
    }

    // MARK: - Status sheet gating

    /// Android gates its status dialog on a hash existing, and this is why: a
    /// send that fails at authentication or at the key step has no transaction
    /// to report on, and a sheet reading "Transaction Failed" would sit on top
    /// of the alert that says which of the two it was.
    func testTheStatusSheetWaitsForAHash() {
        service.status = SendStatus(phase: .sending, message: "Building transaction...")
        XCTAssertFalse(model.showsStatusSheet)

        service.status = SendStatus(phase: .failed, message: "Transaction failed")
        XCTAssertFalse(model.showsStatusSheet)

        service.status = SendStatus(phase: .pending, message: "...", txHash: SendFixtures.txHash)
        XCTAssertTrue(model.showsStatusSheet)
    }

    /// Until a hash exists the CTA's spinner is the only feedback there is, so
    /// it has to cover the authentication and the signing too, not just the
    /// preview.
    func testTheButtonStaysBusyThroughTheWholeSend() {
        XCTAssertFalse(model.isBusy)

        service.status = SendStatus(phase: .sending, message: "Broadcasting transaction...")
        XCTAssertTrue(model.isBusy)
        XCTAssertEqual(model.busyMessage, "Broadcasting transaction...")
        XCTAssertFalse(model.canSubmit)
        XCTAssertFalse(model.isMaxEnabled)

        service.status = SendStatus(phase: .pending, message: "...", txHash: SendFixtures.txHash)
        XCTAssertFalse(model.isBusy)
    }

    // MARK: - Status dismissal

    func testDismissingAConfirmedSendPopsTheScreen() {
        service.status = SendStatus(phase: .confirmed, message: "Transaction confirmed")

        model.dismissStatus()

        XCTAssertEqual(service.dismissCalls, 1)
        XCTAssertTrue(model.isFinished)
    }

    /// A poll that ran out of time is over for the sheet: Close finishes with
    /// it, the form's "in progress" row goes, and the screen stays put (the
    /// transaction lives on in Activity, not here).
    func testATimedOutSendCanBeClosedAndLeavesNoRowBehind() {
        service.status = SendStatus(
            phase: .timedOut,
            message: SendStatusPoller.companion.TIMED_OUT,
            txHash: SendFixtures.txHash
        )
        XCTAssertTrue(model.status.isSettled)
        XCTAssertTrue(model.showsStatusSheet)

        model.dismissStatus()

        XCTAssertEqual(service.dismissCalls, 1)
        XCTAssertFalse(model.isFinished, "only a confirmed send pops the screen")
        XCTAssertFalse(model.hasWatchedTransaction)
        XCTAssertFalse(model.showsStatusSheet)
    }

    /// "Hide" puts the sheet away and nothing else. A send the user has walked
    /// away from still has to reach a terminal state, so the poll must survive
    /// it, and the sheet must be reachable again.
    func testHidingAnInFlightSendKeepsThePollAndTheStatus() {
        service.status = SendStatus(
            phase: .pending,
            message: "Transaction pending...",
            txHash: SendFixtures.txHash
        )
        XCTAssertTrue(model.showsStatusSheet)

        model.hideStatus()

        XCTAssertEqual(service.dismissCalls, 0, "Hide must not stop the poll")
        XCTAssertFalse(model.showsStatusSheet)
        XCTAssertTrue(model.hasWatchedTransaction, "the form keeps a way back to it")
        XCTAssertEqual(model.status.txHash, SendFixtures.txHash)
        XCTAssertFalse(model.isFinished)

        model.reopenStatus()
        XCTAssertTrue(model.showsStatusSheet)
    }

    /// Done and Close are the destructive pair, and only they stop the poll.
    func testFinishingWithASettledSendStopsThePoll() {
        service.status = SendStatus(
            phase: .failed,
            message: "Transaction failed",
            txHash: SendFixtures.txHash
        )

        model.dismissStatus()

        XCTAssertEqual(service.dismissCalls, 1)
        XCTAssertFalse(model.hasWatchedTransaction)
        XCTAssertFalse(model.isFinished, "only a confirmed send pops the screen")
    }

    /// A hidden transaction stays hidden while a new draft is being reviewed.
    ///
    /// Un-hiding on submit threw the old sheet up over the review sheet, and a
    /// settled one did it while its own button said "Done". The reopen row is
    /// still on the form for whoever wants it back.
    func testANewDraftDoesNotUnHideTheTransactionAlreadyBeingWatched() async {
        service.status = SendStatus(
            phase: .confirmed,
            message: "Transaction confirmed",
            txHash: SendFixtures.txHash
        )
        model.hideStatus()

        await openReview(amount: "100")

        XCTAssertFalse(model.showsStatusSheet, "the review sheet must not be covered")
        XCTAssertNotNil(model.review)
        XCTAssertTrue(model.hasWatchedTransaction, "and it is still reachable from the form")
    }

    /// Confirming does clear it: from there the poller's status belongs to the
    /// new transaction, and a carried-over Hide would swallow its lifecycle.
    func testConfirmingANewSendShowsItsOwnStatusSheet() async {
        service.status = SendStatus(phase: .confirmed, message: "...", txHash: SendFixtures.txHash)
        model.hideStatus()
        await openReview(amount: "100")

        await model.confirm()

        XCTAssertTrue(model.showsStatusSheet)
    }

    /// With nothing being watched the flag is stale, and leaving it set would
    /// swallow the next transaction's sheet entirely.
    func testAStaleHideIsClearedWhenNothingIsBeingWatched() async {
        model.hideStatus()

        await openReview(amount: "100")
        service.status = SendStatus(phase: .pending, message: "...", txHash: SendFixtures.txHash)

        XCTAssertTrue(model.showsStatusSheet)
    }

    // MARK: - Scanner

    func testAScannedAddressFillsTheFieldAndClosesTheScanner() {
        model.isScannerPresented = true

        model.acceptScanned(address: SendFixtures.testnetAddress)

        XCTAssertEqual(model.recipient, SendFixtures.testnetAddress)
        XCTAssertFalse(model.isScannerPresented)
    }

    // MARK: - Helpers

    private func openReview(amount: String) async {
        model.updateRecipient(SendFixtures.testnetAddress)
        model.updateAmount(amount)
        await model.submit()
    }
}
