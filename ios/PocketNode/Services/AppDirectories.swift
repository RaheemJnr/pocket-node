import Foundation
import os

/// The one place that names the app's own folder inside Application Support.
///
/// `WalletStore` keeps `wallet.json` there and `LightClientService` keeps the
/// light client's store in `<network>/` beside it, so the two have to agree on
/// the spelling. They did not: the wallet store used `PocketNode` and the light
/// client used `pocketnode`. On an iPhone that is harmless, because the device's
/// data volume is case-sensitive and the two are simply different folders. The
/// simulator's container, however, lives on the Mac's APFS volume, which is
/// case-insensitive, so there the two names are one folder: `WalletStore` runs
/// first in `AppContainer.init` and creates it, then the light client's
/// `mkdir` of the other spelling collides with it and throws, `bootstrap()`
/// gives up before `initLightClient`, and nothing ever syncs.
///
/// Hence a single spelling, plus a migration for the installs that already have
/// light client data under the old one.
enum AppDirectories {
    /// The single spelling. `WalletStore`'s wins: every install already has
    /// `wallet.json` under it, and wallet metadata is the half worth not moving.
    static let directoryName = "PocketNode"

    /// What `LightClientService` used before the two were unified.
    /// Case-sensitive installs (real devices) still have their `store.db`
    /// under it, so it is migrated rather than abandoned: abandoning it would
    /// silently cost a full resync.
    static let legacyLightClientDirectoryName = "pocketnode"

