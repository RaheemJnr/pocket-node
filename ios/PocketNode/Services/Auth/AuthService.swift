import Foundation
import SwiftUI

/// A request for the user to prove who they are before something sensitive
/// happens, raised by ``AuthService/requireAuth(reason:)``. The root view
/// presents it as a sheet; answering it resumes the caller.
struct AuthChallenge: Identifiable, Equatable {
    let id = UUID()
    /// Shown above the PIN pad, and used as the biometric prompt's reason.
    let reason: String
}

/// Session state and the lock gate.
///
/// Mirrors the Android rules:
///
/// - A cold start is always locked when a PIN exists (`AuthScreen`).
/// - Going to the background locks immediately, so the wallet is never
///   readable from the app switcher or by whoever picks the phone up next.
///   `.inactive` does not lock: it fires for a notification banner, a control
///   centre pull and the app switcher preview itself, and locking on it would
///   throw the user out several times a session for nothing.
/// - Biometrics are opt-in and fall back to the app PIN, never to the device
///   passcode (see ``BiometricService``).
///
/// The service never presents UI itself. ``requireAuth(reason:)`` publishes a
/// ``challenge`` and suspends; whatever is showing the sheet resolves it with
/// ``resolveChallenge(granted:)``.
@MainActor
@Observable
final class AuthService {
    enum State: Equatable {
        /// No PIN is configured, so there is nothing to gate. Onboarding
        /// (#515) is what leaves this state.
        case noPin
        /// A PIN exists and the session is not authenticated.
        case locked
        /// Authenticated for this session.
        case unlocked
    }

    private(set) var state: State

    /// Outstanding ``requireAuth(reason:)`` request, if any.
    private(set) var challenge: AuthChallenge?

    /// Set when a biometric attempt failed for a reason worth telling the user
    /// about. Cleared on the next attempt and on a successful unlock.
    private(set) var biometricMessage: String?

    let pin: PinService
    let biometrics: any BiometricAuthenticating

    private let preferences: UserDefaultsPreferences
    private var challengeContinuation: CheckedContinuation<Bool, Never>?

    /// Whether the user has opted into unlocking with Face ID or Touch ID.
    /// Stored under the same key Android uses (`biometric_enabled`).
    var isBiometricEnabled: Bool {
        get { preferences.isBiometricEnabled }
        set { preferences.isBiometricEnabled = newValue }
    }

    /// Whether a send has to be re-authenticated. Not consumed yet; the send
    /// flow lands in a later M2 issue, and the flag is here so the setting and
    /// its key name match Android from the start.
    var isAuthBeforeSendEnabled: Bool {
        get { preferences.isAuthBeforeSendEnabled }
        set { preferences.isAuthBeforeSendEnabled = newValue }
    }

    var biometricAvailability: BiometricAvailability { biometrics.availability }

    /// True when the unlock screen should offer the biometric button: the user
    /// opted in and the sensor can actually run right now.
    var canUseBiometrics: Bool {
        isBiometricEnabled && biometrics.availability.canPrompt
    }

    init(
        pin: PinService,
        biometrics: any BiometricAuthenticating = BiometricService(),
        preferences: UserDefaultsPreferences
    ) {
        self.pin = pin
        self.biometrics = biometrics
        self.preferences = preferences
        // Synchronous so the very first frame is already gated; `PinService`
        // seeds `hasPin` from a non-prompting Keychain lookup in its own init.
        self.state = pin.hasPin ? .locked : .noPin
    }

    // MARK: - Lock gate

    /// Re-reads the PIN state and re-derives ``state``. Safe to call on every
    /// appearance: it never unlocks a locked session.
    func refresh() async {
        await pin.refresh()
        if !pin.hasPin {
            state = .noPin
        } else if state == .noPin {
            // A PIN appeared (onboarding just set one), so the session it was
            // set in stays authenticated rather than being thrown out.
            state = .unlocked
        }
    }

    /// Locks the session. A no-op when there is no PIN to unlock with.
    func lock() {
        guard state == .unlocked else { return }
        state = .locked
        biometricMessage = nil
    }

