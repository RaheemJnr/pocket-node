import Foundation

/// The light client returns CKB RPC-shaped JSON, where every numeric field is a
/// `0x`-prefixed hex string.
enum Hex {
    /// Turns `"0x1a"` into `26`. Returns `nil` for anything that is not a
    /// hex integer, with or without the `0x` prefix.
    static func parse(_ value: String) -> Int? {
        var digits = Substring(value)
        if digits.hasPrefix("0x") || digits.hasPrefix("0X") {
            digits = digits.dropFirst(2)
        }
        guard !digits.isEmpty else { return nil }
        return Int(digits, radix: 16)
    }
}
