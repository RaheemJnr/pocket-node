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

    @Environment(\.scenePhase) private var scenePhase

    @State private var digits = ""
    @State private var errorMessage: String?
    @State private var errorToken = 0
    @State private var isVerifying = false
    /// Drives the lockout countdown. Only runs while a lockout is active.
    @State private var ticker: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 32) {
            header

            // Until the failure state has been read, the permanent-lock flag
            // is a placeholder `false` (`PinService.hasLoadedState`): the pad
            // would flash on the first frame of a cold start after a
            // permanent lock. Neither the pad nor the biometric button shows
            // until the real state is in.
            if !pin.hasLoadedState {
                loading
            } else if pin.isPermanentlyLocked {
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
        // See `HomeView`: without this the identifier would be pushed down
        // onto every key of the pin pad, replacing `pin.key.*`.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("lock.root")
        .task {
            await auth.refresh()
            startTickerIfNeeded()
            // Offer the sensor straight away, as Android does on `AuthScreen`,
            // so the common case is one glance rather than six taps. Skipped
            // once the PIN is permanently locked, which `canUseBiometrics`
            // already accounts for.
            if auth.canUseBiometrics {
                await auth.unlockWithBiometrics()
            }
        }
        // A store that could not be read at launch (a prewarm before the first
        // device unlock) becomes readable once the user unlocks the phone, and
        // the app is brought forward right after. Re-reading here is what turns
        // an `unknown` presence into a real answer without a relaunch.
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task { await auth.refresh() }
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

    /// A neutral stand-in while the PIN's failure state is read.
    private var loading: some View {
        ProgressView()
            .padding(.top, 16)
            .accessibilityIdentifier("lock.loading")
    }

    /// Reached after 10 cumulative failures. There is no timer to wait out and
    /// no reset button here: recovery is reinstalling and restoring from the
    /// recovery phrase, which onboarding (#515) owns.
    private var permanentLock: some View {
        VStack(spacing: 16) {
            Text(AuthCopy.permanentLockTitle)
                .font(.title3.weight(.semibold))

            Text(AuthCopy.permanentLockBody)
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
        if pin.isLockedOut { return AuthCopy.countdown(pin.lockoutRemainingSeconds) }
        if errorMessage != nil { return nil }
        let remaining = pin.remainingAttempts
        guard remaining < PinService.maxAttempts else { return nil }
        return AuthCopy.attemptsRemaining(remaining)
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
        errorMessage = AuthCopy.pinFailure(auth: auth)
        startTickerIfNeeded()
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
