import SwiftUI
import UIKit

/// Covers a view whenever it should not be visible: the app is not in the
/// foreground (`scenePhase != .active`, which also covers the app-switcher
/// snapshot), or the screen is being captured — mirrored, recorded, or shown
/// in the system screenshot editor (`UIScreen.main.isCaptured`).
///
/// `BackupView` applies this to the two steps that show the recovery phrase.
/// It is a supplement to `onBackgrounded()` wiping the words outright, not a
/// replacement for it: this hides the *current* frame instantly (before the
/// background transition finishes and the view model's wipe lands), while the
/// wipe is what keeps the phrase out of memory once the scene actually
/// backgrounds.
struct PrivacyShield: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase
    @State private var isCaptured = UIScreen.main.isCaptured

    func body(content: Content) -> some View {
        content
            .privacySensitive()
            .overlay {
                if isShielded {
                    shield
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
                isCaptured = UIScreen.main.isCaptured
            }
    }

    private var isShielded: Bool {
        scenePhase != .active || isCaptured
    }

    private var shield: some View {
        Rectangle()
            .fill(.background)
            .overlay {
                VStack(spacing: 8) {
                    Image(systemName: "eye.slash.fill")
                        .font(.largeTitle)
                    Text("Hidden for your privacy")
                        .font(.subheadline)
                }
                .foregroundStyle(.secondary)
            }
            .accessibilityIdentifier("backup.privacyShield")
    }
}

extension View {
    /// Applies ``PrivacyShield`` to this view.
    func privacyShielded() -> some View {
        modifier(PrivacyShield())
    }
}
