import SwiftUI

/// The app-wide unlock gate, shown by `RootView` whenever the session is
/// locked and a PIN exists.
///
/// Mirrors Android's `AuthScreen` plus `PinEntryScreen`: biometrics when the
/// user has opted in, the 6-digit pad either way, the attempts remaining, a
/// live countdown while locked out, and the reset-and-restore explanation once
/// the permanent lock is reached.
///
/// Dependencies are passed rather than read from the environment so the view
/// can be rendered against a throwaway Keychain in a test.
struct LockView: View {
    let auth: AuthService

    /// Direct instead of `auth.pin` so the observation is on the service whose
    /// properties this view actually reads.
    private var pin: PinService { auth.pin }

    @State private var digits = ""
    @State private var errorMessage: String?
    @State private var errorToken = 0
    @State private var isVerifying = false
    /// Drives the lockout countdown. Only runs while a lockout is active.
    @State private var ticker: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 32) {
            header

            if pin.isPermanentlyLocked {
                permanentLock
            } else {
                PinEntryView(
                    title: "Enter PIN",
                    subtitle: nil,
                    footnote: footnote,
                    error: errorMessage,
                    errorToken: errorToken,
                    isBusy: isVerifying,
                    isEnabled: !pin.isLockedOut,
                    digits: $digits,
                    onComplete: { entered in Task { await submit(entered) } }
                )

                if auth.canUseBiometrics {
                    Button {
                        Task { await auth.unlockWithBiometrics() }
                    } label: {
                        Label(
                            "Unlock with \(auth.biometricAvailability.displayName)",
                            systemImage: auth.biometricAvailability.symbolName
                        )
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("lock.biometric")
                }
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 32)
        .padding(.top, 48)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityIdentifier("lock.root")
        .task {
            await pin.refresh()
            startTickerIfNeeded()
            // Offer the sensor straight away, as Android does on `AuthScreen`,
            // so the common case is one glance rather than six taps.
            if auth.canUseBiometrics {
                await auth.unlockWithBiometrics()
            }
        }
        .onDisappear { stopTicker() }
    }

    private var header: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock.fill")
                .font(.system(size: 36))
                .foregroundStyle(.secondary)
                .accessibilityLabel("Locked")

            Text("Pocket Node")
                .font(.title.weight(.semibold))

            Text("Wallet is locked")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if let message = auth.biometricMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    /// Reached after 10 cumulative failures. There is no timer to wait out and
    /// no reset button here: recovery is reinstalling and restoring from the
    /// recovery phrase, which onboarding (#515) owns.
    private var permanentLock: some View {
        VStack(spacing: 16) {
            Text("Wallet locked")
                .font(.title3.weight(.semibold))

            Text("Too many incorrect PIN attempts. To regain access, reset this wallet and restore it from your recovery phrase. Your funds are safe as long as you have your recovery phrase.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.top, 16)
        .accessibilityIdentifier("lock.permanent")
    }

    /// The neutral status line under the dots: the countdown while locked out,
    /// otherwise how many tries are left.
    private var footnote: String? {
        if pin.isLockedOut {
            return "Too many attempts. Try again in \(pin.lockoutRemainingSeconds)s"
        }
        if errorMessage != nil { return nil }
        let remaining = pin.remainingAttempts
        guard remaining < PinService.maxAttempts else { return nil }
        return remaining == 1 ? "1 attempt remaining" : "\(remaining) attempts remaining"
    }

    private func submit(_ entered: String) async {
        guard !isVerifying else { return }
        isVerifying = true
        errorMessage = nil
        let unlocked = await auth.unlock(pin: entered)
        isVerifying = false
        digits = ""

        guard !unlocked else {
            stopTicker()
            return
        }

        errorToken += 1
        errorMessage = failureMessage()
        startTickerIfNeeded()
    }

    private func failureMessage() -> String {
        if pin.isPermanentlyLocked {
            return "Too many attempts. Reset and restore from your recovery phrase to regain access."
        }
        if pin.isLockedOut {
            return "Too many attempts. Try again in \(pin.lockoutRemainingSeconds)s"
        }
        let remaining = pin.remainingAttempts
        if remaining == 0 {
            return "No attempts left right now. Wait for the timer, or reset and restore from your recovery phrase."
        }
        return remaining == 1
            ? "Wrong PIN. 1 attempt remaining."
            : "Wrong PIN. \(remaining) attempts remaining."
    }

    /// Re-reads the policy once a second so the countdown ticks down and the
    /// pad re-enables the moment the lockout expires. A refresh is six Keychain
    /// reads and no KDF, and the task stops as soon as the lockout is over.
    private func startTickerIfNeeded() {
        guard pin.isLockedOut, ticker == nil else { return }
        ticker = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                await pin.refresh()
                if !pin.isLockedOut {
                    // The wait is over; clear the stale countdown text.
                    errorMessage = nil
                    stopTicker()
                    return
                }
            }
        }
    }

    private func stopTicker() {
        ticker?.cancel()
        ticker = nil
    }
}
