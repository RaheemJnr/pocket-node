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

    // `async` on purpose: the non-async `setUp()` override of a `nonisolated`
    // superclass method runs task-isolated, so touching this class's
    // `@MainActor` properties from it is a concurrency warning. The async form
    // inherits the class's isolation.
    override func setUp() async throws {
        try await super.setUp()
        keychain = KeychainStore(service: keychainService)
        try? keychain.deleteAll()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        preferences = UserDefaultsPreferences(defaults: defaults)
        biometrics = StubBiometrics()
        clock = TestClock()
    }

    override func tearDown() async throws {
        try? keychain.deleteAll()
        defaults.removePersistentDomain(forName: suiteName)
        keychain = nil
        defaults = nil
        preferences = nil
        biometrics = nil
        clock = nil
        try await super.tearDown()
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

        try await auth.removePin()

        XCTAssertEqual(auth.state, .noPin)
        XCTAssertFalse(auth.isBiometricEnabled)
    }

    /// B1: a store that cannot be read is not a store with no PIN. Collapsing
    /// the two would open the wallet on a launch before the first device
    /// unlock.
    func testAnUnreadableStoreStartsLockedNotOpen() async throws {
        let first = makeAuth()
        try await first.setPin("123456")

        let unreadable = UnreadableKeyValueStore(service: keychainService)
        let blind = AuthService(
            pin: PinService(keychain: unreadable, cost: .testing, clock: clock.source),
            biometrics: biometrics,
            preferences: preferences
        )

        XCTAssertEqual(blind.pin.pinPresence, .unknown)
        XCTAssertEqual(blind.state, .locked)
        XCTAssertTrue(blind.isGated)
    }

    /// And it stays locked across a refresh, rather than falling open once the
    /// read is retried and still fails.
    func testAnUnreadableStoreStaysLockedAcrossARefresh() async throws {
        let first = makeAuth()
        try await first.setPin("123456")

        let unreadable = UnreadableKeyValueStore(service: keychainService)
        let blind = AuthService(
            pin: PinService(keychain: unreadable, cost: .testing, clock: clock.source),
            biometrics: biometrics,
            preferences: preferences
        )

        await blind.refresh()
        XCTAssertEqual(blind.state, .locked)

        // Once the device is unlocked the read works, and it is still locked,
        // because reading a hash is not authentication.
        unreadable.failReads(nil)
        await blind.refresh()
        XCTAssertEqual(blind.state, .locked)
        XCTAssertEqual(blind.pin.pinPresence, .present)
    }

    /// B2: nothing a refresh can read is proof of who is holding the phone, so
    /// it must never be the thing that unlocks.
    func testRefreshNeverUnlocks() async throws {
        let auth = makeAuth()
        XCTAssertEqual(auth.state, .noPin)

        // A PIN appears from outside this session (another `PinService` over
        // the same Keychain, as onboarding on a second screen would be).
        let other = PinService(keychain: keychain, cost: .testing, clock: clock.source)
        try await other.setPin("123456")

        await auth.refresh()

        XCTAssertEqual(auth.state, .locked, "a PIN appearing locks, it does not authenticate")
    }

    func testRefreshLeavesAnUnlockedSessionAlone() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        XCTAssertEqual(auth.state, .unlocked)

        await auth.refresh()

        XCTAssertEqual(auth.state, .unlocked, "a refresh only ever tightens the gate")
    }

    func testIsGatedOnlyOpensForAConfirmedAbsenceOrAnUnlockedSession() async throws {
        let auth = makeAuth()
        XCTAssertFalse(auth.isGated, "no PIN, nothing to gate")

        try await auth.setPin("123456")
        XCTAssertFalse(auth.isGated, "unlocked")

        auth.handleScenePhase(.background)
        XCTAssertTrue(auth.isGated)
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

    // MARK: - The permanent lock is not biometric-bypassable (S2)

    /// At 10 failures the wallet is recoverable only from the recovery phrase.
    /// A face that still opened it would hand the entire escalation schedule
    /// back to whoever is holding the phone.
    func testBiometricsCannotUnlockAPermanentlyLockedPin() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        await exhaustAllAttempts(on: auth)
        XCTAssertTrue(auth.pin.isPermanentlyLocked)
        auth.handleScenePhase(.background)

        let unlocked = await auth.unlockWithBiometrics()

        XCTAssertFalse(unlocked)
        XCTAssertEqual(auth.state, .locked)
        XCTAssertEqual(biometrics.prompts, 0, "no prompt is even raised")
        XCTAssertFalse(auth.canUseBiometrics, "and the button is hidden")
    }

    func testRequireAuthDoesNotPromptBiometricsWhilePermanentlyLocked() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        await exhaustAllAttempts(on: auth)

        let request = Task { await auth.requireAuth(reason: "Reveal your recovery phrase") }
        try await waitForChallenge(on: auth)
        auth.resolveChallenge(granted: false)
        _ = await request.value

        XCTAssertEqual(biometrics.prompts, 0, "it went straight to the PIN, which cannot pass either")
    }

    /// A temporary lockout still allows biometrics, matching Android: that one
    /// is about slowing PIN guessing, not about revoking the wallet.
    func testBiometricsStillWorkDuringATemporaryLockout() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        for _ in 0..<5 { _ = await auth.unlock(pin: "000000") }
        XCTAssertTrue(auth.pin.isLockedOut)
        XCTAssertFalse(auth.pin.isPermanentlyLocked)
        auth.handleScenePhase(.background)

        let unlocked = await auth.unlockWithBiometrics()

        XCTAssertTrue(unlocked)
        XCTAssertEqual(auth.state, .unlocked)
    }

    // MARK: - A store that cannot record an attempt (S3)

    func testAnUnrecordableAttemptIsReportedAndLeavesTheSessionLocked() async throws {
        let scripted = ScriptedKeyValueStore(service: keychainService)
        let auth = AuthService(
            pin: PinService(keychain: scripted, cost: .testing, clock: clock.source),
            biometrics: biometrics,
            preferences: preferences
        )
        try await auth.setPin("123456")
        auth.handleScenePhase(.background)
        let before = auth.pin.remainingAttempts
        scripted.failWrites(to: [PinAccount.writeProbe])

        let unlocked = await auth.unlock(pin: "123456")

        XCTAssertFalse(unlocked)
        XCTAssertEqual(auth.state, .locked)
        XCTAssertEqual(auth.storeMessage, AuthService.storeUnavailableMessage)
        XCTAssertEqual(auth.pin.remainingAttempts, before, "the user lost no attempt")
    }

    // MARK: - setPin failure (S5)

    func testAFailedSetPinLeavesTheStateMatchingWhatIsStored() async throws {
        let scripted = ScriptedKeyValueStore(service: keychainService)
        let auth = AuthService(
            pin: PinService(keychain: scripted, cost: .testing, clock: clock.source),
            biometrics: biometrics,
            preferences: preferences
        )
        // The salt is lost, so the hash that lands is unverifiable, and the
        // cleanup that would remove it is refused too.
        scripted.failWrites(to: [PinAccount.salt])
        scripted.failDeletes(true)

        do {
            try await auth.setPin("123456")
            XCTFail("a partial write must not report success")
        } catch {
            XCTAssertNotNil(error as? PinServiceError)
        }

        XCTAssertEqual(auth.state, .locked, "a stored hash means the app locks, not that it opens")
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

    /// F1: an unreadable store is not "no PIN is configured". Granting a
    /// recovery phrase reveal on it would be the same hole as opening the
    /// wallet on it.
    func testRequireAuthDoesNotGrantOnAnUnreadableStore() async throws {
        let first = makeAuth()
        try await first.setPin("123456")

        let unreadable = UnreadableKeyValueStore(service: keychainService)
        let blind = AuthService(
            pin: PinService(keychain: unreadable, cost: .testing, clock: clock.source),
            biometrics: biometrics,
            preferences: preferences
        )
        XCTAssertEqual(blind.pin.pinPresence, .unknown)

        let request = Task { await blind.requireAuth(reason: "Reveal your recovery phrase") }
        try await waitForChallenge(on: blind)

        // It fell through to the PIN challenge rather than returning true. The
        // challenge itself cannot pass either, because `verify` refuses an
        // unreadable store.
        let answered = await blind.answerChallenge(pin: "123456")
        XCTAssertFalse(answered)
        XCTAssertEqual(blind.storeMessage, AuthService.storeUnavailableMessage)

        blind.resolveChallenge(granted: false)
        let granted = await request.value
        XCTAssertFalse(granted)
    }

    /// F2: a delete the Keychain refused leaves the PIN in place, so the app
    /// must keep gating on it.
    func testAFailedRemovePinLeavesTheAppLocked() async throws {
        let scripted = ScriptedKeyValueStore(service: keychainService)
        let auth = AuthService(
            pin: PinService(keychain: scripted, cost: .testing, clock: clock.source),
            biometrics: biometrics,
            preferences: preferences
        )
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        scripted.failDeletes(true)

        do {
            try await auth.removePin()
            XCTFail("a refused delete must not report the PIN as removed")
        } catch {
            XCTAssertNotNil(error as? PinServiceError)
        }

        XCTAssertEqual(auth.pin.pinPresence, .present)
        XCTAssertEqual(auth.state, .locked)
        XCTAssertTrue(auth.isGated)
        XCTAssertTrue(auth.isBiometricEnabled, "the opt-in still has a PIN to stand in for")
    }

    /// F9: the sheet is torn down with the rest of the UI when the app locks,
    /// so a suspended request would hang with nothing left to answer it.
    func testLockingResolvesAnOutstandingChallenge() async throws {
        let auth = makeAuth()
        try await auth.setPin("123456")

        let request = Task { await auth.requireAuth(reason: "Reveal your recovery phrase") }
        try await waitForChallenge(on: auth)

        auth.handleScenePhase(.background)

        let granted = await request.value
        XCTAssertFalse(granted)
        XCTAssertNil(auth.challenge, "the sheet is cleared with it")
        XCTAssertEqual(auth.state, .locked)
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

    /// Drives the PIN to the permanent lock (10 cumulative failures), waiting
    /// out each escalating lockout on the injected clock.
    private func exhaustAllAttempts(on auth: AuthService) async {
        let waits: [Int64] = [0, 30, 60, 300, 1800, 3600]
        for wait in waits {
            clock.advance(seconds: wait)
            await auth.pin.refresh()
            for _ in 0..<(wait == 0 ? 5 : 1) {
                _ = await auth.unlock(pin: "000000")
            }
        }
        await auth.pin.refresh()
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
