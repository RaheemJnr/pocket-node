import PocketNodeCore
import SwiftUI

/// In-sheet explanation of what a transaction's current state means and what
/// happens next.
///
/// Shown for every state, not just the in-flight ones: "Confirmed" is the
/// answer to the same question the user opened the sheet with. Inline rather
/// than a sheet of its own, because the user is already looking at the
/// transaction they are confused about.
struct TransactionStatusExplainer: View {
    let state: TxDisplayState
    let reason: TxFailureReason?
    let theme: Theme

    var body: some View {
        let colors = TxStatusColors.forState(state, theme: theme)
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.footnote)
                    .foregroundStyle(colors.foreground)
                Text(ActivityCopy.explainerTitle(state))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(colors.foreground)
            }
            ForEach(ActivityCopy.explainerBody(state, reason: reason), id: \.self) { line in
                Text(line)
                    .font(.footnote)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(colors.background, in: RoundedRectangle(cornerRadius: 16))
    }

    private var icon: String {
        switch state {
        case TxDisplayState.broadcasting: return "paperplane.fill"
        case TxDisplayState.confirmed: return "checkmark.circle.fill"
        case TxDisplayState.failed: return "exclamationmark.circle.fill"
        default: return "clock.fill"
        }
    }
}
