import Foundation
import os

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
    /// Exposed so the tests can assert it resolves to the same folder the light
    /// client uses; nothing in the app reads it.
    let fileURL: URL
    private let fileManager: FileManager
    private static let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "WalletStore")

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
    /// `LaunchGate` also detects a wallet whose keys are missing.
    ///
    /// The flag goes on the directory as well as the file, and unlike the file
    /// the directory is never replaced, so its flag survives every atomic
    /// save; the file's own flag is belt and braces. The directory is the
    /// app's own folder (`AppDirectories`), so the flag covers everything in
    /// it: the wallet metadata (`wallet.json`, and a set-aside
    /// `wallet.unreadable.json` if there ever was one) and the light client's
    /// per-network stores, which are chain data the node rebuilds and which
    /// `AppDirectories` excludes on their own as well. Called at launch too,
    /// so existing installs are covered before their next save.
    ///
    /// Best effort: a failure only means the file may be backed up, which is
    /// the state it was already in, and the key-less check covers that case.
    func excludeFromBackup() {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var directory = fileURL.deletingLastPathComponent()
        try? directory.setResourceValues(values)
        guard fileManager.fileExists(atPath: fileURL.path) else { return }
        var url = fileURL
        try? url.setResourceValues(values)
    }

    /// Whether `wallet.json` is currently excluded from backups. For tests.
    var isExcludedFromBackup: Bool {
        (try? fileURL.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false
    }

    /// True when `wallet.json` can be read but does not decode as a record:
    /// corrupt rather than merely locked. A file the data-protection class
    /// keeps unreadable (a launch while the device is locked) does not count,
    /// since nothing is known about its contents.
    var hasUndecodableRecord: Bool {
        guard let data = try? Data(contentsOf: fileURL) else { return false }
        return (try? JSONDecoder().decode(WalletRecord.self, from: data)) == nil
    }

    /// Moves an undecodable `wallet.json` aside as `wallet.unreadable.json`,
    /// replacing any earlier one, so the device reads as having no wallet
    /// metadata. Kept rather than deleted, for diagnosis. Callers decide when
    /// this is safe (see `LaunchGate`).
    func setAsideUndecodableRecord() throws {
        let aside = fileURL.deletingLastPathComponent().appendingPathComponent("wallet.unreadable.json")
        if fileManager.fileExists(atPath: aside.path) {
            try fileManager.removeItem(at: aside)
        }
        try fileManager.moveItem(at: fileURL, to: aside)
    }

    /// Whether the directory holding `wallet.json` is excluded. For tests.
    var isDirectoryExcludedFromBackup: Bool {
        let directory = fileURL.deletingLastPathComponent()
        return (try? directory.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false
    }

    func delete() throws {
        guard fileManager.fileExists(atPath: fileURL.path) else { return }
        try fileManager.removeItem(at: fileURL)
    }

    /// `Application Support/PocketNode`, the folder `AppDirectories` names for
    /// the whole app; the light client's per-network stores sit inside it.
    ///
    /// `init` cannot throw, so a failure here is logged rather than raised, and
    /// the intended path is returned uncreated. It is deliberately not swapped
    /// for a writable one: this used to fall back to `temporaryDirectory`, which
    /// put `wallet.json` where the system may delete it and said nothing. The
    /// path stays right, `save()` retries the directory and throws the real
    /// error, and `hasWallet` reports false rather than pointing at a stray file.
    private static func applicationSupportDirectory(fileManager: FileManager) -> URL {
        do {
            return try AppDirectories.ensure(fileManager: fileManager)
        } catch {
            logger.error("could not create the wallet directory: \(error.localizedDescription, privacy: .public)")
            return AppDirectories.url(fileManager: fileManager)
        }
    }
}
