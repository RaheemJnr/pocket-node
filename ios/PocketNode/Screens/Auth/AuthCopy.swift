import Foundation

/// The strings both PIN surfaces show, in one place.
///
/// `LockView` gates the session and the challenge sheet in `RootView` gates a
/// single action, but the thing they report is identical: how many tries are
/// left, how long a lockout has to run, and what a permanent lock means. Kept
/// together so the two cannot drift, and so the precedence (permanent lock
/// first, then a store failure, then a timed lockout, then the count) is
/// decided once.
///
/// Copy follows the Android strings in `PinEntryScreen.kt` and
/// `res/values/strings.xml`.
enum AuthCopy {
    static let permanentLockTitle = "Wallet locked"

    static let permanentLockBody = "Too many incorrect PIN attempts. To regain access, reset this wallet and restore it from your recovery phrase. Your funds are safe as long as you have your recovery phrase."

    static let permanentLockShort = "Too many attempts. Reset and restore from your recovery phrase to regain access."

    static let outOfAttempts = "No attempts left right now. Wait for the timer, or reset and restore from your recovery phrase."

    static func countdown(_ seconds: Int) -> String {
        "Too many attempts. Try again in \(seconds)s"
    }

    static func attemptsRemaining(_ count: Int) -> String {
        count == 1 ? "1 attempt remaining" : "\(count) attempts remaining"
    }

    static func wrongPin(_ remaining: Int) -> String {
        remaining == 1
            ? "Wrong PIN. 1 attempt remaining."
            : "Wrong PIN. \(remaining) attempts remaining."
    }

    /// What to show after a PIN entry that did not unlock.
    ///
    /// A store failure outranks the attempt count because in that case nothing
    /// was checked and no attempt was spent, so "wrong PIN, 4 remaining" would
    /// be a lie. The permanent lock outranks everything, including a timed
    /// lockout that happens to still be running, because waiting will not help.
    @MainActor
    static func pinFailure(auth: AuthService) -> String {
        if auth.pin.isPermanentlyLocked { return permanentLockShort }
        if let storeMessage = auth.storeMessage { return storeMessage }
        if auth.pin.isLockedOut { return countdown(auth.pin.lockoutRemainingSeconds) }
        let remaining = auth.pin.remainingAttempts
        return remaining == 0 ? outOfAttempts : wrongPin(remaining)
    }
}
