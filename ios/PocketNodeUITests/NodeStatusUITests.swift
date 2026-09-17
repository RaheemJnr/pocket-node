import XCTest

/// End-to-end acceptance: launch the app with a wallet, open Node Status, find
/// the node already running, wait for the testnet tip to advance past genesis,
/// then stop it.
///
/// It used to tap Start to get there. From M3 it does not have to: a wallet
/// brings its own node up, because `SyncService.activate` cannot ask a user to
/// go and find that button before their balance will sync. So Start is already
/// disabled by the time this test arrives, and that is now the assertion rather
/// than the step.
///
/// The node has to reach real testnet bootnodes, so this test lives in its own
/// `PocketNodeNetwork` scheme, which sets `POCKETNODE_NETWORK_TESTS=1` in its
/// test action (`project.yml`). `PocketNodeUITests` also runs under the
/// offline `PocketNode` scheme for CI (#517), and that scheme does not set the
/// variable, so this test skips itself there rather than timing out against a
/// network `ios-ci.yml` has no route to.
final class NodeStatusUITests: XCTestCase {
    private static let tipTimeout: TimeInterval = 120
    private static let stopTimeout: TimeInterval = 10
    /// Sync activation waits for the node itself before starting it, so the
    /// running state can take a few seconds longer to arrive than the screen.
    private static let runningTimeout: TimeInterval = 60

    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["POCKETNODE_NETWORK_TESTS"] == "1",
            "needs live testnet bootnodes; run under the PocketNodeNetwork scheme"
        )
    }

    func testNodeStatusReachesTestnetTipAndStops() throws {
        let app = XCUIApplication()
        // `NetworkPreferences` defaults to mainnet (#514); this test needs
        // testnet, so it asks `AppContainer` to select it at launch rather
        // than the app defaulting to it for everyone.
        app.launchEnvironment["POCKETNODE_NETWORK"] = "testnet"
        // #515 put onboarding in front of the wallet shell this test drives.
        // Nothing on the Node Status path reads key material, so `AppContainer`
        // answers this by writing a throwaway metadata record rather than by
        // minting a real wallet. That record is also what makes the wallet
        // shell activate sync, which is what starts the node.
        app.launchEnvironment["POCKETNODE_SKIP_ONBOARDING"] = "1"
        // Only matters on a physical device, which is where this suite is
        // worth running: XCUITest records the screen there, `PrivacyShield`
        // correctly treats that as a capture, and its overlay then swallows
        // every tap while leaving the elements underneath findable. Without
        // this the run reads as "tapped Node Status, never arrived".
        app.launchEnvironment["POCKETNODE_UITEST_ALLOW_CAPTURE"] = "1"
        app.launch()

        app.buttons["root.nodeStatus"].tap()
        XCTAssertTrue(app.navigationBars["Node Status"].waitForExistence(timeout: 10))

        let status = app.staticTexts["nodeStatus.status"]
        let start = app.buttons["Start"]
        let stop = app.buttons["Stop"]
        XCTAssertTrue(start.waitForExistence(timeout: 10))

        // The node was started by sync activation, not by this test. Waiting
        // rather than reading: the screen redraws from a status that arrives
        // asynchronously (the Rust listener callback, then the 5s refresh).
        let running = expectation(for: NSPredicate(format: "label ENDSWITH %@", "Running"),
                                  evaluatedWith: status)
        wait(for: [running], timeout: Self.runningTimeout)
        wait(for: [disabled(start)], timeout: Self.stopTimeout)
        wait(for: [enabled(stop)], timeout: Self.stopTimeout)

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
        XCTAssertGreaterThan(
            Self.trailingNumber(in: peers.label),
            0,
            "No peers after \(Int(elapsed))s; the tip cannot have come from nowhere"
        )

        // #487: Stop used to deadlock in the bridge, so the screen sat on
        // "Running" forever. It now has to reach Stopped promptly, and stay
        // there — a stopped node cannot be restarted in-process.
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
