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
        // `NetworkPreferences` defaults to mainnet (#514); this test needs
        // testnet, so it asks `AppContainer` to select it at launch rather
        // than the app defaulting to it for everyone.
        app.launchEnvironment["POCKETNODE_NETWORK"] = "testnet"
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
        // Every assertion here waits: the button states and the footer are
        // redrawn from a status that arrives asynchronously (the Rust listener
        // callback, then the 5s refresh), so reading them the instant after the
        // tap races the render.
        let status = app.staticTexts["nodeStatus.status"]
        let stop = app.buttons["Stop"]
        wait(for: [enabled(stop)], timeout: Self.stopTimeout)
        stop.tap()

        let stopped = expectation(for: NSPredicate(format: "label ENDSWITH %@", "Stopped"),
                                  evaluatedWith: status)
        wait(for: [stopped], timeout: Self.stopTimeout)

        print("NODESTATUS stopped statusLabel=\(status.label) startEnabled=\(start.isEnabled)")

        let stoppedShot = XCTAttachment(screenshot: app.screenshot())
        stoppedShot.name = "NodeStatusStopped"
        stoppedShot.lifetime = .keepAlways
        add(stoppedShot)

        // Stop is terminal, so Start must not come back.
        wait(for: [disabled(start)], timeout: Self.stopTimeout)
        XCTAssertTrue(
            app.staticTexts["nodeStatus.relaunchNotice"].waitForExistence(timeout: Self.stopTimeout),
            "The relaunch notice should explain why Start is disabled"
        )
    }

    private func enabled(_ element: XCUIElement) -> XCTestExpectation {
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: element)
    }

    private func disabled(_ element: XCUIElement) -> XCTestExpectation {
        expectation(for: NSPredicate(format: "isEnabled == false"), evaluatedWith: element)
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
