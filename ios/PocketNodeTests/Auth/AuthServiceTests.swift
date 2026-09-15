import SwiftUI
import XCTest

@testable import PocketNode

/// The session state machine: what locks it, what unlocks it, and what
/// `requireAuth` does when biometrics are off, refused or broken.
@MainActor
final class AuthServiceTests: XCTestCase {
    private let keychainService = "com.rjnr.pocketnode.tests.auth.pin"
    private let suiteName = "com.rjnr.pocketnode.tests.auth.prefs"

    private var keychain: KeychainStore!
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!
    private var biometrics: StubBiometrics!
    private var clock: TestClock!

    override func setUp() {
        super.setUp()
        keychain = KeychainStore(service: keychainService)
        try? keychain.deleteAll()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        preferences = UserDefaultsPreferences(defaults: defaults)
        biometrics = StubBiometrics()
        clock = TestClock()
    }

    override func tearDown() {
        try? keychain.deleteAll()
        defaults.removePersistentDomain(forName: suiteName)
        keychain = nil
        defaults = nil
        preferences = nil
        biometrics = nil
        clock = nil
        super.tearDown()
    }

    private func makeAuth() -> AuthService {
        AuthService(
            pin: PinService(keychain: keychain, cost: .testing, clock: clock.source),
            biometrics: biometrics,
            preferences: preferences
        )
    }

    // MARK: - State

    func testStartsWithNoPinWhenNoneIsStored() {
        XCTAssertEqual(makeAuth().state, .noPin)
    }

    func testStartsLockedWhenAPinIsStored() async throws {
        let first = makeAuth()
        try await first.setPin("123456")

        // A relaunch: a new service over the same Keychain.
        XCTAssertEqual(makeAuth().state, .locked, "a cold start is always locked")
    }

    func testSettingAPinLeavesTheSessionUnlocked() async throws {
        let auth = makeAuth()

        try await auth.setPin("123456")

        XCTAssertEqual(auth.state, .unlocked, "the user just chose the secret")
    }

    func testRemovingAPinReturnsToNoPinAndDropsTheBiometricOptIn() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true

        await auth.removePin()

