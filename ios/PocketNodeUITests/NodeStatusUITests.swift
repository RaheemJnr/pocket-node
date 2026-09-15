import XCTest

/// End-to-end M1 acceptance: launch the app, open Node Status, start the node,
/// wait for the testnet tip to advance past genesis, then stop it.
///
/// The node has to reach real testnet bootnodes, so this test lives in its own
/// `PocketNodeNetwork` scheme and is not part of the default test action.
final class NodeStatusUITests: XCTestCase {
    private static let tipTimeout: TimeInterval = 120
    private static let stopTimeout: TimeInterval = 10

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testNodeStatusReachesTestnetTipAndStops() throws {
        let app = XCUIApplication()
        app.launch()

        app.buttons["root.nodeStatus"].tap()
        XCTAssertTrue(app.navigationBars["Node Status"].waitForExistence(timeout: 10))

        let start = app.buttons["Start"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))
        start.tap()

        let tip = app.staticTexts["nodeStatus.tipBlock"]
        let peers = app.staticTexts["nodeStatus.peers"]
        XCTAssertTrue(tip.waitForExistence(timeout: 10))

        let began = Date()
        var observedTip = 0
        while Date().timeIntervalSince(began) < Self.tipTimeout {
            observedTip = Self.trailingNumber(in: tip.label)
            if observedTip > 0 { break }
            _ = tip.waitForExistence(timeout: 2)
        }

        let elapsed = Date().timeIntervalSince(began)
        print("NODESTATUS tip=\(observedTip) tipLabel=\(tip.label) peers=\(peers.label) elapsed=\(Int(elapsed))s")

        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "NodeStatus"
        shot.lifetime = .keepAlways
        add(shot)

        XCTAssertGreaterThan(observedTip, 0, "Tip block stayed at 0 after \(Int(elapsed))s")

        // #487: Stop used to deadlock in the bridge, so the screen sat on
        // "Running" forever. It now has to reach Stopped promptly, and stay
        // there — a stopped node cannot be restarted in-process.
        let status = app.staticTexts["nodeStatus.status"]
        let stop = app.buttons["Stop"]
        XCTAssertTrue(stop.isEnabled, "Stop should be enabled while running")
        stop.tap()

        let stopped = expectation(for: NSPredicate(format: "label ENDSWITH %@", "Stopped"),
                                  evaluatedWith: status)
        wait(for: [stopped], timeout: Self.stopTimeout)

        print("NODESTATUS stopped statusLabel=\(status.label) startEnabled=\(start.isEnabled)")

        let stoppedShot = XCTAttachment(screenshot: app.screenshot())
        stoppedShot.name = "NodeStatusStopped"
        stoppedShot.lifetime = .keepAlways
        add(stoppedShot)

        XCTAssertFalse(start.isEnabled, "Start must stay disabled after a stop")
        XCTAssertTrue(app.staticTexts["nodeStatus.relaunchNotice"].exists,
                      "The relaunch notice should explain why Start is disabled")
    }

    /// A `LabeledContent` row reads back as "Tip block, 1 234 567", so take the
    /// digits after the last separator.
    private static func trailingNumber(in label: String) -> Int {
        let digits = label.unicodeScalars.reversed()
            .prefix { CharacterSet.decimalDigits.contains($0) || $0 == "," || $0 == " " || $0 == "\u{202F}" }
            .reversed()
            .filter { CharacterSet.decimalDigits.contains($0) }
        return Int(String(String.UnicodeScalarView(digits))) ?? 0
    }
}
