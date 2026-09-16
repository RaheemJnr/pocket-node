import Foundation
import PocketNodeCore

/// What the inline line under the recipient field says.
///
/// Recomputed on every keystroke, like Android's `AddressValidationIndicator`.
/// The wrong-network case is a warning and not a block: a user who has
/// deliberately switched networks mid-paste is better served by being told
/// than by a disabled button with no explanation.
enum AddressIndicator: Equatable {
    case invalid
    case wrongNetwork(scanned: String, selected: String)
    case valid(network: String)

    var message: String {
        switch self {
        case .invalid:
            return "Invalid address format"
        case let .wrongNetwork(scanned, selected):
            return "This is a \(scanned) address on \(selected)"
        case let .valid(network):
            return "Valid CKB \(network) address"
        }
    }

    var isProblem: Bool {
        if case .valid = self { return false }
        return true
    }
}

/// A send the user is about to confirm.
///
/// A snapshot rather than a set of flags on the view model, for the reason
/// Android's `SendReview` is one: the sheet can then never disagree with the
/// amount that is about to be signed, because editing the form is only
/// reachable after Cancel has cleared it.
struct SendReview: Equatable {
    let recipient: String
    let amountShannons: Int64
    let feeShannons: Int64
    /// Non-nil when this send would leave the wallet with next to nothing.
    let sweep: SweepNotice?

    var totalShannons: Int64 { amountShannons + feeShannons }
}

/// The leave-something-behind warning, already worded.
struct SweepNotice: Equatable {
    let remainingShannons: Int64
    let belowMinCell: Bool

    var message: String {
        let remaining = formatCkbAmount(shannons: remainingShannons, groupSeparator: ",", decimalSeparator: ".")
        if belowMinCell {
            return "This sends nearly your whole balance. You will have \(remaining) CKB left, "
                + "which is not enough for another transaction."
        }
        return "This sends nearly your whole balance. You will have \(remaining) CKB left."
    }

    init(remainingShannons: Int64, belowMinCell: Bool) {
        self.remainingShannons = remainingShannons
        self.belowMinCell = belowMinCell
    }

    init(_ warning: SweepWarning) {
        self.init(remainingShannons: warning.remainingShannons, belowMinCell: warning.belowMinCell)
    }
}

/// The one failure dialog, for a refused form and a refused broadcast alike.
///
/// Android shows both through the same "Transaction Failed" dialog, and the
/// reason is worth keeping: a user who typed 5 CKB and a user whose node
/// rejected the transaction are both looking at a send that did not happen,
/// and two different-looking dialogs would suggest two different severities.
struct SendAlert: Identifiable, Equatable {
    let id = UUID()
    let message: String
    /// The raw reason, shown in monospace and capped, so a bug report can
    /// quote the exact words the node used.
    let detail: String?
    /// Offered only after a broadcast actually failed, never after a
    /// validation refusal.
    let canRetry: Bool

    static func == (lhs: SendAlert, rhs: SendAlert) -> Bool {
        lhs.message == rhs.message && lhs.detail == rhs.detail && lhs.canRetry == rhs.canRetry
    }

    /// At most this much of the raw reason reaches the dialog.
    static let detailLimit = 200

    var truncatedDetail: String? {
        guard let detail, !detail.isEmpty, detail != message else { return nil }
        return String(detail.prefix(Self.detailLimit))
    }

    /// The two tips Android attaches to the matching failures.
    var tips: [String] {
        var tips: [String] = []
        if message.range(of: "insufficient", options: .caseInsensitive) != nil {
            tips.append("Make sure you have enough CKB to cover the amount plus network fees.")
        }
        if message.range(of: "minimum", options: .caseInsensitive) != nil
            || message.contains("61") {
            tips.append("CKB requires a minimum of 61 CKB per transaction output.")
        }
        return tips
    }
}

/// Drives the Send screen.
///
/// The one rule the whole type is arranged around: nothing is built, priced
/// against a key, or signed until the user has confirmed the review sheet.
/// ``submit()`` validates and prices; ``confirm()`` is the only thing that
/// reaches the key.
@MainActor
@Observable
final class SendViewModel {

    // MARK: - Form

    private(set) var recipient = ""
    private(set) var amount = ""

