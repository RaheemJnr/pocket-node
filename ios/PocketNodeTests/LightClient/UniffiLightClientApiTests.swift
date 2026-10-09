import CkbLightClient
import PocketNodeCore
import XCTest

@testable import PocketNode

/// The iOS binding of the shared `LightClientApi` protocol.
///
/// Nothing has called `initLightClient` in this process - `LightClientService`
/// skips its bootstrap under XCTest - so the bridge answers `NotInitialized`
/// to every query. That is exactly the case worth pinning: the shared contract
/// says a failed query is `nil`, never a throw and never a crash, and this is
/// the only way to exercise it offline.
final class UniffiLightClientApiTests: XCTestCase {

    private var api: UniffiLightClientApi!

    override func setUp() {
        super.setUp()
        api = UniffiLightClientApi()
    }

    override func tearDown() {
        api = nil
        super.tearDown()
    }

    /// The Kotlin interface reaches Swift as an Objective-C protocol named
    /// `LightClientApi`; this is the compile-time and runtime proof that the
    /// Swift class satisfies it, so the shared engines can take it.
    func testConformsToTheSharedKotlinProtocol() {
        XCTAssertTrue(api is any LightClientApi)

        let shared: any LightClientApi = api
        XCTAssertNotNil(shared)
    }

    /// 0 = INIT, 2 = STOPPED. Nothing in this process ever brought a node up,
    /// so the observed value is 0; 2 would only mean some other test shut one
    /// down first, which is why the assertion allows it rather than pinning 0.
    func testStatusIsInitOrStoppedWithoutANode() {
        let status = api.status()

        XCTAssertTrue(status == 0 || status == 2, "unexpected status \(status)")
    }

    func testIsInitializedIsFalseBeforeInitialization() {
        XCTAssertFalse(api.isInitialized())
    }

    /// `NotInitialized` has to come back as nil, not as a throw or a trap.
    ///
    /// `callRpc` is deliberately absent: it is the one query that does not
    /// answer nil here. See `testCallRpcReportsFailureInBand`.
    func testQueriesReturnNilWhenTheNodeIsNotRunning() {
        XCTAssertNil(api.getTipHeader())
        XCTAssertNil(api.getGenesisBlock())
        XCTAssertNil(api.getScripts())
        XCTAssertNil(api.localNodeInfo())
        XCTAssertNil(api.getPeers())
        XCTAssertNil(api.getHeader(hash: "0x00"))
        XCTAssertNil(api.getTransaction(hash: "0x00"))
    }

    /// The exception to the null-on-failure contract. `bridge_core/rpc.rs`
    /// reports an uninitialised node and an unknown method *inside* a JSON-RPC
    /// 2.0 response, which the bridge returns as a success, so a caller that
    /// tests `callRpc` for nil reads a failure as an answer.
    func testCallRpcReportsFailureInBand() {
        let notInitialized = api.callRpc(method: "get_tip_header")

        let envelope = try? XCTUnwrap(notInitialized)
        XCTAssertNotNil(envelope, "callRpc must answer a JSON-RPC envelope, not nil")
        XCTAssertTrue(notInitialized?.contains("\"error\"") ?? false)
        XCTAssertTrue(notInitialized?.contains("-32603") ?? false)

        // An unknown method takes the same shape with a different code, and is
        // likewise not nil.
        let unknown = api.callRpc(method: "no_such_method")
        XCTAssertTrue(unknown?.contains("\"error\"") ?? false)
    }

    /// Malformed input must be absorbed the same way. A non-throwing Kotlin
    /// function aborts the process if an exception escapes, so the adapter has
    /// to be the one that catches.
    ///
    /// `sendTransaction` is absent: it is the second in-band exception to the
    /// contract. See `testSendTransactionReportsTheRejectionReasonInBand`.
    func testGetCellsWithMalformedSearchKeyReturnsNil() {
        XCTAssertNil(api.getCells(searchKeyJson: "not json", order: "asc", limit: 10, cursor: nil))
        XCTAssertNil(
            api.getTransactions(searchKeyJson: "{", order: "desc", limit: 10, cursor: "0xbad")
        )
        XCTAssertNil(api.getCellsCapacity(searchKeyJson: "]"))
    }

