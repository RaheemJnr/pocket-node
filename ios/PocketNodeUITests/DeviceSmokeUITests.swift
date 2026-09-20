import XCTest

/// The one test that uses real coins on a real phone.
///
/// Everything else in `PocketNodeUITests` is offline by construction: a seeded
/// metadata-only wallet, a node with no peers, a balance of zero. This one goes
/// the other way and drives the whole M3 path on hardware against live testnet:
/// create a wallet through onboarding, pick a sync mode, take the receive
/// address, claim from the faucet, wait for the coins to land, read them back
/// off Home and Activity, price a send, and survive a relaunch.
///
/// It is gated twice on purpose. `POCKETNODE_NETWORK_TESTS` is what both
/// networked schemes set, so the offline scheme and `ios-ci.yml` never see it;
/// `POCKETNODE_DEVICE_SMOKE` is set by exactly one scheme,
/// `PocketNodeDeviceSmoke`, so neither CI nor an ordinary networked run can
/// claim from the faucet or broadcast by accident:
///
/// ```
/// xcodebuild -project PocketNode.xcodeproj -scheme PocketNodeDeviceSmoke \
///   -destination 'platform=iOS,id=<device-udid>' \
///   -derivedDataPath /tmp/dd-m3-device \
///   -only-testing:PocketNodeUITests/DeviceSmokeUITests test
/// ```
///
/// Both variables are read from the runner's own environment, which is what a
/// scheme's test action sets. A `TEST_RUNNER_`-prefixed build setting on the
/// xcodebuild command line does NOT arrive: it is forwarded only when
/// xcodebuild inherits it as an environment variable, which is why the gate
/// lives in a scheme rather than in a flag someone has to remember.
///
/// It does broadcast. The last step is a real 100 CKB transfer out of the
/// wallet this run funded, which means a person has to be holding the phone:
/// signing raises the app's own PIN gate (which this test can type) and then a
/// Secure Enclave Face ID prompt (which it cannot, because that sheet belongs
/// to SpringBoard). The run logs `SMOKE_WAITING_FOR_USER_AUTH` and waits; an
/// unattended run reports `SMOKE_AUTH_TIMEOUT` and carries on with the rest of
/// the evidence rather than failing on the spot.
@MainActor
final class DeviceSmokeUITests: XCTestCase {

    // MARK: - Configuration

    /// Where the claim goes. 10,000 testnet CKB, one claim per address per day.
    private static let faucetURL = URL(string: "https://faucet-api.nervos.org/claim_events")!
    private static let faucetAmount = "10000"

    /// The recipient the priced send is addressed to. Any valid `ckt1` address
    /// works; this is the one the rest of the suite already pins, so a reader
    /// comparing the two files sees the same string.
    private static let returnAddress = M3UITest.testnetAddress

    /// The deposit is claimed at the chain tip and the wallet syncs from the
    /// tip, so it is a question of block time rather than of catching up.
    private static let depositTimeout: TimeInterval = 8 * 60
    private static let depositPollInterval: Duration = .seconds(10)

    /// Sync from the tip is immediate; the node still has to find peers first.
    private static let syncTimeout: TimeInterval = 180

    /// How long a person gets to answer the PIN gate and the Secure Enclave's
    /// Face ID prompt before the run gives up on the broadcast.
    private static let userAuthTimeout: TimeInterval = 4 * 60

    /// How long the broadcast then gets to commit.
    private static let confirmationTimeout: TimeInterval = 6 * 60

    /// The amount the real send moves, in CKB.
    private static let sendAmount = "100"

    /// The PIN this run sets and then unlocks with.
    private static let pin = "123456"

    /// Set if the app under test goes away on its own at any point.
    ///
    /// It is not raised as a failure where it happens, because the useful
    /// thing then is to bring the app back and carry on collecting evidence
    /// about a transaction that is already on chain. The run fails on it at
    /// the end instead, so it can never pass quietly.
    private var unexpectedExit: String?

    /// Problems noted along the way that should not abort the run on their
    /// own, because later steps still have something useful to say about the
    /// app. `continueAfterFailure = false` means any `XCTAssertTrue` on the
    /// spot would end the test right there, so these are collected here
    /// instead and asserted together at the end, alongside `unexpectedExit`.
    private var problems: [String] = []

    /// Reported from teardown so a hard assertion in a later step (Node
    /// Status, relaunch) cannot abort the run before these are seen. An
    /// override rather than `addTeardownBlock`, whose Sendable closure cannot
    /// capture the test case under strict concurrency on a device build.
    ///
    /// `MainActor.assumeIsolated` because XCTest declares this override
    /// nonisolated on Xcode 16 (the CI runner) while `problems` and
    /// `unexpectedExit` belong to this main-actor class; teardown always runs
    /// on the main thread, so the assumption holds.
    override func tearDownWithError() throws {
        MainActor.assumeIsolated {
            if let unexpectedExit {
                problems.append("the app went away on its own: \(unexpectedExit)")
            }
            if !problems.isEmpty {
                XCTFail(problems.joined(separator: "; "))
            }
        }
        try super.tearDownWithError()
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        #if targetEnvironment(simulator)
        throw XCTSkip("spends testnet coins and expects Face ID; physical iPhone only")
        #endif
        let environment = ProcessInfo.processInfo.environment
        try XCTSkipUnless(
            environment["POCKETNODE_NETWORK_TESTS"] == "1",
            "needs live testnet bootnodes; run under a networked scheme"
        )
        try XCTSkipUnless(
            environment["POCKETNODE_DEVICE_SMOKE"] == "1",
            "claims real testnet coins and broadcasts; run under the PocketNodeDeviceSmoke scheme"
        )
    }

