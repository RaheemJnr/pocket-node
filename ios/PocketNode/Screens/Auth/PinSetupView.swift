import SwiftUI

/// Choosing the app PIN: enter, confirm, then the biometric opt-in.
///
/// Mirrors Android's `InitialPinSetupScreen` and the SETUP/CONFIRM modes of
/// `PinViewModel`, including the "PINs don't match" reset back to the first
/// entry. The biometric step only appears where there is a sensor enrolled to
/// offer, and it is opt-in: nothing is enabled on the user's behalf.
///
/// Onboarding (#515) is what drives this in the real flow; ``onFinished`` is
/// how it learns the PIN is in place.
struct PinSetupView: View {
    let auth: AuthService
    var onFinished: () -> Void = {}

    private enum Step: Equatable {
        case create
        case confirm
        case biometrics
    }

    @State private var step: Step = .create
    @State private var first = ""
    @State private var digits = ""
    @State private var errorMessage: String?
    @State private var errorToken = 0
    @State private var isSaving = false

    var body: some View {
        VStack(spacing: 32) {
            switch step {
            case .create, .confirm:
                PinEntryView(
                    title: step == .create ? "Create PIN" : "Confirm PIN",
                    subtitle: step == .create ? "Choose a 6-digit PIN" : "Re-enter your PIN",
                    footnote: step == .create ? "A 6-digit PIN that only you know." : nil,
                    error: errorMessage,
                    errorToken: errorToken,
                    isBusy: isSaving,
                    isEnabled: true,
                    digits: $digits,
                    onComplete: { entered in Task { await advance(with: entered) } }
                )
            case .biometrics:
                biometricOptIn
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 32)
        .padding(.top, 48)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        // See `HomeView`: without this the identifier would be pushed down
        // onto every key of the pin pad, replacing `pin.key.*`.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("pinSetup.root")
    }

    private var biometricOptIn: some View {
        VStack(spacing: 20) {
            Image(systemName: auth.biometricAvailability.symbolName)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)

            Text("Unlock with \(auth.biometricAvailability.displayName)?")
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)

            Text("Your PIN still works, and it is what you will use if \(auth.biometricAvailability.displayName) fails.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button {
                auth.isBiometricEnabled = true
                onFinished()
            } label: {
                Text("Turn on \(auth.biometricAvailability.displayName)")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("pinSetup.enableBiometrics")

            Button("Not now") {
                auth.isBiometricEnabled = false
                onFinished()
            }
            .accessibilityIdentifier("pinSetup.skipBiometrics")
        }
        .padding(.top, 24)
    }

    private func advance(with entered: String) async {
        switch step {
        case .create:
            first = entered
            digits = ""
            errorMessage = nil
            step = .confirm

        case .confirm:
            guard entered == first else {
                // Both entries are discarded: keeping the first one would let a
                // typo in it become the stored PIN on the next try.
                first = ""
                digits = ""
                errorToken += 1
                errorMessage = "PINs don't match. Try again."
                step = .create
                return
            }
            await save(entered)

        case .biometrics:
            break
        }
    }

    /// A refused write can be retried. `pinAlreadySet` is also what a PIN
    /// store that cannot be read yet produces, so its copy does not claim a
    /// PIN exists, and points at the relaunch that reads the store again.
    static func message(for error: Error) -> String {
        if error as? AuthServiceError == .pinAlreadySet {
            return pinAlreadySetMessage
        }
        return "Could not save your PIN. Try again."
    }

    static let pinAlreadySetMessage =
        "Could not check your PIN. Close the app and open it again."

    private func save(_ pin: String) async {
        isSaving = true
        defer { isSaving = false }
        do {
            try await auth.setPin(pin)
        } catch {
            first = ""
            digits = ""
            errorToken += 1
            errorMessage = Self.message(for: error)
            step = .create
            return
        }
        first = ""
        digits = ""
        if auth.biometricAvailability.canPrompt {
            step = .biometrics
        } else {
            onFinished()
        }
    }
}
