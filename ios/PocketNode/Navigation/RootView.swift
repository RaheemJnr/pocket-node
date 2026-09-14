import SwiftUI

/// Routes mirror the Android `NavGraph.kt` names so the two apps stay readable
/// side by side. M1 ships Home and NodeStatus only.
enum Route: Hashable {
    case nodeStatus
}

struct RootView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.colorScheme) private var colorScheme

    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            HomeView()
                .navigationTitle("Pocket Node")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            path.append(Route.nodeStatus)
                        } label: {
                            Label("Node status", systemImage: "antenna.radiowaves.left.and.right")
                        }
                        .accessibilityIdentifier("root.nodeStatus")
                    }
                }
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .nodeStatus:
                        NodeStatusView()
                    }
                }
        }
        .onChange(of: colorScheme, initial: true) {
            container.theme = Theme.forScheme(colorScheme)
        }
    }
}
