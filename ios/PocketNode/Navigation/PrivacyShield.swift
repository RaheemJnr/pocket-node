import SwiftUI
import UIKit

/// Covers the app whenever what is on screen could be leaving the device or the
/// user's hands: an active screen recording or AirPlay mirror, and the moment
/// the scene stops being active, which is when iOS takes the app-switcher
/// snapshot.
///
/// Applied once at the root, so it protects the recovery phrase, the PIN pad
/// and everything else without each screen having to remember to ask. It is not
/// a screenshot block, because iOS has no such API for ordinary views; what it
/// prevents is a recorded or mirrored session and a cached switcher thumbnail
/// showing wallet content.
///
/// `.privacySensitive()` on the individual secret views is the separate,
/// complementary thing: it marks content for SwiftUI's own redaction.
struct PrivacyShield: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    @State private var isCaptured = Self.screenIsCaptured

    /// Padded to the full screen and drawn over everything, including anything
    /// presented in a sheet inside the modified view.
    func body(content: Content) -> some View {
        content
            .overlay {
                if isHidden {
                    shield
                        .transition(.opacity)
                        .accessibilityIdentifier("privacyShield")
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
                isCaptured = Self.screenIsCaptured
            }
    }

    private var isHidden: Bool {
        isCaptured || scenePhase != .active
    }

    private var shield: some View {
        ZStack {
            Rectangle()
                .fill(.background)
            VStack(spacing: 12) {
                Image(systemName: "lock.shield")
                    .font(.system(size: 44))
                    .foregroundStyle(.secondary)
                if isCaptured {
                    Text("Hidden while the screen is being recorded or mirrored.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 48)
                }
            }
        }
        .ignoresSafeArea()
    }

    /// Whether any scene's screen is being captured.
    ///
    /// Read through the connected scenes rather than `UIScreen.main`, which is
    /// deprecated: a scene knows the screen it is actually on.
    @MainActor
    private static var screenIsCaptured: Bool {
        #if DEBUG
        // XCUITest records the screen on a physical device, which counts as
        // capture and would hide the whole app from the UI tests. Debug builds
        // let the UI tests opt out of that one signal; the scene-phase shield
        // stays in force. Not compiled into release.
        if ProcessInfo.processInfo.environment["POCKETNODE_UITEST_ALLOW_CAPTURE"] == "1" {
            return false
        }
        #endif
        return UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .contains { $0.screen.isCaptured }
    }
}

extension View {
    /// See ``PrivacyShield``. Apply at the root, once.
    func privacyShield() -> some View {
        modifier(PrivacyShield())
    }
}