    private static let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "AppDirectories")

    /// `Library/Application Support/PocketNode`, without touching the disk.
    ///
    /// Only for the one caller that cannot throw. It is the same path `ensure`
    /// returns, just not created, so a later write reports the real error
    /// instead of landing somewhere else.
    static func url(fileManager: FileManager = .default) -> URL {
        applicationSupport(fileManager: fileManager)
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    /// `Library/Application Support/PocketNode`, created if missing, with any
    /// light client data from before the unified spelling moved in from the
    /// old spelling first.
    ///
    /// Throws instead of falling back to another directory. An earlier version
    /// of this helper fell back to `temporaryDirectory`, which put `store.db`
    /// and `wallet.json` somewhere the system may delete at will, and said
    /// nothing about it. A wallet that cannot find its own folder has to be
    /// loud.
    @discardableResult
    static func ensure(fileManager: FileManager = .default) throws -> URL {
        try ensure(in: applicationSupport(fileManager: fileManager), fileManager: fileManager)
    }

    /// The light client's per-network data directory,
    /// `Application Support/PocketNode/<network>`, created if missing.
    static func dataDirectory(network: String, fileManager: FileManager = .default) throws -> URL {
        try dataDirectory(in: applicationSupport(fileManager: fileManager), network: network, fileManager: fileManager)
    }

    // MARK: - Testable core
    //
    // The same work against an explicit Application Support directory, so the
    // tests can drive it in a temporary folder rather than the test host's
    // real container.

    /// - Parameters:
    ///   - legacyName/targetName: defaulted to the two real names, and passed
    ///     straight through to the migration. The tests override them so that
    ///     dropping the migration call below is a test failure rather than a
    ///     silent regression.
    @discardableResult
    static func ensure(
        in support: URL,
        from legacyName: String = legacyLightClientDirectoryName,
        to targetName: String = directoryName,
        fileManager: FileManager = .default
    ) throws -> URL {
        migrateLegacyLightClientDirectory(in: support, from: legacyName, to: targetName, fileManager: fileManager)
        let directory = support.appendingPathComponent(targetName, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static func dataDirectory(in support: URL, network: String, fileManager: FileManager = .default) throws -> URL {
        let directory = try ensure(in: support, fileManager: fileManager)
            .appendingPathComponent(network, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        excludeFromBackup(directory)
        return directory
    }

    /// Marks the light client's per-network directory as regenerable so
    /// iCloud and iTunes backups skip it. `store.db` and `network/` are chain
    /// data the node rebuilds from a checkpoint, and `pocket_node.db` (sync
    /// progress, balance cache, transactions, pending broadcasts) is derived
    /// from the chain too, so a restored device resyncs and re-derives them;
    /// backing them up costs the user backup space for nothing, and App
    /// Review rejects apps that back up this kind of regenerable cache. Only
    /// the network subdirectory is marked, never `PocketNode/` itself, so
    /// `wallet.json` stays backed up.
    ///
    /// Best-effort: called on every `dataDirectory` (safe, setting the flag
    /// again is a no-op), and a failure here is logged, not thrown, because
    /// the light client still works without it.
    private static func excludeFromBackup(_ directory: URL) {
        var directory = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try directory.setResourceValues(values)
        } catch {
            logger.error("could not exclude \(directory.path, privacy: .public) from backups: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Moves whatever the light client left under the old spelling into the new
    /// one. Idempotent, and a no-op on the overwhelmingly common path where the
    /// old folder is not there.
    ///
    /// Failure here is logged, not thrown: the only cost is that the light
    /// client resyncs from its checkpoint into the new folder, which is a slow
    /// morning rather than a broken install, and refusing to start the node
    /// over it would be the worse trade.
    ///
    /// - Parameters:
    ///   - legacyName/targetName: defaulted to the two real names. The tests
    ///     override them because on a case-insensitive volume the real pair
    ///     cannot be told apart, which is the very thing being fixed.
    /// - Returns: whether anything was moved.
    @discardableResult
    static func migrateLegacyLightClientDirectory(
        in support: URL,
        from legacyName: String = legacyLightClientDirectoryName,
        to targetName: String = directoryName,
        fileManager: FileManager = .default
    ) -> Bool {
        let legacy = support.appendingPathComponent(legacyName, isDirectory: true)
        let target = support.appendingPathComponent(targetName, isDirectory: true)
        // Existence and identity in one lookup: whether the old folder is there
        // at all, and whether it is the same folder as the new one. On a
        // case-insensitive volume the two names address one folder, and moving
        // it onto itself would destroy it.
        guard let legacyIdentity = identity(of: legacy, fileManager: fileManager) else { return false }
        let targetIdentity = identity(of: target, fileManager: fileManager)
        guard targetIdentity != legacyIdentity else { return false }

        var moved = false
        do {
            if targetIdentity == nil {
                try fileManager.moveItem(at: legacy, to: target)
                moved = true
            } else {
                // The usual case: `WalletStore` has already made the target. Move
                // the children across one at a time, leaving anything the target
                // already has, so a half-finished earlier migration resumes
                // rather than overwriting.
                for name in try fileManager.contentsOfDirectory(atPath: legacy.path) {
                    let destination = target.appendingPathComponent(name)
                    guard !fileManager.fileExists(atPath: destination.path) else { continue }
                    try fileManager.moveItem(at: legacy.appendingPathComponent(name), to: destination)
                    moved = true
                }
                try reportOrRemoveLeftovers(at: legacy, named: legacyName, fileManager: fileManager)
            }
            if moved {
                logger.info("moved the light client data directory from \(legacyName, privacy: .public) to \(targetName, privacy: .public)")
            }
            return moved
        } catch {
            logger.error("could not move \(legacyName, privacy: .public) to \(targetName, privacy: .public), the light client will resync: \(error.localizedDescription, privacy: .public)")
            return moved
        }
    }

    /// Clears the old folder if the move emptied it, and says so loudly if it
    /// did not.
    ///
    /// Anything left behind is a second copy of chain data that nothing will
    /// ever read again, and it is not this function's to delete, so the only
    /// honest outcome is to name the survivors. Without this a partly failed
    /// migration is invisible: the node starts, syncs, and the duplicate store
    /// sits there taking up space forever.
    private static func reportOrRemoveLeftovers(
        at legacy: URL,
        named legacyName: String,
        fileManager: FileManager
    ) throws {
        let survivors = try fileManager.contentsOfDirectory(atPath: legacy.path)
        guard !survivors.isEmpty else {
            try fileManager.removeItem(at: legacy)
            return
        }
        let paths = survivors.sorted().map { "\(legacyName)/\($0)" }.joined(separator: ", ")
        logger.error("\(legacyName, privacy: .public) was not emptied; these are left behind and will never be read: \(paths, privacy: .public)")
    }

    // MARK: - Helpers

    /// `Library/Application Support` itself.
    ///
    /// `NSSearchPathForDirectoriesInDomains` rather than
    /// `FileManager.url(for:create:)` because it is a pure string API that
    /// cannot fail: inside an iOS sandbox it always answers
    /// `<home>/Library/Application Support`, whether or not that exists yet.
    /// `ensure` creates it on the way past, via
    /// `createDirectory(withIntermediateDirectories:)`.
    private static func applicationSupport(fileManager: FileManager = .default) -> URL {
        if let path = NSSearchPathForDirectoriesInDomains(.applicationSupportDirectory, .userDomainMask, true).first {
            return URL(fileURLWithPath: path, isDirectory: true)
        }
        return URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
            .appendingPathComponent("Library", isDirectory: true)
            .appendingPathComponent("Application Support", isDirectory: true)
    }

    /// What identifies a filesystem object: the inode, qualified by the device
    /// it lives on. Inode numbers are only unique within a volume, so the
    /// device has to be part of the comparison even though both paths here
    /// share a parent today.
    private struct ItemIdentity: Equatable {
        let device: UInt64
        let fileNumber: UInt64
    }

    /// The item's identity, or `nil` if there is nothing at that path. One
    /// `attributesOfItem` call answers both questions, so existence and
    /// sameness never disagree the way separate `fileExists` checks can on a
    /// case-insensitive volume.
    private static func identity(of url: URL, fileManager: FileManager) -> ItemIdentity? {
        guard let attributes = try? fileManager.attributesOfItem(atPath: url.path),
              let fileNumber = attributes[.systemFileNumber] as? NSNumber,
              let device = attributes[.systemNumber] as? NSNumber
        else { return nil }
        return ItemIdentity(device: device.uint64Value, fileNumber: fileNumber.uint64Value)
    }
}
