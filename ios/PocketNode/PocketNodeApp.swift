import SwiftUI

@main
struct PocketNodeApp: App {
    @State private var container = AppContainer()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(container)
        }
    }
}
