import Foundation

/// Storage seam for `BackupViewModel`: lets tests substitute a store that
/// returns `nil` from `load()` or throws from `save(_:)`, without touching a
/// real `WalletStore` file on disk. `WalletStore` conforms via the extension
/// below; production code never sees this protocol name.
protocol WalletRecordStoring {
    func load() -> WalletRecord?
    func save(_ record: WalletRecord) throws
}

extension WalletStore: WalletRecordStoring {}
