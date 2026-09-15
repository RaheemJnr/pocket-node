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

    func testHasStoredPinTracksTheHash() {
        XCTAssertFalse(KeychainPinStore.hasStoredPin(keychain: keychain))

        store.update { $0.pinHash(v: "abc") }
        XCTAssertTrue(KeychainPinStore.hasStoredPin(keychain: keychain))

        store.update { $0.pinHash(v: nil) }
        XCTAssertFalse(KeychainPinStore.hasStoredPin(keychain: keychain))
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
