import Foundation
import LocalAuthentication

/// What the device can offer right now, as one value the UI can switch on.
///
/// `notEnrolled` and `lockedOut` are kept apart from `unavailable` because they
/// are recoverable by the user: one by enrolling a face or finger in Settings,
/// the other by unlocking the device with its passcode. `unavailable` covers
/// hardware that is absent or a policy the system refuses for any other reason.
enum BiometricAvailability: Equatable, Sendable {
    case faceID
    case touchID
    case notEnrolled
    case lockedOut
    case unavailable

    /// Whether a prompt is worth raising. `notEnrolled` and `lockedOut` would
    /// only fail, so the caller should go straight to the PIN.
    var canPrompt: Bool {
        self == .faceID || self == .touchID
    }

    /// The system's own name for the sensor, for button and prompt copy.
    var displayName: String {
        switch self {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .notEnrolled, .lockedOut, .unavailable: return "Biometrics"
        }
    }

    /// SF Symbol matching the sensor, for the unlock button.
    var symbolName: String {
        switch self {
        case .faceID: return "faceid"
        case .touchID: return "touchid"
        case .notEnrolled, .lockedOut, .unavailable: return "lock"
        }
    }
}

/// Why a biometric prompt did not succeed.
///
/// `cancelled` and `fallbackRequested` are both "the user dismissed it", but
/// they mean opposite things to the caller: a cancel is a refusal and aborts
/// whatever asked for auth, a fallback is a request to be shown the PIN pad
/// instead. Mirrors the Android split between `BiometricPrompt`'s
/// `ERROR_USER_CANCELED` and its negative button.
enum BiometricError: Error, Equatable, Sendable {
    /// The user dismissed the prompt without asking for another route.
    case cancelled
    /// The user tapped the fallback button; show the PIN.
    case fallbackRequested
    /// Too many failed attempts; biometry is locked until a device unlock.
    case lockedOut
    /// No face or finger is enrolled.
    case notEnrolled
    /// The sensor ran and did not recognise the user.
    case failed
    /// Biometry is not available on this device or is disabled by policy.
    case unavailable
    /// Any other `LAError`, carried as its raw code for diagnostics.
    case other(Int)
}

/// The seam `AuthService` depends on, so its state machine can be tested
/// without a sensor. The only production conformer is ``BiometricService``.
protocol BiometricAuthenticating: Sendable {
    var availability: BiometricAvailability { get }
    func authenticate(reason: String) async -> Result<Void, BiometricError>
}

/// `LocalAuthentication` wrapper for Face ID and Touch ID.
///
/// Deliberately `.deviceOwnerAuthenticationWithBiometrics` rather than
/// `.deviceOwnerAuthentication`: the device passcode is not an acceptable
/// fallback for a wallet, because anyone who has shoulder-surfed the phone's
/// passcode would then own the wallet too. Our own 6-digit app PIN is the
/// fallback, and it is a separate secret with its own lockout schedule.
///
/// A fresh `LAContext` per call. Reusing one caches the last successful
/// evaluation, which would let a second `authenticate` return without
/// prompting at all.
struct BiometricService: BiometricAuthenticating {
    /// Shown on the prompt's fallback button. Tapping it returns
    /// ``BiometricError/fallbackRequested`` rather than raising the device
    /// passcode sheet, because the policy above is biometrics-only.
    static let fallbackTitle = "Use PIN"

    var availability: BiometricAvailability {
        let context = LAContext()
        var error: NSError?
        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            switch context.biometryType {
            case .faceID: return .faceID
            case .touchID: return .touchID
            default: return .unavailable
            }
        }
        // `biometryType` is populated by the `canEvaluatePolicy` call above even
        // when it returns false, so the lockout case could still name the
        // sensor; the caller only needs the reason, so it is not read here.
        switch LAError.Code(rawValue: error?.code ?? 0) {
        case .biometryNotEnrolled: return .notEnrolled
        case .biometryLockout: return .lockedOut
        default: return .unavailable
        }
    }

    func authenticate(reason: String) async -> Result<Void, BiometricError> {
        let context = LAContext()
        context.localizedFallbackTitle = Self.fallbackTitle
        context.localizedCancelTitle = "Cancel"

        return await withCheckedContinuation { continuation in
            context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { success, error in
                // Mapped inside the callback so only the `Sendable` result
                // crosses back; the `NSError` never leaves this closure.
                if success {
                    continuation.resume(returning: .success(()))
                } else {
                    continuation.resume(returning: .failure(Self.map(error)))
                }
            }
        }
    }

    private static func map(_ error: Error?) -> BiometricError {
        guard let nsError = error as NSError? else { return .failed }
        switch LAError.Code(rawValue: nsError.code) {
        case .userCancel, .appCancel, .systemCancel: return .cancelled
        case .userFallback: return .fallbackRequested
        case .biometryLockout: return .lockedOut
        case .biometryNotEnrolled: return .notEnrolled
        case .authenticationFailed: return .failed
        case .biometryNotAvailable, .passcodeNotSet: return .unavailable
        case .none: return .failed
        case .some(let code): return .other(code.rawValue)
        }
    }
}
