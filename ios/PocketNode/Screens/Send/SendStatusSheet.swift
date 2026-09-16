import SwiftUI
import PocketNodeCore

/// What happened to the transaction, from the moment the user confirms until
/// it commits or fails.
///
/// The sheet cannot be finished with while the transaction is in flight: it
/// offers "Hide" instead, which puts the sheet away, leaves the poll running
/// and leaves the form a row to reopen it from. Only a confirmed or failed
/// send gets "Done" / "Close", which are what actually stop the poll, and
/// dismissing a confirmed one pops back to Home.
struct SendStatusSheet: View {
    let status: SendStatus
    let network: NetworkType
    let theme: Theme

    /// Done / Close: stop the poll and clear the transaction.
    let onDismiss: () -> Void

    /// Hide: put the sheet away and nothing else.
    let onHide: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                icon
                Text(Self.title(for: status.phase))
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("sendStatus.title")

                Text(status.message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("sendStatus.message")

                // Hidden once there is an outcome: a stepper next to "Failed"
                // reads as progress that is still happening.
                if !status.isSettled {
                    stepper
                }

                if let hash = status.txHash {
                    hashCard(hash)
                }

                if status.phase == .confirmed, status.confirmations > 0 {
                    Text("\(status.confirmations) confirmation\(status.confirmations > 1 ? "s" : "")")
                        .font(.footnote.weight(.medium))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Theme.successGreen.opacity(0.15), in: Capsule())
                        .foregroundStyle(Theme.successGreen)
                        .accessibilityIdentifier("sendStatus.confirmations")
                }

                Button(
                    Self.dismissLabel(for: status.phase),
                    action: status.isSettled ? onDismiss : onHide
                )
                    .buttonStyle(status.isSettled ? AnyButtonStyle(.borderedProminent) : AnyButtonStyle(.bordered))
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("sendStatus.done")
            }
            .padding(24)
        }
        .background(theme.background)
        .presentationDetents([.medium, .large])
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("sendStatus.sheet")
    }

    @ViewBuilder
    private var icon: some View {
        switch status.phase {
        case .confirmed:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 48))
                .foregroundStyle(Theme.successGreen)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 48))
                .foregroundStyle(Theme.errorRed)
        default:
            ProgressView()
                .controlSize(.large)
        }
    }

    /// Submitted -> Pending -> Confirmed. Three dots rather than a bar: the
    /// middle step has no known duration, and a bar would have to lie about it.
    private var stepper: some View {
        HStack(spacing: 0) {
            ForEach(Self.steps, id: \.name) { step in
                VStack(spacing: 6) {
                    Circle()
                        .fill(step.isDone(status.phase) ? theme.primary : Color.secondary.opacity(0.3))
                        .frame(width: 10, height: 10)
                    Text(step.name)
                        .font(.caption2)
                        .foregroundStyle(step.isDone(status.phase) ? .primary : .secondary)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .accessibilityIdentifier("sendStatus.stepper")
    }

    private func hashCard(_ hash: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Transaction Hash")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 8)
                if let url = ActivityFormat.explorerURL(txHash: hash, network: network) {
                    Link("View on Explorer", destination: url)
                        .font(.caption)
                        .accessibilityIdentifier("sendStatus.explorer")
                }
            }
            Text(hash)
                .font(.caption.monospaced())
                .textSelection(.enabled)
                .accessibilityIdentifier("sendStatus.hash")
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Copy

    /// Android's `TransactionStatusDialog` titles, unchanged.
    static func title(for phase: SendPhase) -> String {
        switch phase {
        case .confirmed: return "Transaction Confirmed!"
        case .failed: return "Transaction Failed"
        case .sending: return "Sending..."
        case .pending: return "Waiting for Confirmation"
        case .proposed: return "Processing..."
        case .idle: return "Transaction Submitted"
        }
    }

    /// "Done" once it worked, "Close" once it did not, "Hide" while it is
    /// still happening. Only the last of those leaves the poll running, and it
    /// is the only one that can be tapped before there is an outcome.
    static func dismissLabel(for phase: SendPhase) -> String {
        switch phase {
        case .confirmed: return "Done"
        case .failed: return "Close"
        default: return "Hide"
        }
    }

    private struct Step {
        let name: String
        let isDone: (SendPhase) -> Bool
    }

    private static let steps: [Step] = [
        Step(name: "Submitted", isDone: { $0 != .sending }),
        Step(name: "Pending", isDone: { $0 == .pending || $0 == .proposed || $0 == .confirmed }),
        Step(name: "Confirmed", isDone: { $0 == .confirmed }),
    ]
}

/// Type-erases the two button styles the dismiss button picks between.
///
/// `buttonStyle(_:)` takes a concrete type, so a ternary between
/// `.borderedProminent` and `.bordered` does not compile without this.
struct AnyButtonStyle: PrimitiveButtonStyle {
    private let make: (Configuration) -> AnyView

    init<Style: PrimitiveButtonStyle>(_ style: Style) {
        make = { configuration in
            AnyView(Button(configuration).buttonStyle(style))
        }
    }

    func makeBody(configuration: Configuration) -> some View {
        make(configuration)
    }
}
