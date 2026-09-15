import CkbLightClient
import Foundation
import os
import PocketNodeCore

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

/// Enable/disable rules for the Node Status lifecycle buttons.
///
/// A plain value rather than logic inside the view, so the rules can be
/// exercised offline — in particular the one that is easy to get wrong:
/// stopping is terminal, so Start must stay disabled afterwards (#487).
struct NodeControls: Equatable {
    let status: NodeStatus
    let isInitialized: Bool

    /// Startable only from the resting INIT state. `.stopped` is a dead end:
    /// the Rust globals live in `OnceLock`s that stop cannot clear, so the
    /// bridge would answer `Stopped` and the node would never come back.
    var canStart: Bool {
        isInitialized && status == .initializing
    }

    /// Only a running node has anything to shut down.
    var canStop: Bool {
        status == .running
    }

    /// Whether to tell the user that Start is not coming back this launch.
    var showsRelaunchNotice: Bool {
        status == .stopped
    }
}

/// Runs an actor's jobs on a dedicated serial `DispatchQueue`.
///
/// A plain actor runs on the cooperative thread pool, which is sized to the
/// core count and assumes its jobs never block. The UniFFI lifecycle calls do
/// block - `initLightClient` opens the store and brings the P2P service up,
/// `stopLightClient` drains the network for up to two seconds - so leaving them
/// on the pool takes a core away from every other task in the process for the
/// duration. A private queue gives them a thread of their own to block.
private final class SerialQueueExecutor: SerialExecutor {
    private let queue = DispatchQueue(label: "com.rjnr.pocketnode.lightclient")

    func enqueue(_ job: consuming ExecutorJob) {
        let job = UnownedJob(job)
        let executor = asUnownedSerialExecutor()
        queue.async { job.runSynchronously(on: executor) }
    }

    func asUnownedSerialExecutor() -> UnownedSerialExecutor {
        UnownedSerialExecutor(ordinary: self)
    }
}

/// Serializes every UniFFI call off the main actor, onto the private queue
/// above: `initLightClient` and `startLightClient` block while the P2P service
/// comes up, so none of them may run on the main actor.
private actor LightClientRunner {
    private let executor = SerialQueueExecutor()

    nonisolated var unownedExecutor: UnownedSerialExecutor {
        executor.asUnownedSerialExecutor()
    }

    func initialize(configPath: String, dataDir: String, listener: StatusListener?) throws {
        try initLightClient(configPath: configPath, dataDir: dataDir, listener: listener)
    }

    func start() throws { try startLightClient() }
    func isInitialized() -> Bool { CkbLightClient.isInitialized() }
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

/// Owns the embedded CKB light client. Which network it points at comes from
/// `NetworkPreferences` (read once, at construction, by `AppContainer`); both
/// `mainnet.toml` and `testnet.toml` are bundled.
@MainActor
@Observable
final class LightClientService {
    private(set) var status: NodeStatus = .initializing
    /// `status == .initializing` is ambiguous on its own: it covers both "init
    /// has not finished" and "initialized, waiting for Start".
    private(set) var isInitialized = false
    private(set) var tipNumber: Int = 0
    private(set) var tipHash: String = ""
    private(set) var peerCount: Int = 0
    private(set) var lastError: String?

    private let network: NetworkType
    private let runner = LightClientRunner()
    private let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "LightClientService")

    /// `autoStart` is off under XCTest: the unit tests run inside the app host,
    /// and a node spinning up in the background would race their assertions and
    /// leave a store behind.
    init(network: NetworkType, autoStart: Bool = !ProcessInfo.processInfo.isRunningTests) {
        self.network = network
        guard autoStart else { return }
        Task { await bootstrap() }
    }

    /// Refreshes `<network>.toml` from the bundle and hands its directory to
    /// the light client as the data directory.
    private func bootstrap() async {
        do {
            let dataDir = try Self.prepareDataDirectory(network: network.configName)
            let configPath = try Self.installConfig(named: network.configName, into: dataDir)
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

    /// Lifecycle button state for the Node Status screen.
    var controls: NodeControls {
        NodeControls(status: status, isInitialized: isInitialized)
    }

    /// Re-runs init after a failed bootstrap. The Rust side publishes its globals
    /// only once init fully succeeds, so retrying after a failure is clean.
    func retryInit() async {
        guard !isInitialized else { return }
        lastError = nil
        await bootstrap()
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

    /// Shuts the node down for the rest of this launch. The bridge cannot
    /// restart it in-process, so `controls.canStart` stays false afterwards and
    /// the screen says so.
    func stop() async {
        do {
            try await runner.stop()
            lastError = nil
            logger.info("light client stopped; it stays stopped until relaunch")
        } catch {
            report(error)
        }
        await refresh()
    }

    /// Pulls status, tip header and peer count. The chain queries reject
    /// anything but the running state, so they are skipped before the node is
    /// started and again once it is stopped — otherwise the 5s poll on Node
    /// Status would log a `NotInitialized` failure every tick after a stop.
    /// Individual failures while running are transient and leave the affected
    /// field at its last value.
    func refresh() async {
        apply(rawStatus: await runner.status())
        isInitialized = await runner.isInitialized()

        guard status == .running else { return }

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
        case .Stopped: return "The node was stopped. Relaunch the app to start it again."
        case let .Config(reason): return "Configuration error: \(reason)"
        case let .Storage(reason): return "Storage error: \(reason)"
        case let .Network(reason): return "Network error: \(reason)"
        case let .NotFound(reason): return "Not found: \(reason)"
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

    /// Rewrites the on-disk config from the bundle on every init, so an app
    /// update that changes bootnodes actually takes effect. The config is not
    /// user-editable, and `store.db`/`network/` alongside it are left untouched.
    /// Android does the same in `GatewayRepository.initializeNode`.
    private static func installConfig(named network: String, into dataDir: URL) throws -> URL {
        guard let source = Bundle.main.url(forResource: network, withExtension: "toml") else {
            throw LightClientError.Config(reason: "\(network).toml missing from the app bundle")
        }
        let destination = dataDir.appendingPathComponent("\(network).toml")
        try Data(contentsOf: source).write(to: destination, options: .atomic)
        return destination
    }
}

extension ProcessInfo {
    var isRunningTests: Bool {
        environment["XCTestConfigurationFilePath"] != nil
    }
}

private extension NetworkType {
    /// Matches the bundled `<name>.toml` resource and the `data/<name>/`
    /// directory layout (Android's `GatewayRepository` uses the same
    /// lowercased-name convention for its `data/mainnet` / `data/testnet`).
    var configName: String { name.lowercased() }
}
