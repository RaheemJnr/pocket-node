import XCTest

/// What every M3 acceptance test launches with, in one place.
///
/// The four suites below it (`SyncModeUITests`, `ActivityUITests`,
/// `SendUITests`, `ScannerUITests`) are all offline: the light client starts
/// because the wallet shell starts it, finds no peers on a simulator, and
/// nothing here ever waits on a sync, a balance or a chain read. What they
/// cover is the part the view-model tests cannot, that the screens are
/// reachable and that the words on them are the ones the shared core produced.
enum M3UITest {

    /// The pinned testnet address of the seeded UI Test Wallet, and the same
    /// wallet on mainnet. Both come from `AppContainer.seedWalletForTestingIfRequested`,
    /// which takes them from `WalletCreatorTests`, so they are real addresses
    /// that decode on both platforms.
    static let testnetAddress =
        "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"
    static let mainnetAddress =
        "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft"

    /// Launches the app on testnet with the seeded wallet and no PIN.
    ///
    /// `POCKETNODE_RESET_STATE` wipes the Keychain envelope, the PIN items, the
    /// wallet record and the install marker before anything else in
    /// `AppContainer.init` runs, so each test starts from the same device;
    /// `POCKETNODE_SKIP_ONBOARDING` then writes the throwaway metadata record
    /// that opens the wallet gate. The two `POCKETNODE_UITEST_*` flags let the
    /// privacy shield through XCUITest's screen recording and expose the words
    /// SwiftUI would otherwise mark privacy-sensitive.
    ///
    /// - Parameter route: a `POCKETNODE_START_ROUTE` value for a test that does
    ///   not care how the screen was reached. Left nil by the tests whose point
    ///   is that Home reaches it.
    @MainActor
    static func launch(route: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["POCKETNODE_RESET_STATE"] = "1"
        app.launchEnvironment["POCKETNODE_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["POCKETNODE_NETWORK"] = "testnet"
        app.launchEnvironment["POCKETNODE_UITEST_ALLOW_CAPTURE"] = "1"
        app.launchEnvironment["POCKETNODE_UITEST_EXPOSE_WORDS"] = "1"
        if let route {
            app.launchEnvironment["POCKETNODE_START_ROUTE"] = route
        }
        app.launch()
        return app
    }

    /// Launches and taps through to the Send screen from Home, which is also
    /// the assertion that Home reaches it.
    @MainActor
    static func launchToSend(_ test: XCTestCase) -> XCUIApplication {
        let app = launch()
        let send = app.buttons["home.send"]
        XCTAssertTrue(send.waitForExistence(timeout: 30), "Home never came up")
        send.tap()
        XCTAssertTrue(
            app.textFields["send.recipient"].waitForExistence(timeout: 15),
            "Home did not reach the Send screen"
        )
        return app
    }

    /// Types into a text field, tapping it first so the keyboard is up.
    @MainActor
    static func type(_ text: String, into field: XCUIElement) {
        field.tap()
        field.typeText(text)
    }

    /// The label of whichever static text carries `identifier`.
    ///
    /// `firstMatch` on purpose: SwiftUI pushes an identifier set on a plain
    /// container down onto every element inside it, so a query can match more
    /// than one element even where the code sets it once.
    @MainActor
    static func label(_ identifier: String, in app: XCUIApplication, timeout: TimeInterval = 10) -> String {
        let element = app.staticTexts[identifier].firstMatch
        guard element.waitForExistence(timeout: timeout) else { return "" }
        return element.label
    }
}
