import SwiftUI

/// The settings list. A Security section mirroring the rows Android keeps in
/// `ui/screens/settings/SecuritySettingsScreen.kt` (reach the backup flow from
/// somewhere permanent, and turn biometric unlock on or off), and a Wallet
/// section with "Export restore hint" (#559).
///
/// The backup row leads to the same `BackupView` as the Home banner, and that
/// screen runs its own re-auth gate (`isOnboarding: false`), so nothing here
/// has to guard it.
struct SettingsView: View {
    let auth: AuthService
    /// The restore hint export (#559). Owned by `AppContainer`, not by this
    /// view, so a sealed file waiting for the save sheet survives the screen
    /// being torn down and rebuilt (a lock replaces the wallet stack).
    let restoreHint: RestoreHintExportViewModel
    let onBackUp: () -> Void

    @Environment(\.scenePhase) private var scenePhase

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

            restoreHintSection
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        // See `HomeView`: without this the identifier would be pushed down
        // onto every row, replacing `settings.backup` / `settings.biometrics`.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("settings.root")
        .fileExporter(
            isPresented: saverBinding,
            document: restoreHint.pendingDocument,
            contentType: .json,
            defaultFilename: restoreHint.pendingDocument?.fileName
        ) { result in
            // A cancelled sheet reports a failure (`CocoaError.userCancelled`),
            // so every way the sheet ends lands here.
            switch result {
            case .success: restoreHint.saveSucceeded()
            case .failure: restoreHint.saveDidNotComplete()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { restoreHint.onBackgrounded() }
        }
    }

    /// Android's Settings > Wallet "Export restore hint" row and its
    /// explainer. The export asks for the same confirmation as the phrase
    /// reveal; the view model runs it.
    private var restoreHintSection: some View {
        Section {
            Button {
                Task { await restoreHint.export() }
            } label: {
                HStack {
                    Label(RestoreHintCopy.exportTitle, systemImage: "square.and.arrow.up")
                    Spacer(minLength: 0)
                    if restoreHint.isExporting {
                        ProgressView()
                    }
                }
            }
            .disabled(restoreHint.isExporting)
            .accessibilityIdentifier("settings.restoreHint")

            if let message = restoreHint.message {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("settings.restoreHintMessage")
            }
        } header: {
            Text("Wallet")
        } footer: {
            Text(RestoreHintCopy.exportExplainer)
        }
    }

    /// Up while a sealed file is waiting. The setter does nothing on purpose:
    /// the completion callback above is what ends a save,
    /// and a sheet torn down with the screen (a lock) must leave the file
    /// waiting so the sheet comes back with the screen.
    private var saverBinding: Binding<Bool> {
        Binding(
            get: { restoreHint.isPresentingSaver },
            set: { _ in }
        )
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
