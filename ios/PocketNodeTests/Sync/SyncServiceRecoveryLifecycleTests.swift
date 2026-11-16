import Foundation
import PocketNodeCore
import XCTest
@testable import PocketNode

@MainActor
final class SyncServiceRecoveryLifecycleTests: XCTestCase {
    private let wallet = WalletRecord(id: "fresh-wallet", name: "Fixture", type: WalletCreator.typeMnemonic,
        derivationPath: "m/44'/309'/0'/0/0",
        mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft",
        testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn",
        mnemonicBackedUp: true, createdAt: 1)

    func testFreshModeChoicesResumeAfterServiceRecreationWithoutInventedRecovery() async throws {
        for (mode, start) in [(SyncMode.theNewWallet, Int64(500_000)), (.recent, 300_000), (.custom, 123)] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let suite = "sync-lifecycle-\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite); try? FileManager.default.removeItem(at: directory) }
            let preferences = UserDefaultsPreferences(defaults: defaults)
            preferences.setSelectedNetwork(network: .testnet)
            let api = RecoveryLifecycleApi()
            var service = SyncService(lightClient: RecoveryLifecycleNode(), api: api, preferences: preferences, storageDirectory: directory)
            try await service.ensureVerifiedCache() // Empty handoff before a wallet is activated.
            service.activate(wallet: wallet)
            try await service.ensureVerifiedCache()
            let accepted = await service.choose(mode: mode, customBlockHeight: mode == .custom ? 123 : nil)
            XCTAssertTrue(accepted)
            XCTAssertEqual(preferences.getSyncModeOrNull(network: .testnet, walletId: wallet.id), mode)
            XCTAssertEqual(api.lastStart, start)
            XCTAssertEqual(try String(contentsOf: directory.appendingPathComponent("verified-chain-generation"), encoding: .utf8), RecoveryLifecycleApi.generation)
            service.shutdown()
            defaults.synchronize()
            api.clearRequests()
            service = SyncService(lightClient: RecoveryLifecycleNode(), api: api,
                preferences: UserDefaultsPreferences(defaults: UserDefaults(suiteName: suite)!), storageDirectory: directory)
            service.activate(wallet: wallet)
            try await service.ensureVerifiedCache()
            for _ in 0..<200 where api.lastStart == nil { try await Task.sleep(for: .milliseconds(10)) }
            XCTAssertEqual(api.lastStart, start, "ordinary restart must retain the chosen range")
            let probe = RecoveryLifecycleDatabase(directory: directory)
            let job = try await probe.job(walletId: wallet.id)
            XCTAssertNil(job, "fresh configuration is neither recovery COMPLETE nor PENDING")
            probe.close()
            service.shutdown()
        }
    }

    func testLegacyErasedWalletRetriesAcrossRoomAndFileHandoffInterruption() async throws {
        for markerWritten in [false, true] {
            let fixture = try LifecycleFixture()
            defer { fixture.close() }
            fixture.preferences.setSyncMode(mode: .recent, network: .testnet, walletId: wallet.id)
            let probe = RecoveryLifecycleDatabase(directory: fixture.directory)
            // Room handoff already committed, but all older progress/history were erased.
            try await probe.prepare()
            if markerWritten { try fixture.writeGeneration() }
            probe.close()
            let service = fixture.service()
            service.activate(wallet: wallet)
            try await service.ensureVerifiedCache()
            try await waitForRegistration(fixture.api)
            XCTAssertEqual(fixture.api.lastStart, 0)
            service.shutdown()
        }
    }

    func testCompletedRecoveryRetainsNormalResume() async throws {
        let fixture = try LifecycleFixture()
        defer { fixture.close() }
        fixture.preferences.setSyncMode(mode: .custom, network: .testnet, walletId: wallet.id)
        fixture.preferences.setCustomBlockHeight(height: KotlinLong(longLong: 123), network: .testnet, walletId: wallet.id)
        let probe = RecoveryLifecycleDatabase(directory: fixture.directory)
        try await probe.prepare()
        // Fixture represents a previously proof-completed job; the service must
        // preserve it, not turn same-generation missing provenance into a new job.
        try await probe.seedCompleted(walletId: wallet.id)
        try fixture.writeGeneration()
        let service = fixture.service()
        service.activate(wallet: wallet)
        try await service.ensureVerifiedCache()
        try await waitForRegistration(fixture.api)
        XCTAssertEqual(fixture.api.lastStart, 123)
        let job = try await probe.job(walletId: wallet.id)
        XCTAssertEqual(job?.status, "COMPLETE")
        probe.close()
        service.shutdown()
    }

    func testMissingMalformedAndStaleProvenanceRepairConfiguredWallet() async throws {
        for replacement in [nil, "broken", "stale", "wrong-network"] as [String?] {
            let fixture = try LifecycleFixture()
            defer { fixture.close() }
            var service = fixture.service()
            service.activate(wallet: wallet)
            try await service.ensureVerifiedCache()
            let accepted = await service.choose(mode: .custom, customBlockHeight: 123)
            XCTAssertTrue(accepted)
            service.shutdown()
            let file = fixture.directory.appendingPathComponent("fresh-sync-configuration-v1.json")
            if let replacement {
                var contents = try String(contentsOf: file, encoding: .utf8)
                if replacement == "broken" { contents = "broken" }
                if replacement == "stale" { contents = contents.replacingOccurrences(of: RecoveryLifecycleApi.generation, with: String(repeating: "b", count: 32)) }
                if replacement == "wrong-network" { contents = contents.replacingOccurrences(of: "TESTNET", with: "MAINNET") }
                try Data(contents.utf8).write(to: file, options: .atomic)
            } else { try FileManager.default.removeItem(at: file) }
            fixture.api.clearRequests()
            service = fixture.service()
            service.activate(wallet: wallet)
            try await service.ensureVerifiedCache()
            try await waitForRegistration(fixture.api)
            XCTAssertEqual(fixture.api.lastStart, 0)
            service.shutdown()
        }
    }

    func testFreshProvenanceNeverSuppressesExistingOrNewGenerationObligations() async throws {
        for newGeneration in [false, true] {
            let fixture = try LifecycleFixture()
            defer { fixture.close() }
            var service = fixture.service()
            service.activate(wallet: wallet)
            try await service.ensureVerifiedCache()
            let accepted = await service.choose(mode: .recent, customBlockHeight: nil)
            XCTAssertTrue(accepted)
            service.shutdown()
            let probe = RecoveryLifecycleDatabase(directory: fixture.directory)
            if newGeneration {
                fixture.api.generationToken.set(String(repeating: "b", count: 32))
            } else {
                try await probe.seedPending(walletId: wallet.id)
            }
            fixture.api.clearRequests()
            service = fixture.service()
            service.activate(wallet: wallet)
            try await service.ensureVerifiedCache()
            try await waitForRegistration(fixture.api)
            XCTAssertEqual(fixture.api.lastStart, 0)
            let job = try await probe.job(walletId: wallet.id)
            XCTAssertEqual(job?.generation, fixture.api.generationToken.get())
            XCTAssertEqual(job?.status, "REGISTERED")
            probe.close()
            service.shutdown()
        }
    }

    func testFreshProvenanceWriteFailureKeepsModeSelectionClosed() async throws {
        let fixture = try LifecycleFixture()
        defer { fixture.close() }
        try FileManager.default.createDirectory(at: fixture.directory.appendingPathComponent("fresh-sync-configuration-v1.json"), withIntermediateDirectories: false)
        let service = fixture.service()
        service.activate(wallet: wallet)
        let accepted = await service.choose(mode: .theNewWallet, customBlockHeight: nil)
        XCTAssertFalse(accepted)
        XCTAssertNil(fixture.preferences.getSyncModeOrNull(network: .testnet, walletId: wallet.id))
        XCTAssertNil(fixture.api.lastStart)
        service.shutdown()
    }

    private func waitForRegistration(_ api: RecoveryLifecycleApi) async throws {
        for _ in 0..<200 where api.lastStart == nil { try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertNotNil(api.lastStart)
    }

}