    /// The form's fee line. A one-input guess until a plan replaces it, which
    /// is exactly why the review sheet quotes the plan instead (#490).
    private(set) var estimatedFeeShannons: Int64 = 0

    /// Set while the typed amount would leave unspendable change.
    private(set) var dustWarning: String?

    /// True while the preview is running, which is the only thing the Send
    /// button waits on.
    private(set) var isPreparing = false
    private(set) var preparingMessage = ""

    private(set) var review: SendReview?

    /// "I understand" on the sweep warning. Reset with every new review, so an
    /// acknowledgement never carries over to a different draft.
    var sweepAcknowledged = false

    private(set) var alert: SendAlert?

    /// Whether the QR scanner sheet is up.
    var isScannerPresented = false

    /// Set once a confirmed send has been dismissed, so the screen can pop.
    private(set) var isFinished = false

    private let service: any SendServicing

    init(service: any SendServicing) {
        self.service = service
        recomputeFee()
    }

    // MARK: - Reads

    var status: SendStatus { service.status }
    var availableShannons: Int64 { service.availableShannons }
    var network: NetworkType { service.network }

    /// "12,345.60", as the Available row shows it.
    var availableText: String {
        formatCkbBalance(shannons: availableShannons, groupSeparator: ",", decimalSeparator: ".")
    }

    /// "~0.001000 CKB", or "~0 CKB" before there is anything to price.
    var estimatedFeeText: String {
        guard estimatedFeeShannons > 0 else { return "~0 CKB" }
        return "~\(formatCkbFixed(shannons: estimatedFeeShannons, decimals: 6)) CKB"
    }

    /// True from the moment Send is tapped until there is an outcome: the
    /// preview, the authentication, the signing and the broadcast all count.
    /// The CTA carries a spinner through all of it, because until a hash
    /// exists there is no status sheet to carry it instead.
    var isBusy: Bool {
        isPreparing || status.phase == .sending
    }

    /// What the CTA's spinner says. The preview's own line while it runs, the
    /// shared send copy after that.
    var busyMessage: String {
        let message = isPreparing ? preparingMessage : status.message
        return message.isEmpty ? "Sending..." : message
    }

    /// The status sheet appears only once a broadcast has returned a hash,
    /// which is what Android gates its dialog on too. Before that a failure
    /// has no transaction to report on, and a sheet saying "Transaction
    /// Failed" would cover the alert that says WHY.
    var showsStatusSheet: Bool {
        hasWatchedTransaction && !isStatusSheetHidden
    }

    /// A broadcast is being watched, whether or not its sheet is on screen.
    /// What the "transaction in progress" row on the form turns on.
    var hasWatchedTransaction: Bool {
        status.isActive && status.txHash != nil
    }

    /// True once Hide has been tapped, until the sheet is reopened or the
    /// transaction is dismissed for good.
    private(set) var isStatusSheetHidden = false

    var canSubmit: Bool {
        !isBusy && !recipient.isEmpty && !amount.isEmpty
    }

    var isMaxEnabled: Bool {
        !isBusy && availableShannons > 0
    }

    /// Nil while the field is empty: an indicator under an empty field would
    /// call every new send invalid before it began.
    var addressIndicator: AddressIndicator? {
        let trimmed = recipient.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard AddressUtils.shared.isValid(address: trimmed) else { return .invalid }
        let selected = network
        guard let scanned = AddressUtils.shared.getNetwork(address: trimmed) else { return .invalid }
        guard scanned == selected else {
            return .wrongNetwork(
                scanned: Self.name(of: scanned),
                selected: Self.name(of: selected)
            )
        }
        return .valid(network: Self.name(of: selected))
    }

    /// Confirm is blocked until an acknowledged sweep is acknowledged.
    var canConfirm: Bool {
        guard let review else { return false }
        return review.sweep == nil || sweepAcknowledged
    }

    // MARK: - Editing

    func updateRecipient(_ text: String) {
        recipient = text
        alert = nil
    }

    /// Runs the shared sanitiser on every keystroke: non-numeric characters, a
    /// second decimal point and a ninth decimal are dropped rather than
    /// rejected, so the field never fights the user mid-entry.
    func updateAmount(_ text: String) {
        guard let sanitized = sanitizeAmount(input: text) else { return }
        amount = sanitized
        alert = nil
        recomputeFee()
    }

