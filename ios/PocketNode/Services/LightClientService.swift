import CkbLightClient
import Foundation
import os

/// Lifecycle state reported by the Rust light client.
enum NodeStatus: UInt8 {
    case initializing = 0
    case running = 1
    case stopped = 2

    var label: String {
        switch self {
        case .initializing: return "Init"
        case .running: return "Running"
        case .stopped: return "Stopped"
        }
    }
}

/// Serializes every UniFFI call onto a background executor. `initLightClient`
/// and `startLightClient` block while the P2P service comes up, so none of them
/// may run on the main actor.
private actor LightClientRunner {
    func initialize(configPath: String, dataDir: String, listener: StatusListener?) throws {
        try initLightClient(configPath: configPath, dataDir: dataDir, listener: listener)
    }

    func start() throws { try startLightClient() }
    func stop() throws { try stopLightClient() }
    func status() -> UInt8 { getStatus() }
    func tipHeader() throws -> String { try getTipHeader() }
    func nodeInfo() throws -> String { try localNodeInfo() }
}

/// Bridges `StatusListener` callbacks, which arrive on a Rust thread, back onto
/// the main actor.
private final class StatusRelay: StatusListener {
    private let handler: @Sendable (UInt8) -> Void

    init(handler: @escaping @Sendable (UInt8) -> Void) {
        self.handler = handler
    }

    func onStatus(status: UInt8) {
        handler(status)
    }
}

/// Owns the embedded CKB light client. M1 is testnet only; `mainnet.toml` is
/// bundled but never loaded.
@MainActor
@Observable
final class LightClientService {
    private(set) var status: NodeStatus = .initializing
    private(set) var tipNumber: Int = 0
    private(set) var tipHash: String = ""
    private(set) var peerCount: Int = 0
    private(set) var lastError: String?

    private let runner = LightClientRunner()
    private let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "LightClientService")

    /// `autoStart` is off under XCTest: the unit tests run inside the app host,
    /// and a node spinning up in the background would race their assertions and
    /// leave a store behind.
    init(autoStart: Bool = !ProcessInfo.processInfo.isRunningTests) {
        guard autoStart else { return }
        Task { await bootstrap() }
    }

    /// Copies `testnet.toml` out of the bundle (once) and hands its directory to
    /// the light client as the data directory.
    private func bootstrap() async {
        do {
            let dataDir = try Self.prepareDataDirectory(network: "testnet")
            let configPath = try Self.installConfig(named: "testnet", into: dataDir)
            let relay = StatusRelay { [weak self] raw in
                Task { @MainActor in self?.apply(rawStatus: raw) }
            }
            logger.info("initLightClient dataDir=\(dataDir.path, privacy: .public)")
            try await runner.initialize(
                configPath: configPath.path,
                dataDir: dataDir.path,
                listener: relay
            )
            lastError = nil
            logger.info("light client initialized")
        } catch {
            report(error)
        }
        await refresh()
    }

    func start() async {
        do {
            try await runner.start()
            lastError = nil
            logger.info("light client started")
        } catch {
            report(error)
        }
        await refresh()
    }

    func stop() async {
        do {
            try await runner.stop()
            lastError = nil
            logger.info("light client stopped")
        } catch {
            report(error)
        }
        await refresh()
    }

    /// Pulls status, tip header and peer count. Individual query failures are
    /// expected while the node is still handshaking, so they only clear the
    /// affected field.
    func refresh() async {
        apply(rawStatus: await runner.status())

        do {
            let json = try await runner.tipHeader()
            if let header = Self.decode(json) {
                if let number = (header["number"] as? String).flatMap(Hex.parse) {
                    tipNumber = number
                }
                tipHash = header["hash"] as? String ?? tipHash
            }
        } catch {
            logger.debug("tip header unavailable: \(error.localizedDescription, privacy: .public)")
        }

        do {
            let json = try await runner.nodeInfo()
            if let info = Self.decode(json),
               let connections = (info["connections"] as? String).flatMap(Hex.parse) {
                peerCount = connections
            }
        } catch {
            logger.debug("node info unavailable: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func apply(rawStatus: UInt8) {
        status = NodeStatus(rawValue: rawStatus) ?? .initializing
    }

    private func report(_ error: Error) {
        let message = Self.describe(error)
        lastError = message
        logger.error("\(message, privacy: .public)")
    }

    // MARK: - Helpers

    /// Turns a `LightClientError` into something worth showing a user.
    static func describe(_ error: Error) -> String {
        guard let error = error as? LightClientError else {
            return error.localizedDescription
        }
        switch error {
        case .NotInitialized: return "The node is not initialized yet."
        case .AlreadyInitialized: return "The node is already initialized."
        case let .Config(reason): return "Configuration error: \(reason)"
        case let .Storage(reason): return "Storage error: \(reason)"
        case let .Network(reason): return "Network error: \(reason)"
        case let .Internal(reason): return "Internal error: \(reason)"
        }
    }

    private static func decode(_ json: String) -> [String: Any]? {
        guard let data = json.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func prepareDataDirectory(network: String) throws -> URL {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dataDir = support
            .appendingPathComponent("pocketnode", isDirectory: true)
            .appendingPathComponent(network, isDirectory: true)
        try FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true)
        return dataDir
    }

    private static func installConfig(named network: String, into dataDir: URL) throws -> URL {
        let destination = dataDir.appendingPathComponent("\(network).toml")
        guard !FileManager.default.fileExists(atPath: destination.path) else { return destination }
        guard let source = Bundle.main.url(forResource: network, withExtension: "toml") else {
            throw LightClientError.Config(reason: "\(network).toml missing from the app bundle")
        }
        try FileManager.default.copyItem(at: source, to: destination)
        return destination
    }
}

extension ProcessInfo {
    var isRunningTests: Bool {
        environment["XCTestConfigurationFilePath"] != nil
    }
}
