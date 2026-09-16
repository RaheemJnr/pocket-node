import PocketNodeCore
import SwiftUI
import UIKit

/// One row of the detail sheet's key/value list.
private struct DetailRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(value)
                .font(.caption.weight(.medium))
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }
}

/// Everything known about one transaction, in the order Android shows it:
/// what state it is in and what that means, how much moved, then the on-chain
/// facts, then the one action a failed transaction offers.
struct TransactionDetailSheet: View {
    let item: ActivityItem
    let network: NetworkType
    let theme: Theme

    /// Re-derived on the screen's ticker, so a sheet left open ages its badge.
    let elapsed: ElapsedBucket?

    /// Nil until the send path is wired up (#5); the button then says why it
    /// cannot run rather than silently doing nothing.
    let onRetry: ((String) -> Void)?

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let record = item.record
        let style = DirectionStyle.forRecord(record, theme: theme)

        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header

                TransactionStatusExplainer(
                    state: item.displayState,
                    reason: item.failureReason,
                    theme: theme
                )
                .padding(.top, 16)

                amountCard(record: record, style: style)
                    .padding(.top, 20)

                hashRow(record: record)
                    .padding(.top, 20)

                Divider().padding(.vertical, 12)
                DetailRow(label: "Block Number", value: blockNumberValue(record: record))

                if !record.blockHash.isEmpty && record.blockHash != "0x0" {
                    Divider().padding(.vertical, 12)
                    DetailRow(
                        label: "Block Hash",
                        value: ActivityFormat.truncateHash(record.blockHash)
                    )
                }

                Divider().padding(.vertical, 12)
                DetailRow(label: timeLabel, value: timeValue(record: record))

                // Shown for everything the wallet can originate; hidden for a
                // plain receive, where the sender paid it. "Pending" when the
                // fee is not resolvable yet, never a silent zero, which no real
                // transaction pays.
                if record.paysNetworkFee() {
                    Divider().padding(.vertical, 12)
                    DetailRow(
                        label: "Network fee",
                        value: record.formattedFee() ?? ActivityCopy.feePending
                    )
                }

                retrySection(record: record)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 40)
        }
        .presentationDragIndicator(.visible)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("activity.detail")
    }

    // MARK: - Sections

    private var header: some View {
        HStack {
            Text("Transaction Details")
                .font(.title2.weight(.bold))
            Spacer(minLength: 0)
            TransactionStatusChip(state: item.displayState, elapsed: elapsed, theme: theme)
        }
        .padding(.top, 24)
    }

    private func amountCard(record: TransactionRecord, style: DirectionStyle) -> some View {
        VStack(spacing: 8) {
            Text(ActivityCopy.amountCaption(record))
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(record.formattedAmount())
                .font(.title.weight(.bold))
                .foregroundStyle(style.color)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(theme.primary.opacity(0.35), lineWidth: 1)
        )
    }

    private func hashRow(record: TransactionRecord) -> some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text("TX Hash")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(record.shortTxHash())
                    .font(.caption.monospaced())
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                UIPasteboard.general.string = record.txHash
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            } label: {
                Image(systemName: "doc.on.doc")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Copy transaction hash")
            .accessibilityIdentifier("activity.detail.copyHash")

            if let url = ActivityFormat.explorerURL(txHash: record.txHash, network: network) {
                Link(destination: url) {
                    Image(systemName: "arrow.up.forward.square")
                        .foregroundStyle(theme.primary)
                }
                .accessibilityLabel("Open in the block explorer")
                .accessibilityIdentifier("activity.detail.explorer")
            }
        }
    }

    @ViewBuilder
    private func retrySection(record: TransactionRecord) -> some View {
        if item.displayState == TxDisplayState.failed && record.isOutgoing() {
            VStack(spacing: 8) {
                Button {
                    onRetry?(record.txHash)
                    dismiss()
                } label: {
                    Text(ActivityCopy.retryTransaction)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.errorRed)
                .disabled(onRetry == nil)
                .accessibilityIdentifier("activity.detail.retry")

                if onRetry == nil {
                    Text(ActivityCopy.retryUnavailable)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.top, 24)
        }
    }

    // MARK: - Values

    /// An in-flight transaction is in no block yet, so the row says that rather
    /// than rendering an empty value.
    private func blockNumberValue(record: TransactionRecord) -> String {
        item.isInFlight ? ActivityCopy.notInABlock : ActivityFormat.blockNumber(record.blockNumber)
    }

    /// Before a transaction is in a block there is no block timestamp, so the
    /// row shows how long it has been in flight instead.
    private var timeLabel: String {
        item.isInFlight ? ActivityCopy.submitted : "Time"
    }

    private func timeValue(record: TransactionRecord) -> String {
        item.isInFlight
            ? ActivityCopy.elapsedText(elapsed)
            : ActivityFormat.blockTimestamp(record.blockTimestampHex)
    }
}
