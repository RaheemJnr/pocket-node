import XCTest

/// M3 acceptance: the QR scanner on a device with no camera.
///
/// A simulator has no capture device, so `AVFoundationQrScanner` answers
/// `.unavailable` rather than `.denied` and the sheet draws the "camera not
/// available" card instead of asking for permission. No permission alert is
/// ever raised here, which is what keeps the suite runnable unattended.
///
/// The paste field is not only a simulator affordance: it is the way anyone who
/// has copied an address rather than photographed one gets it into the form,
/// and it runs the same validation a decoded payload does.
@MainActor
final class ScannerUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testTheScannerFallsBackToPastingWhenThereIsNoCamera() throws {
        let app = M3UITest.launchToSend(self)

        app.buttons["send.scan"].firstMatch.tap()

        XCTAssertTrue(
            app.staticTexts["Camera not available"].firstMatch.waitForExistence(timeout: 15),
            "the simulator should report the camera as unavailable, not denied"
        )
        XCTAssertFalse(
            app.buttons["scanner.openSettings"].firstMatch.exists,
            "an unavailable camera is not a denied one, so there is nothing to open Settings for"
        )
        XCTAssertTrue(app.textFields["scanner.pasteField"].firstMatch.exists)

        let useAddress = app.buttons["scanner.useButton"].firstMatch
        XCTAssertTrue(useAddress.exists)
        XCTAssertFalse(useAddress.isEnabled, "an empty paste field has nothing to use")
    }

    func testPastingAValidAddressDismissesTheScannerWithTheRecipientFilled() throws {
        let app = M3UITest.launchToSend(self)

        app.buttons["send.scan"].firstMatch.tap()

        let pasteField = app.textFields["scanner.pasteField"]
        XCTAssertTrue(pasteField.waitForExistence(timeout: 15))
        M3UITest.type(M3UITest.testnetAddress, into: pasteField)

        let useAddress = app.buttons["scanner.useButton"].firstMatch
        XCTAssertTrue(useAddress.isEnabled, "a typed address should be usable")
        useAddress.tap()

        let recipient = app.textFields["send.recipient"]
        XCTAssertTrue(recipient.waitForExistence(timeout: 15), "the scanner did not dismiss")
        XCTAssertEqual(recipient.value as? String, M3UITest.testnetAddress)
        XCTAssertEqual(M3UITest.label("send.addressState", in: app), "Valid CKB testnet address")
    }
}
