import SwiftUI

/// Manual dependency injection (design D0): one object graph created at launch
/// and handed down through the SwiftUI environment. No DI framework on iOS.
@MainActor
@Observable
final class AppContainer {
    let lightClient: LightClientService

    /// Kept in sync with the system color scheme by `RootView`.
    var theme: Theme = .light

    init() {
        self.lightClient = LightClientService()
    }
}