    /// The scan button's result.
    func acceptScanned(address: String) {
        updateRecipient(address)
        isScannerPresented = false
    }

    /// The MAX pill.
    func setMaxAmount() async {
        let spendable = await service.maxSendableShannons()
        // Clamped to the balance ``validate()`` measures against. The two are
        // read from different places on purpose: the pipeline prices MAX over
        // the cells a send would select, which include the change in-flight
        // broadcasts are about to create, while the balance is the on-chain
        // read. The first can therefore be the larger of the two, and MAX
        // filling a number the screen then refuses as "Insufficient balance"
        // is worse than a MAX that is a few shannons conservative.
        let shannons = Swift.min(spendable, availableShannons)
        // Trailing zeros trimmed and a "." decimal point, because the value
        // goes straight back into the field the sanitiser owns.
        updateAmount(formatCkbTrimmed(shannons: shannons))
    }

    // MARK: - Submit

    /// Validate, price, and open the review sheet. Nothing is signed here.
    func submit() async {
        // Two taps in the same runloop turn each start their own Task, and
        // `canSubmit` only disables the button on the next render, so the
        // guard has to be here. Synchronous, and ahead of every `await`, so
        // the second caller is refused before it can reach the preview.
        guard !isBusy else { return }
        guard let amountShannons = validate() else { return }
        guard let from = service.fromAddress else {
            alert = SendAlert(message: "Wallet not initialized", detail: nil, canRetry: false)
            return
        }

        sweepAcknowledged = false
        // Only when there is nothing left being watched. A transaction the
        // user hid stays hidden through the next draft: un-hiding it here
        // would throw its sheet up over the review sheet, and a settled one
        // would do it while claiming to be finished. The form's reopen row is
        // still there for whoever wants it back, and ``confirm()`` clears the
        // flag when a new transaction actually replaces the old one.
        if !hasWatchedTransaction { isStatusSheetHidden = false }
        isPreparing = true
        preparingMessage = "Preparing transaction..."
        defer {
            isPreparing = false
            preparingMessage = ""
        }

        do {
            let plan = try await service.preview(
                from: from,
                recipients: [RecipientOutput(address: recipient, amountShannons: amountShannons)]
            )
            // The form's fee line was the one-input guess; the plan is what the
            // send will actually pay, so both surfaces now agree.
            estimatedFeeShannons = plan.feeShannons
            review = SendReview(
                recipient: recipient,
                amountShannons: amountShannons,
                feeShannons: plan.feeShannons,
                sweep: sweepWarning(
                    balanceShannons: availableShannons,
                    amountShannons: amountShannons,
                    feeShannons: plan.feeShannons
                ).map(SweepNotice.init)
            )
        } catch {
            alert = Self.alert(for: error)
        }
    }

    /// Review dismissed without sending. Nothing was built, so there is
    /// nothing to undo.
    func cancelReview() {
        review = nil
        sweepAcknowledged = false
    }

    /// The only path to the key.
    func confirm() async {
        guard let review else { return }
        // The sheet disables Confirm until the box is ticked; enforced here too
        // so the warning cannot be skipped by a caller that does not (#447).
        guard review.sweep == nil || sweepAcknowledged else { return }
        guard let from = service.fromAddress else {
            alert = SendAlert(message: "Wallet not initialized", detail: nil, canRetry: false)
            return
        }

        self.review = nil
        sweepAcknowledged = false
        // The new transaction's sheet has to be visible whatever the previous
        // one was left as: from here the poller's status belongs to this send,
        // and a carried-over Hide would swallow its whole lifecycle.
        isStatusSheetHidden = false

        let result = await service.send(
            from: from,
            to: review.recipient,
            amount: review.amountShannons,
            expectedFee: review.feeShannons
        )
        switch result {
        case .success:
            // The form is cleared the moment the broadcast is accepted, so a
            // second tap cannot resend the same draft. The status sheet is
            // what the user is looking at now.
            recipient = ""
            amount = ""
            dustWarning = nil
            recomputeFee()
        case let .failure(error):
            // A dismissed Face ID or PIN prompt is an answer, not a failure.
            guard !error.isCancellation else { return }
            // Retry re-broadcasts the ORIGINAL signed bytes, so it is offered
            // only when there is a hash to re-broadcast. A send that failed at
            // authentication, at the key, or during the build never reached a
            // broadcast and has nothing to retry; offering the button there
            // would be a button that does nothing.
            alert = SendAlert(
                message: error.message,
                detail: error.detail,
                canRetry: status.txHash != nil
            )
        }
    }

