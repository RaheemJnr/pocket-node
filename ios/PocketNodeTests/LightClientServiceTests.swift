import CkbLightClient
import XCTest

@testable import PocketNode

final class LightClientServiceTests: XCTestCase {
    /// Nothing has called `initLightClient` in this process, so the bridge must
    /// report the INIT state rather than trap.
    func testStatusIsInitBeforeInitialization() {
        XCTAssertEqual(getStatus(), 0)
    }

    /// A node waiting in INIT is the only startable state.
    func testControlsAllowStartOnlyFromInit() {
        let controls = NodeControls(status: .initializing, isInitialized: true)

        XCTAssertTrue(controls.canStart)
        XCTAssertFalse(controls.canStop)
        XCTAssertFalse(controls.showsRelaunchNotice)
    }

    /// Nothing is startable before init finishes.
    func testControlsDisableStartBeforeInitialization() {
        let controls = NodeControls(status: .initializing, isInitialized: false)

        XCTAssertFalse(controls.canStart)
        XCTAssertFalse(controls.canStop)
    }

    func testControlsAllowStopOnlyWhileRunning() {
        let controls = NodeControls(status: .running, isInitialized: true)

        XCTAssertTrue(controls.canStop)
        XCTAssertFalse(controls.canStart)
        XCTAssertFalse(controls.showsRelaunchNotice)
    }

    /// Stop is terminal: the bridge cannot restart the node in-process, so the
    /// screen has to keep Start disabled and say why (#487).
    func testControlsStayDisabledAfterStop() {
        let controls = NodeControls(status: .stopped, isInitialized: true)

        XCTAssertFalse(controls.canStart)
        XCTAssertFalse(controls.canStop)
        XCTAssertTrue(controls.showsRelaunchNotice)
    }

    /// `describe` lives on the main-actor-isolated service, so the test has to
    /// be isolated too under Swift 6 concurrency checking.
    @MainActor
    func testStoppedErrorExplainsThatRelaunchIsNeeded() {
        let message = LightClientService.describe(LightClientError.Stopped)

        XCTAssertEqual(message, "The node was stopped. Relaunch the app to start it again.")
    }

    func testHexParsingDecodesPrefixedValue() {
        XCTAssertEqual(Hex.parse("0x1a"), 26)
        XCTAssertEqual(Hex.parse("1a"), 26)
        XCTAssertNil(Hex.parse("0x"))
        XCTAssertNil(Hex.parse("nope"))
    }
}
