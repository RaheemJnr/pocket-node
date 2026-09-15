import SwiftUI

/// The settings list. One section for now, mirroring the security rows Android
/// keeps in `ui/screens/settings/SecuritySettingsScreen.kt`: reach the backup
/// flow from somewhere permanent, and turn biometric unlock on or off.
///
/// The backup row leads to the same `BackupView` as the Home banner, and that
/// screen runs its own re-auth gate (`isOnboarding: false`), so nothing here
/// has to guard it.
struct SettingsView: View {
    let auth: AuthService
    let onBackUp: () -> Void

    var body: some View {
        List {
            Section {
                Button(action: onBackUp) {
                    HStack {
                        Label("Back up wallet", systemImage: "doc.text")
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("settings.backup")

                // Only when the sensor can actually be prompted: an unenrolled
                // or locked-out device would fail every attempt, and a toggle
                // that cannot do anything is worse than no toggle.
                if auth.biometricAvailability.canPrompt {
                    Toggle(isOn: biometricBinding) {
                        Label(
                            "Unlock with \(auth.biometricAvailability.displayName)",
                            systemImage: auth.biometricAvailability.symbolName
                        )
                    }
                    .accessibilityIdentifier("settings.biometrics")
                }
            } header: {
                Text("Security")
            } footer: {
                Text("Your recovery phrase is shown only after you confirm it is you.")
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings.root")
    }

    /// Written through `AuthService` rather than straight to preferences, so
    /// the session's view of biometrics and the stored setting cannot drift.
    private var biometricBinding: Binding<Bool> {
        Binding(
            get: { auth.isBiometricEnabled },
            set: { auth.isBiometricEnabled = $0 }
        )
    }
}
