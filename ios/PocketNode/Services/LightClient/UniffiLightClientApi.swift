import CkbLightClient
import Foundation
import PocketNodeCore
import os

/// iOS binding of the shared `LightClientApi` protocol to the UniFFI bridge.
///
/// The Kotlin interface is exported through `PocketNodeCore` as an Objective-C
/// protocol named `LightClientApi` (`PNCLightClientApi` in the generated
/// header). Kotlin's `init` cannot keep its name in Objective-C, where anything
/// starting with `init` is an initializer, so Kotlin/Native exports it as
/// `doInit(configPath:dataDir:listener:)`; that is the only name that differs
/// from the Kotlin declaration.
///
/// ## Error contract
///
/// The shared contract is null-on-any-failure, the historical JNI behaviour
/// that all the Android call sites are written against. UniFFI hands Swift a
/// typed `LightClientError` instead, so every call here catches it, logs the
/// reason, and returns `nil`. The reason is deliberately not propagated: the
/// shared engines have nowhere to put it, and a screen that wants it (Node
/// Status does) calls `LightClientService` directly and gets the real error.
///
/// ### The one deliberate exception
///
/// ``sendTransaction(txJson:)`` does propagate its reason, because a broadcast
/// is the one call where the reason IS the answer. A node that refuses a
/// transaction says why - an input it cannot resolve, a script that did not
/// verify - and a `nil` turns all of that into "Send failed - native returned
/// null", which blames the network for what is usually a local, actionable
/// fault. So a thrown `LightClientError` comes back as
/// `"__SEND_ERROR__:<reason>"`, the sentinel `SendPipeline` already strips off
/// the Android JNI's answers (`SendPipeline.BROADCAST_ERROR_PREFIX`, mirroring
/// `SEND_ERROR_PREFIX` in `bridge_core/query.rs`). `nil` is kept for the case
/// it was always meant for: the bridge genuinely answering nothing.
///
/// ## Threading
///
/// Every call blocks, some of them for seconds (`doInit` opens the store and
/// brings the P2P service up, `stop` drains the network for up to two). The
/// caller is responsible for staying off the main actor. This type deliberately
/// does not route through `LightClientService`'s `LightClientRunner` actor:
/// the shared engine calls it from its own background dispatcher, and hopping
/// through an actor would mean either blocking the cooperative pool or making
/// the protocol async, which Kotlin cannot express here.
final class UniffiLightClientApi: NSObject, LightClientApi, @unchecked Sendable {

