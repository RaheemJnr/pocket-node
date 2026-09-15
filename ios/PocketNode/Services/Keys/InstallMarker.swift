import Foundation

/// Reinstall hygiene.
///
/// Keychain items outlive the app on iOS: deleting Pocket Node and installing it
/// again leaves the previous wallet's envelope and wrapping key behind. A fresh
/// install must not inherit them, or a new owner of the phone would be looking
/// at someone else's wallet state, and our own onboarding would see a wallet it
/// never created.
///
/// `UserDefaults` does not survive deletion, so its emptiness is the signal: no
/// marker means this install has never run, and anything still in the Keychain
/// belongs to a previous one.
///
/// ## Hazards
///
/// This type deletes wallets. Three changes would make it delete a live one, and
/// none of them looks dangerous in a diff:
///
/// 1. **Renaming ``defaultsKey``.** The marker would read as absent on a device
///    that has one, and the next launch would wipe a wallet in use. If the key
///    ever has to change, migrate the old value across first.
/// 2. **Moving the defaults.** Passing an App Group suite, or any
///    `UserDefaults` other than the one the marker was written to, has exactly
///    the same effect as a rename.
/// 3. **Shipping without the call.** If `AppContainer` stops invoking
///    ``wipeIfFreshInstall(keychain:wrapper:)``, nothing fails visibly; a
///    reinstall simply inherits the previous install's wallet. The wipe has no
///    test that runs in production, so the call site is the only guard.
///
/// ``currentVersion`` exists so a later release can re-run the wipe deliberately
/// by bumping it. Bumping it wipes every device on upgrade, so it is a decision,
/// not a tidy-up.
struct InstallMarker {
    static let defaultsKey = "com.rjnr.pocketnode.installMarkerVersion"

    /// The marker this build writes. A stored value below it means the wipe has
    /// not run for this generation of the store.
    static let currentVersion = 1

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// The marker version stored on this device, or 0 when there is none.
    var storedVersion: Int {
        defaults.integer(forKey: Self.defaultsKey)
    }

    /// Whether this install has already been recorded for the current version.
    var isRecorded: Bool {
        storedVersion >= Self.currentVersion
    }

    /// Wipes leftover key material when the marker is absent, then records the
    /// marker so later launches leave the wallet alone.
    ///
    /// Returns true when it actually deleted something. Two rules keep this from
    /// destroying data it should not:
    ///
    /// - Nothing is deleted unless there is something to delete, so a device with
    ///   no wallet just records the marker.
    /// - The marker is written only once the wipe has fully succeeded. If a
    ///   delete fails, the marker stays absent and the next launch tries again,
    ///   rather than leaving a stranded wallet marked as handled.
    @discardableResult
    func wipeIfFreshInstall(keychain: any KeyValueStoring, wrapper: any KeyWrapping) -> Bool {
        guard !isRecorded else { return false }

        let hasEnvelope = (try? keychain.contains(account: WalletKeyAccount.envelope)) ?? true
        guard hasEnvelope || wrapper.hasKey else {
            record()
            return false
        }

        do {
            try keychain.deleteAll()
            try wrapper.deleteKey()
        } catch {
            // Leave the marker absent: the wipe has to be retried next launch.
            return false
        }

        record()
        return true
    }

    private func record() {
        defaults.set(Self.currentVersion, forKey: Self.defaultsKey)
    }
}
