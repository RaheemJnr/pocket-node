import Foundation

public struct WalletService {
    private let label: String

    /// Reads the live cell count through the Rust core.
    public func cellCount() -> UInt64 {
        return getCellCount(prefix: "addr")
    }

    public func boot() -> Bool {
        return startNode()
    }
}
