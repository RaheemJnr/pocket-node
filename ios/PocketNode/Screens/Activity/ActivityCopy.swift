import Foundation
import PocketNodeCore

/// Every word the activity list and the transaction detail sheet put on screen,
/// and the two format helpers that are not the shared core's job.
///
/// One file rather than string literals scattered through the views, for the
/// same reason Android keeps these in `strings.xml`: the copy is the part a
/// user actually reads, it was written once for #432 and #497, and the two
/// apps have to say the same thing. Every string below is the Android string
/// verbatim, minus the two em dashes Android uses as placeholders.
enum ActivityCopy {

    // MARK: - Status

    static func statusLabel(_ state: TxDisplayState) -> String {
        switch state {
        case TxDisplayState.broadcasting: return "Broadcasting"
        case TxDisplayState.pending: return "Pending"
        case TxDisplayState.confirmed: return "Confirmed"
        case TxDisplayState.failed: return "Failed"
        default: return "Pending"
        }
    }

    /// `"Pending · 2 min"` while in flight, the bare label otherwise.
    static func statusLabel(_ state: TxDisplayState, elapsed: ElapsedBucket?) -> String {
        let label = statusLabel(state)
        guard showsElapsed(state: state), let elapsed else { return label }
        return "\(label) · \(elapsedText(elapsed))"
    }

    /// `"<1 min"`, `"2 min"`, `"3 hr"`, `"2 d"`. The bucketing itself is the
    /// shared core's `elapsedBucket`; only the words are here.
    static func elapsedText(_ bucket: ElapsedBucket?) -> String {
        guard let bucket else { return "Not recorded" }
        let value = bucket.value?.intValue ?? 0
        switch bucket.unit {
        case ElapsedUnit.underMinute: return "<1 min"
        case ElapsedUnit.minutes: return "\(value) min"
        case ElapsedUnit.hours: return "\(value) hr"
        case ElapsedUnit.days: return "\(value) d"
        default: return "<1 min"
        }
    }

    // MARK: - Failure

    static func failureReason(_ reason: TxFailureReason?) -> String {
        switch reason {
        case TxFailureReason.dropped:
            return "The network stopped reporting this transaction, so it never made it into a block. Nothing was sent and your balance is unchanged."
        case TxFailureReason.rejected:
            return "The network kept it waiting for about 6 minutes and then dropped it. That usually means one of the coins it spends had already been used by another transaction."
        default:
            return "The wallet could not find this transaction on chain. Nothing was sent and your balance is unchanged."
        }
    }

    // MARK: - Explainer

    static func explainerTitle(_ state: TxDisplayState) -> String {
        switch state {
        case TxDisplayState.broadcasting: return "Sending to the network"
        case TxDisplayState.pending: return "Waiting to be confirmed"
        case TxDisplayState.confirmed: return "Confirmed on chain"
        case TxDisplayState.failed: return "This did not go through"
        default: return "Waiting to be confirmed"
        }
    }

    /// The body of the explainer card, one paragraph per line.
    static func explainerBody(_ state: TxDisplayState, reason: TxFailureReason?) -> [String] {
        switch state {
        case TxDisplayState.broadcasting:
            return ["Your phone is handing the signed transaction to the CKB network. This normally takes a few seconds. Nothing has moved on chain yet, and your balance has not changed."]
        case TxDisplayState.confirmed:
            return ["This transaction is in a block and counted in your balance. Every block added after it makes it harder to reverse."]
        case TxDisplayState.failed:
            return [
                failureReason(reason),
                "Retry re-sends the exact same signed transaction, reusing the same coins, so it can never pay twice.",
            ]
        default:
            return [
                "The network has your transaction and is waiting to include it in a block. This usually takes under a minute. It turns Confirmed as soon as it lands in a block.",
                "There is nothing to do while it is Pending, and you do not need to send it again. If the network still has not picked it up after about 6 minutes, the wallet marks it Failed and offers a retry.",
            ]
        }
    }

    // MARK: - List

    static func filterLabel(_ filter: ActivityFilter) -> String {
        switch filter {
        case ActivityFilter.received: return "Received"
        case ActivityFilter.sent: return "Sent"
        default: return "All"
        }
    }

    static func filterIdentifier(_ filter: ActivityFilter) -> String {
        switch filter {
        case ActivityFilter.received: return "activity.filter.received"
        case ActivityFilter.sent: return "activity.filter.sent"
        default: return "activity.filter.all"
        }
    }

    static func emptyMessage(_ filter: ActivityFilter) -> String {
        switch filter {
        case ActivityFilter.received: return "No received transactions"
        case ActivityFilter.sent: return "No sent transactions"
        default: return "No transactions yet"
        }
    }

    static let loadFailed = "Failed to load transactions"
    static let retry = "Retry"
    static let retryTransaction = "Retry Transaction"
    static let notInABlock = "Not in a block yet"
    static let submitted = "Submitted"
    static let feePending = "Pending"

    /// Shown under the Retry button until the send path is wired to it.
    static let retryUnavailable = "Retry needs the send path, which arrives with the next update."

    // MARK: - Type labels

    /// The row's and the detail sheet's type label, e.g. "Dao Deposit".
    static func typeLabel(_ record: TransactionRecord) -> String {
        if record.isDaoDeposit() { return "Dao Deposit" }
        if record.isDaoWithdraw() { return "Dao Withdraw" }
        if record.isDaoUnlock() { return "Dao Unlock" }
        if record.isIncoming() { return "Received" }
        if record.isSelfTransfer() { return "Self Transfer" }
        return "Sent"
    }

    /// The amount card's caption. Differs from [typeLabel] in one place, and
    /// Android differs the same way: a self transfer has no direction worth
    /// naming above a number, so it reads "Amount".
    static func amountCaption(_ record: TransactionRecord) -> String {
        if record.isDaoDeposit() { return "Dao Deposit" }
        if record.isDaoWithdraw() { return "Dao Withdraw" }
        if record.isDaoUnlock() { return "Dao Unlock" }
        if record.isIncoming() { return "Received" }
        if record.isOutgoing() { return "Sent" }
        return "Amount"
    }
}