    /// Re-broadcast the failed transaction's original signed bytes.
    func retry() async {
        guard let hash = status.txHash else { return }
        alert = nil
        let result = await service.retry(txHash: hash)
        if case let .failure(error) = result, !error.isCancellation {
            alert = SendAlert(message: error.message, detail: error.detail, canRetry: false)
        }
    }

    func dismissAlert() {
        alert = nil
    }

    /// Put the sheet away without touching the transaction.
    ///
    /// What "Hide" does, and the only thing it does: the poll keeps running,
    /// the last ``SendStatus`` is kept, and ``reopenStatus()`` brings the
    /// sheet back. A send the user has walked away from still has to reach a
    /// terminal state, and stopping the poll because a sheet was in the way
    /// would leave it reported as pending forever.
    func hideStatus() {
        isStatusSheetHidden = true
    }

    /// Bring a hidden sheet back, from the row the form shows in its place.
    func reopenStatus() {
        isStatusSheetHidden = false
    }

    /// Finish with this transaction: stop the poll and clear the status.
    ///
    /// What "Done" and "Close" do, and only reachable once there is an outcome
    /// to be done with. A confirmed send also pops the screen, which is what
    /// ``isFinished`` tells the view.
    func dismissStatus() {
        let wasConfirmed = status.phase == .confirmed
        service.dismissStatus()
        isStatusSheetHidden = false
        if wasConfirmed { isFinished = true }
    }

    // MARK: - Internals

    /// Android's `validateSingleInputs`, order included: the first failure is
    /// the one the user is shown.
    private func validate() -> Int64? {
        if recipient.isEmpty {
            alert = SendAlert(message: "Please enter recipient address", detail: nil, canRetry: false)
            return nil
        }
        if amount.isEmpty {
            alert = SendAlert(message: "Please enter amount", detail: nil, canRetry: false)
            return nil
        }
        guard let shannons = ckbToShannons(text: amount)?.int64Value else {
            alert = SendAlert(message: "Invalid amount", detail: nil, canRetry: false)
            return nil
        }
        if shannons < SendCopyKt.MIN_CELL_SHANNONS {
            alert = SendAlert(message: "Minimum transfer is 61 CKB", detail: nil, canRetry: false)
            return nil
        }
        if shannons > availableShannons {
            alert = SendAlert(message: "Insufficient balance", detail: nil, canRetry: false)
            return nil
        }
        return shannons
    }

    /// The form's fee guess and the dust warning, recomputed per keystroke.
    ///
    /// The output count is the Android heuristic: a send that would leave less
    /// than a minimal cell behind produces no change output, so it is priced
    /// for one output rather than two.
    private func recomputeFee() {
        let shannons = ckbToShannons(text: amount)?.int64Value ?? 0
        let balance = availableShannons
        let outputCount = (shannons > 0 && balance - shannons < SendCopyKt.MIN_CELL_SHANNONS) ? 1 : 2
        let fee = service.estimateFee(inputCount: 1, outputCount: outputCount)
        estimatedFeeShannons = fee

        let change = balance - shannons - fee
        if change > 0, change < SendCopyKt.MIN_CELL_SHANNONS {
            let lost = formatCkbTrimmed(shannons: change)
            dustWarning = "Heads up: this would leave \(lost) CKB change, below the 61 CKB "
                + "minimum cell size. The send will be refused. Adjust the amount so the "
                + "change is 0 or at least 61 CKB."
        } else {
            dustWarning = nil
        }
    }

    private static func name(of network: NetworkType) -> String {
        network.name.lowercased()
    }

    /// Everything that reaches the dialog goes through the shared mapping, so
    /// iOS and Android say the same thing about the same rejection.
    private static func alert(for error: Error) -> SendAlert {
        if let sendError = error as? SendError {
            return SendAlert(message: sendError.message, detail: sendError.detail, canRetry: false)
        }
        let raw = (error as NSError).kotlinMessage ?? error.localizedDescription
        return SendAlert(
            message: mapSendErrorMessage(message: raw),
            detail: raw,
            canRetry: false
        )
    }
}
