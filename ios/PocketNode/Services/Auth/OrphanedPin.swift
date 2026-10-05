import Foundation

/// A PIN left behind with no wallet for it to protect.
///
/// `AppContainer` clears the PIN service on a fresh install, but as a separate
/// step after `InstallMarker` has already recorded the install, so if that
/// delete fails it is never retried. The device then has no wallet and an old
/// PIN: onboarding starts at welcome, and once the user has a wallet,
/// `AuthService.setPin` refuses every PIN because one is already stored. The
/// user could never finish onboarding.
///
/// A PIN with no wallet protects nothing, so it is removed, but only on proof
/// that there really is no wallet: no metadata file and a key envelope the
/// Keychain confirms is absent. A Keychain that cannot be read, or a PIN store
/// that cannot be read, proves nothing and leaves everything alone, since
/// deleting the PIN in front of a wallet that is still there would drop its
/// lock.
enum OrphanedPin {
    /// Deletes the PIN if it is orphaned. Returns true only when a PIN was
    /// found orphaned and the delete succeeded.
    ///
    /// - Parameters:
    ///   - walletMetadataExists: whether `wallet.json` is on disk.
    ///   - keyKeychain: the store holding the wallet's key envelope.
    ///   - pinKeychain: the PIN's own Keychain service.
    ///   - preferences: where the biometric opt-in lives. It goes with the
    ///     PIN, as it does in `AuthService.removePin`: a face standing in for
    ///     a PIN that no longer exists is not a setting worth keeping.
    @discardableResult
    static func removeIfOrphaned(
        walletMetadataExists: Bool,
        keyKeychain: any KeyValueStoring,
        pinKeychain: any KeyValueStoring,
        preferences: UserDefaultsPreferences
    ) -> Bool {
        guard !walletMetadataExists else { return false }
        // `try?` folds a refused lookup into nil, which is not an absence.
        guard let hasEnvelope = try? keyKeychain.contains(account: WalletKeyAccount.envelope),
              !hasEnvelope
        else { return false }
        guard KeychainPinStore.pinPresence(keychain: pinKeychain) == .present else { return false }
        do {
            try pinKeychain.deleteAll()
            preferences.isBiometricEnabled = false
            return true
        } catch {
            return false
        }
    }
}
