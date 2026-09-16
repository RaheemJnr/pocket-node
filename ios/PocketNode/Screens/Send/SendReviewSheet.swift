import SwiftUI
import PocketNodeCore

/// The last stop before a broadcast (#490).
///
/// Every number here is the one the transaction will carry: the fee comes
/// from `SendPipeline.previewTransfer`, which runs the real cell selection,
/// and the same figure is handed back to the send as `expectedFeeShannons` so
/// the pipeline refuses to broadcast if a re-plan disagrees. A sheet that
/// quoted an estimate and then paid something else is the bug this closes.
struct SendReviewSheet: View {
    let review: SendReview
    let theme: Theme

    /// "I understand" on the sweep warning. A binding, because Confirm being
    /// disabled until it is ticked is the whole point of the warning.
    @Binding var acknowledged: Bool

    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Review transaction")
                    .font(.title3.weight(.bold))
                Text("Check the details. This cannot be undone once confirmed.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, 4)

                row(
                    label: "Recipient",
                    value: Self.truncate(review.recipient),
                    monospaced: true,
                    identifier: "review.recipient"
                )
                .padding(.top, 20)

                row(
                    label: "Amount",
                    value: "\(formatCkbAmount(shannons: review.amountShannons, groupSeparator: ",", decimalSeparator: ".")) CKB",
                    identifier: "review.amount"
                )
                .padding(.top, 12)

                row(
                    // The same shared formatter the transaction detail sheet
                    // uses, so the two are character-identical for one send
                    // and a user can cross-check them (#497).
                    label: "Network fee",
                    value: "\(formatCkbTrimmed(shannons: review.feeShannons)) CKB",
                    identifier: "review.fee"
                )
                .padding(.top, 12)

                Divider().padding(.vertical, 16)

                row(
                    label: "Total",
                    value: "\(formatCkbAmount(shannons: review.totalShannons, groupSeparator: ",", decimalSeparator: ".")) CKB",
                    emphasised: true,
                    identifier: "review.total"
                )

                if let sweep = review.sweep {
                    sweepWarningCard(sweep)
                        .padding(.top, 16)
                }

                HStack(spacing: 12) {
                    Button("Cancel", action: onCancel)
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("review.cancel")

                    Button("Confirm", action: onConfirm)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .frame(maxWidth: .infinity)
                        .disabled(review.sweep != nil && !acknowledged)
                        .accessibilityIdentifier("review.confirm")
                }
                .padding(.top, 24)
            }
            .padding(24)
        }
        .background(theme.background)
        .presentationDetents([.medium, .large])
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("review.sheet")
    }

    /// #447: a send that leaves the wallet empty, or with less than a usable
    /// cell, is almost always a surprise rather than an intent. The line
    /// states the real leftover and Confirm stays disabled until it is
    /// acknowledged.
    private func sweepWarningCard(_ sweep: SweepNotice) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(sweep.message)
                .font(.subheadline)
                .accessibilityIdentifier("review.sweepWarning")

            // One tap target over the box and its label, so VoiceOver sees one
            // control rather than two.
            Toggle(isOn: $acknowledged) {
                Text("I understand")
                    .font(.subheadline.weight(.medium))
            }
            .toggleStyle(CheckboxToggleStyle())
            .accessibilityIdentifier("review.acknowledge")
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.errorRed.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }

    private func row(
        label: String,
        value: String,
        monospaced: Bool = false,
        emphasised: Bool = false,
        identifier: String
    ) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .font(emphasised ? .headline : .subheadline.weight(.medium))
                .fontDesign(monospaced ? .monospaced : .default)
                .multilineTextAlignment(.trailing)
                .accessibilityIdentifier(identifier)
        }
    }

    /// The same 10 and 6 the activity list truncates to.
    static func truncate(_ address: String) -> String {
        guard address.count > 20 else { return address }
        return "\(address.prefix(10))...\(address.suffix(6))"
    }
}

/// A checkbox, because SwiftUI's default `Toggle` on iOS is a switch and a
/// switch reads as a setting rather than as an acknowledgement.
struct CheckboxToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .imageScale(.large)
                configuration.label
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(configuration.isOn ? [.isButton, .isSelected] : .isButton)
    }
}
