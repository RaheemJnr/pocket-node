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
/// - Going to the background locks immediately, so whoever opens the app next
///   meets the lock screen rather than the wallet. It does not scrub the app
///   switcher thumbnail: iOS snapshots the window as the app is backgrounded,
///   and this lock races that snapshot rather than beating it. Blurring the
///   snapshot is a separate job (a cover window on `.inactive`) and is not
///   done yet.
/// - `.inactive` does not lock: it fires for a notification banner, a control
///   centre pull and a half-swiped app switcher, and locking on it would throw
///   the user out several times a session for nothing.
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

    /// Set when the PIN store refused to record an attempt, so nothing was
    /// checked. Distinct from a wrong PIN: the user did nothing wrong and has
    /// lost no attempt. Cleared on the next try.
    private(set) var storeMessage: String?

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
    /// opted in, the sensor can actually run right now, and the PIN is not
    /// permanently locked.
    ///
    /// The permanent lock is the point: at 10 cumulative failures the wallet is
    /// recoverable only from the recovery phrase, and a face that still unlocks
    /// it would hand the whole escalation schedule back to anyone holding the
    /// phone. A *temporary* lockout still allows biometrics, matching Android,
    /// because that one is about slowing PIN guessing.
    var canUseBiometrics: Bool {
        isBiometricEnabled && biometrics.availability.canPrompt && !pin.isPermanentlyLocked
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
        // seeds its presence from a non-prompting Keychain lookup in its own
        // init. Anything but a confirmed absence starts locked, so a store that
        // cannot be read yet (a prewarm launch before the first device unlock)
        // does not open the wallet.
        self.state = pin.pinPresence == .absent ? .noPin : .locked
    }

    // MARK: - Lock gate

    /// Re-reads the PIN state and re-derives ``state``.
    ///
    /// This can only ever tighten the gate. Nothing it reads is proof of who is
    /// holding the phone, so it never reaches ``State/unlocked``: only a
    /// verified PIN, a successful biometric result, or setting the PIN in this
    /// session does that. A PIN that appears from elsewhere (an unreadable
    /// store becoming readable, or another session's write) locks.
    func refresh() async {
        await pin.refresh()
        switch pin.pinPresence {
        case .absent:
            state = .noPin
        case .present, .unknown:
            if state == .noPin { state = .locked }
        }
    }

    /// True while the wallet must stay behind ``LockView``. Anything that is
    /// not a confirmed "no PIN" or an authenticated session gates.
    var isGated: Bool {
        state != .noPin && state != .unlocked
    }

    /// Locks the session. A no-op when there is no PIN to unlock with.
    func lock() {
        guard state == .unlocked else { return }
        state = .locked
        biometricMessage = nil
        storeMessage = nil
    }

    /// Marks the session authenticated. Only for callers that have already
    /// proved it: ``unlock(pin:)``, ``unlockWithBiometrics()`` and onboarding.
    func markUnlocked() {
        guard state != .noPin else { return }
        state = .unlocked
        biometricMessage = nil
        storeMessage = nil
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
    ///
    /// A store that cannot record the attempt leaves the session locked and
    /// sets ``storeMessage`` rather than reporting a wrong PIN: nothing was
    /// hashed, and the user has not used up a try.
    @discardableResult
    func unlock(pin entered: String) async -> Bool {
        storeMessage = nil
        do {
            guard try await pin.verify(entered) else { return false }
        } catch {
            storeMessage = Self.storeUnavailableMessage
            return false
        }
        markUnlocked()
        return true
    }

    // MARK: - PIN management

    /// Sets the PIN and treats the session as authenticated, since the user
    /// just chose the secret.
    ///
    /// On failure the state is re-derived from what is actually stored rather
    /// than left optimistic: if `PinService`'s cleanup could not remove a
    /// partially written PIN, the app locks behind it instead of believing
    /// there is none.
    func setPin(_ value: String) async throws {
        do {
            try await pin.setPin(value)
        } catch {
            state = pin.pinPresence == .absent ? .noPin : .locked
            throw error
        }
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
    ///
    /// A store failure leaves the challenge open with ``storeMessage`` set, the
    /// same as ``unlock(pin:)``: the user may retry, and the caller is not told
    /// anything was approved.
    @discardableResult
    func answerChallenge(pin entered: String) async -> Bool {
        storeMessage = nil
        let matched: Bool
        do {
            matched = try await pin.verify(entered)
        } catch {
            storeMessage = Self.storeUnavailableMessage
            return false
        }
        if matched { resolveChallenge(granted: true) }
        return matched
    }

    // MARK: - Copy

    static let unlockReason = "Unlock your Pocket Node wallet"

    static let storeUnavailableMessage = "Could not record this attempt. Try again."

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
        case .systemError:
            return "Biometrics could not run. Enter your PIN."
        }
    }
}
