import XCTest

/// Drives the wallet shell's navigation on a simulator: Home to Receive, the
/// backup nag on both of them, and Home to Settings to the backup flow.
///
/// Offline, unlike its neighbour `NodeStatusUITests`: nothing here starts the
/// light client. It lives in the same `PocketNodeNetwork` scheme only because
/// that is where the UI-test target is wired, and it is what covers the one
/// thing the view-model tests cannot, that `RootView`'s routes are reachable.
///
/// The wallet is the throwaway metadata record `AppContainer` writes for
/// `POCKETNODE_SKIP_ONBOARDING`: a mnemonic wallet marked not backed up, which
/// is exactly the state the nag is for.
@MainActor
final class WalletShellUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testHomeReachesReceiveAndSettingsAndTheBackupFlow() throws {
        let app = XCUIApplication()
        app.launchEnvironment["POCKETNODE_SKIP_ONBOARDING"] = "1"
        app.launch()

        // Home: the wallet card, the nag, and the way to Receive.
        XCTAssertTrue(app.staticTexts["home.address"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["home.backupBanner"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["home.receive"].exists)
        capture(named: "wiring-home-banner")

        // Receive, including the protect prompt an un-backed-up wallet raises.
        app.buttons["home.receive"].tap()
        XCTAssertTrue(app.buttons["receive.copy"].waitForExistence(timeout: 10))
        let notNow = app.buttons["Not now"]
        if notNow.waitForExistence(timeout: 5) {
            notNow.tap()
        }
        XCTAssertTrue(app.staticTexts["receive.address"].exists)
        capture(named: "wiring-receive")

        // Back to Home, then into Settings.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["home.receive"].waitForExistence(timeout: 10))

        app.buttons["root.settings"].tap()
        XCTAssertTrue(app.buttons["settings.backup"].waitForExistence(timeout: 10))
        capture(named: "wiring-settings")

        // Settings to the backup flow. It stops at the reveal gate on purpose:
        // this wallet is metadata only, so revealing has nothing to decrypt.
        app.buttons["settings.backup"].tap()
        XCTAssertTrue(app.buttons["backup.reveal"].waitForExistence(timeout: 10))
        capture(named: "wiring-backup-gate")

        // The node status entry point `NodeStatusUITests` depends on is still
        // on the Home toolbar after the settings gear joined it.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["settings.backup"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["root.nodeStatus"].waitForExistence(timeout: 10))
    }

    /// Saves a full-screen PNG next to the test attachment, so the images can
    /// be copied off the simulator the same way the unit-test screenshots are.
    private func capture(named name: String) {
        let screenshot = XCUIScreen.main.screenshot()

        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)

        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("\(name).png")
        do {
            try screenshot.pngRepresentation.write(to: url)
            print("SCREENSHOT \(name): \(url.path)")
        } catch {
            XCTFail("could not write \(name): \(error)")
        }
    }
}