    // MARK: - The run

    func testAFundedWalletSyncsReceivesAndPricesASend() async throws {
        let app = launch(reset: true)

        // (a) Onboarding, for real: this wallet has to hold keys, so
        // `POCKETNODE_SKIP_ONBOARDING` (metadata only) is exactly what it
        // cannot use.
        try createWallet(in: app)
        capture(app, "01-home-first")

        // (b) New wallet starts at the tip, which is the only mode that can
        // see a deposit claimed a minute from now without a long catch-up.
        try chooseNewWalletSyncMode(in: app)
        try waitForSynced(in: app)
        capture(app, "02-synced")

        // (c) The address this run will be paid at.
        let address = try readReceiveAddress(in: app)
        NSLog("SMOKE_ADDRESS %@", address)
        attach(text: address, named: "smoke-address")
        capture(app, "03-receive")
        back(in: app, from: "Receive")

        // (d) Claim. A refusal is reported rather than failed on: the steps
        // after it still say something about the app, just about its empty
        // states instead.
        let funded = await claimFromFaucet(address: address)

        // (e) Home, waiting for the coins.
        let balance = try await waitForBalance(in: app, expectingFunds: funded)
        NSLog("SMOKE_BALANCE %@", balance)
        capture(app, "04-balance")

        // (f) The same coins, seen from the activity list.
        let receiveHash = try await inspectActivity(in: app, expectingFunds: funded)

        // (g) Priced, then actually sent.
        let sentHash = try await sendForReal(in: app, expectingFunds: funded)
        if let sentHash {
            try await inspectSentTransaction(in: app, sentHash: sentHash, receiveHash: receiveHash)
        }

        // (h) The node that did all of it.
        try inspectNodeStatus(in: app)

        // (i) And it is all still there on the next launch.
        try relaunchAndVerify(app, expectedBalance: balance)

        // `problems` and `unexpectedExit` are reported from the teardown
        // block registered in `setUpWithError`, so they surface even when a
        // step above aborted the run.
    }

    /// Brings the app back if it has gone away, and notes that it did.
    ///
    /// Returns true if the app had to be restarted. Relaunched without
    /// `POCKETNODE_RESET_STATE`, so the wallet, its PIN and its sync choice
    /// are the ones the run has been building up.
    @discardableResult
    private func recoverIfGone(_ app: XCUIApplication, during step: String) -> Bool {
        guard app.state == .notRunning else { return false }

        let note = "exited during \(step) at \(Self.now())"
        NSLog("SMOKE_APP_EXITED %@", note)
        attach(text: note, named: "smoke-app-exited")
        if unexpectedExit == nil { unexpectedExit = note }

        app.launchEnvironment["POCKETNODE_RESET_STATE"] = nil
        app.launch()
        if app.otherElements["lock.root"].firstMatch.waitForExistence(timeout: 30) {
            Self.enterPin(Self.pin, in: app)
        }
        _ = app.buttons["home.receive"].firstMatch.waitForExistence(timeout: 60)
        return true
    }

    // MARK: - (a) Onboarding