@MainActor
private final class RecoveryLifecycleNode: SyncNodeLifecycle {
    let status = NodeStatus.running
    var controls: NodeControls { NodeControls(status: status, isInitialized: true) }
    let tipNumber = 500_000
    let tipHash = String(repeating: "a", count: 64)
    func start() async {}
    func refresh() async {}
}

private final class RecoveryLifecycleDatabase: @unchecked Sendable {
    let database: PocketNodeCoreDatabase
    @MainActor init(directory: URL) {
        database = createIosPocketNodeCoreDatabase(path: directory.appendingPathComponent("pocket_node.db").path,
            queryContext: SharedRuntime.shared.defaultContext)
    }
    func job(walletId: String) async throws -> HistoryRecoveryJob? {
        try await database.historyRecovery().job(walletId: walletId, network: "TESTNET")
    }
    func prepare() async throws { try await database.verificationCache().invalidate(network: "TESTNET", generation: RecoveryLifecycleApi.generation) }
    func seedPending(walletId: String) async throws {
        try await database.historyRecovery().ensureWallet(walletId: walletId, network: "TESTNET", generation: RecoveryLifecycleApi.generation)
    }
    func seedCompleted(walletId: String) async throws {
        let dao = database.historyRecovery()
        try await dao.ensureWallet(walletId: walletId, network: "TESTNET", generation: RecoveryLifecycleApi.generation)
        try await dao.registered(walletId: walletId, network: "TESTNET", generation: RecoveryLifecycleApi.generation, target: 500_000, scriptJson: "fixture")
        try await dao.complete(walletId: walletId, network: "TESTNET", generation: RecoveryLifecycleApi.generation, scriptJson: "fixture", tip: 500_000)
    }
    func close() { database.close() }
}

