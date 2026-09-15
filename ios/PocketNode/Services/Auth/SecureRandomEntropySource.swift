import Foundation
import PocketNodeCore
import Security

/// iOS implementation of the shared `core.crypto.EntropySource`.
///
/// `SecRandomCopyBytes` is the platform CSPRNG; Android passes a
/// `java.security.SecureRandom`-backed source to the same seam. The shared
/// contract is explicit that a short or predictable return is a wallet-losing
/// bug, so a refusal from the system traps rather than falling back to
/// anything weaker. `SecRandomCopyBytes` fails only when the kernel RNG is
/// unavailable, which is not a state this app can safely continue in.
///
/// `NSObject` because `EntropySource` is bridged in as an Objective-C
/// protocol; `@unchecked Sendable` because the type is stateless.
final class SecureRandomEntropySource: NSObject, PocketNodeCore.EntropySource, @unchecked Sendable {
    func nextBytes(n: Int32) -> KotlinByteArray {
        precondition(n >= 0, "cannot request a negative number of random bytes")
        var buffer = [UInt8](repeating: 0, count: Int(n))
        let status = buffer.withUnsafeMutableBytes { raw -> Int32 in
            guard let base = raw.baseAddress else { return errSecSuccess }
            return SecRandomCopyBytes(kSecRandomDefault, raw.count, base)
        }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed with \(status)")
        defer { buffer.resetBytes(in: 0..<buffer.count) }
        return KotlinByteArray.from(buffer)
    }
}

private extension Array where Element == UInt8 {
    mutating func resetBytes(in range: Range<Int>) {
        for index in range { self[index] = 0 }
    }
}