    /// Marks the session authenticated. Only for callers that have already
    /// proved it: ``unlock(pin:)``, ``unlockWithBiometrics()`` and onboarding.
    func markUnlocked() {
        guard state != .noPin else { return }
        state = .unlocked
        biometricMessage = nil
    }

    /// The background rule. Only `.background` locks.
    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .background:
            lock()
        case .inactive, .active:
            break
        @unknown default:
            break
        }
    }

    // MARK: - Unlocking

    /// Raises the biometric prompt and unlocks on success.
    ///
    /// Returns false for every other outcome, including a cancel: the PIN pad
    /// is already on screen behind the prompt, so there is nothing to fall back
    /// to and nothing to report for a deliberate dismissal.
    @discardableResult
    func unlockWithBiometrics() async -> Bool {
        guard canUseBiometrics, state == .locked else { return false }
        biometricMessage = nil
        switch await biometrics.authenticate(reason: Self.unlockReason) {
        case .success:
            markUnlocked()
            return true
        case .failure(let error):
            biometricMessage = Self.message(for: error)
            return false
        }
    }

    /// Verifies `pin` and unlocks on a match. The lockout schedule is applied
    /// by the shared policy, so a wrong PIN here escalates exactly as it does
    /// on Android.
    @discardableResult
    func unlock(pin entered: String) async -> Bool {
        guard await pin.verify(entered) else { return false }
        markUnlocked()
        return true
    }

    // MARK: - PIN management

    /// Sets the PIN and treats the session as authenticated, since the user
    /// just chose the secret.
    func setPin(_ value: String) async throws {
        try await pin.setPin(value)
        state = .unlocked
    }

    /// Removes the PIN and the biometric opt-in with it: unlocking with a face
    /// and no second factor to fall back to is not a state worth having.
    func removePin() async {
        await pin.removePin()
        isBiometricEnabled = false
        state = .noPin
    }

    // MARK: - Step-up auth for a single action

    /// Asks the user to prove who they are before something sensitive, such as
    /// revealing the recovery phrase (#516) or confirming a send.
    ///
    /// Biometrics first when enabled. A cancel is a refusal and returns false
    /// straight away; every other biometric outcome falls through to the PIN,
    /// because those are failures of the sensor rather than of the user's
    /// intent. With no PIN configured there is nothing to check and it returns
    /// true.
    func requireAuth(reason: String) async -> Bool {
        guard pin.hasPin else { return true }

        if canUseBiometrics {
            switch await biometrics.authenticate(reason: reason) {
            case .success:
                return true
            case .failure(.cancelled):
                return false
            case .failure:
                break
            }
        }

        // Only one challenge can be outstanding; a second caller is refused
        // rather than silently stealing the first one's continuation.
        guard challengeContinuation == nil else { return false }
        return await withCheckedContinuation { continuation in
            challengeContinuation = continuation
            challenge = AuthChallenge(reason: reason)
        }
    }

    /// Answers the outstanding ``challenge``. Called by the sheet presenting it,
    /// with false on dismissal.
    func resolveChallenge(granted: Bool) {
        challenge = nil
        let continuation = challengeContinuation
        challengeContinuation = nil
        continuation?.resume(returning: granted)
    }

    /// Verifies `entered` as the answer to the outstanding challenge.
    @discardableResult
    func answerChallenge(pin entered: String) async -> Bool {
        let matched = await pin.verify(entered)
        if matched { resolveChallenge(granted: true) }
        return matched
    }

    // MARK: - Copy

    static let unlockReason = "Unlock your Pocket Node wallet"

    private static func message(for error: BiometricError) -> String? {
        switch error {
        case .cancelled, .fallbackRequested:
            return nil
        case .lockedOut:
            return "Biometrics are locked. Enter your PIN to unlock."
        case .notEnrolled:
            return "No face or fingerprint is enrolled on this device. Enter your PIN."
        case .failed:
            return "Not recognised. Try again or enter your PIN."
        case .unavailable, .other:
            return "Biometrics are unavailable. Enter your PIN."
        }
    }
}
