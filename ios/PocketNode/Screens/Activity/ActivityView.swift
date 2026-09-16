import PocketNodeCore
import SwiftUI

/// The wallet's transaction history: three tabs, date-grouped rows, and a
/// detail sheet per transaction.
///
/// Android's activity screen, narrowed to what M3 ships on iOS: no CSV export
/// (that needs a document picker and a wallet with history worth exporting) and
/// no multi-wallet switcher.
struct ActivityView: View {
    let model: ActivityViewModel
    let theme: Theme

    /// Re-read every 20 seconds while anything is in flight, so "Pending ·
    /// 2 min" ages on screen instead of freezing at the value it was drawn
    /// with. It reads the device clock only: no query, no bridge call.
    @State private var now = Date()

    var body: some View {
        VStack(spacing: 0) {
            filterTabs

            content
        }
        .background(theme.background)
        .navigationTitle("Activity")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.onAppear() }
        .task(id: hasInFlight) {
            guard hasInFlight else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(20))
                guard !Task.isCancelled else { return }
                now = Date()
                // "TODAY" is a claim about the wall clock, so the tick that
                // crosses midnight has to re-decide the headers as well as
                // the elapsed badges.
                model.refreshGroupingIfDayChanged(now: now)
            }
        }
        .sheet(item: Binding(
            get: { model.selected },
            set: { model.selected = $0 }
        )) { item in
            TransactionDetailSheet(
                item: item,
                network: model.network,
                theme: theme,
                elapsed: elapsed(for: item),
                onRetry: model.onRetry
            )
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("activity.root")
    }

    // MARK: - Filters

    private var filterTabs: some View {
        HStack(spacing: 8) {
            ForEach(ActivityFilter.entries, id: \.self) { filter in
                let selected = filter == model.filter
                Button {
                    model.setFilter(filter)
                } label: {
                    VStack(spacing: 4) {
                        Text(ActivityCopy.filterLabel(filter))
                            .font(.subheadline)
                            .fontWeight(selected ? .semibold : .regular)
                            .foregroundStyle(selected ? theme.primary : Color.secondary)
                        Rectangle()
                            .fill(selected ? theme.primary : .clear)
                            .frame(height: 2)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(ActivityCopy.filterIdentifier(filter))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if model.isLoading && model.isEmpty {
            centered { ProgressView().tint(theme.primary) }
        } else if let error = model.error, model.isEmpty {
            errorState(error)
        } else if model.isEmpty {
            emptyState
        } else {
            list
        }
    }

    private var list: some View {
        List {
            // Iterated by identity, and every row carries its own header
            // decision. Nothing in this closure reads the array it came from:
            // SwiftUI re-evaluates a row body against whatever `model.rows`
            // holds at that moment, so an index captured when the row was
            // built can address an element that is no longer there.
            ForEach(model.rows) { row in
                if row.showsGroupHeader {
                    Text(row.groupLabel)
                        .font(.caption2.weight(.bold))
                        .kerning(1)
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 4)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowBackground(Color.clear)
                }

                ActivityRow(item: row.item, theme: theme, elapsed: elapsed(for: row.item))
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .onTapGesture { model.selected = row.item }
                    .onAppear {
                        if model.isLastLoadedRow(row.item) { model.loadMore() }
                    }
            }

            if model.isLoadingMore {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .padding(16)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { model.refresh() }
        .accessibilityIdentifier("activity.list")
    }

    private var emptyState: some View {
        centered {
            VStack(spacing: 16) {
                Image(systemName: "doc.text")
                    .font(.system(size: 48))
                    .foregroundStyle(.secondary.opacity(0.4))
                Text(ActivityCopy.emptyMessage(model.filter))
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .accessibilityIdentifier("activity.empty")
    }

    private func errorState(_ message: String) -> some View {
        centered {
            VStack(spacing: 12) {
                Text(message.isEmpty ? ActivityCopy.loadFailed : message)
                    .font(.subheadline)
                    .foregroundStyle(Theme.errorRed)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                Button(ActivityCopy.retry) { model.refresh() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("activity.retry")
            }
        }
        .accessibilityIdentifier("activity.error")
    }

    private func centered<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack { content() }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Ticker

    private var hasInFlight: Bool { model.rows.contains { $0.item.isInFlight } }

    /// The row's elapsed bucket as of the ticker's current reading. The shared
    /// core stamped one at page time; this re-derives it so the label ages
    /// without re-reading the database.
    private func elapsed(for item: ActivityItem) -> ElapsedBucket? {
        guard item.isInFlight, let since = item.pendingSinceMs?.int64Value else { return nil }
        let nowMillis = Int64(now.timeIntervalSince1970 * 1000)
        return elapsedBucket(elapsedMillis: nowMillis - since)
    }
}

/// `sheet(item:)` needs an `Identifiable`; a transaction is identified by its
/// hash, which is unique per network and is what every other screen keys on.
extension ActivityItem: @retroactive Identifiable {
    public var id: String { record.txHash }
}
