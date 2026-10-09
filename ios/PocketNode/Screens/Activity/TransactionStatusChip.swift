import PocketNodeCore
import SwiftUI

/// Foreground and background for one display state.
struct TxStatusColors {
    let foreground: Color
    let background: Color

    /// The Android pairing, token for token: primary while the app itself is
    /// working, amber while the network is, green once it landed, red when it
    /// did not. The background is the same color at 15 percent.
    static func forState(_ state: TxDisplayState, theme: Theme) -> TxStatusColors {
        let base: Color
        switch state {
        case TxDisplayState.broadcasting: base = theme.primary
        case TxDisplayState.confirmed: base = Theme.successGreen
        case TxDisplayState.failed: base = Theme.errorRed
        default: base = Theme.pendingAmber
        }
        return TxStatusColors(foreground: base, background: base.opacity(0.15))
    }
}

/// The status badge the list rows and the detail sheet share, so one state
/// always looks the same wherever it appears.
///
/// BROADCASTING carries a small spinner: it is the only state where the app
/// itself is mid-operation, and telling "we are still working" from "nothing is
/// happening" is the whole point of showing it.
struct TransactionStatusChip: View {
    let state: TxDisplayState
    let elapsed: ElapsedBucket?
    let theme: Theme
    var compact: Bool = false

    var body: some View {
        let colors = TxStatusColors.forState(state, theme: theme)
        HStack(spacing: 6) {
            if state == TxDisplayState.broadcasting {
                ProgressView()
                    .controlSize(.mini)
                    .tint(colors.foreground)
            }
            Text(ActivityCopy.statusLabel(state, elapsed: elapsed))
                .font(compact ? .caption2.weight(.semibold) : .caption.weight(.semibold))
                .foregroundStyle(colors.foreground)
        }
        .padding(.horizontal, compact ? 6 : 12)
        .padding(.vertical, compact ? 2 : 6)
        .background(colors.background, in: RoundedRectangle(cornerRadius: 8))
    }
}