    private let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "lightclient-api")

    // MARK: - Lifecycle

    func doInit(
        configPath: String,
        dataDir: String,
        listener: (any LightClientStatusListener)?
    ) -> Bool {
        let relay = listener.map(StatusListenerBridge.init)
        do {
            try CkbLightClient.initLightClient(
                configPath: configPath,
                dataDir: dataDir,
                listener: relay
            )
            return true
        } catch {
            log("initLightClient", error)
            return false
        }
    }

    func start() -> Bool {
        do {
            try CkbLightClient.startLightClient()
            return true
        } catch {
            log("startLightClient", error)
            return false
        }
    }

    func stop() -> Bool {
        do {
            try CkbLightClient.stopLightClient()
            return true
        } catch {
            log("stopLightClient", error)
            return false
        }
    }

    func status() -> Int32 {
        Int32(CkbLightClient.getStatus())
    }

    func isInitialized() -> Bool {
        CkbLightClient.isInitialized()
    }

    // MARK: - Chain queries

    func getTipHeader() -> String? {
        query("getTipHeader") { try CkbLightClient.getTipHeader() }
    }

    func getGenesisBlock() -> String? {
        query("getGenesisBlock") { try CkbLightClient.getGenesisBlock() }
    }

    func getHeader(hash: String) -> String? {
        query("getHeader") { try CkbLightClient.getHeader(hash: hash) }
    }

    func fetchHeader(hash: String) -> String? {
        query("fetchHeader") { try CkbLightClient.fetchHeader(hash: hash) }
    }

    func getHeaderByNumber(blockNumber: String) -> String? {
        query("getHeaderByNumber") {
            try CkbLightClient.getHeaderByNumber(blockNumber: blockNumber)
        }
    }

    // MARK: - Filter scripts

    func setScripts(scriptsJson: String, command: Int32) -> Bool {
        do {
            try CkbLightClient.setScripts(scriptsJson: scriptsJson, command: command)
            return true
        } catch {
            log("setScripts", error)
            return false
        }
    }

    func getScripts() -> String? {
        query("getScripts") { try CkbLightClient.getScripts() }
    }

    // MARK: - Indexer queries

    /// `cursor` is optional in the shared contract and a plain string on the
    /// bridge, where the empty string means "first page".
    func getCells(
        searchKeyJson: String,
        order: String,
        limit: Int32,
        cursor: String?
    ) -> String? {
        query("getCells") {
            try CkbLightClient.getCells(
                searchKeyJson: searchKeyJson,
                order: order,
                limit: limit,
                cursor: cursor ?? ""
            )
        }
    }

    func getTransactions(
        searchKeyJson: String,
        order: String,
        limit: Int32,
        cursor: String?
    ) -> String? {
        query("getTransactions") {
            try CkbLightClient.getTransactions(
                searchKeyJson: searchKeyJson,
                order: order,
                limit: limit,
                cursor: cursor ?? ""
            )
        }
    }

    func getCellsCapacity(searchKeyJson: String) -> String? {
        query("getCellsCapacity") {
            try CkbLightClient.getCellsCapacity(searchKeyJson: searchKeyJson)
        }
    }

    // MARK: - Transactions

    /// Broadcast a signed transaction.
    ///
    /// The exception to this type's null-on-failure contract: a rejection is
    /// returned in band as `"__SEND_ERROR__:<reason>"` so the shared send path
    /// can surface why, instead of reporting a bare null. See the type's error
    /// contract above.
    func sendTransaction(txJson: String) -> String? {
        do {
            return try CkbLightClient.sendTransaction(txJson: txJson)
        } catch let error as LightClientError {
            log("sendTransaction", error)
            return SendPipeline.companion.BROADCAST_ERROR_PREFIX + Self.reason(for: error)
        } catch {
            // Not a bridge error at all, so there is no reason worth handing a
            // user. Falls back to the contract every other call follows.
            log("sendTransaction", error)
            return nil
        }
    }

    func getTransaction(hash: String) -> String? {
        query("getTransaction") { try CkbLightClient.getTransaction(hash: hash) }
    }

    func fetchTransaction(hash: String) -> String? {
        query("fetchTransaction") { try CkbLightClient.fetchTransaction(hash: hash) }
    }

    func estimateCycles(txJson: String) -> String? {
        query("estimateCycles") { try CkbLightClient.estimateCycles(txJson: txJson) }
    }

    // MARK: - Node info

    func localNodeInfo() -> String? {
        query("localNodeInfo") { try CkbLightClient.localNodeInfo() }
    }

    func getPeers() -> String? {
        query("getPeers") { try CkbLightClient.getPeers() }
    }

    func callRpc(method: String) -> String? {
        query("callRpc") { try CkbLightClient.callRpc(method: method) }
    }

    // MARK: - DAO helpers

    func extractDaoFields(daoHex: String) -> String? {
        query("extractDaoFields") { try CkbLightClient.extractDaoFields(daoHex: daoHex) }
    }

    /// `-1` is the shared failure sentinel, matching what the JNI DAO bridge
    /// returns: the value is a plain `Int64` and cannot be null.
    func calculateMaxWithdraw(
        depositHeaderDaoHex: String,
        withdrawHeaderDaoHex: String,
        depositCapacity: Int64,
        occupiedCapacity: Int64
    ) -> Int64 {
        do {
            return try CkbLightClient.calculateMaxWithdraw(
                depositHeaderDaoHex: depositHeaderDaoHex,
                withdrawHeaderDaoHex: withdrawHeaderDaoHex,
                depositCapacity: depositCapacity,
                occupiedCapacity: occupiedCapacity
            )
        } catch {
            log("calculateMaxWithdraw", error)
            return -1
        }
    }

    func calculateUnlockEpoch(depositEpochHex: String, withdrawEpochHex: String) -> String? {
        query("calculateUnlockEpoch") {
            try CkbLightClient.calculateUnlockEpoch(
                depositEpochHex: depositEpochHex,
                withdrawEpochHex: withdrawEpochHex
            )
        }
    }

    // MARK: - Helpers

    private func query(_ name: String, _ body: () throws -> String) -> String? {
        do {
            return try body()
        } catch {
            log(name, error)
            return nil
        }
    }

    /// `NotFound` is an ordinary answer for a light client, which only stores
    /// what it matched, so it is logged at debug while everything else is a
    /// warning worth finding in a sysdiagnose.
    private func log(_ name: String, _ error: Error) {
        let reason = Self.reason(for: error)
        if case LightClientError.NotFound = error {
            logger.debug("\(name, privacy: .public): \(reason, privacy: .public)")
        } else {
            logger.warning("\(name, privacy: .public): \(reason, privacy: .public)")
        }
    }

    /// Short, log-shaped description of a bridge failure.
    ///
    /// Separate from `LightClientService.describe`, which is main-actor
    /// isolated and writes for a user rather than for a log line.
    static func reason(for error: Error) -> String {
        guard let error = error as? LightClientError else {
            return error.localizedDescription
        }
        switch error {
        case .NotInitialized: return "not initialized"
        case .AlreadyInitialized: return "already initialized"
        case .Stopped: return "stopped; relaunch to run a node again"
        case let .NotFound(reason): return "not found: \(reason)"
        case let .Config(reason): return "config: \(reason)"
        case let .Storage(reason): return "storage: \(reason)"
        case let .Network(reason): return "network: \(reason)"
        case let .Internal(reason): return "internal: \(reason)"
        }
    }
}

/// Adapts the bridge's numeric status callback to the shared listener, which
/// takes the same state names the Android JNI callback sends
/// (`jni_bridge/lifecycle.rs`).
final class StatusListenerBridge: StatusListener, @unchecked Sendable {

    private let downstream: any LightClientStatusListener

    init(_ downstream: any LightClientStatusListener) {
        self.downstream = downstream
    }

    func onStatus(status: UInt8) {
        downstream.onStatusChanged(status: Self.name(for: status), data: "")
    }

    /// The bridge sends no payload with a state change on either platform, so
    /// `data` is always empty.
    static func name(for status: UInt8) -> String {
        switch status {
        case 1: return "running"
        case 2: return "stopped"
        default: return "initialized"
        }
    }
}
