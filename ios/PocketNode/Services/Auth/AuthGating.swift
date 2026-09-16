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
}

extension AuthService: AuthGating {}