    /// Welcome, create, read the phrase back, answer the quiz, set a PIN.
    ///
    /// The same flow `OnboardingUITests` drives, but this one keeps the wallet:
    /// there is no reset in `tearDown`, because the whole point is to leave a
    /// funded wallet on the phone for a person to finish the send on.
    private func createWallet(in app: XCUIApplication) throws {
        tap("onboarding.create", in: app, timeout: 60)
        tap("create.submit", in: app)
        tap("backup.reveal", in: app)

        XCTAssertTrue(
            app.staticTexts.matching(identifier: "backup.word.11").firstMatch.waitForExistence(timeout: 30),
            "the phrase grid never filled in"
        )
        let words = (0..<12).map { Self.word(at: $0, in: app) }
        XCTAssertTrue(words.allSatisfy { !$0.isEmpty }, "a word came back blank")

        // The shield matters most here. `BackupViewModel.onBackgrounded()`
        // wipes the words and re-arms the gate whenever the scene stops being
        // active, so a banner during this step both swallows the tap and
        // throws the flow back to `backup.reveal`.
        tap("backup.wroteItDown", in: app)

        // Wait for the quiz's own prompts, not for the Verify button.
        //
        // Verify is on screen from the first frame of the step, so waiting on
        // it says nothing about whether the three questions have been laid out
        // yet. Reading the positions too early returns an empty list, which
        // answers nothing, leaves Verify disabled and hangs the run on a
        // `backup.done` that never comes. On a physical iPhone this step took
        // over 20s once, so the wait is on the thing that actually arrives.
        let prompts = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Word #"))
        let quizReady = expectation(for: NSPredicate(format: "count >= 3"), evaluatedWith: prompts)
        let arrived = XCTWaiter().wait(for: [quizReady], timeout: 90)
        if arrived != .completed {
            // Almost always the banner case above: the tap was eaten and the
            // gate is back. One retry through the reveal rather than a dead
            // run, since the phrase is the same one either way.
            NSLog("SMOKE_BACKUP_RETRY the verify step never arrived; re-revealing at=%@", Self.now())
            tap("backup.reveal", in: app)
            _ = app.staticTexts.matching(identifier: "backup.word.11").firstMatch.waitForExistence(timeout: 30)
            tap("backup.wroteItDown", in: app)
            wait(for: [expectation(for: NSPredicate(format: "count >= 3"), evaluatedWith: prompts)], timeout: 90)
        }

        let positions = Self.quizPositions(in: app)
        XCTAssertEqual(positions.count, 3, "the quiz asked \(positions.count) questions, not 3")
        for position in positions {
            let choice = app.buttons["backup.verify.\(position).\(words[position])"]
            XCTAssertTrue(choice.waitForExistence(timeout: 20),
                          "no choice button for word #\(position + 1)")
            choice.tap()
        }

        let verify = app.buttons["backup.verifyButton"]
        let verifyEnabled = expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: verify)
        wait(for: [verifyEnabled], timeout: 30)
        verify.tap()

        XCTAssertTrue(app.buttons["backup.done"].waitForExistence(timeout: 60),
                      "the quiz did not accept three correct answers")
        app.buttons["backup.done"].firstMatch.tap()

        XCTAssertTrue(app.staticTexts["Create PIN"].waitForExistence(timeout: 20))
        Self.enterPin(Self.pin, in: app)
        XCTAssertTrue(app.staticTexts["Confirm PIN"].waitForExistence(timeout: 20))
        Self.enterPin(Self.pin, in: app)

        // Opting in here would have `LockView` raise a system Face ID sheet on
        // the relaunch in step (i), which XCUITest cannot answer. The PIN pad
        // is the path this test can actually drive, so it skips the sensor.
        let skipBiometrics = app.buttons["pinSetup.skipBiometrics"]
        if skipBiometrics.waitForExistence(timeout: 10) {
            skipBiometrics.tap()
        }

