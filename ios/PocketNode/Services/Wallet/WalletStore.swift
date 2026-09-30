import Foundation

/// Metadata for the single wallet iOS supports before M3 multi-wallet.
///
/// Field names mirror Android's `WalletEntity`
/// (`android/app/.../data/database/entity/WalletEntity.kt`) so a later
/// multi-wallet store can import this JSON directly. `mnemonicBackedUp` lives
/// on Android's separate `key_material` table (`KeyMaterialEntity`); it is
/// folded in here because iOS has exactly one wallet and one JSON file.
struct WalletRecord: Codable, Equatable {
    let id: String
    let name: String
    /// "mnemonic" | "raw_key" — matches Android's `WalletEntity.type`.
    let type: String
    /// BIP44 path; `nil` for a raw-key wallet.
    let derivationPath: String?
    let mainnetAddress: String
    let testnetAddress: String
    let mnemonicBackedUp: Bool
    let createdAt: Int64

    init(
        id: String,
        name: String,
        type: String,
        derivationPath: String? = nil,
        mainnetAddress: String,
        testnetAddress: String,
        mnemonicBackedUp: Bool = false,
        createdAt: Int64
    ) {
        self.id = id
        self.name = name
        self.type = type
        self.derivationPath = derivationPath
        self.mainnetAddress = mainnetAddress
        self.testnetAddress = testnetAddress
        self.mnemonicBackedUp = mnemonicBackedUp
        self.createdAt = createdAt
    }
}

/// Persists the single active wallet's metadata as a JSON file in
/// Application Support. No key material lives here — that stays in
/// `Services/Keys/WalletKeyStore`, behind the Keychain and Secure Enclave.
///
/// This is the M2 single-wallet store. M3 (multi-wallet) decides whether to
/// bring Room over via KMP or move to SQLDelight; either way this file's
/// `WalletRecord` shape is the migration seed, which is why its field names
/// mirror Android's `WalletEntity` rather than being iOS-idiomatic.
final class WalletStore {
    private let fileURL: URL
    private let fileManager: FileManager

    /// `directory` defaults to `Application Support/PocketNode`; tests pass a
    /// throwaway directory (e.g. under `NSTemporaryDirectory()`) so nothing
    /// here can touch a real wallet file on the maintainer's simulator.
    init(directory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let base = directory ?? Self.applicationSupportDirectory(fileManager: fileManager)
        try? fileManager.createDirectory(at: base, withIntermediateDirectories: true)
        self.fileURL = base.appendingPathComponent("wallet.json")
    }

    /// True once a wallet has been saved and not since deleted.
    var hasWallet: Bool {
        fileManager.fileExists(atPath: fileURL.path)
    }

    func load() -> WalletRecord? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return try? JSONDecoder().decode(WalletRecord.self, from: data)
    }

    /// Writes the record atomically with complete file protection: the file
    /// is unreadable while the device is locked, matching the sensitivity of
    /// wallet metadata (it is not secret, but it is identifying).
    func save(_ record: WalletRecord) throws {
        let data = try JSONEncoder().encode(record)
        // The directory is created in init under try?, so a failure there (for
        // example a full disk) would otherwise surface here as ENOENT on the
        // atomic write. Ensuring it on every save keeps the first writer honest.
        try fileManager.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try data.write(to: fileURL, options: [.atomic, .completeFileProtection])
        // An atomic write replaces the file, dropping the flag with the old
        // one, so it is set again after every save.
        excludeFromBackup()
    }

    /// Keeps `wallet.json` out of iCloud and Finder backups.
    ///
    /// The key material it describes is `ThisDeviceOnly` and never leaves the
    /// device, so a backup restored onto another phone would bring the address
    /// back without the keys: a wallet the app shows but cannot use. Leaving
    /// the metadata out makes a restored device start from onboarding instead.
    /// Backups taken before this existed still carry the file, which is why
    /// `AppContainer` also detects a wallet whose keys are missing.
    ///
    /// Best effort: a failure only means the file may be backed up, which is
    /// the state it was already in, and the key-less check covers that case.
    func excludeFromBackup() {
        guard fileManager.fileExists(atPath: fileURL.path) else { return }
        var url = fileURL
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    /// Whether `wallet.json` is currently excluded from backups. For tests.
    var isExcludedFromBackup: Bool {
        (try? fileURL.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false
    }

    func delete() throws {
        guard fileManager.fileExists(atPath: fileURL.path) else { return }
        try fileManager.removeItem(at: fileURL)
    }

    private static func applicationSupportDirectory(fileManager: FileManager) -> URL {
        let base = (try? fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fileManager.temporaryDirectory

        let directory = base.appendingPathComponent("PocketNode", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
