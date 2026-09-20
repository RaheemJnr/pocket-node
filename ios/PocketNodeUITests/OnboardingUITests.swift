import XCTest

/// M2 acceptance (#517): drives the real onboarding flow end to end on a
/// simulator, entirely offline — create a wallet and import one, through
/// welcome, backup, the PIN pad and Receive.
///
/// Each test launches with `POCKETNODE_RESET_STATE=1`, which asks
/// `AppContainer` to wipe the wallet envelope, the PIN Keychain items, the
/// wallet metadata record and the install marker before anything else in
/// `init()` runs (see `AppContainer.resetStateForTestingIfRequested`). Without
/// it, only the first launch of a simulator would ever see onboarding:
/// `POCKETNODE_SKIP_ONBOARDING` (used by `WalletShellUITests` and
/// `NodeStatusUITests`) only opens the gate in front of an already-seeded
/// wallet, it does not clear one out.
///
/// `POCKETNODE_NETWORK=testnet` is what makes the Receive assertions land on
/// `ckt1`, matching Android's default sync-mode choice for a fresh wallet and
/// the `ckt1` fixture pinned on both platforms.
@MainActor
final class OnboardingUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Both tests write a real wallet to the Keychain and to `wallet.json`,
    /// which outlives the app process. `WalletShellUITests` and
    /// `NodeStatusUITests` share this simulator and do not reset state
    /// themselves — they rely on `POCKETNODE_SKIP_ONBOARDING` only writing its
    /// throwaway record when no wallet is already there — so leaving one
    /// behind would silently change what they see depending on run order.
    /// Relaunching once more with the same reset flag leaves the device the
    /// way this suite found it.
    ///
    /// `MainActor.assumeIsolated` because XCTest declares this override
    /// nonisolated on Xcode 16 (the CI runner) while `XCUIApplication` is
    /// main-actor only; XCTest always calls teardown on the main thread, so
    /// the assumption holds. Xcode 26 accepts the direct calls, which is why
    /// this only ever failed in CI.
    override func tearDownWithError() throws {
        MainActor.assumeIsolated {
            let cleanup = XCUIApplication()
            cleanup.launchEnvironment["POCKETNODE_RESET_STATE"] = "1"
            cleanup.launch()
            cleanup.terminate()
        }
        try super.tearDownWithError()
    }

    /// Welcome -> Create -> the 12-word backup grid and quiz -> PIN -> Home
    /// with the backup nag gone -> Receive with a `ckt1` address.
    func testCreateWalletFlowShowsATestnetReceiveAddress() throws {
        let app = XCUIApplication()
        app.launchEnvironment["POCKETNODE_NETWORK"] = "testnet"
        app.launchEnvironment["POCKETNODE_RESET_STATE"] = "1"
        app.launchEnvironment["POCKETNODE_UITEST_ALLOW_CAPTURE"] = "1"
        app.launchEnvironment["POCKETNODE_UITEST_EXPOSE_WORDS"] = "1"
        app.launch()

        // Welcome -> Create wallet (defaults: 12 words, "My Wallet").
        XCTAssertTrue(app.buttons["onboarding.create"].waitForExistence(timeout: 20))
        app.buttons["onboarding.create"].firstMatch.tap()

        XCTAssertTrue(app.buttons["create.submit"].waitForExistence(timeout: 10))
        app.buttons["create.submit"].firstMatch.tap()

        // Backup: reveal is exempt from the PIN gate this once, but the tap
        // is still required to move past it.
        XCTAssertTrue(app.buttons["backup.reveal"].waitForExistence(timeout: 10))
        app.buttons["backup.reveal"].firstMatch.tap()

        // Read the 12 generated words back off the grid before writing
        // anything down: this is the only copy this test ever sees them.
        //
        // 20s rather than 10, but the timeout is not really the point: on a
        // physical iPhone this step waits for a PERSON. `reveal()` unwraps the
        // data key with the Secure Enclave key guarded by the current
        // biometric set, so it raises a Face ID prompt that XCUITest cannot
        // answer; on a simulator the key is software and there is no prompt.
        // This case is therefore attended-only on hardware. The longer wait
        // buys a moment to look at the phone, nothing more.
        XCTAssertTrue(app.staticTexts.matching(identifier: "backup.word.11").firstMatch.waitForExistence(timeout: 20))
        let words = (0..<12).map { Self.word(at: $0, in: app) }
        XCTAssertEqual(words.count, 12)
        XCTAssertTrue(words.allSatisfy { !$0.isEmpty }, "\(words)")

        app.buttons["backup.wroteItDown"].firstMatch.tap()

        // Verify: answer each of the 3 prompts with the word this test just
        // read at that position, rather than guessing.
        let verifyButton = app.buttons["backup.verifyButton"]
        XCTAssertTrue(verifyButton.waitForExistence(timeout: 10))
        for position in Self.quizPositions(in: app) {
            let word = words[position]
            let choice = app.buttons["backup.verify.\(position).\(word)"]
            XCTAssertTrue(choice.waitForExistence(timeout: 5), "no choice button for word #\(position + 1) (\(word))")
            choice.tap()
        }
        wait(for: [enabled(verifyButton)], timeout: 5)
        verifyButton.tap()

        XCTAssertTrue(app.buttons["backup.done"].waitForExistence(timeout: 10))
        app.buttons["backup.done"].firstMatch.tap()

        // PIN setup: create, then confirm with the same 6 digits.
        XCTAssertTrue(app.staticTexts["Create PIN"].waitForExistence(timeout: 10))
        Self.enterPin("123456", in: app)
        XCTAssertTrue(app.staticTexts["Confirm PIN"].waitForExistence(timeout: 10))
        Self.enterPin("123456", in: app)

        // Biometric opt-in only appears where the simulator has a sensor
        // enrolled; skip it either way this flow reaches Home.
        let skipBiometrics = app.buttons["pinSetup.skipBiometrics"]
        if skipBiometrics.waitForExistence(timeout: 5) {
            skipBiometrics.tap()
        }

        // Home: the wallet was just verified, so the backup nag is gone.
        XCTAssertTrue(app.staticTexts["home.address"].waitForExistence(timeout: 10))
        XCTAssertNotEqual(app.staticTexts["home.address"].label, "No address yet")
        XCTAssertFalse(app.buttons["home.backupBanner"].exists)
        capture(app: app, named: "517-home")

        // Receive: the testnet address and heading.
        app.buttons["home.receive"].firstMatch.tap()
        let address = app.staticTexts["receive.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        XCTAssertTrue(address.label.hasPrefix("ckt1"), "expected a ckt1 address, got \(address.label)")
        XCTAssertTrue(app.staticTexts["receive.networkHeading"].label.contains("Testnet"))
        capture(app: app, named: "517-receive")
    }

    /// Cross-platform parity check: importing the standard all-"abandon" test
    /// phrase must reach the same `ckt1` address the shared Kotlin core
    /// derives for it (`CrossPlatformAddressParityTest`, `:shared`) and that
    /// iOS's own `WalletCreatorTests` pins independently.
    func testImportingTheTestPhraseShowsThePinnedTestnetAddress() throws {
        let app = XCUIApplication()
        app.launchEnvironment["POCKETNODE_NETWORK"] = "testnet"
        app.launchEnvironment["POCKETNODE_RESET_STATE"] = "1"
        app.launchEnvironment["POCKETNODE_UITEST_ALLOW_CAPTURE"] = "1"
        app.launchEnvironment["POCKETNODE_UITEST_EXPOSE_WORDS"] = "1"
        app.launch()

        XCTAssertTrue(app.buttons["onboarding.import"].waitForExistence(timeout: 20))
        app.buttons["onboarding.import"].firstMatch.tap()

        // Recovery phrase, 12 words, is the default mode and length; paste is
        // a system control this test cannot drive, so each word goes in by
        // typing into its own field.
        XCTAssertTrue(app.textFields["import.word.0"].waitForExistence(timeout: 10))
        for (index, word) in Self.testPhrase.enumerated() {
            let field = app.textFields["import.word.\(index)"]
            field.tap()
            field.typeText(word)
        }

        let submit = app.buttons["import.submit"]
        XCTAssertTrue(submit.isEnabled, "the submit button should enable once every word is a real one")
        submit.tap()

        // A phrase the user typed in has nothing to back up, so this path
        // skips straight to the PIN.
        XCTAssertTrue(app.staticTexts["Create PIN"].waitForExistence(timeout: 10))
        Self.enterPin("123456", in: app)
        XCTAssertTrue(app.staticTexts["Confirm PIN"].waitForExistence(timeout: 10))
        Self.enterPin("123456", in: app)

        let skipBiometrics = app.buttons["pinSetup.skipBiometrics"]
        if skipBiometrics.waitForExistence(timeout: 5) {
            skipBiometrics.tap()
        }

        XCTAssertTrue(app.buttons["home.receive"].waitForExistence(timeout: 10))
        app.buttons["home.receive"].firstMatch.tap()

        let address = app.staticTexts["receive.address"]
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        XCTAssertEqual(address.label, Self.testTestnetAddress)
    }

    // MARK: - The pinned cross-platform vector

    /// The standard all-"abandon" BIP-39 test phrase. Its `m/44'/309'/0'/0/0`
    /// private key and both addresses are pinned independently in iOS's
    /// `WalletCreatorTests` and in the shared module's
    /// `CrossPlatformAddressParityTest` (`:shared:testAndroidHostTest` and
    /// `:shared:iosSimulatorArm64Test`); this is the one place all three meet
    /// through the real UI.
    private static let testPhrase = Array(repeating: "abandon", count: 11) + ["about"]
    private static let testTestnetAddress =
        "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"

    // MARK: - Helpers

    private func enabled(_ element: XCUIElement) -> XCTestExpectation {
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: element)
    }

    /// Taps `pin.key.<digit>` once per character.
    private static func enterPin(_ pin: String, in app: XCUIApplication) {
        for digit in pin {
            app.buttons["pin.key.\(digit)"].firstMatch.tap()
        }
    }

    /// `backup.word.<index>` is set on a row with two `Text` children (the
    /// position and the word); SwiftUI does not merge them into one element,
    /// it pushes the identifier down onto both (see `HomeView`'s comment on
    /// the same behaviour), so the query matches two `staticTexts`. This
    /// tries each match and keeps whichever one is not just the "<n>."
    /// position label — the same trim `NodeStatusUITests` uses to read a
    /// merged `LabeledContent` row, applied here to tell the two apart rather
    /// than to join them.
    private static func word(at index: Int, in app: XCUIApplication) -> String {
        let matches = app.staticTexts.matching(identifier: "backup.word.\(index)")
        for i in 0..<matches.count {
            let candidate = trimmed(matches.element(boundBy: i).label)
            if !candidate.isEmpty { return candidate }
        }
        return ""
    }

    private static func trimmed(_ label: String) -> String {
        let separators = CharacterSet.decimalDigits.union(CharacterSet(charactersIn: ".,: "))
        return label.trimmingCharacters(in: separators)
    }

    /// The 0-based word positions the verify quiz is asking about, in the
    /// order they appear on screen. `BackupQuiz.generate` sorts its 3 prompts
    /// ascending, and `"Word #<position + 1>"` is each prompt's own, unmerged
    /// static text (its siblings are buttons, which never merge away).
    private static func quizPositions(in app: XCUIApplication) -> [Int] {
        let prompts = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Word #"))
        return (0..<prompts.count).compactMap { index in
            let label = prompts.element(boundBy: index).label
            guard let number = Int(label.dropFirst("Word #".count)) else { return nil }
            return number - 1
        }
    }

    /// Saves a full-screen PNG next to the test attachment, the same
    /// convention `WalletShellUITests.capture(named:)` uses, so the images
    /// can be copied off the simulator afterwards.
    private func capture(app: XCUIApplication, named name: String) {
        let screenshot = app.screenshot()

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