        XCTAssertTrue(app.staticTexts["home.address"].firstMatch.waitForExistence(timeout: 30),
                      "onboarding never reached Home")
        XCTAssertNotEqual(app.staticTexts["home.address"].firstMatch.label, "No address yet")
    }

    // MARK: - (b) Sync

    private func chooseNewWalletSyncMode(in app: XCUIApplication) throws {
        let choose = app.buttons["home.syncChooseButton"]
        XCTAssertTrue(choose.waitForExistence(timeout: 60), "Home never offered the sync-mode prompt")
        choose.tap()

        let newWallet = app.buttons["syncMode.option.newWallet"].firstMatch
        XCTAssertTrue(newWallet.waitForExistence(timeout: 20))
        newWallet.tap()

        let confirm = app.buttons["syncMode.confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 20) && confirm.isEnabled,
                      "New wallet needs no extra input, so Apply should be enabled")
        confirm.tap()
    }

    /// Synced, or catching up on the way to it. Registering at the tip means
    /// there is nothing to catch up on, but the node still has to find peers
    /// and report a status before the card can say so.
    private func waitForSynced(in app: XCUIApplication) throws {
        let synced = app.staticTexts["home.syncSynced"].firstMatch
        let reached = synced.waitForExistence(timeout: Self.syncTimeout)
        if !reached {
            let catchingUp = M3UITest.label("home.syncCatchingUp", in: app, timeout: 5)
            let error = M3UITest.label("home.syncError", in: app, timeout: 5)
            NSLog("SMOKE_SYNC not-synced catchingUp=%@ error=%@", catchingUp, error)
        }
        XCTAssertTrue(reached, "the sync card never reached Synced in \(Int(Self.syncTimeout))s")
        NSLog("SMOKE_SYNC synced")
    }

    // MARK: - (c) Receive

    private func readReceiveAddress(in app: XCUIApplication) throws -> String {
        app.buttons["home.receive"].firstMatch.tap()

        let addressLabel = app.staticTexts["receive.address"].firstMatch
        XCTAssertTrue(addressLabel.waitForExistence(timeout: 30), "Receive never showed an address")

        let address = addressLabel.label.trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertTrue(address.hasPrefix("ckt1"), "expected a testnet address, got \(address)")
        XCTAssertGreaterThan(address.count, 80, "the address looks truncated: \(address)")
        return address
    }

    // MARK: - (d) Faucet

    /// Claims from the Nervos testnet faucet, and says whether to expect coins.
    ///
    /// A rate-limited address is a normal outcome (one claim per address per
    /// day) and not this app's fault, so it is logged and the run carries on
    /// against the empty states rather than failing here.
    private func claimFromFaucet(address: String) async -> Bool {
        var request = URLRequest(url: Self.faucetURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "claim_event": ["address_hash": address, "amount": Self.faucetAmount]
        ])

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            let body = String(data: data, encoding: .utf8) ?? "<undecodable>"
            NSLog("SMOKE_FAUCET status=%d body=%@", status, body)
            attach(text: "status=\(status)\n\(body)", named: "smoke-faucet")
            return (200..<300).contains(status)
        } catch {
            NSLog("SMOKE_FAUCET error=%@", String(describing: error))
            attach(text: "error=\(error)", named: "smoke-faucet")
            return false
        }
    }

    // MARK: - (e) Balance

    /// Polls Home until the balance is something other than zero.
    ///
    /// The only wait-by-sleeping in the suite, and it is here because the wait
    /// is on a block being produced and relayed, which no predicate on the app
    /// can be notified of. Returns whatever the label finally said either way.
    private func waitForBalance(in app: XCUIApplication, expectingFunds: Bool) async throws -> String {
        let balance = app.staticTexts["home.balance"].firstMatch
        XCTAssertTrue(balance.waitForExistence(timeout: 60), "Home never showed a balance line")

        let deadline = Date().addingTimeInterval(Self.depositTimeout)
        var label = balance.label
        while Date() < deadline {
            label = balance.label
            if Self.isNonZeroBalance(label) { break }
            NSLog("SMOKE_BALANCE_POLL %@", label)
            try await Task.sleep(for: Self.depositPollInterval)
        }

        if expectingFunds {
            XCTAssertTrue(
                Self.isNonZeroBalance(label),
                "the claim was accepted but nothing arrived in \(Int(Self.depositTimeout))s; last label was \(label)"
            )
        } else {
            NSLog("SMOKE_BALANCE_UNFUNDED the faucet refused; balance stayed at %@", label)
        }
        return label
    }

    // MARK: - (f) Activity

    @discardableResult
    private func inspectActivity(in app: XCUIApplication, expectingFunds: Bool) async throws -> String? {
        app.buttons["home.activity"].firstMatch.tap()
        XCTAssertTrue(app.buttons["activity.filter.received"].firstMatch.waitForExistence(timeout: 30),
                      "Home did not reach Activity")

        guard expectingFunds else {
            XCTAssertTrue(app.staticTexts["activity.empty"].firstMatch.waitForExistence(timeout: 30),
                          "an unfunded wallet should settle into an empty list")
            NSLog("SMOKE_RX_HASH <none: unfunded>")
            capture(app, "05-activity")
            back(in: app, from: "Activity")
            return nil
        }

        app.buttons["activity.filter.received"].firstMatch.tap()

        // The row identifier carries the hash, so finding the row and reading
        // the hash are the same step.
        let rows = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "activity.row."))
        let appeared = expectation(for: NSPredicate(format: "count > 0"), evaluatedWith: rows)
        await fulfillment(of: [appeared], timeout: 180)

        let row = rows.firstMatch
        let hash = row.identifier.replacingOccurrences(of: "activity.row.", with: "")
        NSLog("SMOKE_RX_HASH %@", hash)
        attach(text: hash, named: "smoke-receive-hash")
        capture(app, "05-activity")

        row.tap()
        XCTAssertTrue(app.staticTexts["Transaction Details"].firstMatch.waitForExistence(timeout: 30),
                      "the row did not open its detail sheet")

        // A transaction claimed at the tip is in flight for a block or two, so
        // the block number is waited for rather than read at once.
        try await waitForConfirmation(in: app)
        capture(app, "06-detail")

        // The sender paid for this one. A fee row here would be the wallet
        // claiming a cost it never bore (`TransactionDetailSheet`,
        // `record.paysNetworkFee()`).
        XCTAssertFalse(
            app.staticTexts["Network fee"].firstMatch.exists,
            "a received transaction must not show a network fee"
        )

        app.swipeDown(velocity: .fast)
        back(in: app, from: "Activity")
        return hash
    }

    /// Waits for the detail sheet's Block Number row to stop saying the
    /// transaction is not in a block yet.
    private func waitForConfirmation(in app: XCUIApplication) async throws {
        let deadline = Date().addingTimeInterval(Self.depositTimeout)
        while Date() < deadline {
            if !app.staticTexts["Not in a block yet"].firstMatch.exists {
                NSLog("SMOKE_RX_CONFIRMED")
                return
            }
            try await Task.sleep(for: Self.depositPollInterval)
        }
        XCTFail("the received transaction never made it into a block")
    }

    // MARK: - (g) Send, priced and then broadcast

    /// Prices a send, checks the sweep warning, then broadcasts a real one.
    ///
    /// Returns the committed transaction's hash, or nil if nothing was sent
    /// (an unfunded wallet, or a person who did not answer the auth prompts).
    private func sendForReal(in app: XCUIApplication, expectingFunds: Bool) async throws -> String? {
        app.buttons["home.send"].firstMatch.tap()
        let recipient = app.textFields["send.recipient"]
        XCTAssertTrue(recipient.waitForExistence(timeout: 30), "Home did not reach Send")

        M3UITest.type(Self.returnAddress, into: recipient)
        M3UITest.type(Self.sendAmount, into: app.textFields["send.amount"])
        Self.dismissKeyboard(app)
        app.buttons["send.submit"].firstMatch.tap()

        guard expectingFunds else {
            XCTAssertTrue(app.staticTexts["sendFailure.message"].firstMatch.waitForExistence(timeout: 60),
                          "an unfunded send produced no outcome at all")
            NSLog("SMOKE_REVIEW skipped: %@", M3UITest.label("sendFailure.message", in: app))
            app.buttons["sendFailure.ok"].firstMatch.tap()
            back(in: app, from: "Send CKB")
            return nil
        }

        // The fee comes out of the real cell selection, so reaching this sheet
        // at all means cells were found and a transaction was built.
        XCTAssertTrue(app.staticTexts["review.total"].firstMatch.waitForExistence(timeout: 120),
                      "a funded send never reached the review sheet")

        let recipientRow = M3UITest.label("review.recipient", in: app)
        let amount = M3UITest.label("review.amount", in: app)
        let fee = M3UITest.label("review.fee", in: app)
        let total = M3UITest.label("review.total", in: app)
        NSLog("SMOKE_REVIEW recipient=%@ amount=%@ fee=%@ total=%@", recipientRow, amount, fee, total)

        // The row truncates, so the two ends are what can be compared.
        XCTAssertTrue(recipientRow.hasPrefix(String(Self.returnAddress.prefix(10))),
                      "review showed \(recipientRow) for \(Self.returnAddress)")
        XCTAssertTrue(recipientRow.hasSuffix(String(Self.returnAddress.suffix(6))),
                      "review showed \(recipientRow) for \(Self.returnAddress)")
        XCTAssertEqual(amount, "\(Self.sendAmount).00 CKB")
        XCTAssertTrue(fee.hasSuffix(" CKB"), "the fee row read \(fee)")
        XCTAssertFalse(fee.isEmpty || fee == "0 CKB", "a built transaction always pays a fee; got \(fee)")
        XCTAssertTrue(total.hasSuffix(" CKB"), "the total row read \(total)")
        capture(app, "07-review")

        app.buttons["review.cancel"].firstMatch.tap()
        XCTAssertTrue(app.buttons["send.submit"].firstMatch.waitForExistence(timeout: 30),
                      "Cancel did not return to the form")

        // MAX spends everything, which is the state #447 makes the user
        // acknowledge before Confirm will do anything.
        app.buttons["send.max"].firstMatch.tap()
        Self.dismissKeyboard(app)
        app.buttons["send.submit"].firstMatch.tap()

        XCTAssertTrue(app.staticTexts["review.sweepWarning"].firstMatch.waitForExistence(timeout: 120),
                      "a MAX send did not warn about sweeping the wallet")
        XCTAssertTrue(
            app.switches["review.acknowledge"].firstMatch.exists
                || app.buttons["review.acknowledge"].firstMatch.exists,
            "the warning has no acknowledgement to tick"
        )
        XCTAssertFalse(app.buttons["review.confirm"].firstMatch.isEnabled,
                       "Confirm must stay disabled until the sweep is acknowledged")
        NSLog("SMOKE_SWEEP %@", M3UITest.label("review.sweepWarning", in: app))
        capture(app, "08-sweep")

        app.buttons["review.cancel"].firstMatch.tap()
        XCTAssertTrue(app.buttons["send.submit"].firstMatch.waitForExistence(timeout: 30),
                      "Cancel did not return to the form after the sweep warning")

        // Back to a plain 100 CKB, and this time it goes out. MAX left its own
        // number in the field, so the field is cleared rather than typed on.
        let amountField = app.textFields["send.amount"]
        Self.clear(amountField, in: app)
        amountField.typeText(Self.sendAmount)
        if M3UITest.label("send.addressState", in: app, timeout: 5) != "Valid CKB testnet address" {
            Self.clear(app.textFields["send.recipient"], in: app)
            app.textFields["send.recipient"].typeText(Self.returnAddress)
        }
        Self.dismissKeyboard(app)
        app.buttons["send.submit"].firstMatch.tap()

        XCTAssertTrue(app.staticTexts["review.total"].firstMatch.waitForExistence(timeout: 120),
                      "the real send never reached the review sheet")
        let realFee = M3UITest.label("review.fee", in: app)
        NSLog("SMOKE_REVIEW_FEE %@", realFee)
        attach(text: realFee, named: "smoke-review-fee")
        XCTAssertTrue(realFee.hasSuffix(" CKB"), "the fee row read \(realFee)")

        return try await broadcast(in: app)
    }

    /// Taps Confirm and then waits for a person.
    ///
    /// Two prompts stand between Confirm and a broadcast. The first is the
    /// app's own PIN gate, which this test can type because `PinEntryView` is
    /// an in-app pad with real accessibility identifiers. The second is the
    /// Secure Enclave unwrapping the wallet's data key, which raises a system
    /// Face ID sheet owned by SpringBoard; XCUITest cannot answer that one, so
    /// the run logs and waits for the user holding the phone.
    private func broadcast(in app: XCUIApplication) async throws -> String? {
        app.buttons["review.confirm"].firstMatch.tap()

        NSLog("SMOKE_WAITING_FOR_USER_AUTH at=%@", Self.now())

        let statusTitle = app.staticTexts["sendStatus.title"].firstMatch
        let pinGate = app.otherElements["authChallenge.root"].firstMatch
        var typedPin = false

        let deadline = Date().addingTimeInterval(Self.userAuthTimeout)
        while Date() < deadline {
            if statusTitle.exists {
                NSLog("SMOKE_AUTH_GRANTED at=%@ status=%@", Self.now(), statusTitle.label)
                break
            }
            // The in-app gate, if this build asks for it. Typed once: a second
            // pass would enter six more digits on top of a pad that is already
            // verifying.
            if !typedPin, pinGate.exists {
                NSLog("SMOKE_PIN_PROMPT at=%@", Self.now())
                Self.enterPin(Self.pin, in: app)
                typedPin = true
            }
            // Face ID belongs to SpringBoard, so it shows up as the app losing
            // the foreground rather than as anything in this app's tree.
            if app.state != .runningForeground {
                NSLog("SMOKE_SYSTEM_AUTH_PROMPT at=%@ appState=%d", Self.now(), Int(app.state.rawValue))
            }
            try await Task.sleep(for: .seconds(2))
        }

        guard statusTitle.waitForExistence(timeout: 10) else {
            NSLog("SMOKE_AUTH_TIMEOUT at=%@ nobody answered in %ds", Self.now(), Int(Self.userAuthTimeout))
            capture(app, "07b-sending")
            // Leave the form the way it was found rather than mid-flight.
            if app.buttons["review.cancel"].firstMatch.exists {
                app.buttons["review.cancel"].firstMatch.tap()
            }
            back(in: app, from: "Send CKB")
            return nil
        }

        capture(app, "07b-sending")
        NSLog("SMOKE_SEND_PHASE %@", statusTitle.label)

        // "Sending..." -> "Waiting for Confirmation" -> "Transaction Confirmed!".
        // The hash appears as soon as the broadcast is accepted, before the
        // commit, so it is read on the way rather than only at the end.
        var hash = ""
        var confirmedOnScreen = false
        let confirmDeadline = Date().addingTimeInterval(Self.confirmationTimeout)
        while Date() < confirmDeadline {
            // A notification banner takes the scene out of `.active`, which
            // correctly raises `PrivacyShield` behind the sheet; the sheet
            // itself is presented outside the shielded hierarchy and stays
            // readable, so the poll survives one. The app disappearing
            // altogether is the case that does not, and it is handled rather
            // than thrown, because the transaction is already broadcast.
            if recoverIfGone(app, during: "the send confirmation poll") { break }

            let hashLabel = app.staticTexts["sendStatus.hash"].firstMatch
            if hash.isEmpty, hashLabel.exists, hashLabel.label.hasPrefix("0x") {
                hash = hashLabel.label
                NSLog("SMOKE_TX_HASH %@", hash)
                attach(text: hash, named: "smoke-tx-hash")
            }
            if statusTitle.label == "Transaction Confirmed!" {
                confirmedOnScreen = true
                break
            }
            if statusTitle.label == "Transaction Failed" {
                NSLog("SMOKE_SEND_FAILED %@", M3UITest.label("sendStatus.message", in: app))
                break
            }
            NSLog("SMOKE_SEND_POLL at=%@ phase=%@", Self.now(), statusTitle.label)
            try await Task.sleep(for: .seconds(5))
        }

        NSLog("SMOKE_SEND_FINAL at=%@ phase=%@ hash=%@", Self.now(), statusTitle.label, hash)
        capture(app, "07c-confirmed")
        // Noted rather than asserted here: with `continueAfterFailure = false`
        // an `XCTAssertTrue` on the spot would end the run before it reaches
        // (h) and (i), and before `unexpectedExit` ever gets reported. Both
        // are checked together at the end instead.
        if !hash.hasPrefix("0x") {
            problems.append("no transaction hash was ever shown")
        }

        guard confirmedOnScreen else {
            // The sheet did not get to say so, either because the app went
            // away or because the commit outran the timeout. The Activity
            // list is checked next and is the better witness anyway, since it
            // reads the chain rather than this one send's poller.
            NSLog("SMOKE_SEND_UNCONFIRMED_ON_SHEET hash=%@", hash)
            // Nil rather than "" when no hash ever showed up, so the caller's
            // `if let` skips a hash lookup in Activity that could never match
            // anything and would otherwise burn its own timeout for nothing.
            return hash.isEmpty ? nil : hash
        }

        // Done on a confirmed send stops the poll and pops back to Home.
        app.buttons["sendStatus.done"].firstMatch.tap()
        _ = app.buttons["home.receive"].firstMatch.waitForExistence(timeout: 30)
        return hash
    }

    /// The send, seen from the activity list: a Sent row above the Received
    /// one, and this time a fee, because this wallet paid it.
    private func inspectSentTransaction(
        in app: XCUIApplication,
        sentHash: String,
        receiveHash: String?
    ) async throws {
        recoverIfGone(app, during: "the walk to Activity after the send")

        // The status sheet may still be up (a send whose commit outran the
        // poll), and the Send screen may still be pushed. Get back to Home
        // either way before asking Home for Activity.
        if app.buttons["sendStatus.done"].firstMatch.exists {
            app.buttons["sendStatus.done"].firstMatch.tap()
        }
        if !app.buttons["home.activity"].firstMatch.waitForExistence(timeout: 15) {
            back(in: app, from: "Send CKB")
        }

        app.buttons["home.activity"].firstMatch.tap()
        XCTAssertTrue(app.buttons["activity.filter.all"].firstMatch.waitForExistence(timeout: 30),
                      "Home did not reach Activity")

        let sentRow = app.descendants(matching: .any)["activity.row.\(sentHash)"].firstMatch
        let appeared = expectation(for: NSPredicate(format: "exists == true"), evaluatedWith: sentRow)
        await fulfillment(of: [appeared], timeout: 180)

        // Newest first, so the send this run just made sits above the deposit
        // that funded it.
        if let receiveHash {
            let receivedRow = app.descendants(matching: .any)["activity.row.\(receiveHash)"].firstMatch
            if receivedRow.exists {
                XCTAssertLessThan(sentRow.frame.minY, receivedRow.frame.minY,
                                  "the Sent row should be above the Received one")
            }
        }

        sentRow.tap()
        XCTAssertTrue(app.staticTexts["Transaction Details"].firstMatch.waitForExistence(timeout: 30),
                      "the Sent row did not open its detail sheet")
        XCTAssertTrue(app.staticTexts["Network fee"].firstMatch.waitForExistence(timeout: 15),
                      "a sent transaction must show the fee this wallet paid")
        NSLog("SMOKE_SENT_DETAIL hash=%@", sentHash)
        capture(app, "07d-sent-detail")

        app.swipeDown(velocity: .fast)
        back(in: app, from: "Activity")
    }

    // MARK: - (h) Node status

    private func inspectNodeStatus(in app: XCUIApplication) throws {
        recoverIfGone(app, during: "the walk to Node Status")
        app.buttons["root.nodeStatus"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Node Status"].waitForExistence(timeout: 30))

        let status = app.staticTexts["nodeStatus.status"].firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 30))
        XCTAssertTrue(status.label.hasSuffix("Running"), "the node read \(status.label)")

        let peers = app.staticTexts["nodeStatus.peers"].firstMatch
        let tip = app.staticTexts["nodeStatus.tipBlock"].firstMatch
        XCTAssertTrue(peers.waitForExistence(timeout: 30))
        NSLog("SMOKE_NODE status=%@ tip=%@ peers=%@", status.label, tip.label, peers.label)

        XCTAssertGreaterThan(Self.trailingNumber(in: peers.label), 0,
                             "no peers, yet the wallet synced: \(peers.label)")
        capture(app, "09-node")

        // Stop is terminal in the bridge, so this screen is only looked at.
        back(in: app, from: "Node Status")
    }

    // MARK: - (i) Relaunch

    /// The wallet, its PIN and its sync choice all survive a cold start.
    ///
    /// No reset flag this time, which is the whole assertion: a second launch
    /// must not ask "Choose how far back to sync" again, because a mode was
    /// already registered and re-registering from the tip would lose history.
    private func relaunchAndVerify(_ app: XCUIApplication, expectedBalance: String) throws {
        if app.state != .notRunning { app.terminate() }

        let relaunched = launch(reset: false)

        // A PIN exists and the session did not survive the process, so the
        // gate is expected. Biometrics were skipped at setup, so this is the
        // pad rather than a system sheet.
        if relaunched.otherElements["lock.root"].firstMatch.waitForExistence(timeout: 30) {
            Self.enterPin(Self.pin, in: relaunched)
        }

        let balance = relaunched.staticTexts["home.balance"].firstMatch
        XCTAssertTrue(balance.waitForExistence(timeout: 60), "Home never came back after a relaunch")

        XCTAssertFalse(
            relaunched.buttons["home.syncChooseButton"].waitForExistence(timeout: 15),
            "the wallet forgot its sync mode and asked again"
        )
        XCTAssertTrue(
            relaunched.staticTexts["home.syncSynced"].firstMatch.waitForExistence(timeout: Self.syncTimeout),
            "the sync card did not return to Synced"
        )

        NSLog("SMOKE_RELAUNCH balance=%@ wasBefore=%@", balance.label, expectedBalance)
        capture(relaunched, "10-relaunch")
    }

    // MARK: - Launching

    /// The app under test, on testnet, with the two UI-test flags every suite
    /// needs on hardware: XCUITest records the screen, which the privacy
    /// shield would otherwise cover the whole app for, and iOS redacts
    /// `privacySensitive` text out of the accessibility tree.
    private func launch(reset: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        if reset {
            app.launchEnvironment["POCKETNODE_RESET_STATE"] = "1"
        }
        app.launchEnvironment["POCKETNODE_NETWORK"] = "testnet"
        app.launchEnvironment["POCKETNODE_UITEST_ALLOW_CAPTURE"] = "1"
        app.launchEnvironment["POCKETNODE_UITEST_EXPOSE_WORDS"] = "1"
        app.launch()
        return app
    }

    // MARK: - Helpers

    /// Taps `element`, first waiting out any privacy shield over it.
    ///
    /// `POCKETNODE_UITEST_ALLOW_CAPTURE` neutralises only the `isCaptured`
    /// half of `PrivacyShield`. The other half is `scenePhase != .active`,
    /// which a notification banner, a Control Centre pull or the app switcher
    /// all trigger, and the shield is an `.overlay`, so the elements beneath
    /// stay findable while every tap lands on it instead. On a phone that
    /// receives notifications, that is a tap the run believes it made and the
    /// app never saw. Waiting for the shield to go is the difference between a
    /// smoke run that works on a real person's device and one that only works
    /// on a quiet one.
    private func tap(_ identifier: String, in app: XCUIApplication, timeout: TimeInterval = 30) {
        let element = app.buttons[identifier].firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "\(identifier) never appeared")
        waitOutPrivacyShield(in: app, before: identifier)
        element.tap()
    }

    /// Blocks while the shield is up, for at most 30s.
    private func waitOutPrivacyShield(in app: XCUIApplication, before what: String) {
        let shield = app.otherElements["privacyShield"].firstMatch
        let deadline = Date().addingTimeInterval(30)
        while shield.exists, Date() < deadline {
            NSLog("SMOKE_SHIELD up before %@ at=%@", what, Self.now())
            _ = shield.waitForNonExistence(timeout: 5)
        }
    }

    /// Pops one pushed screen off the wallet shell's `NavigationStack`.
    private func back(in app: XCUIApplication, from title: String) {
        let bar = app.navigationBars[title]
        guard bar.waitForExistence(timeout: 10) else { return }
        bar.buttons.firstMatch.tap()
        _ = app.buttons["home.receive"].firstMatch.waitForExistence(timeout: 30)
    }

    /// "10,000.00 CKB" is funded; "0.00 CKB" and "Reading your balance" are not.
    private static func isNonZeroBalance(_ label: String) -> Bool {
        guard label.hasSuffix("CKB") else { return false }
        return label.contains { $0.isNumber && $0 != "0" }
    }

    /// A `LabeledContent` row reads back as "Peers, 8", so take the digits
    /// after the last separator (the same read `NodeStatusUITests` does).
    private static func trailingNumber(in label: String) -> Int {
        let digits = label.unicodeScalars.reversed()
            .prefix { CharacterSet.decimalDigits.contains($0) || $0 == "," || $0 == " " || $0 == "\u{202F}" }
            .reversed()
            .filter { CharacterSet.decimalDigits.contains($0) }
        return Int(String(String.UnicodeScalarView(digits))) ?? 0
    }

    private static func enterPin(_ pin: String, in app: XCUIApplication) {
        for digit in pin {
            app.buttons["pin.key.\(digit)"].firstMatch.tap()
        }
    }

    /// Puts the decimal pad away so it cannot sit over the submit button.
    private static func dismissKeyboard(_ app: XCUIApplication) {
        app.staticTexts["Available"].firstMatch.tap()
    }

    /// Empties a field by selecting everything in it and typing over it, the
    /// same route `SendUITests` takes: a 95-character address is too slow and
    /// too flaky to delete one key at a time.
    private static func clear(_ field: XCUIElement, in app: XCUIApplication) {
        field.tap()
        field.press(forDuration: 1.2)
        let selectAll = app.menuItems["Select All"]
        if selectAll.waitForExistence(timeout: 5) {
            selectAll.tap()
        }
        field.typeText(XCUIKeyboardKey.delete.rawValue)
    }

    /// Wall clock, so the logged prompt times can be lined up against what the
    /// person holding the phone saw.
    private static func now() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: Date())
    }

    /// `backup.word.<index>` is pushed down onto both `Text`s of its row (the
    /// position and the word); this keeps whichever match is not the "<n>."
    /// label. Same read as `OnboardingUITests`.
    private static func word(at index: Int, in app: XCUIApplication) -> String {
        let matches = app.staticTexts.matching(identifier: "backup.word.\(index)")
        let separators = CharacterSet.decimalDigits.union(CharacterSet(charactersIn: ".,: "))
        for i in 0..<matches.count {
            let candidate = matches.element(boundBy: i).label.trimmingCharacters(in: separators)
            if !candidate.isEmpty { return candidate }
        }
        return ""
    }

    /// The 0-based word positions the verify quiz is asking about.
    private static func quizPositions(in app: XCUIApplication) -> [Int] {
        let prompts = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Word #"))
        return (0..<prompts.count).compactMap { index in
            let label = prompts.element(boundBy: index).label
            guard let number = Int(label.dropFirst("Word #".count)) else { return nil }
            return number - 1
        }
    }

    // MARK: - Evidence

    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func attach(text: String, named name: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
