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
        let verified = try await pin.verify("123456")
        XCTAssertTrue(verified)
    }

    func testAWrongPinDoesNotVerify() async throws {
        try await pin.setPin("123456")

        let verified = try await pin.verify("654321")
        XCTAssertFalse(verified)
    }

    /// The hash and the salt outlive the instance that wrote them, so an
    /// upgrade or a relaunch does not strand the user.
    func testAPinSurvivesANewServiceInstance() async throws {
        try await pin.setPin("112233")

        let reopened = makeService()
        XCTAssertTrue(reopened.hasPin, "seeded synchronously, before any refresh")
        let verified = try await reopened.verify("112233")
        XCTAssertTrue(verified)
    }

    func testRemovePinClearsEverything() async throws {
        try await pin.setPin("123456")
        _ = try? await pin.verify("000000")

        try await pin.removePin()

        XCTAssertFalse(pin.hasPin)
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: keychain), .absent)
        let verified = try await pin.verify("123456")
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

        let verified = try await pin.verify("12345")

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

        _ = try? await pin.verify("000000")
        XCTAssertEqual(pin.remainingAttempts, 4)

        _ = try? await pin.verify("000000")
        XCTAssertEqual(pin.remainingAttempts, 3)
    }

    func testASuccessResetsTheCounter() async throws {
        try await pin.setPin("123456")
        _ = try? await pin.verify("000000")
        _ = try? await pin.verify("000000")
        XCTAssertEqual(pin.remainingAttempts, 3)

        let verified = try await pin.verify("123456")

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
        let duringLockout = try await pin.verify("123456")
        XCTAssertFalse(duringLockout)
    }

    func testTheLockoutExpiresOnItsOwn() async throws {
        try await pin.setPin("123456")
        await fail(times: 5)
        XCTAssertTrue(pin.isLockedOut)

        clock.advance(seconds: 30)
        await pin.refresh()

        XCTAssertFalse(pin.isLockedOut)
        let verified = try await pin.verify("123456")
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
        let verified = try await pin.verify("123456")
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

    // MARK: - An unwritable store buys no guesses (S3)

    /// Without the write probe this is the hole: reads work, so every
    /// comparison runs, but every failure the policy tries to record is
    /// dropped, so the counter never reaches a lockout and the attacker gets
    /// unlimited tries.
    func testVerifyRefusesToHashWhenTheStoreCannotRecordTheAttempt() async throws {
        let scripted = ScriptedKeyValueStore(service: service)
        let guarded = PinService(keychain: scripted, cost: .testing, clock: clock.source)
        try await guarded.setPin("123456")
        await guarded.refresh()
        let before = guarded.remainingAttempts

        // Every write now fails, including the probe.
        scripted.failWrites(to: [
            PinAccount.hash, PinAccount.salt, PinAccount.kdfVersion,
            PinAccount.failedAttempts, PinAccount.lastFailedAt,
            PinAccount.lockoutUntil, PinAccount.writeProbe,
        ])

        do {
            _ = try await guarded.verify("000000")
            XCTFail("a verify that cannot be recorded must not be answered")
        } catch {
            guard case .storeUnavailable = error as? PinServiceError else {
                return XCTFail("expected storeUnavailable, got \(error)")
            }
        }

        await guarded.refresh()
        XCTAssertEqual(guarded.remainingAttempts, before, "no attempt was spent, and none was granted")
    }

    /// The same refusal applies to a correct PIN: an unrecordable success is
    /// still an unrecordable attempt.
    func testVerifyRefusesTheCorrectPinTooWhenTheStoreIsUnwritable() async throws {
        let scripted = ScriptedKeyValueStore(service: service)
        let guarded = PinService(keychain: scripted, cost: .testing, clock: clock.source)
        try await guarded.setPin("123456")
        scripted.failWrites(to: [PinAccount.writeProbe])

        do {
            _ = try await guarded.verify("123456")
            XCTFail("expected a refusal")
        } catch {
            XCTAssertNotNil(error as? PinServiceError)
        }
    }

    // MARK: - Partial setPin cleanup (S5)

    /// The hash is written last, so a lost salt leaves a hash that can never
    /// verify. Cleanup removes it rather than leaving the user with a PIN that
    /// is guaranteed to fail five times and lock them out.
    func testSetPinCleansUpWhenAFieldIsLost() async throws {
        let scripted = ScriptedKeyValueStore(service: service)
        let guarded = PinService(keychain: scripted, cost: .testing, clock: clock.source)
        scripted.failWrites(to: [PinAccount.salt])

        do {
            try await guarded.setPin("123456")
            XCTFail("a partial write must not report success")
        } catch {
            XCTAssertNotNil(error as? PinServiceError)
        }

        XCTAssertEqual(guarded.pinPresence, .absent, "the unusable hash was cleaned up")
    }

    /// When the cleanup is refused as well, the hash really is still there, and
    /// the published presence has to say so: reporting `.absent` would drop the
    /// lock in front of a stored PIN.
    func testSetPinReportsThePinThatSurvivedAFailedCleanup() async throws {
        let scripted = ScriptedKeyValueStore(service: service)
        let guarded = PinService(keychain: scripted, cost: .testing, clock: clock.source)
        scripted.failWrites(to: [PinAccount.salt])
        scripted.failDeletes(true)

        do {
            try await guarded.setPin("123456")
            XCTFail("a partial write must not report success")
        } catch {
            XCTAssertNotNil(error as? PinServiceError)
        }

        XCTAssertEqual(guarded.pinPresence, .present, "the hash is stored, so the app must lock")
    }

    // MARK: - A lost salt must not destroy the PIN (F3)

    /// The nastiest of the read failures. The shared policy reads a `nil` salt
    /// as "none generated yet" and mints a replacement, which is right for a
    /// first use and permanent data loss for a transient read error: the hash
    /// it overwrote the salt for can never match again.
    func testATransientSaltReadFailureDoesNotDestroyTheStoredSalt() async throws {
        let selective = SelectiveReadKeyValueStore(service: service)
        let guarded = PinService(keychain: selective, cost: .testing, clock: clock.source)
        try await guarded.setPin("123456")
        let saltBefore = try XCTUnwrap(try selective.rawGet(account: PinAccount.salt))

        // The hash still reads; only the salt does not.
        selective.failReads(to: [PinAccount.salt])

        do {
            _ = try await guarded.verify("123456")
            XCTFail("a verify with an unreadable salt must not proceed")
        } catch {
            guard case .storeUnavailable = error as? PinServiceError else {
                return XCTFail("expected storeUnavailable, got \(error)")
            }
        }

        XCTAssertEqual(
            try selective.rawGet(account: PinAccount.salt),
            saltBefore,
            "the stored salt must be exactly as it was; a replacement would be unrecoverable"
        )

        // And once reads recover, the real PIN still works.
        selective.failReads(to: [])
        let verified = try await guarded.verify("123456")
        XCTAssertTrue(verified)
    }

    func testAnUnreadableHashRefusesTheVerifyRatherThanAnsweringFalse() async throws {
        let selective = SelectiveReadKeyValueStore(service: service)
        let guarded = PinService(keychain: selective, cost: .testing, clock: clock.source)
        try await guarded.setPin("123456")
        selective.failReads(to: [PinAccount.hash])

        do {
            _ = try await guarded.verify("123456")
            XCTFail("expected a refusal, not a false")
        } catch {
            XCTAssertNotNil(error as? PinServiceError)
        }
    }

    // MARK: - removePin (F2)

    /// A PIN that is still stored must never be reported as gone.
    func testRemovePinThrowsWhenADeleteIsRefused() async throws {
        let scripted = ScriptedKeyValueStore(service: service)
        let guarded = PinService(keychain: scripted, cost: .testing, clock: clock.source)
        try await guarded.setPin("123456")
        scripted.failDeletes(true)

        do {
            try await guarded.removePin()
            XCTFail("a refused delete must not report the PIN as removed")
        } catch {
            XCTAssertNotNil(error as? PinServiceError)
        }

        XCTAssertEqual(guarded.pinPresence, .present)
    }

    // MARK: - Presence (B1)

    func testAnUnreadableStoreReportsUnknownRatherThanAbsent() async throws {
        try await pin.setPin("123456")

        let unreadable = UnreadableKeyValueStore(service: service)
        let blind = PinService(keychain: unreadable, cost: .testing, clock: clock.source)

        XCTAssertEqual(blind.pinPresence, .unknown)
        XCTAssertFalse(blind.hasPin, "hasPin is not the gate; presence is")
    }

    func testPresenceRecoversOnceTheStoreBecomesReadable() async throws {
        try await pin.setPin("123456")

        let unreadable = UnreadableKeyValueStore(service: service)
        let blind = PinService(keychain: unreadable, cost: .testing, clock: clock.source)
        XCTAssertEqual(blind.pinPresence, .unknown)

        unreadable.failReads(nil)
        await blind.refresh()

        XCTAssertEqual(blind.pinPresence, .present)
    }

    private func fail(times: Int) async {
        for _ in 0..<times {
            _ = try? await pin.verify("000000")
        }
    }
}
