import Foundation
import PocketNodeCore

/// iOS implementation of the shared `core.log.Logger` seam (design D1).
///
/// Shared Kotlin cannot reach `os.Logger` or `android.util.Log`, so every class
/// in `PocketNodeCore` that logs takes this protocol as a constructor
/// dependency. Android's counterpart is `util/AndroidLogger.kt`.
///
/// `NSObject` because `Logger` is bridged in as an Objective-C protocol, and
/// `@unchecked Sendable` because the type is stateless: every method forwards
/// straight to `NSLog`, which is itself thread safe.
///
/// Nothing sensitive is ever passed to this from the auth path. The shared
/// `PinPolicy` logs only KDF version transitions and migration failures, never
/// a PIN, a salt or a hash, and the Swift side follows the same rule.
final class NSLogLogger: NSObject, PocketNodeCore.Logger, @unchecked Sendable {
    func d(tag: String, msg: String) {
        #if DEBUG
        NSLog("D/%@: %@", tag, msg)
        #endif
    }

    func i(tag: String, msg: String) {
        NSLog("I/%@: %@", tag, msg)
    }

    func w(tag: String, msg: String, t: KotlinThrowable?) {
        NSLog("W/%@: %@%@", tag, msg, Self.suffix(t))
    }

    func e(tag: String, msg: String, t: KotlinThrowable?) {
        NSLog("E/%@: %@%@", tag, msg, Self.suffix(t))
    }

    private static func suffix(_ t: KotlinThrowable?) -> String {
        guard let t else { return "" }
        return " (\(t.message ?? String(describing: type(of: t))))"
    }
}
