import PocketNodeCore
import XCTest

@testable import PocketNode

/// The Keychain adapter on its own, against a throwaway service. No `PinPolicy`
/// here: this is the storage contract, field by field.
final class KeychainPinStoreTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.pin.store"
    private var keychain: KeychainStore!
    private var store: KeychainPinStore!

    override func setUp() {
        super.setUp()
        keychain = KeychainStore(service: service)
        try? keychain.deleteAll()
        store = KeychainPinStore(keychain: keychain)
    }

    override func tearDown() {
        try? keychain.deleteAll()
        keychain = nil
        store = nil
        super.tearDown()
    }

    // MARK: - Defaults

    func testEmptyStoreReadsAsUnset() {
        XCTAssertNil(store.getPinHash())
        XCTAssertNil(store.getSalt())
        XCTAssertNil(store.getKdfVersion())
        XCTAssertNil(store.getLastFailedAt())
        XCTAssertNil(store.getLockoutUntil())
        // The one field the shared contract says is never nil.
        XCTAssertEqual(store.getFailedAttempts(), 0)
    }

    // MARK: - Round trips

    func testEveryFieldRoundTrips() {
        let salt = KotlinByteArray.from([UInt8](repeating: 0xAB, count: 32))
        store.update { editor in
            editor.pinHash(v: "deadbeef")
            editor.salt(v: salt)
            editor.kdfVersion(v: KotlinInt(int: 2))
            editor.failedAttempts(v: KotlinInt(int: 3))
            editor.lastFailedAt(v: KotlinLong(longLong: 1_700_000_000_123))
            editor.lockoutUntil(v: KotlinLong(longLong: 1_700_000_030_123))
        }

        XCTAssertEqual(store.getPinHash(), "deadbeef")
        XCTAssertEqual(store.getSalt()?.data, salt.data)
        XCTAssertEqual(store.getKdfVersion()?.int32Value, 2)
        XCTAssertEqual(store.getFailedAttempts(), 3)
        XCTAssertEqual(store.getLastFailedAt()?.int64Value, 1_700_000_000_123)
        XCTAssertEqual(store.getLockoutUntil()?.int64Value, 1_700_000_030_123)
    }

    /// The failure counter escalates past anything a 32-bit read could truncate
    /// only in theory, but the timestamps are 64-bit and routinely above 2^32,
    /// so the big-endian encoding has to survive the full range.
    func testLargeTimestampsSurvive() {
        store.update { editor in
            editor.lastFailedAt(v: KotlinLong(longLong: Int64.max))
            editor.lockoutUntil(v: KotlinLong(longLong: Int64.max))
        }

        XCTAssertEqual(store.getLastFailedAt()?.int64Value, Int64.max)
        XCTAssertEqual(store.getLockoutUntil()?.int64Value, Int64.max)
    }

    // MARK: - Partial edits

    func testPartialUpdateLeavesUntouchedFieldsIntact() {
        store.update { editor in
            editor.pinHash(v: "cafebabe")
            editor.kdfVersion(v: KotlinInt(int: 2))
            editor.failedAttempts(v: KotlinInt(int: 1))
        }

        store.update { editor in
            editor.failedAttempts(v: KotlinInt(int: 2))
        }

        XCTAssertEqual(store.getFailedAttempts(), 2)
        XCTAssertEqual(store.getPinHash(), "cafebabe")
        XCTAssertEqual(store.getKdfVersion()?.int32Value, 2)
    }

    func testNullRemovesAField() {
        store.update { editor in
            editor.pinHash(v: "cafebabe")
            editor.lockoutUntil(v: KotlinLong(longLong: 99))
        }
        XCTAssertNotNil(store.getLockoutUntil())

        store.update { editor in
            editor.lockoutUntil(v: nil)
        }

        XCTAssertNil(store.getLockoutUntil())
        XCTAssertEqual(store.getPinHash(), "cafebabe", "clearing one field must not touch another")
    }

    /// `clearFailureState` is the shared extension every reset goes through.
    func testClearFailureStateLeavesThePinAlone() {
        store.update { editor in
            editor.pinHash(v: "cafebabe")
            editor.salt(v: KotlinByteArray.from([1, 2, 3]))
            editor.failedAttempts(v: KotlinInt(int: 4))
            editor.lastFailedAt(v: KotlinLong(longLong: 5))
            editor.lockoutUntil(v: KotlinLong(longLong: 6))
        }

        store.update { editor in
            PinStoreKt.clearFailureState(editor)
        }

        XCTAssertEqual(store.getFailedAttempts(), 0)
        XCTAssertNil(store.getLastFailedAt())
        XCTAssertNil(store.getLockoutUntil())
        XCTAssertEqual(store.getPinHash(), "cafebabe")
        XCTAssertEqual(store.getSalt()?.data, Data([1, 2, 3]))
    }

    // MARK: - Durability

    /// The whole point of the Keychain rather than a file: the state is not
    /// owned by the instance that wrote it.
    func testAFreshInstanceSeesWhatAnEarlierOneWrote() {
        store.update { editor in
            editor.pinHash(v: "persisted")
            editor.failedAttempts(v: KotlinInt(int: 7))
        }

        let reopened = KeychainPinStore(keychain: KeychainStore(service: service))

        XCTAssertEqual(reopened.getPinHash(), "persisted")
        XCTAssertEqual(reopened.getFailedAttempts(), 7)
    }

    /// The Keychain has no transaction, so `updateDurable` is the same write.
    /// Declared explicitly because Kotlin's default body does not cross the
    /// Objective-C bridge.
    func testUpdateDurableWritesLikeUpdate() {
        store.updateDurable { editor in
            editor.failedAttempts(v: KotlinInt(int: 9))
        }

        XCTAssertEqual(store.getFailedAttempts(), 9)
    }

    func testPresenceTracksTheHash() {
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: keychain), .absent)

        store.update { $0.pinHash(v: "abc") }
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: keychain), .present)

        store.update { $0.pinHash(v: nil) }
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: keychain), .absent)
    }

    /// The blocker the tri-state exists for: a read that fails is not a PIN
    /// that is missing.
    func testAnUnreadableStoreIsUnknownNotAbsent() {
        store.update { $0.pinHash(v: "abc") }
        let unreadable = UnreadableKeyValueStore(service: service)

        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: unreadable), .unknown)

        unreadable.failReads(nil)
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: unreadable), .present)
    }

    func testNegativeIntegersRoundTrip() {
        store.update { editor in
            editor.kdfVersion(v: KotlinInt(int: -7))
            editor.failedAttempts(v: KotlinInt(int: Int32.min))
            editor.lastFailedAt(v: KotlinLong(longLong: -1))
            editor.lockoutUntil(v: KotlinLong(longLong: Int64.min))
        }

        XCTAssertEqual(store.getKdfVersion()?.int32Value, -7)
        XCTAssertEqual(store.getFailedAttempts(), Int32.min)
        XCTAssertEqual(store.getLastFailedAt()?.int64Value, -1)
        XCTAssertEqual(store.getLockoutUntil()?.int64Value, Int64.min)
    }

    /// A stored integer of the wrong width is damaged, not absent. Its getter
    /// still has to return the seam's `nil` (or 0), but the read is recorded
    /// as a failure so it counts as dirty rather than as "no lockout".
    func testAWrongSizedIntegerIsADirtyReadNotAnAbsence() throws {
        store.update { $0.pinHash(v: "abc") }
        for account in [PinAccount.failedAttempts, PinAccount.kdfVersion, PinAccount.lastFailedAt, PinAccount.lockoutUntil] {
            try keychain.set(Data([0x01, 0x02, 0x03]), account: account)
        }
        XCTAssertNil(store.takeFailure())

        XCTAssertEqual(store.getFailedAttempts(), 0)
        XCTAssertEqual(store.takeFailure()?.status, errSecDecode)
        XCTAssertNil(store.getKdfVersion())
        XCTAssertEqual(store.takeFailure()?.status, errSecDecode)
        XCTAssertNil(store.getLastFailedAt())
        XCTAssertEqual(store.takeFailure()?.status, errSecDecode)
        XCTAssertNil(store.getLockoutUntil())
        XCTAssertEqual(store.takeFailure()?.status, errSecDecode)
    }

    /// A field that is really not there is still not a failure.
    func testAMissingIntegerIsNotAFailure() {
        XCTAssertNil(store.getLockoutUntil())
        XCTAssertNil(store.takeFailure())
    }

    // MARK: - Write probe

    func testTheWriteProbeSucceedsAndLeavesNothingBehind() throws {
        XCTAssertNil(store.probeWrite())
        XCTAssertNil(try keychain.get(account: PinAccount.writeProbe), "the probe cleans up after itself")
    }

    func testTheWriteProbeReportsARefusedWrite() {
        let failing = FailingKeyValueStore(service: service)
        failing.failWrites(true)

        XCTAssertNotNil(KeychainPinStore(keychain: failing).probeWrite())
    }

    // MARK: - Keychain attributes
    //
    // Mirrors the wallet envelope's attribute test. The PIN state is what the
    // failure counter's durability rests on, so it has to be device-only and
    // out of iCloud for the same reasons the envelope is.

    func testPinItemsAreDeviceOnlyAndNotSynchronizable() throws {
        store.update { $0.pinHash(v: "abc") }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: PinAccount.hash,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)

        let attributes = try XCTUnwrap(result as? [String: Any])
        XCTAssertEqual(
            attributes[kSecAttrAccessible as String] as? String,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly as String
        )
        XCTAssertNotEqual(attributes[kSecAttrSynchronizable as String] as? Bool, true)
    }

    // MARK: - Write ordering
    //
    // The four rules in `KeychainPinStore.apply`, asserted on the order the
    // accounts are written. The Keychain gives no transaction, so that ordering
    // is the only thing between an interrupted write and a state that is either
    // exploitable or unrecoverable.

    func testAHashBeingRemovedIsWrittenFirst() throws {
        let recording = RecordingKeyValueStore(service: service)
        let store = KeychainPinStore(keychain: recording)
        store.update { editor in
            editor.pinHash(v: "abc")
            editor.salt(v: KotlinByteArray.from([1]))
            editor.kdfVersion(v: KotlinInt(int: 2))
        }
        recording.reset()

        // `removePin`'s edit.
        store.update { editor in
            editor.pinHash(v: nil)
            editor.kdfVersion(v: nil)
            editor.salt(v: nil)
            PinStoreKt.clearFailureState(editor)
        }

        XCTAssertEqual(
            recording.writes.first,
            PinAccount.hash,
            "the credential has to die before its salt, or it is left unverifiable"
        )
    }

    func testAHashBeingSetIsWrittenLastAndAfterItsSaltAndKdfVersion() throws {
        let recording = RecordingKeyValueStore(service: service)
        let store = KeychainPinStore(keychain: recording)

        // `setPin`'s edit.
        store.update { editor in
            editor.pinHash(v: "abc")
            editor.kdfVersion(v: KotlinInt(int: 2))
            editor.salt(v: KotlinByteArray.from([1]))
            PinStoreKt.clearFailureState(editor)
        }

        let writes = recording.writes
        XCTAssertEqual(writes.last, PinAccount.hash)
        // Also what keeps the Kotlin "missing KDF version means legacy Blake2b"
        // branch unreachable on iOS: a stored hash always has both beside it.
        XCTAssertLessThan(try index(of: PinAccount.salt, in: writes), try index(of: PinAccount.hash, in: writes))
        XCTAssertLessThan(try index(of: PinAccount.kdfVersion, in: writes), try index(of: PinAccount.hash, in: writes))
    }

    func testALockoutBeingAppliedIsWrittenBeforeTheCounter() throws {
        let recording = RecordingKeyValueStore(service: service)
        let store = KeychainPinStore(keychain: recording)

        // `recordFailedAttempt`'s edit.
        store.update { editor in
            editor.failedAttempts(v: KotlinInt(int: 5))
            editor.lastFailedAt(v: KotlinLong(longLong: 1))
            editor.lockoutUntil(v: KotlinLong(longLong: 30_000))
        }

        let writes = recording.writes
        XCTAssertLessThan(
            try index(of: PinAccount.lockoutUntil, in: writes),
            try index(of: PinAccount.failedAttempts, in: writes),
            "an interrupted failure record must still stop the guessing"
        )
    }

    func testALockoutBeingClearedIsWrittenAfterTheCounter() throws {
        let recording = RecordingKeyValueStore(service: service)
        let store = KeychainPinStore(keychain: recording)

        store.update { editor in
            PinStoreKt.clearFailureState(editor)
        }

        let writes = recording.writes
        XCTAssertGreaterThan(
            try index(of: PinAccount.lockoutUntil, in: writes),
            try index(of: PinAccount.failedAttempts, in: writes),
            "the loosening write lands last, so an interruption strands nobody but an attacker"
        )
    }

    private func index(of account: String, in writes: [String]) throws -> Int {
        try XCTUnwrap(writes.firstIndex(of: account), "\(account) was never written")
    }

    // MARK: - Write failures

    /// A refused write is recorded rather than swallowed, so `PinService` can
    /// turn "your PIN was not actually stored" into an error the user sees.
    func testARefusedWriteIsReported() {
        let failing = FailingKeyValueStore(service: service)
        let store = KeychainPinStore(keychain: failing)
        failing.failWrites(true)

        store.update { $0.pinHash(v: "nope") }

        XCTAssertNotNil(store.takeFailure())
        XCTAssertNil(store.takeFailure(), "taking the failure clears it")
    }
}
