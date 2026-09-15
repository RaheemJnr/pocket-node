import Foundation

/// Reinstall hygiene.
///
/// Keychain items outlive the app on iOS: deleting Pocket Node and installing it
/// again leaves the previous wallet's ciphertext and wrapped data key behind.
/// A fresh install must not inherit them, or a new owner of the phone would be
/// looking at someone else's wallet state, and our own onboarding would see a
/// wallet it never created.
///
/// `UserDefaults` does not survive deletion, so its emptiness is the signal: no
/// marker means this install has never run, and anything still in the Keychain
/// belongs to a previous one and gets wiped.
struct InstallMarker {
    static let defaultsKey = "com.rjnr.pocketnode.installMarker"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Whether this install has been recorded already.
    var isRecorded: Bool {
        defaults.bool(forKey: Self.defaultsKey)
    }

    /// Wipes leftover key material when the marker is absent, then records the
    /// marker so later launches leave the wallet alone.
    ///
    /// Returns true when it wiped. Failures are swallowed on purpose: a wipe
    /// that cannot run must not stop the app from launching, and the marker is
    /// only written once the wipe has actually been attempted.
    @discardableResult
    func wipeIfFreshInstall(keychain: KeychainStore, wrapper: any KeyWrapping) -> Bool {
        guard !isRecorded else { return false }

        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        defaults.set(true, forKey: Self.defaultsKey)
        return true
    }
}
