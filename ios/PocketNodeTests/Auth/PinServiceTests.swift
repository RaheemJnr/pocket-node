import XCTest

@testable import PocketNode

/// `PinService` over the shared `PinPolicy`, at lowered Argon2id cost.
///
/// The escalation table being asserted here is the shared Kotlin one
/// (`PinPolicy.lockoutDurationFor`), so these tests are also the iOS half of
/// the cross-platform parity claim: Android runs the same schedule from the
/// same source.
@MainActor
final class PinServiceTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.pin.service"
    private var keychain: KeychainStore!
    private var clock: TestClock!
    private var pin: PinService!

    // `async` on purpose: the non-async `setUp()` override of a `nonisolated`
    // superclass method runs task-isolated, so it cannot build the
    // `@MainActor` `PinService`. The async form inherits the class's isolation.
    override func setUp() async throws {
        try await super.setUp()
        keychain = KeychainStore(service: service)
        try? keychain.deleteAll()
        clock = TestClock()
        pin = makeService()
    }

    override func tearDown() async throws {
        try? keychain.deleteAll()
        keychain = nil
        clock = nil
        pin = nil
        try await super.tearDown()
    }

    private func makeService() -> PinService {
        PinService(keychain: keychain, cost: .testing, clock: clock.source)
    }

    // MARK: - Set and verify

    func testNoPinOnAFreshStore() async {
        XCTAssertFalse(pin.hasPin)
        await pin.refresh()
        XCTAssertFalse(pin.hasPin)
    }

    func testSetThenVerifyTheSamePin() async throws {
        try await pin.setPin("123456")

        XCTAssertTrue(pin.hasPin)
        let verified = await pin.verify("123456")
        XCTAssertTrue(verified)
    }

    func testAWrongPinDoesNotVerify() async throws {
        try await pin.setPin("123456")

        let verified = await pin.verify("654321")
        XCTAssertFalse(verified)
    }

    /// The hash and the salt outlive the instance that wrote them, so an
    /// upgrade or a relaunch does not strand the user.
    func testAPinSurvivesANewServiceInstance() async throws {
        try await pin.setPin("112233")

        let reopened = makeService()
        XCTAssertTrue(reopened.hasPin, "seeded synchronously, before any refresh")
        let verified = await reopened.verify("112233")
        XCTAssertTrue(verified)
    }

    func testRemovePinClearsEverything() async throws {
        try await pin.setPin("123456")
        _ = await pin.verify("000000")

        await pin.removePin()

        XCTAssertFalse(pin.hasPin)
        XCTAssertFalse(KeychainPinStore.hasStoredPin(keychain: keychain))
        let verified = await pin.verify("123456")
        XCTAssertFalse(verified, "there is nothing left to verify against")
    }

    // MARK: - Format

    func testFiveDigitsIsRejectedBeforeHashing() async {
        do {
            try await pin.setPin("12345")
            XCTFail("a 5-digit PIN must not be stored")
        } catch {
            XCTAssertEqual(error as? PinServiceError, .invalidFormat)
        }
        XCTAssertFalse(pin.hasPin)
    }

    func testSevenDigitsIsRejected() async {
        do {
            try await pin.setPin("1234567")
            XCTFail("a 7-digit PIN must not be stored")
        } catch {
            XCTAssertEqual(error as? PinServiceError, .invalidFormat)
        }
    }

    func testNonDigitsAreRejected() async {
        for candidate in ["12345a", "12 456", "１２３４５６", "abcdef"] {
            do {
                try await pin.setPin(candidate)
                XCTFail("\(candidate) must not be stored")
            } catch {
                XCTAssertEqual(error as? PinServiceError, .invalidFormat, candidate)
            }
        }
    }

    /// A malformed entry must not cost the user an attempt: the policy never
    /// sees it, so the counter does not move.
    func testMalformedVerifyReturnsFalseWithoutCountingAnAttempt() async throws {
        try await pin.setPin("123456")

        let verified = await pin.verify("12345")

        XCTAssertFalse(verified)
        XCTAssertEqual(pin.remainingAttempts, PinService.maxAttempts)
        XCTAssertFalse(pin.isLockedOut)
    }

    func testAsciiDigitsRejectsNonAsciiDigits() {
        XCTAssertNotNil(PinService.asciiDigits("012345"))
        XCTAssertNil(PinService.asciiDigits("01234"))
        XCTAssertNil(PinService.asciiDigits("0123456"))
        XCTAssertNil(PinService.asciiDigits(""))
        // Arabic-Indic digits are digits to `Character.isNumber` and are not
        // what the shared policy will accept, so the check is on the code point.
        XCTAssertNil(PinService.asciiDigits("٠١٢٣٤٥"))
    }

    // MARK: - Attempts

    func testAWrongPinDecrementsTheRemainingAttempts() async throws {
        try await pin.setPin("123456")
        XCTAssertEqual(pin.remainingAttempts, 5)

        _ = await pin.verify("000000")
        XCTAssertEqual(pin.remainingAttempts, 4)

        _ = await pin.verify("000000")
        XCTAssertEqual(pin.remainingAttempts, 3)
    }

    func testASuccessResetsTheCounter() async throws {
        try await pin.setPin("123456")
        _ = await pin.verify("000000")
        _ = await pin.verify("000000")
        XCTAssertEqual(pin.remainingAttempts, 3)

        let verified = await pin.verify("123456")

        XCTAssertTrue(verified)
        XCTAssertEqual(pin.remainingAttempts, 5)
        XCTAssertFalse(pin.isLockedOut)
    }

    // MARK: - The lockout schedule
    //
    // Shared table (`PinPolicy.lockoutDurationFor`):
    //   5 -> 30 s, 6 -> 1 min, 7 -> 5 min, 8 -> 30 min, 9 -> 1 h, 10+ -> permanent.

    func testFiveFailuresLockOutForThirtySeconds() async throws {
        try await pin.setPin("123456")
        await fail(times: 5)

        XCTAssertTrue(pin.isLockedOut)
        XCTAssertEqual(pin.remainingAttempts, 0)
        XCTAssertEqual(pin.lockoutRemainingSeconds, 30)
        XCTAssertFalse(pin.isPermanentlyLocked)

        // The correct PIN is refused while the lockout stands.
        let duringLockout = await pin.verify("123456")
        XCTAssertFalse(duringLockout)
    }

    func testTheLockoutExpiresOnItsOwn() async throws {
        try await pin.setPin("123456")
        await fail(times: 5)
        XCTAssertTrue(pin.isLockedOut)

        clock.advance(seconds: 30)
        await pin.refresh()

        XCTAssertFalse(pin.isLockedOut)
        let verified = await pin.verify("123456")
        XCTAssertTrue(verified)
        XCTAssertEqual(pin.remainingAttempts, 5, "a success clears the counter as well")
    }

    /// The counter does not reset when a lockout expires, so the next failure
    /// escalates from where the last one left off.
    func testTheSixthFailureLocksOutForAMinute() async throws {
        try await pin.setPin("123456")
        await fail(times: 5)
        clock.advance(seconds: 30)
        await pin.refresh()
        XCTAssertFalse(pin.isLockedOut)

        await fail(times: 1)

        XCTAssertTrue(pin.isLockedOut)
        XCTAssertEqual(pin.lockoutRemainingSeconds, 60)
    }

    func testTenFailuresLockThePinPermanently() async throws {
        try await pin.setPin("123456")

        // 5 -> 30 s, 6 -> 60 s, 7 -> 5 min, 8 -> 30 min, 9 -> 1 h, 10 -> permanent.
        let waits: [Int64] = [30, 60, 300, 1800, 3600]
        await fail(times: 4)
        for wait in waits {
            await fail(times: 1)
            XCTAssertTrue(pin.isLockedOut)
            clock.advance(seconds: wait)
            await pin.refresh()
            XCTAssertFalse(pin.isLockedOut, "a \(wait)s lockout must expire after \(wait)s")
        }

        await fail(times: 1) // the tenth

        XCTAssertTrue(pin.isPermanentlyLocked)
        XCTAssertTrue(pin.isLockedOut)

        // Waiting does not help any more.
        clock.advance(seconds: 365 * 24 * 60 * 60)
        await pin.refresh()
        XCTAssertTrue(pin.isLockedOut)
        let verified = await pin.verify("123456")
        XCTAssertFalse(verified)
    }

    /// The counter is the thing that has to survive, which is why it is in the
    /// Keychain and not in memory (#370).
    func testTheFailureCountSurvivesANewServiceInstance() async throws {
        try await pin.setPin("123456")
        await fail(times: 3)

        let reopened = makeService()
        await reopened.refresh()

        XCTAssertEqual(reopened.remainingAttempts, 2)
    }

    private func fail(times: Int) async {
        for _ in 0..<times {
            _ = await pin.verify("000000")
        }
    }
}
