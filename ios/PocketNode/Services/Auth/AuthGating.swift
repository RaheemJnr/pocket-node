import Foundation

/// The re-authentication gate in front of one sensitive action.
///
/// `AuthService` conforms directly: its `requireAuth(reason:)` has this exact
/// shape. Tests substitute a stub so the gate can be exercised without a PIN
/// pad or a biometric prompt.
///
/// It lives here rather than beside its first caller because it no longer has
/// one first caller. `BackupViewModel` asks it before decrypting the recovery
/// phrase and `SendService` asks it before reaching a key, and a service
/// depending on a protocol declared inside a screen is a layering the code map
/// is right to flag. The two conformances and the stub are unchanged; only the
/// file moved.
@MainActor
protocol AuthGating: AnyObject {
    func requireAuth(reason: String) async -> Bool
    /// Bumped on every session lock. A reveal records it before prompting and
    /// drops its result if it moved, so a phrase never lands behind the lock.
    ///
    /// `SendService` does not check it, on purpose: a send the user approved
    /// should finish even if the app is backgrounded and locks while it signs
    /// and broadcasts, and it puts no secret on screen for the lock to hide.
    var lockGeneration: Int { get }
}

extension AuthService: AuthGating {}
