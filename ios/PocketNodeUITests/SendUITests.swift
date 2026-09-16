import XCTest

/// M3 acceptance: the send form, offline.
///
/// Nothing here can reach a key: the seeded wallet is metadata only, and the
/// node has no peers, so the wallet's balance is zero and every attempt stops
/// at the form's own validation. That is what makes the suite safe to run
/// anywhere, and it is also the half of the screen worth driving through the
/// real UI, because the sanitiser and the address indicator run per keystroke.
@MainActor
final class SendUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// The three states of the line under the recipient field.
    func testTheRecipientLineNamesInvalidWrongNetworkAndValidAddresses() throws {
        let app = M3UITest.launchToSend(self)

        let recipient = app.textFields["send.recipient"]
        M3UITest.type("not-an-address", into: recipient)
        XCTAssertEqual(M3UITest.label("send.addressState", in: app), "Invalid address format")

        Self.clear(recipient, in: app)
        recipient.typeText(M3UITest.mainnetAddress)
        XCTAssertEqual(
            M3UITest.label("send.addressState", in: app),
            "This is a mainnet address on testnet"
        )

        Self.clear(recipient, in: app)
        recipient.typeText(M3UITest.testnetAddress)
        XCTAssertEqual(M3UITest.label("send.addressState", in: app), "Valid CKB testnet address")
    }

    /// The shared sanitiser drops a ninth decimal as it is typed, and the field
    /// shows what the model kept rather than what was typed.
    ///
    /// The second half is what only a running screen can prove. The model's
    /// truncation is already pinned by `SendViewModelTests`; that the field is
    /// actually redrawn to match is a property of `SendView.amountText`, and a
    /// plain sanitising `Binding` does not have it.
    func testATypedNinthDecimalIsDroppedFromTheField() throws {
        let app = M3UITest.launchToSend(self)

        let amount = app.textFields["send.amount"]
        M3UITest.type("12.3456789", into: amount)
        XCTAssertEqual(amount.value as? String, "12.3456789", "seven decimals are kept as typed")

        amount.typeText("0")
        XCTAssertEqual(amount.value as? String, "12.34567890", "the eighth decimal still lands")

        amount.typeText("1")
        XCTAssertEqual(amount.value as? String, "12.34567890", "the ninth is dropped from the field too")
    }

    /// A character that is not part of a number never lingers on screen.
    ///
    /// `sanitizeAmount` refuses the whole entry rather than stripping the bad
    /// character out of it, so the model keeps the last good value. Without the
    /// mirror the "x" would sit in a field in front of an amount that never
    /// contained it.
    func testATypedLetterDoesNotLingerInTheAmountField() throws {
        let app = M3UITest.launchToSend(self)

        let amount = app.textFields["send.amount"]
        M3UITest.type("61", into: amount)
        XCTAssertEqual(amount.value as? String, "61")

        amount.typeText("x")
        XCTAssertEqual(amount.value as? String, "61", "a rejected character should not linger")

        amount.typeText(".5")
        XCTAssertEqual(amount.value as? String, "61.5", "and typing carries on from the kept value")
    }

    /// Below one minimal cell the form refuses before anything is built.
    func testAnAmountUnderSixtyOneCkbIsRefusedWithTheMinimumAlert() throws {
        let app = M3UITest.launchToSend(self)

        M3UITest.type(M3UITest.testnetAddress, into: app.textFields["send.recipient"])
        M3UITest.type("10", into: app.textFields["send.amount"])
        Self.dismissKeyboard(app)

        app.buttons["send.submit"].firstMatch.tap()

        XCTAssertTrue(
            app.staticTexts["sendFailure.message"].firstMatch.waitForExistence(timeout: 15),
            "no failure dialog for an amount under the minimum"
        )
        XCTAssertEqual(
            M3UITest.label("sendFailure.message", in: app),
            "Minimum transfer is 61 CKB"
        )
    }

    /// What a well-formed send does on a wallet with nothing in it.
    ///
    /// The review sheet is deliberately NOT reached: `validate()` measures the
    /// amount against the balance the sync layer last read, which on a node
    /// with no peers is zero, so the form stops at "Insufficient balance"
    /// before any cell is selected. The CTA's "Preparing transaction..." is the
    /// other state this path can show, and it only appears once validation has
    /// passed, so asserting the alert is asserting that nothing was priced.
    func testAValidSendOnAnEmptyWalletStopsAtInsufficientBalance() throws {
        let app = M3UITest.launchToSend(self)

        M3UITest.type(M3UITest.testnetAddress, into: app.textFields["send.recipient"])
        M3UITest.type("100", into: app.textFields["send.amount"])
        Self.dismissKeyboard(app)

        app.buttons["send.submit"].firstMatch.tap()

        XCTAssertTrue(
            app.staticTexts["sendFailure.message"].firstMatch.waitForExistence(timeout: 20),
            "no outcome at all from a send on an empty wallet"
        )
        XCTAssertEqual(M3UITest.label("sendFailure.message", in: app), "Insufficient balance")
        XCTAssertFalse(
            app.staticTexts["review.total"].firstMatch.exists,
            "the review sheet should never open without cells to fund it"
        )
    }

    /// The scan button presents the scanner, and its paste fallback is what
    /// fills the field on a device with no camera.
    func testTheScanButtonPresentsTheScannerAndItsPasteFallbackFillsTheRecipient() throws {
        let app = M3UITest.launchToSend(self)

        app.buttons["send.scan"].firstMatch.tap()

        let pasteField = app.textFields["scanner.pasteField"]
        XCTAssertTrue(pasteField.waitForExistence(timeout: 15), "the scanner sheet never appeared")

        M3UITest.type(M3UITest.testnetAddress, into: pasteField)
        app.buttons["scanner.useButton"].firstMatch.tap()

        XCTAssertTrue(
            app.textFields["send.recipient"].waitForExistence(timeout: 15),
            "the scanner did not dismiss"
        )
        XCTAssertEqual(M3UITest.label("send.addressState", in: app), "Valid CKB testnet address")
    }

    // MARK: - Helpers

    /// Empties a text field by selecting everything in it and typing over it.
    ///
    /// A CKB address is 95 characters, so deleting one key at a time is both
    /// slow and flaky. The select-all menu is the shortest reliable route the
    /// simulator offers.
    private static func clear(_ field: XCUIElement, in app: XCUIApplication) {
        field.tap()
        field.press(forDuration: 1.2)
        let selectAll = app.menuItems["Select All"]
        if selectAll.waitForExistence(timeout: 5) {
            selectAll.tap()
        }
        field.typeText(XCUIKeyboardKey.delete.rawValue)
    }

    /// Puts the decimal pad away so it cannot sit over the submit button.
    private static func dismissKeyboard(_ app: XCUIApplication) {
        app.staticTexts["Available"].firstMatch.tap()
    }
}
