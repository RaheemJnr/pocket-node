import PocketNodeCore
import SwiftUI

/// The icon, the icon's ground and the amount's color for one direction.
///
/// The Android pairing exactly: a DAO deposit is the primary color, a DAO
/// withdraw amber (it is money on its way back, not yet back), a DAO unlock and
/// a plain receive read as incoming, a self transfer is neutral, and everything
/// else is outgoing red.
struct DirectionStyle {
    let systemImage: String
    let color: Color

    static func forRecord(_ record: TransactionRecord, theme: Theme) -> DirectionStyle {
        if record.isDaoDeposit() {
            return DirectionStyle(systemImage: "building.columns", color: theme.primary)
        }
        if record.isDaoWithdraw() {
            return DirectionStyle(systemImage: "building.columns", color: Theme.pendingAmber)
        }
        if record.isDaoUnlock() {
            return DirectionStyle(systemImage: "arrow.down.left", color: Theme.successGreen)
        }
        if record.isIncoming() {
            return DirectionStyle(systemImage: "arrow.down.left", color: theme.primary)
        }
        if record.isSelfTransfer() {
            return DirectionStyle(systemImage: "arrow.left.arrow.right", color: .secondary)
        }
        return DirectionStyle(systemImage: "arrow.up.right", color: Theme.errorRed)
    }
}

/// One transaction in the list.
///
/// In-flight and failed rows get a tinted ground and a left accent bar so they
/// read as a distinct block at the top of the list rather than as ordinary
/// history with a small chip on it. Confirmed rows stay unbadged: the list is
/// overwhelmingly confirmed history, and badging every row would drown the
/// handful that need attention.
struct ActivityRow: View {
    let item: ActivityItem
    let theme: Theme

    /// Re-derived on the screen's ticker so "Pending · 2 min" ages in place.
    let elapsed: ElapsedBucket?

    var body: some View {
        let record = item.record
        let style = DirectionStyle.forRecord(record, theme: theme)
        let colors = TxStatusColors.forState(item.displayState, theme: theme)
        let isFailed = item.displayState == TxDisplayState.failed

        HStack(spacing: 0) {
            if item.isInFlight || isFailed {
                RoundedRectangle(cornerRadius: 2)
                    .fill(colors.foreground)
                    .frame(width: 3, height: 36)
                    .padding(.trailing, 9)
            }

            ZStack {
                Circle().fill(style.color.opacity(0.15))
                Image(systemName: style.systemImage)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(style.color)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(ActivityCopy.typeLabel(record))
                        .font(.subheadline.weight(.medium))

                    if isFailed || item.isInFlight {
                        TransactionStatusChip(
                            state: item.displayState,
                            elapsed: elapsed,
                            theme: theme,
                            compact: true
                        )
                    }

                    if item.isBulk {
                        Text("Bulk")
                            .font(.caption2)
                            .foregroundStyle(theme.primary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(
                                theme.primary.opacity(0.15),
                                in: RoundedRectangle(cornerRadius: 4)
                            )
                    }
                }

                // On the row from the moment of broadcast, so it is copyable
                // from the detail sheet before the transaction is on chain.
                Text(record.shortTxHash())
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)

                if isFailed {
                    Text(ActivityCopy.failureReason(item.failureReason))
                        .font(.caption2)
                        .foregroundStyle(Theme.errorRed)
                        .lineLimit(2)
                }
            }
            .padding(.leading, 12)
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 2) {
                Text(record.formattedAmount())
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(style.color)

                // An in-flight transaction has no block timestamp yet, and a
                // placeholder there reads as missing data. Its elapsed time is
                // on the badge instead.
                if !item.isInFlight {
                    Text(ActivityFormat.blockTimestamp(record.blockTimestampHex))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.leading, 12)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(rowBackground(isFailed: isFailed, colors: colors))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("activity.row.\(record.txHash)")
    }

    private func rowBackground(isFailed: Bool, colors: TxStatusColors) -> Color {
        if item.isInFlight { return colors.foreground.opacity(0.10) }
        if isFailed { return Theme.errorRed.opacity(0.06) }
        return .clear
    }
}
