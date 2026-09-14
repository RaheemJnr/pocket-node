import SwiftUI

struct NodeStatusView: View {
    @Environment(AppContainer.self) private var container

    private static let refreshInterval = Duration.seconds(5)

    private var theme: Theme { container.theme }
    private var service: LightClientService { container.lightClient }

    var body: some View {
        List {
            Section("Node") {
                row("Status", service.status.label, id: "nodeStatus.status")
                row("Network", "CKB Testnet")
            }

            Section("Chain") {
                row("Tip block", service.tipNumber.formatted(.number.grouping(.automatic)), id: "nodeStatus.tipBlock")
                row("Tip hash", shortHash(service.tipHash), id: "nodeStatus.tipHash")
                row("Peers", "\(service.peerCount)", id: "nodeStatus.peers")
            }

            if let error = service.lastError {
                Section("Last error") {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(theme.error)
                }
            }

            Section {
                Button("Start") { Task { await service.start() } }
                    .disabled(service.status == .running)
                Button("Stop") { Task { await service.stop() } }
                    .disabled(service.status == .stopped)
            }
        }
        .navigationTitle("Node Status")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            // Poll while the screen is visible; the task is cancelled on exit.
            while !Task.isCancelled {
                await service.refresh()
                do {
                    try await Task.sleep(for: Self.refreshInterval)
                } catch {
                    return
                }
            }
        }
    }

    private func row(_ label: String, _ value: String, id: String? = nil) -> some View {
        LabeledContent(label) {
            Text(value)
                .font(.body.monospacedDigit())
                .foregroundStyle(.primary)
                .accessibilityIdentifier(id ?? "")
        }
    }

    private func shortHash(_ hash: String) -> String {
        guard hash.count > 18 else { return hash.isEmpty ? "—" : hash }
        return "\(hash.prefix(10))…\(hash.suffix(6))"
    }
}
