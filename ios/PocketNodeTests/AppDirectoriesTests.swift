import XCTest

@testable import PocketNode

/// Covers the folder name collision: the light client asked for
/// `Application Support/pocketnode` while the wallet store had already made
/// `Application Support/PocketNode`.
/// On the simulator, whose container sits on the Mac's case-insensitive APFS
/// volume, those are one folder, so the second `mkdir` failed and the node
/// never initialised.
///
/// Runs against a throwaway directory under the process's temporary directory,
/// so nothing here touches the test host's real container.
final class AppDirectoriesTests: XCTestCase {
    private var support: URL!
    private let fileManager = FileManager.default

    override func setUpWithError() throws {
        try super.setUpWithError()
        support = fileManager.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.appDirs-\(UUID().uuidString)")
            .appendingPathComponent("Application Support", isDirectory: true)
        try fileManager.createDirectory(at: support, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? fileManager.removeItem(at: support.deletingLastPathComponent())
        support = nil
        try super.tearDownWithError()
    }

    // MARK: - The regression

    /// The failure mode itself: the wallet store's folder already exists, and
    /// the light client then asks for its data directory. Before the fix this
    /// threw `NSPOSIXErrorDomain 20` on a case-insensitive volume, because the
    /// two used different spellings of the same name.
    func testDataDirectoryResolvesWhenTheWalletFolderAlreadyExists() throws {
        // Exactly what `AppContainer.init` leaves behind before the light
        // client bootstraps: `WalletStore` runs first and creates its folder.
        let walletFolder = support.appendingPathComponent(AppDirectories.directoryName, isDirectory: true)
        try fileManager.createDirectory(at: walletFolder, withIntermediateDirectories: true)
        try Data("{}".utf8).write(to: walletFolder.appendingPathComponent("wallet.json"))

        let dataDir = try AppDirectories.dataDirectory(in: support, network: "testnet", fileManager: fileManager)

        var isDirectory: ObjCBool = false
        XCTAssertTrue(fileManager.fileExists(atPath: dataDir.path, isDirectory: &isDirectory))
        XCTAssertTrue(isDirectory.boolValue)
        XCTAssertEqual(dataDir.lastPathComponent, "testnet")
        // And the wallet file it had to share the folder with is untouched.
        XCTAssertTrue(fileManager.fileExists(atPath: walletFolder.appendingPathComponent("wallet.json").path))
    }

    /// The other half of the regression: one spelling, used by both services.
    /// A future edit that reintroduces a second spelling fails here rather than
    /// only on a case-insensitive volume at runtime.
    ///
    /// Compares against `AppDirectories.url()`, which the light client's
    /// `dataDirectory` is built from, rather than calling `dataDirectory`
    /// itself: `url()` touches no disk, so this asserts on the real paths
    /// without leaving a stray `testnet` folder in the test host's container.
    func testWalletStoreAndLightClientResolveTheSameFolder() {
        let walletFolder = WalletStore().fileURL.deletingLastPathComponent()

        XCTAssertEqual(walletFolder.standardizedFileURL, AppDirectories.url().standardizedFileURL)
        XCTAssertEqual(walletFolder.lastPathComponent, AppDirectories.directoryName)
    }

    /// Both services spell the same folder, so the names differ only by case.
    /// That is the whole reason the migration below has to exist, and the
    /// reason the migration cannot simply trust `fileExists`.
    func testTheLegacyNameDiffersFromTheCurrentOneOnlyByCase() {
        XCTAssertNotEqual(AppDirectories.legacyLightClientDirectoryName, AppDirectories.directoryName)
        XCTAssertEqual(
            AppDirectories.legacyLightClientDirectoryName.lowercased(),
            AppDirectories.directoryName.lowercased()
        )
    }

    // MARK: - Backup exclusion

    /// The effective policy: nothing in `PocketNode/` is backed up. The
    /// wallet's keys are `ThisDeviceOnly` and never leave the phone, so
    /// backing up `wallet.json` would only restore a key-less wallet onto a
    /// new one, and the light client's per-network stores are chain data the
    /// node rebuilds. Driven through the same calls the app makes, in the
    /// order it makes them: `AppDirectories.ensure` names the folder,
    /// `WalletStore` saves into it and marks it (as `AppContainer` does at
    /// launch), then the light client asks for its data directories. Calling
    /// `dataDirectory` twice proves setting the flag again is a no-op, not an
    /// error.
    func testTheWholeAppFolderIsExcludedFromBackup() throws {
        let folder = try AppDirectories.ensure(in: support, fileManager: fileManager)
        let walletStore = WalletStore(directory: folder, fileManager: fileManager)
        try walletStore.save(
            WalletRecord(
                id: "w", name: "n", type: "mnemonic",
                mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
            )
        )
        walletStore.excludeFromBackup()
        let testnet = try AppDirectories.dataDirectory(in: support, network: "testnet", fileManager: fileManager)
        let testnetAgain = try AppDirectories.dataDirectory(in: support, network: "testnet", fileManager: fileManager)
        let mainnet = try AppDirectories.dataDirectory(in: support, network: "mainnet", fileManager: fileManager)
        XCTAssertEqual(testnet, testnetAgain)

        func isExcluded(_ url: URL) throws -> Bool? {
            try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        }
        XCTAssertEqual(walletStore.fileURL.deletingLastPathComponent().standardizedFileURL, folder.standardizedFileURL)
        XCTAssertEqual(try isExcluded(folder), true, "PocketNode/ itself")
        XCTAssertEqual(try isExcluded(walletStore.fileURL), true, "wallet.json")
        XCTAssertEqual(try isExcluded(testnet), true, "the testnet store")
        XCTAssertEqual(try isExcluded(mainnet), true, "the mainnet store")
    }

    // MARK: - Migration
    //
    // These pass explicit names. The real pair cannot be told apart on a
    // case-insensitive volume, which is the whole bug, so driving the real
    // names here would exercise nothing on the machines that run these tests.

    /// A device install from before the fix: the light client's stores are under
    /// the old name and the wallet store's folder already exists. Everything
    /// moves across, so `store.db` survives and the node does not resync.
    func testMigrationMovesLegacyStoresIntoTheWalletFolder() throws {
        let legacy = support.appendingPathComponent("legacy", isDirectory: true)
        let target = support.appendingPathComponent("current", isDirectory: true)
        try fileManager.createDirectory(at: legacy.appendingPathComponent("testnet"), withIntermediateDirectories: true)
        try fileManager.createDirectory(at: legacy.appendingPathComponent("mainnet"), withIntermediateDirectories: true)
        try Data("db".utf8).write(to: legacy.appendingPathComponent("testnet/store.db"))
        try fileManager.createDirectory(at: target, withIntermediateDirectories: true)
        try Data("{}".utf8).write(to: target.appendingPathComponent("wallet.json"))

        let moved = AppDirectories.migrateLegacyLightClientDirectory(
            in: support, from: "legacy", to: "current", fileManager: fileManager
        )

        XCTAssertTrue(moved)
        XCTAssertEqual(try Data(contentsOf: target.appendingPathComponent("testnet/store.db")), Data("db".utf8))
        XCTAssertTrue(fileManager.fileExists(atPath: target.appendingPathComponent("mainnet").path))
        XCTAssertTrue(fileManager.fileExists(atPath: target.appendingPathComponent("wallet.json").path))
        XCTAssertFalse(fileManager.fileExists(atPath: legacy.path))
    }

    /// No wallet folder yet, so the whole thing is renamed in one move.
    func testMigrationRenamesWhenTheTargetIsAbsent() throws {
        let legacy = support.appendingPathComponent("legacy", isDirectory: true)
        try fileManager.createDirectory(at: legacy.appendingPathComponent("testnet"), withIntermediateDirectories: true)
        try Data("db".utf8).write(to: legacy.appendingPathComponent("testnet/store.db"))

        XCTAssertTrue(AppDirectories.migrateLegacyLightClientDirectory(
            in: support, from: "legacy", to: "current", fileManager: fileManager
        ))

        let target = support.appendingPathComponent("current", isDirectory: true)
        XCTAssertEqual(try Data(contentsOf: target.appendingPathComponent("testnet/store.db")), Data("db".utf8))
        XCTAssertFalse(fileManager.fileExists(atPath: legacy.path))
    }

    /// A store already moved keeps the newer copy; the migration never
    /// overwrites the folder it is moving into.
    func testMigrationKeepsWhatTheTargetAlreadyHas() throws {
        let legacy = support.appendingPathComponent("legacy", isDirectory: true)
        let target = support.appendingPathComponent("current", isDirectory: true)
        try fileManager.createDirectory(at: legacy.appendingPathComponent("testnet"), withIntermediateDirectories: true)
        try Data("old".utf8).write(to: legacy.appendingPathComponent("testnet/store.db"))
        try fileManager.createDirectory(at: target.appendingPathComponent("testnet"), withIntermediateDirectories: true)
        try Data("new".utf8).write(to: target.appendingPathComponent("testnet/store.db"))

        let moved = AppDirectories.migrateLegacyLightClientDirectory(
            in: support, from: "legacy", to: "current", fileManager: fileManager
        )

        XCTAssertFalse(moved)
        XCTAssertEqual(try Data(contentsOf: target.appendingPathComponent("testnet/store.db")), Data("new".utf8))
        // The leftover stays put rather than being deleted: it is the user's
        // chain data, and nothing here is entitled to throw it away. It is
        // logged at error level instead, so the duplicate store is not silent.
        XCTAssertTrue(fileManager.fileExists(atPath: legacy.appendingPathComponent("testnet/store.db").path))
    }

    /// The guard that makes the migration safe on a case-insensitive volume,
    /// where both names are one folder. Moving it onto itself would destroy it,
    /// so the inode check has to win over `fileExists`.
    func testMigrationIsANoOpWhenBothNamesAreOneFolder() throws {
        let folder = support.appendingPathComponent("same", isDirectory: true)
        try fileManager.createDirectory(at: folder.appendingPathComponent("testnet"), withIntermediateDirectories: true)
        try Data("db".utf8).write(to: folder.appendingPathComponent("testnet/store.db"))

        let moved = AppDirectories.migrateLegacyLightClientDirectory(
            in: support, from: "same", to: "same", fileManager: fileManager
        )

        XCTAssertFalse(moved)
        XCTAssertEqual(try Data(contentsOf: folder.appendingPathComponent("testnet/store.db")), Data("db".utf8))
    }

    /// The common path: no old folder, nothing to do.
    func testMigrationIsANoOpWithoutALegacyFolder() throws {
        XCTAssertFalse(AppDirectories.migrateLegacyLightClientDirectory(
            in: support, from: "legacy", to: "current", fileManager: fileManager
        ))
    }

    /// `ensure` is what every caller actually goes through, so the migration
    /// has to be wired into it. Without this, deleting the migration call from
    /// `ensure` leaves the rest of the suite green.
    func testEnsureRunsTheMigration() throws {
        let legacy = support.appendingPathComponent("legacy", isDirectory: true)
        try fileManager.createDirectory(at: legacy.appendingPathComponent("testnet"), withIntermediateDirectories: true)
        try Data("db".utf8).write(to: legacy.appendingPathComponent("testnet/store.db"))

        let directory = try AppDirectories.ensure(in: support, from: "legacy", to: "current", fileManager: fileManager)

        XCTAssertEqual(directory.lastPathComponent, "current")
        XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("testnet/store.db")), Data("db".utf8))
        XCTAssertFalse(fileManager.fileExists(atPath: legacy.path))
    }

    /// `ensure` is idempotent, and creating Application Support itself is part
    /// of its job: a fresh install has `Library` but no `Application Support`.
    func testEnsureCreatesTheFolderAndIsIdempotent() throws {
        let absent = support.deletingLastPathComponent()
            .appendingPathComponent("Nested", isDirectory: true)
            .appendingPathComponent("Application Support", isDirectory: true)

        let first = try AppDirectories.ensure(in: absent, fileManager: fileManager)
        let second = try AppDirectories.ensure(in: absent, fileManager: fileManager)

        XCTAssertEqual(first, second)
        var isDirectory: ObjCBool = false
        XCTAssertTrue(fileManager.fileExists(atPath: first.path, isDirectory: &isDirectory))
        XCTAssertTrue(isDirectory.boolValue)
    }
}