    /// A rejected broadcast used to collapse to nil, which reached the
    /// user as "Send failed - native returned null" with no reason at all. The
    /// binding now answers the shared sentinel plus the bridge's own words, so
    /// `SendPipeline` can strip the prefix and surface what actually happened.
    ///
    /// The node is not initialised in this process, so the reason here is the
    /// `NotInitialized` one. What is pinned is the shape: the exact prefix the
    /// shared code strips, and a reason that is not empty.
    func testSendTransactionReportsTheRejectionReasonInBand() throws {
        let prefix = SendPipeline.companion.BROADCAST_ERROR_PREFIX

        let answer = try XCTUnwrap(
            api.sendTransaction(txJson: "not json"),
            "a rejected broadcast must carry its reason, not nil"
        )

        XCTAssertTrue(answer.hasPrefix(prefix), "expected the shared sentinel prefix")
        XCTAssertFalse(
            String(answer.dropFirst(prefix.count)).isEmpty,
            "the sentinel must be followed by a reason"
        )
    }

    func testDaoHelpersReturnTheFailureSentinelsOnBadInput() {
        XCTAssertNil(api.extractDaoFields(daoHex: "0xnothex"))
        XCTAssertNil(api.calculateUnlockEpoch(depositEpochHex: "zz", withdrawEpochHex: "zz"))
        XCTAssertEqual(
            api.calculateMaxWithdraw(
                depositHeaderDaoHex: "0xnothex",
                withdrawHeaderDaoHex: "0xnothex",
                depositCapacity: 0,
                occupiedCapacity: 0
            ),
            -1
        )
    }

    // No test calls stop(). It is terminal for the process (the `OnceLock`
    // globals cannot be cleared) and would change what every later test sees.
    // The lifecycle paths beyond the one case below are exercised on device,
    // not in this bundle.

    /// `start` before `init` must refuse instead of flipping the process
    /// to RUNNING. Before the fix, `bridge_core::lifecycle::start` only
    /// checked that the state flag read INIT, which is also the flag's
    /// default before `init` ever ran, so calling `start` here used to move
    /// the whole process to RUNNING and change what every later test in this
    /// bundle observed. It is safe to call now: the guard checks that `init`
    /// actually published its storage, so this fails without touching state.
    func testStartBeforeInitFailsWithoutFlippingTheProcessToRunning() {
        let statusBefore = api.status()

        let started = api.start()

        XCTAssertFalse(started, "start before init must report failure")
        XCTAssertNotEqual(api.status(), 1, "start before init must not move to RUNNING")
        XCTAssertEqual(api.status(), statusBefore, "status must be unchanged by the failed start")
    }

    /// The bridge reports a numeric state; the shared listener takes the same
    /// names the Android JNI callback sends.
    func testStatusNamesMatchTheAndroidCallbackVocabulary() {
        XCTAssertEqual(StatusListenerBridge.name(for: 0), "initialized")
        XCTAssertEqual(StatusListenerBridge.name(for: 1), "running")
        XCTAssertEqual(StatusListenerBridge.name(for: 2), "stopped")
        XCTAssertEqual(StatusListenerBridge.name(for: 99), "initialized")
    }

    func testStatusListenerBridgeForwardsToTheSharedListener() {
        let spy = StatusListenerSpy()
        let bridge = StatusListenerBridge(spy)

        bridge.onStatus(status: 1)
        bridge.onStatus(status: 2)

        XCTAssertEqual(spy.statuses, ["running", "stopped"])
        XCTAssertEqual(spy.payloads, ["", ""])
    }

    func testErrorReasonsAreRenderedForLogging() {
        XCTAssertEqual(
            UniffiLightClientApi.reason(for: LightClientError.NotInitialized),
            "not initialized"
        )
        XCTAssertEqual(
            UniffiLightClientApi.reason(for: LightClientError.NotFound(reason: "header 0x1")),
            "not found: header 0x1"
        )
    }
}

/// Records what the adapter hands the shared listener.
private final class StatusListenerSpy: NSObject, LightClientStatusListener {
    private(set) var statuses: [String] = []
    private(set) var payloads: [String] = []

    func onStatusChanged(status: String, data: String) {
        statuses.append(status)
        payloads.append(data)
    }
}
