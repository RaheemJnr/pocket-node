import XCTest

/// M3 acceptance: the sync-mode choice, offline.
///
/// A wallet with no mode chosen is the one state the Home card asks the user to
/// resolve, and it is the state `POCKETNODE_RESET_STATE` leaves the device in.
/// Nothing here confirms a choice, so nothing is ever registered with the light
/// client and no test that runs after this one inherits a mode.
@MainActor
final class SyncModeUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Home invites the choice, the sheet offers four modes, and Recent is the
    /// one marked Recommended.
    func testHomeOffersTheFourModesWithRecentRecommended() throws {
        let app = M3UITest.launch()

        let choose = app.buttons["home.syncChooseButton"]
        XCTAssertTrue(choose.waitForExistence(timeout: 30), "Home never offered the sync-mode prompt")
        choose.tap()

        for mode in ["newWallet", "recent", "custom", "fullHistory"] {
            XCTAssertTrue(
                app.buttons["syncMode.option.\(mode)"].firstMatch.waitForExistence(timeout: 10),
                "the sheet is missing the \(mode) option"
            )
        }

        // The badge is a `Text` inside the option's `Button`, so SwiftUI folds
        // it into that button's label rather than leaving it separately
        // addressable. Reading the label is what makes the assertion about
        // WHICH option carries it rather than only that one of them does.
        let recent = app.buttons["syncMode.option.recent"].firstMatch
        XCTAssertTrue(recent.label.contains("Recommended"), "recent's label was \(recent.label)")

        let newWallet = app.buttons["syncMode.option.newWallet"].firstMatch
        XCTAssertFalse(newWallet.label.contains("Recommended"), "new wallet's label was \(newWallet.label)")
    }

    /// A custom start needs a block number above zero before it can be applied.
    func testACustomStartCannotBeConfirmedUntilAPositiveHeightIsTyped() throws {
        let app = M3UITest.launch()

        let choose = app.buttons["home.syncChooseButton"]
        XCTAssertTrue(choose.waitForExistence(timeout: 30))
        choose.tap()

        let custom = app.buttons["syncMode.option.custom"].firstMatch
        XCTAssertTrue(custom.waitForExistence(timeout: 10))
        custom.tap()

        let confirm = app.buttons["syncMode.confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        XCTAssertFalse(confirm.isEnabled, "an empty height should not be applicable")

        let height = app.textFields["syncMode.customHeight"].firstMatch
        XCTAssertTrue(height.waitForExistence(timeout: 10), "choosing custom should reveal the height field")
        M3UITest.type("0", into: height)
        XCTAssertFalse(confirm.isEnabled, "block 0 is not a custom start")

        // Typed on the end rather than after a clear: "0" then "12000000" is
        // "012000000", which parses to the same 12,000,000 and saves the test
        // driving a system delete key.
        height.typeText("12000000")
        XCTAssertTrue(
            confirm.waitForExistence(timeout: 5) && confirm.isEnabled,
            "a positive height should be applicable"
        )
    }

    /// Cancelling leaves the wallet exactly as it was, still asking.
    func testCancellingLeavesThePromptInPlace() throws {
        let app = M3UITest.launch()

        let choose = app.buttons["home.syncChooseButton"]
        XCTAssertTrue(choose.waitForExistence(timeout: 30))
        choose.tap()

        let cancel = app.buttons["syncMode.cancel"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10))
        cancel.tap()

        XCTAssertTrue(
            app.buttons["home.syncChooseButton"].waitForExistence(timeout: 10),
            "the card should still be asking after a cancel"
        )
    }
}
