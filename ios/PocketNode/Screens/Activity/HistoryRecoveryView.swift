import PocketNodeCore
import SwiftUI

/// Historical hints have their own read-only surface, with no transaction action sheet.
struct HistoryRecoveryView: View {
    let model: RecoveryPresentation
    let theme: Theme
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.title).font(.headline)
            Text(model.explanation).font(.caption)
            if model.isRevalidating {
                ProgressView("Checking wallet history")
                    .font(.caption)
                    .accessibilityIdentifier("historyRecovery.progress")
            }
            if !model.transactions.isEmpty || !model.daoHints.isEmpty {
                Button(expanded ? "Hide retained records" : "Show retained records") {
                    expanded.toggle()
                }
                .font(.footnote)
                .accessibilityIdentifier("historyRecovery.expand")
            }
            if expanded {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        if !model.transactions.isEmpty {
                            Text(model.transactionHeading).font(.subheadline.weight(.semibold))
                            ForEach(model.transactions, id: \.identity) { row in
                                hint(row)
                            }
                        }
                        if !model.daoHints.isEmpty {
                            Text(model.daoHeading).font(.subheadline.weight(.semibold))
                            Text(model.daoExplanation).font(.caption)
                            ForEach(model.daoHints, id: \.identity) { row in
                                hint(row)
                            }
                        }
                    }
                }
                .frame(maxHeight: 240)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("historyRecovery.root")
    }

    private func hint(_ row: RecoveryHint) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(row.identity).font(.caption.monospaced())
            Text(row.details).font(.caption).foregroundStyle(.secondary)
            Divider()
        }
        .textSelection(.enabled)
    }
}