        XCTAssertEqual(auth.state, .noPin)
        XCTAssertFalse(auth.isBiometricEnabled)
    }

    // MARK: - Lock on background

    func testBackgroundLocks() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        XCTAssertEqual(auth.state, .unlocked)

        auth.handleScenePhase(.background)

        XCTAssertEqual(auth.state, .locked)
    }

    /// `.inactive` fires for a notification banner, a control centre pull and
    /// the app switcher preview. Locking on it would eject the user several
    /// times a session.
    func testInactiveDoesNotLock() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")

        auth.handleScenePhase(.inactive)

        XCTAssertEqual(auth.state, .unlocked)
    }

    func testBackgroundDoesNothingWithoutAPin() {
        let auth = makeAuth()

        auth.handleScenePhase(.background)

        XCTAssertEqual(auth.state, .noPin, "there is nothing to unlock with")
    }

    // MARK: - Unlocking

    func testUnlockWithTheCorrectPin() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.handleScenePhase(.background)

        let unlocked = await auth.unlock(pin: "123456")

        XCTAssertTrue(unlocked)
        XCTAssertEqual(auth.state, .unlocked)
    }

    func testAWrongPinLeavesTheSessionLocked() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.handleScenePhase(.background)

        let unlocked = await auth.unlock(pin: "000000")

        XCTAssertFalse(unlocked)
        XCTAssertEqual(auth.state, .locked)
        XCTAssertEqual(auth.pin.remainingAttempts, 4)
    }

    func testBiometricUnlockSucceeds() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        auth.handleScenePhase(.background)

        let unlocked = await auth.unlockWithBiometrics()

        XCTAssertTrue(unlocked)
        XCTAssertEqual(auth.state, .unlocked)
    }

    func testBiometricUnlockDoesNotPromptWhenTheOptInIsOff() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.handleScenePhase(.background)

        let unlocked = await auth.unlockWithBiometrics()

        XCTAssertFalse(unlocked)
        XCTAssertEqual(biometrics.prompts, 0)
        XCTAssertEqual(auth.state, .locked, "the PIN pad is the only way in")
    }

    func testBiometricUnlockDoesNotPromptWhenNothingIsEnrolled() async throws {
        biometrics.set(availability: .notEnrolled)
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        auth.handleScenePhase(.background)

        let unlocked = await auth.unlockWithBiometrics()

        XCTAssertFalse(unlocked)
        XCTAssertEqual(biometrics.prompts, 0, "a prompt that can only fail is not raised")
    }

    func testAFailedBiometricAttemptExplainsItselfAndStaysLocked() async throws {
        biometrics.set(result: .failure(.lockedOut))
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        auth.handleScenePhase(.background)

        let unlocked = await auth.unlockWithBiometrics()

        XCTAssertFalse(unlocked)
        XCTAssertEqual(auth.state, .locked)
        XCTAssertNotNil(auth.biometricMessage)
    }

    /// A deliberate dismissal is not an error to report; the pad is right there.
    func testACancelledBiometricAttemptShowsNoMessage() async throws {
        biometrics.set(result: .failure(.cancelled))
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        auth.handleScenePhase(.background)

        _ = await auth.unlockWithBiometrics()

        XCTAssertNil(auth.biometricMessage)
    }

    // MARK: - requireAuth

    func testRequireAuthPassesStraightThroughWithNoPin() async {
        let auth = makeAuth()

        let granted = await auth.requireAuth(reason: "Reveal your recovery phrase")

        XCTAssertTrue(granted)
        XCTAssertEqual(biometrics.prompts, 0)
    }

    func testRequireAuthSucceedsOnBiometrics() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true

        let granted = await auth.requireAuth(reason: "Reveal your recovery phrase")

        XCTAssertTrue(granted)
        XCTAssertEqual(biometrics.prompts, 1)
        XCTAssertNil(auth.challenge, "no PIN sheet is needed")
    }

    func testRequireAuthReturnsFalseOnCancelWithoutAskingForThePin() async throws {
        biometrics.set(result: .failure(.cancelled))
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true

        let granted = await auth.requireAuth(reason: "Reveal your recovery phrase")

        XCTAssertFalse(granted)
        XCTAssertNil(auth.challenge, "a cancel is a refusal, not a request for the PIN")
    }

    /// Biometrics off: the request goes straight to the PIN sheet and waits.
    func testRequireAuthFallsBackToThePinSheetAndIsAnsweredByAVerifiedPin() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")

        let request = Task { await auth.requireAuth(reason: "Reveal your recovery phrase") }
        try await waitForChallenge(on: auth)

        let answered = await auth.answerChallenge(pin: "123456")
        let granted = await request.value

        XCTAssertTrue(answered)
        XCTAssertTrue(granted)
        XCTAssertNil(auth.challenge)
        XCTAssertEqual(auth.state, .unlocked, "step-up auth does not change the session state")
    }

    func testAWrongPinDoesNotAnswerTheChallenge() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")

        let request = Task { await auth.requireAuth(reason: "Reveal your recovery phrase") }
        try await waitForChallenge(on: auth)

        let answered = await auth.answerChallenge(pin: "000000")
        XCTAssertFalse(answered)
        XCTAssertNotNil(auth.challenge, "the sheet stays up")

        auth.resolveChallenge(granted: false)
        let granted = await request.value
        XCTAssertFalse(granted)
    }

    /// Dismissing the sheet is what `RootView` turns into this call.
    func testDismissingTheChallengeSheetRefusesTheRequest() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")

        let request = Task { await auth.requireAuth(reason: "Confirm this send") }
        try await waitForChallenge(on: auth)

        auth.resolveChallenge(granted: false)

        let granted = await request.value
        XCTAssertFalse(granted)
        XCTAssertNil(auth.challenge)
    }

    /// A broken sensor is a reason to ask for the PIN, not to give up.
    func testABiometricFailureFallsThroughToThePin() async throws {
        biometrics.set(result: .failure(.failed))
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true

        let request = Task { await auth.requireAuth(reason: "Reveal your recovery phrase") }
        try await waitForChallenge(on: auth)

        _ = await auth.answerChallenge(pin: "123456")
        let granted = await request.value

        XCTAssertTrue(granted)
        XCTAssertEqual(biometrics.prompts, 1)
    }

    // MARK: - Preferences

    func testTheBiometricOptInPersistsUnderTheAndroidKeyName() {
        let auth = makeAuth()

        auth.isBiometricEnabled = true

        XCTAssertTrue(defaults.bool(forKey: "biometric_enabled"))
        XCTAssertTrue(preferences.isBiometricEnabled)
    }

    func testAuthBeforeSendDefaultsOffAndPersists() {
        let auth = makeAuth()
        XCTAssertFalse(auth.isAuthBeforeSendEnabled)

        auth.isAuthBeforeSendEnabled = true

        XCTAssertTrue(defaults.bool(forKey: "auth_before_send"))
    }

    /// `requireAuth` publishes the challenge from inside a suspended task, so
    /// the test has to let that task run before answering it.
    private func waitForChallenge(on auth: AuthService, file: StaticString = #filePath, line: UInt = #line) async throws {
        for _ in 0..<200 {
            if auth.challenge != nil { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("no challenge was raised", file: file, line: line)
    }
}