private final class RecoveryLifecycleApi: NSObject, LightClientApi, @unchecked Sendable {
    static let generation = String(repeating: "a", count: 32)
    let generationToken = SharedValueBox<String>(RecoveryLifecycleApi.generation)
    private let lock = NSLock()
    private var scripts = "[]"
    private var requests: [String] = []
    var lastStart: Int64? { lock.withLock {
        guard let raw = requests.last, let data = raw.data(using: .utf8),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              let number = rows.first?["block_number"] as? String else { return nil }
        return Int64(number.replacingOccurrences(of: "0x", with: ""), radix: 16)
    } }
    func clearRequests() { lock.withLock { requests = [] } }
    func setScripts(scriptsJson: String, command: Int32) -> Bool {
        lock.withLock { scripts = scriptsJson; requests.append(scriptsJson) }; return true
    }
    func getScripts() -> String? { lock.withLock { scripts } }
    func callRpc(method: String) -> String? { "{\"result\":{\"version\":1,\"generation\":\"\(generationToken.get())\"}}" }
    func getTipHeader() -> String? {
        "{\"hash\":\"0x\(String(repeating: "a", count: 64))\",\"number\":\"0x7a120\",\"epoch\":\"0x0\",\"timestamp\":\"0x0\",\"parent_hash\":\"0x0\",\"transactions_root\":\"0x0\",\"proposals_hash\":\"0x0\",\"extra_hash\":\"0x0\",\"dao\":\"0x0\",\"nonce\":\"0x0\"}"
    }
    func doInit(configPath: String, dataDir: String, listener: (any LightClientStatusListener)?) -> Bool { true }
    func start() -> Bool { true }
    func stop() -> Bool { true }
    func status() -> Int32 { 1 }
    func isInitialized() -> Bool { true }
    func getHistorySnapshot(scriptJson: String) -> String? { nil }
    func getGenesisBlock() -> String? { nil }
    func getHeader(hash: String) -> String? { nil }
    func fetchHeader(hash: String) -> String? { nil }
    func getHeaderByNumber(blockNumber: String) -> String? { nil }
    func getCells(searchKeyJson: String, order: String, limit: Int32, cursor: String?) -> String? { nil }
    func getTransactions(searchKeyJson: String, order: String, limit: Int32, cursor: String?) -> String? { nil }
    func getCellsCapacity(searchKeyJson: String) -> String? { nil }
    func sendTransaction(txJson: String) -> String? { nil }
    func getTransaction(hash: String) -> String? { nil }
    func fetchTransaction(hash: String) -> String? { nil }
    func estimateCycles(txJson: String) -> String? { nil }
    func requestDaoRecovery(txHash: String) -> String? { nil }
    func pollDaoRecovery(requestId: String) -> String? { nil }
    func validateDaoRecovery(requestId: String, receiptId: String) -> String? { nil }
    func cancelDaoRecovery(requestId: String) -> String? { nil }
    func localNodeInfo() -> String? { nil }
    func getPeers() -> String? { nil }
    func extractDaoFields(daoHex: String) -> String? { nil }
    func calculateMaxWithdraw(depositHeaderDaoHex: String, withdrawHeaderDaoHex: String, depositCapacity: Int64, occupiedCapacity: Int64) -> Int64 { -1 }
    func calculateUnlockEpoch(depositEpochHex: String, withdrawEpochHex: String) -> String? { nil }
}


@MainActor
private final class LifecycleFixture {
    let directory: URL
    let suite = "sync-lifecycle-\(UUID().uuidString)"
    let defaults: UserDefaults
    let preferences: UserDefaultsPreferences
    let api = RecoveryLifecycleApi()
    init() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defaults = UserDefaults(suiteName: suite)!
        preferences = UserDefaultsPreferences(defaults: defaults)
        preferences.setSelectedNetwork(network: .testnet)
    }
    func service() -> SyncService {
        SyncService(lightClient: RecoveryLifecycleNode(), api: api, preferences: preferences, storageDirectory: directory)
    }
    func writeGeneration() throws {
        try Data(RecoveryLifecycleApi.generation.utf8).write(to: directory.appendingPathComponent("verified-chain-generation"), options: .atomic)
    }
    func close() {
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: directory)
    }
}
